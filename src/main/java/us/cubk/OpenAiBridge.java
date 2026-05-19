package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class OpenAiBridge {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final int LOG_LIMIT = 300;
    private final BearerBuilder.SessionContext sess;
    private final BearerApiClient bearerClient;
    private final JsonNode templateBase;
    private final Deque<String> requestLogs = new ConcurrentLinkedDeque<>();
    private final Deque<String> systemLogs = new ConcurrentLinkedDeque<>();
    private final boolean dashboardEnabled = getBooleanSetting("DASHBOARD_ENABLED", true);
    private final boolean corsEnabled = getBooleanSetting("CORS_ENABLED", true);
    private final String corsAllowOrigin = getSettingOrDefault("CORS_ALLOW_ORIGIN", "*");
    private final String proxyApiKey;
    private final List<String> dashboardPasswords = resolveDashboardPasswords();

    public OpenAiBridge(String pat) throws Exception {
        String explicitApiKey = getSetting("QODER_API_KEY");
        this.proxyApiKey = (explicitApiKey == null || explicitApiKey.isBlank()) ? pat : explicitApiKey;
        String mid = UUID.randomUUID().toString();
        String mtoken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).substring(0, 50).getBytes());
        String mtype = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var sigClient = new SignatureApiClient(mid, mtoken, mtype);
        JsonNode jt = sigClient.exchangeJobToken(pat);
        System.out.println("[bridge] session for " + jt.path("name").asText() + " (" + jt.path("id").asText() + ")");
        var identity = new BearerBuilder.AuthIdentity(jt.path("name").asText(""), jt.path("id").asText(""), jt.path("id").asText(""), "", "", "", jt.path("userType").asText("personal_standard"), jt.path("securityOauthToken").asText(), jt.path("refreshToken").asText());
        this.sess = BearerBuilder.newSession(identity, mid, mtoken, mtype);
        this.bearerClient = new BearerApiClient(sess);
        String basePrompt = new String(java.nio.file.Files.readAllBytes(new File("baseprompt.json").toPath()));
        basePrompt = basePrompt.replace("{UUID1}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID2}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID3}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID4}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID5}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{TIME1}", String.valueOf(System.currentTimeMillis()));
        this.templateBase = objectMapper.readTree(basePrompt);
        logSystem("Bridge initialized for user=" + jt.path("id").asText() + " dashboardEnabled=" + dashboardEnabled + " corsEnabled=" + corsEnabled + " apiKeyAuth=" + (proxyApiKey != null && !proxyApiKey.isBlank()));
    }

    public void start(int port) throws Exception {
        String host = getSetting("QODER_HOST");
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handleRoot);
        server.createContext("/v1/chat/completions", this::handleChat);
        if (dashboardEnabled) {
            server.createContext("/dashboard", this::handleDashboardPage);
            server.createContext("/dashboard/api/info", this::handleDashboardInfo);
            server.createContext("/dashboard/api/logs/requests", this::handleDashboardRequestLogs);
            server.createContext("/dashboard/api/logs/system", this::handleDashboardSystemLogs);
        }
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[bridge] listening http://" + host + ":" + port + "/v1/chat/completions");
        logSystem("Server listening on http://" + host + ":" + port + " dashboard=" + dashboardEnabled);
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        try {
            if (handleCorsPreflight(ex)) {
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (!"/".equals(path)) {
                applyCors(ex);
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (dashboardEnabled) {
                ex.getResponseHeaders().add("Location", "/dashboard");
                applyCors(ex);
                ex.sendResponseHeaders(302, -1);
                return;
            }
            writeJson(ex, 200, objectMapper.createObjectNode().put("status", "ok").put("message", "Qoder bridge is running"));
        } finally {
            ex.close();
        }
    }

    private void handleDashboardPage(HttpExchange ex) throws IOException {
        try {
            if (handleCorsPreflight(ex)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(405, -1);
                return;
            }
            byte[] html = dashboardHtml().getBytes(StandardCharsets.UTF_8);
            applyCors(ex);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
        } finally {
            ex.close();
        }
    }

    private void handleDashboardInfo(HttpExchange ex) throws IOException {
        try {
            if (handleCorsPreflight(ex)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!isDashboardAuthorized(ex)) {
                writeDashboardAuthError(ex);
                return;
            }
            ObjectNode info = objectMapper.createObjectNode();
            info.put("dashboard_enabled", dashboardEnabled);
            info.put("cors_enabled", corsEnabled);
            info.put("api_key_required", requiresProxyApiKey());
            info.put("endpoint_base_path", "/v1");
            info.put("endpoint_path", "/v1/chat/completions");
            info.put("proxy_api_key", proxyApiKey == null ? "" : proxyApiKey);
            info.put("model_note", "Model selector hidden. Bridge does not strictly enforce model IDs.");
            writeJson(ex, 200, info);
        } finally {
            ex.close();
        }
    }

    private void handleDashboardRequestLogs(HttpExchange ex) throws IOException {
        handleDashboardLogs(ex, requestLogs);
    }

    private void handleDashboardSystemLogs(HttpExchange ex) throws IOException {
        handleDashboardLogs(ex, systemLogs);
    }

    private void handleDashboardLogs(HttpExchange ex, Deque<String> source) throws IOException {
        try {
            if (handleCorsPreflight(ex)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!isDashboardAuthorized(ex)) {
                writeDashboardAuthError(ex);
                return;
            }
            ArrayNode logs = objectMapper.createArrayNode();
            for (String entry : source) {
                logs.add(entry);
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("logs", logs);
            writeJson(ex, 200, payload);
        } finally {
            ex.close();
        }
    }

    private void handleChat(HttpExchange ex) throws IOException {
        String modelForLog = "";
        boolean streamForLog = false;
        int messageCountForLog = 0;
        int toolCountForLog = 0;
        try {
            if (handleCorsPreflight(ex)) {
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(405, -1);
                appendRequestLog("method_not_allowed method=" + ex.getRequestMethod() + " path=" + ex.getRequestURI().getPath());
                return;
            }
            if (!isProxyApiAuthorized(ex)) {
                writeAuthError(ex, "Invalid or missing API key");
                appendRequestLog("unauthorized path=" + ex.getRequestURI().getPath());
                return;
            }
            JsonNode req = objectMapper.readTree(ex.getRequestBody());
            boolean stream = req.path("stream").asBoolean(false);
            String model = req.path("model").asText("lite");
            JsonNode messages = req.path("messages");
            streamForLog = stream;
            modelForLog = model;
            messageCountForLog = messages != null && messages.isArray() ? messages.size() : 0;

            ObjectNode body = templateBase.deepCopy();
            String nid = UUID.randomUUID().toString();
            body.put("request_id", nid);
            body.put("chat_record_id", nid);
            body.put("request_set_id", UUID.randomUUID().toString());
            body.put("session_id", UUID.randomUUID().toString());
            body.put("stream", true);
            body.put("aliyun_user_type", sess.identity().userType());
            ObjectNode mc = (ObjectNode) body.path("model_config");
            mc.put("key", model);
            ObjectNode biz = (ObjectNode) body.path("business");
            biz.put("id", UUID.randomUUID().toString());
            biz.put("begin_at", System.currentTimeMillis());

            String prompt = extractLatestUserPrompt(messages);
            ObjectNode ctx = (ObjectNode) body.path("chat_context");
            ((ObjectNode) ctx.path("text")).put("text", prompt);
            ((ObjectNode) ctx.path("extra").path("originalContent")).put("text", prompt);
            biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            JsonNode incomingTools = req.path("tools");
            boolean toolsEnabled = incomingTools.isArray() && incomingTools.size() > 0;
            toolCountForLog = incomingTools != null && incomingTools.isArray() ? incomingTools.size() : 0;
            body.set("messages", buildQoderMessages((ArrayNode) body.path("messages"), messages, prompt, toolsEnabled));
            if (toolsEnabled) {
                body.set("tools", incomingTools.deepCopy());
            }

            int messageCount = messageCountForLog;
            int toolCount = toolCountForLog;
            System.out.println("[bridge] request model=" + model + " stream=" + stream + " messages=" + messageCount + " tools=" + toolCount + " prompt_chars=" + prompt.length());
            if (isDebugLogEnabled()) {
                for (JsonNode msg : body.path("messages")) {
                    String content = msg.path("content").asText();
                    String contentsStr = msg.path("contents").toString();
                    System.out.println("[bridge][debug] msg role=" + msg.path("role").asText() + " content=" + (content.length() > 40 ? content.substring(0, 40) + "..." : content) + " contents=" + (contentsStr.length() > 120 ? contentsStr.substring(0, 120) + "..." : contentsStr));
                }
            }

            String url = "https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation" + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
            Map<String, String> extraHeaders = Map.of("x-model-key", model, "x-model-source", mc.path("source").asText("system"));

            String reqId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            long created = System.currentTimeMillis() / 1000;

            if (stream) {
                applyCors(ex);
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                StreamAccumulator accumulator = new StreamAccumulator(out, reqId, created, model, toolsEnabled);
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    BridgeDelta delta = extractDelta(line.substring(5).trim());
                    if (delta.isEmpty()) return;
                    try {
                        accumulator.accept(delta);
                    } catch (IOException ie) {
                        throw new RuntimeException(ie);
                    }
                });
                accumulator.flush();
                ObjectNode done = makeChunk(reqId, created, model);
                ((ObjectNode) done.path("choices").get(0)).put("finish_reason", accumulator.finishReason());
                ((ObjectNode) done.path("choices").get(0)).set("delta", objectMapper.createObjectNode());
                out.write(("data: " + objectMapper.writeValueAsString(done) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.close();
                appendRequestLog("ok stream=true model=" + model + " messages=" + messageCount + " tools=" + toolCount);
            } else {
                StringBuilder full = new StringBuilder();
                ToolCallAccumulator toolCalls = new ToolCallAccumulator();
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    BridgeDelta delta = extractDelta(line.substring(5).trim());
                    if (delta.content() != null) {
                        full.append(delta.content());
                    }
                    if (delta.toolCalls() != null && delta.toolCalls().size() > 0) {
                        toolCalls.append(delta.toolCalls());
                    }
                });
                ArrayNode fallbackToolCalls = null;
                if (toolCalls.isEmpty() && toolsEnabled) {
                    fallbackToolCalls = parseToolCallsText(full.toString());
                }
                ObjectNode out = objectMapper.createObjectNode();
                out.put("id", reqId);
                out.put("object", "chat.completion");
                out.put("created", created);
                out.put("model", model);
                ArrayNode choices = objectMapper.createArrayNode();
                ObjectNode ch = objectMapper.createObjectNode();
                ch.put("index", 0);
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                if (fallbackToolCalls != null) {
                    msg.putNull("content");
                    msg.set("tool_calls", fallbackToolCalls);
                } else if (full.isEmpty() && !toolCalls.isEmpty()) {
                    msg.putNull("content");
                } else {
                    msg.put("content", full.toString());
                }
                if (!toolCalls.isEmpty()) {
                    msg.set("tool_calls", toolCalls.snapshot());
                }
                ch.set("message", msg);
                ch.put("finish_reason", (!toolCalls.isEmpty() || fallbackToolCalls != null) ? "tool_calls" : "stop");
                choices.add(ch);
                out.set("choices", choices);
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put("prompt_tokens", 0);
                usage.put("completion_tokens", 0);
                usage.put("total_tokens", 0);
                out.set("usage", usage);
                byte[] outBytes = objectMapper.writeValueAsBytes(out);
                applyCors(ex);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, outBytes.length);
                ex.getResponseBody().write(outBytes);
                appendRequestLog("ok stream=false model=" + model + " messages=" + messageCount + " tools=" + toolCount + " chars=" + full.length());
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ObjectNode error = objectMapper.createObjectNode();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("message", message);
            payload.put("type", "qoder_error");
            error.set("error", payload);
            byte[] errBytes = objectMapper.writeValueAsBytes(error);
            logSystem("chat_error model=" + modelForLog + " stream=" + streamForLog + " error=" + message);
            appendRequestLog("error model=" + modelForLog + " stream=" + streamForLog + " messages=" + messageCountForLog + " tools=" + toolCountForLog + " error=" + message);
            try {
                applyCors(ex);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(500, errBytes.length);
                ex.getResponseBody().write(errBytes);
            } catch (IOException ignore) {
            }
        } finally {
            ex.close();
        }
    }

    private BridgeDelta extractDelta(String dataLine) {
        try {
            JsonNode wrapper = objectMapper.readTree(dataLine);
            String inner = wrapper.path("body").asText("");
            if (inner.isEmpty()) return BridgeDelta.empty();
            JsonNode innerJson = objectMapper.readTree(inner);
            for (JsonNode ch : innerJson.path("choices")) {
                JsonNode delta = ch.path("delta");
                String role = delta.has("role") ? delta.path("role").asText("") : "";
                String content = delta.has("content") ? delta.path("content").asText("") : "";
                ArrayNode toolCalls = null;
                if (delta.path("tool_calls").isArray() && delta.path("tool_calls").size() > 0) {
                    toolCalls = (ArrayNode) delta.path("tool_calls").deepCopy();
                }
                if (!role.isEmpty() || !content.isEmpty() || toolCalls != null) {
                    return new BridgeDelta(role, content, toolCalls);
                }
            }
        } catch (Exception ignore) {
        }
        return BridgeDelta.empty();
    }

    private String extractLatestUserPrompt(JsonNode messages) {
        if (messages == null || !messages.isArray()) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("user".equals(message.path("role").asText())) {
                String text = normalizeMessageText(message);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private ArrayNode buildQoderMessages(ArrayNode templateMessages, JsonNode incomingMessages, String prompt, boolean toolsEnabled) {
        ArrayNode rebuilt = objectMapper.createArrayNode();
        boolean keepTemplateSystem = !hasRole(incomingMessages, "system");
        if (keepTemplateSystem) {
            for (JsonNode msg : templateMessages) {
                if ("system".equals(msg.path("role").asText())) {
                    rebuilt.add(msg.deepCopy());
                }
            }
        }
        if (incomingMessages != null && incomingMessages.isArray()) {
            for (int i = 0; i < incomingMessages.size(); i++) {
                JsonNode message = incomingMessages.get(i);
                boolean allowStructuredToolCalls = toolsEnabled && hasResolvedToolResponse(incomingMessages, i);
                ObjectNode converted = convertIncomingMessage(message, toolsEnabled, allowStructuredToolCalls);
                if (converted != null) {
                    rebuilt.add(converted);
                }
            }
        }
        if (rebuilt.isEmpty() && !prompt.isBlank()) {
            rebuilt.add(buildUserMessage(prompt));
        }
        return rebuilt;
    }

    private boolean hasRole(JsonNode messages, String role) {
        if (messages == null || !messages.isArray()) return false;
        for (JsonNode message : messages) {
            if (role.equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode convertIncomingMessage(JsonNode message, boolean toolsEnabled, boolean allowStructuredToolCalls) {
        String role = message.path("role").asText("user");
        String text = normalizeMessageText(message);
        ArrayNode anyToolCalls = extractAnyToolCalls(message, text, toolsEnabled);
        ArrayNode structuredToolCalls = resolveStructuredToolCalls(message, text, toolsEnabled, allowStructuredToolCalls);

        if ("assistant".equals(role) && structuredToolCalls != null) {
            return buildAssistantToolCallMessage(text, structuredToolCalls);
        }

        if ("assistant".equals(role) && anyToolCalls != null && allowStructuredToolCalls == false) {
            return buildStructuredMessage("assistant", summarizeUnresolvedToolCalls(anyToolCalls));
        }

        if (!toolsEnabled && message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0) {
            text = joinSections(text, renderToolCalls(message.path("tool_calls")));
        }

        if ("tool".equals(role)) {
            if (toolsEnabled) {
                return buildToolMessage(message, text);
            }
            role = "user";
            text = renderToolResult(message, text);
        }

        if (text.isBlank()) {
            return null;
        }

        if ("user".equals(role)) {
            return buildUserMessage(text);
        }

        return buildStructuredMessage(role, text);
    }

    private boolean hasResolvedToolResponse(JsonNode messages, int assistantIndex) {
        JsonNode message = messages.get(assistantIndex);
        if (!"assistant".equals(message.path("role").asText())) {
            return false;
        }
        boolean hasToolCalls = (message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0)
                || parseToolCallsText(normalizeMessageText(message)) != null;
        if (!hasToolCalls) {
            return false;
        }
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            String nextRole = messages.get(i).path("role").asText();
            if ("tool".equals(nextRole)) {
                return true;
            }
            if ("assistant".equals(nextRole) || "user".equals(nextRole) || "system".equals(nextRole)) {
                return false;
            }
        }
        return false;
    }

    private ObjectNode buildUserMessage(String text) {
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", "");
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode cn = objectMapper.createObjectNode();
        cn.put("type", "text");
        cn.put("text", text);
        contents.add(cn);
        userMsg.set("contents", contents);
        userMsg.set("response_meta", blankResponseMeta());
        userMsg.put("reasoning_content_signature", "");
        return userMsg;
    }

    private ObjectNode buildStructuredMessage(String role, String text) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("role", role);
        out.put("content", text == null ? "" : text);
        out.set("response_meta", blankResponseMeta());
        out.put("reasoning_content_signature", "");
        return out;
    }

    private ObjectNode buildAssistantToolCallMessage(String text, ArrayNode toolCalls) {
        String content = text == null ? "" : text;
        if (parseToolCallsText(content) != null) {
            content = "";
        }
        ObjectNode out = buildStructuredMessage("assistant", content);
        out.set("tool_calls", toolCalls.deepCopy());
        return out;
    }

    private ObjectNode buildToolMessage(JsonNode message, String text) {
        ObjectNode out = buildStructuredMessage("tool", text);
        if (message.path("name").isTextual()) {
            out.put("name", message.path("name").asText());
        }
        if (message.path("tool_call_id").isTextual()) {
            out.put("tool_call_id", message.path("tool_call_id").asText());
        }
        return out;
    }

    private ObjectNode blankResponseMeta() {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);
        ObjectNode completionDetails = objectMapper.createObjectNode();
        completionDetails.put("reasoning_tokens", 0);
        usage.set("completion_tokens_details", completionDetails);
        ObjectNode promptDetails = objectMapper.createObjectNode();
        promptDetails.put("cached_tokens", 0);
        usage.set("prompt_tokens_details", promptDetails);
        ObjectNode responseMeta = objectMapper.createObjectNode();
        responseMeta.put("id", "");
        responseMeta.set("usage", usage);
        return responseMeta;
    }

    private String normalizeMessageText(JsonNode message) {
        String text = normalizeContent(message.path("content"));
        if (text.isBlank()) {
            text = normalizeContent(message.path("contents"));
        }
        return text;
    }

    private ArrayNode resolveStructuredToolCalls(JsonNode message, String text, boolean toolsEnabled, boolean allowStructuredToolCalls) {
        if (!toolsEnabled || !allowStructuredToolCalls) {
            return null;
        }
        return extractAnyToolCalls(message, text, true);
    }

    private ArrayNode extractAnyToolCalls(JsonNode message, String text, boolean toolsEnabled) {
        if (!toolsEnabled) {
            return null;
        }
        if (message.path("tool_calls").isArray() && message.path("tool_calls").size() > 0) {
            return normalizeToolCalls(message.path("tool_calls"));
        }
        return parseToolCallsText(text);
    }

    private String normalizeContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : content) {
                String part = normalizeContentPart(item);
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return String.join("\n\n", parts);
        }
        return normalizeContentPart(content);
    }

    private String normalizeContentPart(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) return "";
        if (item.isTextual()) return item.asText();
        if (item.isObject()) {
            String type = item.path("type").asText("");
            if (item.path("text").isTextual()) {
                return item.path("text").asText();
            }
            if (("image_url".equals(type) || "input_image".equals(type)) && item.path("image_url").path("url").isTextual()) {
                return "[image] " + item.path("image_url").path("url").asText();
            }
            if (item.path("content").isContainerNode()) {
                return normalizeContent(item.path("content"));
            }
            return item.toString();
        }
        return item.toString();
    }

    private String renderToolCalls(JsonNode toolCalls) {
        return "Tool calls:\n" + toolCalls.toString();
    }

    private ArrayNode parseToolCallsText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("Tool calls:")) {
            return null;
        }
        String payload = trimmed.substring("Tool calls:".length()).trim();
        if (payload.startsWith("```") && payload.endsWith("```")) {
            int newline = payload.indexOf('\n');
            if (newline >= 0) {
                payload = payload.substring(newline + 1, payload.length() - 3).trim();
            }
        }
        if (!payload.startsWith("[")) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            return normalizeToolCalls(parsed);
        } catch (Exception ignore) {
            return null;
        }
    }

    private ArrayNode normalizeToolCalls(JsonNode rawToolCalls) {
        if (rawToolCalls == null || !rawToolCalls.isArray()) {
            return null;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode rawToolCall : rawToolCalls) {
            JsonNode function = rawToolCall.path("function");
            String name = function.path("name").asText("");
            String arguments = normalizeToolArguments(function.path("arguments"));
            if (name.isBlank() && arguments.isBlank()) {
                continue;
            }
            ObjectNode call = objectMapper.createObjectNode();
            call.put("id", rawToolCall.path("id").asText(""));
            call.put("type", rawToolCall.path("type").asText("function"));
            ObjectNode normalizedFunction = objectMapper.createObjectNode();
            normalizedFunction.put("name", name);
            normalizedFunction.put("arguments", arguments);
            call.set("function", normalizedFunction);
            normalized.add(call);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private String summarizeUnresolvedToolCalls(ArrayNode toolCalls) {
        StringBuilder sb = new StringBuilder("Previously planned but unexecuted tool calls");
        int limit = Math.min(toolCalls.size(), 6);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String name = toolCalls.get(i).path("function").path("name").asText("");
            if (name.isBlank()) {
                name = "unknown";
            }
            names.add(name);
        }
        if (!names.isEmpty()) {
            sb.append(": ").append(String.join(", ", names));
        }
        if (toolCalls.size() > limit) {
            sb.append(" and ").append(toolCalls.size() - limit).append(" more");
        }
        sb.append('.');
        return sb.toString();
    }

    private String normalizeToolArguments(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return "";
        }
        if (arguments.isTextual()) {
            return arguments.asText();
        }
        return arguments.toString();
    }

    private String renderToolResult(JsonNode message, String text) {
        String name = message.path("name").asText("");
        String toolCallId = message.path("tool_call_id").asText("");
        StringBuilder sb = new StringBuilder("Tool result");
        if (!name.isBlank()) {
            sb.append(" (").append(name).append(')');
        }
        if (!toolCallId.isBlank()) {
            sb.append(" [").append(toolCallId).append(']');
        }
        if (!text.isBlank()) {
            sb.append(":\n").append(text);
        }
        return sb.toString();
    }

    private String joinSections(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private ObjectNode makeChunk(String id, long created, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode c = objectMapper.createObjectNode();
        c.put("index", 0);
        c.set("delta", objectMapper.createObjectNode());
        c.putNull("finish_reason");
        choices.add(c);
        root.set("choices", choices);
        return root;
    }

    private void writeSseChunk(OutputStream out, String id, long created, String model, String role, String content, ArrayNode toolCalls) throws IOException {
        ObjectNode chunk = makeChunk(id, created, model);
        ObjectNode delta = (ObjectNode) chunk.path("choices").get(0).path("delta");
        if (role != null && !role.isEmpty()) {
            delta.put("role", role);
        }
        if (content != null && !content.isEmpty()) {
            delta.put("content", content);
        }
        if (toolCalls != null && toolCalls.size() > 0) {
            delta.set("tool_calls", toolCalls);
        }
        out.write(("data: " + objectMapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private record BridgeDelta(String role, String content, ArrayNode toolCalls) {
        private static BridgeDelta empty() {
            return new BridgeDelta("", "", null);
        }

        private boolean isEmpty() {
            return role.isEmpty() && content.isEmpty() && (toolCalls == null || toolCalls.isEmpty());
        }
    }

    private final class StreamAccumulator {
        private final OutputStream out;
        private final String reqId;
        private final long created;
        private final String model;
        private final boolean toolCallFallbackEnabled;
        private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();
        private final StringBuilder pendingContent = new StringBuilder();
        private String pendingRole = "assistant";
        private boolean emittedChunk;
        private boolean streamingText;

        private StreamAccumulator(OutputStream out, String reqId, long created, String model, boolean toolCallFallbackEnabled) {
            this.out = out;
            this.reqId = reqId;
            this.created = created;
            this.model = model;
            this.toolCallFallbackEnabled = toolCallFallbackEnabled;
        }

        private void accept(BridgeDelta delta) throws IOException {
            if (delta.role() != null && !delta.role().isEmpty()) {
                pendingRole = delta.role();
            }
            if (delta.toolCalls() != null && delta.toolCalls().size() > 0) {
                discardBufferedToolCallText();
                toolCalls.append(delta.toolCalls());
                emit(null, withToolCallIndices(delta.toolCalls()));
                return;
            }
            if (delta.content() == null || delta.content().isEmpty()) {
                return;
            }
            if (!toolCallFallbackEnabled || streamingText) {
                streamingText = true;
                emit(delta.content(), null);
                return;
            }

            pendingContent.append(delta.content());
            if (isPotentialToolCallText(pendingContent.toString())) {
                return;
            }

            streamingText = true;
            emitBufferedText();
        }

        private void flush() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            ArrayNode parsedToolCalls = toolCallFallbackEnabled ? parseToolCallsText(buffered) : null;
            if (parsedToolCalls != null) {
                toolCalls.append(parsedToolCalls);
                emit(null, withToolCallIndices(parsedToolCalls));
                return;
            }
            streamingText = true;
            emit(buffered, null);
        }

        private String finishReason() {
            return toolCalls.isEmpty() ? "stop" : "tool_calls";
        }

        private void emitBufferedText() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            emit(buffered, null);
        }

        private void discardBufferedToolCallText() throws IOException {
            if (pendingContent.length() == 0) {
                return;
            }
            String buffered = pendingContent.toString();
            pendingContent.setLength(0);
            if (toolCallFallbackEnabled && isPotentialToolCallText(buffered)) {
                return;
            }
            streamingText = true;
            emit(buffered, null);
        }

        private void emit(String content, ArrayNode toolCalls) throws IOException {
            String role = "";
            if (!emittedChunk) {
                role = pendingRole == null || pendingRole.isBlank() ? "assistant" : pendingRole;
            }
            writeSseChunk(out, reqId, created, model, role, content, toolCalls);
            emittedChunk = true;
        }

        private boolean isPotentialToolCallText(String text) {
            String candidate = text == null ? "" : text.stripLeading();
            if (candidate.isEmpty()) {
                return true;
            }
            return "Tool calls:".startsWith(candidate) || candidate.startsWith("Tool calls:");
        }

        private ArrayNode withToolCallIndices(ArrayNode rawToolCalls) {
            ArrayNode indexed = objectMapper.createArrayNode();
            for (int i = 0; i < rawToolCalls.size(); i++) {
                ObjectNode call = (ObjectNode) rawToolCalls.get(i).deepCopy();
                if (!call.path("index").isInt()) {
                    call.put("index", i);
                }
                indexed.add(call);
            }
            return indexed;
        }
    }

    private final class ToolCallAccumulator {
        private final ArrayNode calls = objectMapper.createArrayNode();

        private void append(ArrayNode deltaCalls) {
            for (JsonNode deltaCall : deltaCalls) {
                int index = deltaCall.path("index").isInt() ? deltaCall.path("index").asInt() : calls.size();
                while (calls.size() <= index) {
                    ObjectNode placeholder = objectMapper.createObjectNode();
                    placeholder.put("id", "");
                    placeholder.put("type", "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", "");
                    function.put("arguments", "");
                    placeholder.set("function", function);
                    calls.add(placeholder);
                }

                ObjectNode existing = (ObjectNode) calls.get(index);
                if (deltaCall.path("id").isTextual()) {
                    existing.put("id", deltaCall.path("id").asText());
                }
                if (deltaCall.path("type").isTextual()) {
                    existing.put("type", deltaCall.path("type").asText());
                }

                JsonNode deltaFunction = deltaCall.path("function");
                ObjectNode existingFunction = (ObjectNode) existing.path("function");
                if (deltaFunction.path("name").isTextual()) {
                    existingFunction.put("name", deltaFunction.path("name").asText());
                }
                if (deltaFunction.path("arguments").isTextual()) {
                    existingFunction.put("arguments", existingFunction.path("arguments").asText("") + deltaFunction.path("arguments").asText());
                }
            }
        }

        private boolean isEmpty() {
            return calls.isEmpty();
        }

        private ArrayNode snapshot() {
            return calls.deepCopy();
        }
    }

    private void appendRequestLog(String message) {
        appendLog(requestLogs, message);
    }

    private void logSystem(String message) {
        appendLog(systemLogs, message);
    }

    private void appendLog(Deque<String> queue, String message) {
        String entry = LOG_TIME.format(Instant.now()) + " " + message;
        queue.addLast(entry);
        while (queue.size() > LOG_LIMIT) {
            queue.pollFirst();
        }
    }

    private boolean handleCorsPreflight(HttpExchange ex) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            return false;
        }
        applyCors(ex);
        ex.sendResponseHeaders(204, -1);
        return true;
    }

    private void applyCors(HttpExchange ex) {
        if (!corsEnabled) {
            return;
        }
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsAllowOrigin);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization,X-API-Key,X-Dashboard-Password");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }

    private boolean requiresProxyApiKey() {
        return proxyApiKey != null && !proxyApiKey.isBlank();
    }

    private boolean isProxyApiAuthorized(HttpExchange ex) {
        if (!requiresProxyApiKey()) {
            return true;
        }
        String bearer = bearerToken(ex);
        if (proxyApiKey.equals(bearer)) {
            return true;
        }
        String xApiKey = ex.getRequestHeaders().getFirst("X-API-Key");
        return proxyApiKey.equals(xApiKey);
    }

    private String bearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null) {
            return "";
        }
        String prefix = "Bearer ";
        if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return auth.substring(prefix.length()).trim();
        }
        return "";
    }

    private void writeAuthError(HttpExchange ex, String message) throws IOException {
        ObjectNode error = objectMapper.createObjectNode();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", message);
        payload.put("type", "auth_error");
        error.set("error", payload);
        writeJson(ex, 401, error);
    }

    private boolean isDashboardAuthorized(HttpExchange ex) {
        if (dashboardPasswords.isEmpty()) {
            return true;
        }
        String direct = ex.getRequestHeaders().getFirst("X-Dashboard-Password");
        return direct != null && dashboardPasswords.contains(direct);
    }

    private void writeDashboardAuthError(HttpExchange ex) throws IOException {
        ObjectNode error = objectMapper.createObjectNode();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", "Invalid or missing dashboard password");
        payload.put("type", "dashboard_auth_error");
        error.set("error", payload);
        writeJson(ex, 401, error);
    }

    private List<String> resolveDashboardPasswords() {
        String raw = getSetting("DASHBOARD_PASSWORDS");
        if (raw == null || raw.isBlank()) {
            raw = getSetting("DASHBOARD_PASSWORD");
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private void writeJson(HttpExchange ex, int statusCode, JsonNode payload) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        applyCors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(statusCode, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private String dashboardHtml() {
        String dashboardPasswordRequired = dashboardPasswords.isEmpty() ? "false" : "true";
        return ("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Qoder2API Dashboard</title>
                  <style>
                    :root {
                      --bg: #0b0f14;
                      --panel: #121923;
                      --panel-2: #1a2330;
                      --text: #e6edf7;
                      --muted: #9fb0c6;
                      --line: #2a3546;
                      --good: #20c997;
                      --warn: #f5c542;
                      --bad: #ff6b6b;
                      --accent: #53a7ff;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
                      color: var(--text);
                      background: radial-gradient(circle at top, #152033 0%, var(--bg) 45%);
                    }
                    .wrap {
                      max-width: 1080px;
                      margin: 24px auto;
                      padding: 0 16px 24px;
                    }
                    .card {
                      background: linear-gradient(160deg, var(--panel), var(--panel-2));
                      border: 1px solid var(--line);
                      border-radius: 14px;
                      padding: 16px;
                      margin-bottom: 14px;
                    }
                    h1 { margin: 0 0 8px; font-size: 24px; }
                    h2 { margin: 0 0 10px; font-size: 16px; }
                    .row { display: flex; gap: 10px; flex-wrap: wrap; }
                    .grow { flex: 1; min-width: 240px; }
                    input, textarea, button {
                      width: 100%;
                      background: #0e151f;
                      color: var(--text);
                      border: 1px solid var(--line);
                      border-radius: 10px;
                      padding: 10px;
                    }
                    textarea { min-height: 120px; resize: vertical; }
                    button {
                      cursor: pointer;
                      background: #173657;
                      border-color: #29517b;
                      font-weight: 600;
                    }
                    .mini { font-size: 12px; color: var(--muted); }
                    .warn {
                      color: #111;
                      background: var(--warn);
                      padding: 10px;
                      border-radius: 10px;
                      font-weight: 600;
                    }
                    .error {
                      color: #fff;
                      background: #7d2230;
                      padding: 10px;
                      border-radius: 10px;
                      font-weight: 600;
                    }
                    pre {
                      margin: 0;
                      background: #0b1119;
                      border: 1px solid var(--line);
                      border-radius: 10px;
                      padding: 10px;
                      white-space: pre-wrap;
                      word-break: break-word;
                      max-height: 280px;
                      overflow: auto;
                    }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <div class="card">
                      <h1>Qoder2API Dashboard</h1>
                      <div class="mini">Backend-only control panel. No persistent storage: config from env vars only.</div>
                    </div>

                    <div class="card protected">
                      <h2>Endpoint</h2>
                      <div class="row">
                        <div class="grow"><input id="baseUrl" readonly></div>
                        <div style="width: 180px;"><button id="copyBaseUrl">Copy Base URL</button></div>
                      </div>
                      <div class="mini" style="margin-top: 8px;">Base URL ends at <code>/v1</code>.</div>
                      <div class="row" style="margin-top: 8px;">
                        <div class="grow"><input id="chatEndpoint" readonly></div>
                        <div style="width: 180px;"><button id="copyEndpoint">Copy Chat Endpoint</button></div>
                      </div>
                      <div class="row" style="margin-top: 8px;">
                        <div class="grow"><input id="proxyKey" readonly></div>
                        <div style="width: 180px;"><button id="copyProxyKey">Copy API Key</button></div>
                      </div>
                      <div class="warn" style="margin-top: 10px;">
                        Warning: some online container platforms expose HTTPS URLs. Verify your client uses the correct scheme (https:// vs http://).
                      </div>
                      <div class="mini" style="margin-top: 8px;">Model switch is hidden because this bridge does not strictly enforce model IDs.</div>
                    </div>

                    <div class="card" id="dashboardAuthCard">
                      <h2>Dashboard Access</h2>
                      <div class="mini">Dashboard password is required for logs/info API. Enter password below to unlock data panels.</div>
                      <div class="row" style="margin-top: 8px;">
                        <div class="grow"><input id="dashboardPassword" type="password" placeholder="Dashboard password"></div>
                        <div style="width: 180px;"><button id="unlockDashboard">Unlock</button></div>
                      </div>
                      <div id="dashboardAuthStatus" class="mini" style="margin-top: 8px;"></div>
                    </div>

                    <div class="card protected">
                      <h2>Playground</h2>
                      <textarea id="prompt" placeholder="Type a test prompt..."></textarea>
                      <div class="row" style="margin-top: 8px;">
                        <div style="width: 200px;"><button id="send">Send Test Chat</button></div>
                      </div>
                      <div class="mini" style="margin: 8px 0;">Payload uses a fixed model key: <code>auto</code>.</div>
                      <pre id="response"></pre>
                    </div>

                    <div class="card protected">
                      <h2>Request Logs</h2>
                      <pre id="requestLogs"></pre>
                    </div>

                    <div class="card protected">
                      <h2>System Logs</h2>
                      <pre id="systemLogs"></pre>
                    </div>
                  </div>

                  <script>
                    const dashboardPasswordRequired = __DASHBOARD_PASSWORD_REQUIRED__;
                    let dashboardPassword = '';
                    let dashboardUnlocked = !dashboardPasswordRequired;
                    let dashboardInfo = null;
                    const protectedCards = Array.from(document.querySelectorAll('.protected'));

                    function setProtectedVisible(visible) {
                      protectedCards.forEach((el) => { el.style.display = visible ? '' : 'none'; });
                    }
                    function setEndpointPlaceholders() {
                      document.getElementById('baseUrl').value = '(locked)';
                      document.getElementById('chatEndpoint').value = '(locked)';
                      document.getElementById('proxyKey').value = '(locked)';
                    }
                    function applyDashboardInfo(info) {
                      dashboardInfo = info || {};
                      const basePath = dashboardInfo.endpoint_base_path || '/v1';
                      const endpointPath = dashboardInfo.endpoint_path || '/v1/chat/completions';
                      document.getElementById('baseUrl').value = window.location.origin + basePath;
                      document.getElementById('chatEndpoint').value = window.location.origin + endpointPath;
                      document.getElementById('proxyKey').value = dashboardInfo.proxy_api_key || '(not configured)';
                    }
                    setEndpointPlaceholders();

                    async function copyText(text) {
                      try {
                        await navigator.clipboard.writeText(text);
                      } catch (err) {}
                    }
                    document.getElementById('copyBaseUrl').onclick = () => copyText(document.getElementById('baseUrl').value);
                    document.getElementById('copyEndpoint').onclick = () => copyText(document.getElementById('chatEndpoint').value);
                    document.getElementById('copyProxyKey').onclick = () => copyText(document.getElementById('proxyKey').value);

                    const dashboardAuthCard = document.getElementById('dashboardAuthCard');
                    const dashboardAuthStatus = document.getElementById('dashboardAuthStatus');
                    if (!dashboardPasswordRequired) {
                      dashboardAuthCard.style.display = 'none';
                      setProtectedVisible(true);
                    } else {
                      setProtectedVisible(false);
                      dashboardAuthStatus.textContent = 'Locked';
                    }

                    async function unlockDashboard() {
                      try {
                        const info = await getJson('/dashboard/api/info');
                        applyDashboardInfo(info);
                        dashboardUnlocked = true;
                        setProtectedVisible(true);
                        if (dashboardPasswordRequired) {
                          dashboardAuthStatus.textContent = 'Unlocked';
                        }
                        await refreshLogs();
                      } catch (err) {
                        dashboardUnlocked = false;
                        setProtectedVisible(false);
                        setEndpointPlaceholders();
                        if (dashboardPasswordRequired) {
                          dashboardAuthStatus.textContent = 'Locked / wrong password';
                        }
                      }
                    }

                    document.getElementById('unlockDashboard').onclick = async () => {
                      dashboardPassword = document.getElementById('dashboardPassword').value.trim();
                      await unlockDashboard();
                    };

                    function dashboardHeaders(base = {}) {
                      const h = { ...base };
                      if (dashboardPassword) {
                        h['X-Dashboard-Password'] = dashboardPassword;
                      }
                      return h;
                    }

                    async function getJson(url) {
                      const res = await fetch(url, { headers: dashboardHeaders(), credentials: 'same-origin' });
                      if (!res.ok) throw new Error('HTTP ' + res.status);
                      return res.json();
                    }

                    async function refreshLogs() {
                      if (!dashboardUnlocked) {
                        return;
                      }
                      try {
                        const [req, sys] = await Promise.all([
                          getJson('/dashboard/api/logs/requests'),
                          getJson('/dashboard/api/logs/system')
                        ]);
                        document.getElementById('requestLogs').textContent = (req.logs || []).join('\\n');
                        document.getElementById('systemLogs').textContent = (sys.logs || []).join('\\n');
                        if (dashboardPasswordRequired) {
                          dashboardAuthStatus.textContent = 'Unlocked';
                        }
                      } catch (err) {
                        if (dashboardPasswordRequired && dashboardUnlocked) {
                          dashboardUnlocked = false;
                          setProtectedVisible(false);
                          setEndpointPlaceholders();
                          dashboardAuthStatus.textContent = 'Locked / wrong password';
                          document.getElementById('requestLogs').textContent = 'Dashboard logs are locked. Enter dashboard password above.';
                          document.getElementById('systemLogs').textContent = 'Dashboard logs are locked. Enter dashboard password above.';
                        }
                      }
                    }

                    document.getElementById('send').onclick = async () => {
                      if (!dashboardUnlocked) {
                        document.getElementById('response').textContent = 'Unlock dashboard first.';
                        return;
                      }
                      const prompt = document.getElementById('prompt').value.trim();
                      if (!prompt) return;
                      const headers = { 'Content-Type': 'application/json' };
                      const proxyKey = dashboardInfo && dashboardInfo.proxy_api_key ? dashboardInfo.proxy_api_key : '';
                      if (proxyKey) {
                        headers['Authorization'] = 'Bearer ' + proxyKey;
                      }
                      const payload = {
                        model: 'auto',
                        stream: false,
                        messages: [{ role: 'user', content: prompt }]
                      };
                      const out = document.getElementById('response');
                      out.textContent = 'Sending...';
                      try {
                        const res = await fetch(endpoint, {
                          method: 'POST',
                          headers,
                          body: JSON.stringify(payload)
                        });
                        const text = await res.text();
                        out.textContent = 'HTTP ' + res.status + '\\n' + text;
                      } catch (err) {
                        out.textContent = String(err);
                      }
                      refreshLogs();
                    };

                    if (dashboardPasswordRequired) {
                      document.getElementById('requestLogs').textContent = 'Dashboard logs are locked. Enter dashboard password above.';
                      document.getElementById('systemLogs').textContent = 'Dashboard logs are locked. Enter dashboard password above.';
                    } else {
                      unlockDashboard();
                    }
                    setInterval(refreshLogs, 2500);
                  </script>
                </body>
                </html>
                """)
                .replace("__DASHBOARD_PASSWORD_REQUIRED__", dashboardPasswordRequired);
    }

    public static void run(String pat, int port) throws Exception {
        if (pat == null || pat.isBlank()) {
            pat = getSetting("QODER_PAT");
            if (pat == null || pat.isBlank()) throw new RuntimeException("Token required!");
        }
        new OpenAiBridge(pat).start(port);
        Thread.currentThread().join();
    }

    private static String getSetting(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value;
    }

    private static String getSettingOrDefault(String key, String fallback) {
        String value = getSetting(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static boolean getBooleanSetting(String key, boolean fallback) {
        String value = getSetting(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if ("1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized) || "no".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    private static boolean isDebugLogEnabled() {
        String value = getSetting("QODER_DEBUG_LOG");
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized);
    }

    private static int resolvePort() {
        String port = getSetting("QODER_PORT");
        if (port == null || port.isBlank()) {
            return 8963;
        }
        return Integer.parseInt(port);
    }

    public static void main(String[] args) throws Exception {
        run(null, resolvePort());
    }
}
