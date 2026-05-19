# Qoder2API Codebase Index

> **Last Updated:** 2026-05-19
> **Purpose:** Practical codebase map for maintenance and reverse-engineering changes

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Directory Structure](#directory-structure)
3. [Runtime Endpoints](#runtime-endpoints)
4. [Source Files](#source-files)
5. [Architecture Summary](#architecture-summary)
6. [Authentication and Signing Flow](#authentication-and-signing-flow)
7. [OpenAI Message/Tool Translation](#openai-messagetool-translation)
8. [Dashboard, Auth, and CORS](#dashboard-auth-and-cors)
9. [Configuration (Env Vars)](#configuration-env-vars)
10. [Operational Findings](#operational-findings)
11. [Known Limitations](#known-limitations)
12. [Dependencies](#dependencies)
13. [Build and Run](#build-and-run)
14. [Security Notes](#security-notes)
15. [Maintenance Priorities](#maintenance-priorities)

---

## Project Overview

| Attribute | Value |
|---|---|
| Project Name | Qoder2API |
| Maven Artifact | `us.cubk:qoder-client:0.1.0` |
| Language | Java 17 |
| Server Runtime | JDK `HttpServer` (`com.sun.net.httpserver.HttpServer`) |
| Outbound Clients | Java HttpClient + Apache HttpClient 5 (SSE path) |
| Main Class | `us.cubk.OpenAiBridge` |
| Primary Goal | OpenAI-compatible `/v1/chat/completions` bridge to Qoder backend |
| State Model | Stateless request handling + in-memory logs only |

Qoder2API is a reverse-engineered adapter that receives OpenAI-style chat requests and translates them into Qoder's internal encoded/signed protocol.

---

## Directory Structure

```text
qoderapi/
├── Dockerfile
├── docker-compose.yaml
├── pom.xml
├── README.md
├── baseprompt.json
├── codebase_index.md
├── images/
│   └── img.png
└── src/
    └── main/
        └── java/
            └── us/
                └── cubk/
                    ├── OpenAiBridge.java
                    ├── BearerApiClient.java
                    ├── BearerBuilder.java
                    ├── SignatureApiClient.java
                    ├── Signature.java
                    ├── QoderEncoding.java
                    └── LocalAuth.java
```

---

## Runtime Endpoints

### Bridge API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI-compatible chat completion endpoint |

### Dashboard/UI (when enabled)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Redirects to `/dashboard` if dashboard enabled |
| `GET` | `/dashboard` | Built-in dark dashboard UI |
| `GET` | `/dashboard/api/info` | Dashboard runtime info |
| `GET` | `/dashboard/api/logs/requests` | In-memory request logs |
| `GET` | `/dashboard/api/logs/system` | In-memory system logs |
| `OPTIONS` | `*` | CORS preflight support (when enabled) |

### Upstream Qoder APIs

| Endpoint | Purpose | Class |
|---|---|---|
| `https://center.qoder.sh/algo/api/v3/user/jobToken` | PAT -> job token identity exchange | `SignatureApiClient` |
| `https://center.qoder.sh/algo/api/v3/user/status` | user status check | `SignatureApiClient` |
| `https://center.qoder.sh/algo/api/v1/heartbeat` | heartbeat event | `SignatureApiClient` |
| `https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation` | main generation stream | `BearerApiClient` |

---

## Source Files

### `OpenAiBridge.java`

Main server and translation layer.

Key responsibilities:
- Creates and runs `HttpServer`
- Handles `/v1/chat/completions`
- Builds Qoder request body from `baseprompt.json`
- Converts OpenAI roles/messages/tools to Qoder format
- Reads upstream SSE deltas and maps back to OpenAI chunks/responses
- Implements dashboard routes and in-memory logs
- Implements optional proxy API-key auth and dashboard auth
- Implements default-on CORS support

Notable behavior:
- Defaults incoming missing model to `lite`
- Sends upstream with `stream=true` regardless of downstream mode; non-stream mode is assembled by buffering streamed delta content
- Session/request IDs are regenerated per request

Inner types:
- `BridgeDelta`
- `StreamAccumulator`
- `ToolCallAccumulator`

### `BearerApiClient.java`

Authenticated client for Qoder generation API.

Highlights:
- Builds signed bearer headers per request
- Encodes JSON payload with `QoderEncoding`
- Uses Apache HttpClient streaming path for SSE line handling
- No forced 3-second idle cut-off anymore in stream loop (prevents premature truncation)

### `BearerBuilder.java`

Session and bearer construction.

Highlights:
- Generates per-process session context
- RSA encrypts temp AES key (`SERVER_PUBKEY_PEM` hardcoded)
- AES-CBC encrypts auth payload with temp key
- Generates request signature via MD5 concatenation protocol

### `SignatureApiClient.java`

Client for center API endpoints (`jobToken`, `status`, `heartbeat`) with `Signature` + `QoderEncoding`.

### `QoderEncoding.java`

Custom Base64 variant:
- standard Base64
- rotate thirds
- remap to custom 64-char alphabet
- custom pad char `$`

### `Signature.java`

Utility for center API signature:
- RFC1123 UTC date
- `md5("cosy&<SECRET>&<date>")`
- now explicitly uses UTF-8 bytes

### `LocalAuth.java`

Local helper to decode `~/.qoder/.auth/user` using machine-id derived AES key.
Useful for reverse engineering and diagnostics.

---

## Architecture Summary

```text
OpenAI Client
   |
   |  POST /v1/chat/completions
   v
OpenAiBridge (local proxy)
   |
   |  signed + encoded POST (SSE upstream)
   v
Qoder API (api3.qoder.sh)
```

Pattern:
- Adapter/bridge between OpenAI schema and Qoder private schema.
- No DB, no persistent cache, no queue.
- Per-request stateless processing.

Concurrency:
- `Executors.newFixedThreadPool(8)` for inbound HTTP.
- SSE reader thread feeds queue in `BearerApiClient` stream handler.

---

## Authentication and Signing Flow

1. Server boot reads PAT (`QODER_PAT` unless explicit argument).
2. `SignatureApiClient.exchangeJobToken(pat)` gets Qoder identity tokens.
3. `BearerBuilder.newSession(...)` builds session crypto context:
   - random temp key
   - RSA-encrypted key (`cosyKey`)
   - AES-encrypted identity payload (`info`)
4. For each upstream call:
   - Encode body with `QoderEncoding`
   - Build payload base64 metadata
   - Sign with MD5 concat scheme
   - Send `Authorization: Bearer COSY.<payload>.<sig>` plus required headers

---

## OpenAI Message/Tool Translation

OpenAI -> Qoder:
- `user`: converted to `contents:[{type:text}]` and blank `content`
- `assistant`: passes text; may include normalized `tool_calls`
- `tool`: preserved as `tool` when tools enabled; rendered as user text fallback if tools disabled
- `system`: template system message retained unless explicit incoming system exists

Qoder -> OpenAI:
- Reads delta from nested `body.choices[].delta`
- Re-emits role/content/tool_calls chunks for streaming
- Non-stream mode accumulates final response and tool call arguments

Fallback handling:
- Supports parsing textual `Tool calls:` blocks when structured calls are absent.

---

## Dashboard, Auth, and CORS

### Dashboard

Implemented as server-rendered static HTML in `OpenAiBridge`.

Features:
- Dark theme UI
- Endpoint copy field (`/v1/chat/completions` full origin)
- Warning about HTTP vs HTTPS in hosted container environments
- Simple playground for test chats
- Request logs and system logs views (in-memory)
- Model switch intentionally omitted (model IDs are not strictly enforced by bridge)

### Proxy API Auth

- If `QODER_API_KEY` is set, it is required for `/v1/chat/completions`.
- If `QODER_API_KEY` is not set, bridge now falls back to using `QODER_PAT` as the API key.
- Accepts either:
  - `Authorization: Bearer <key>`
  - `X-API-Key: <key>`

### Dashboard Auth

Optional; if password env vars are present:
- `DASHBOARD_PASSWORD` (single)
- `DASHBOARD_PASSWORDS` (comma-separated)

Accepted via:
- HTTP Basic auth password field
- `X-Dashboard-Password` header

### CORS

Enabled by default.
- Handles preflight `OPTIONS`
- Configurable allow-origin

---

## Configuration (Env Vars)

| Variable | Default | Description |
|---|---|---|
| `QODER_PAT` | required | Upstream Qoder PAT |
| `QODER_HOST` | `127.0.0.1` | Bind host |
| `QODER_PORT` | `8963` | Bind port |
| `DASHBOARD_ENABLED` | `true` | Enables `/dashboard` and dashboard APIs |
| `QODER_API_KEY` | fallback to `QODER_PAT` | API auth key for `/v1/chat/completions` |
| `DASHBOARD_PASSWORD` | unset | Single dashboard password |
| `DASHBOARD_PASSWORDS` | unset | Comma-separated dashboard passwords |
| `CORS_ENABLED` | `true` | Enables CORS headers + preflight |
| `CORS_ALLOW_ORIGIN` | `*` | `Access-Control-Allow-Origin` value |
| `QODER_DEBUG_LOG` | `false` | Enables verbose message content logging |

---

## Operational Findings

### 1) Model ID Handling Probe (2026-05-19)

Runtime test against image `sha256:edb966978d328c3dc5bd514232be654cd63fc039aa0adf7c12d7f8d70af0d0a0`:

| Request | Result |
|---|---|
| `model: "lite"` | success |
| `model: "auto"` | success |
| `model: "fakemodel00"` | success |
| model omitted | success, returned model defaults to `lite` |

Conclusion: bridge does not strictly validate model IDs at boundary.

### 2) Multi-turn Server-side Reuse Retest (2026-05-19)

- Client-managed history (`messages` includes prior turns): works.
- Server-managed reuse attempt (new request without prior history): no memory.
- Explicit `session_id` reuse attempt: still no memory; response remained stateless.
- In this retest, no hang occurred.

Conclusion: behavior is stateless for practical purposes.

### 3) Lite model quirk observed during tests

In some runs, `lite` returned empty assistant content while request succeeded (`finish_reason=stop`). `auto` produced normal text responses in the same environment.

---

## Known Limitations

1. Token usage fields are currently hardcoded to zero.
2. Only `/v1/chat/completions` is implemented (no `/v1/models`, `/v1/embeddings`, etc.).
3. Stateless by design at bridge layer; conversation continuity requires full history from client.
4. Reverse-engineered protocol and hardcoded upstream constants may break if Qoder internals change.
5. No automated tests in repository.

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `org.apache.httpcomponents.client5:httpclient5` | 5.3.1 | SSE-capable streaming HTTP client path |
| `com.fasterxml.jackson.core:jackson-databind` | 2.16.1 | JSON parsing/serialization |
| `org.jsoup:jsoup` | 1.17.2 | currently unused (can be removed) |

---

## Build and Run

### Maven

```bash
mvn clean package
QODER_PAT=your-token java -jar target/qoder-client-0.1.0.jar
```

### Docker

```bash
docker build -t qoder2api .
docker run -p 8963:8963 -e QODER_PAT=your-token qoder2api
```

### Docker Compose

```bash
QODER_PAT=your-token docker-compose up
```

---

## Security Notes

- PAT is env-driven (good), but if API-key fallback to PAT is used, treat proxy endpoint as sensitive.
- Hardcoded public key and signing secret are protocol-coupled and expected for reverse-engineered behavior.
- Content logging should stay off in production (`QODER_DEBUG_LOG=false`) to reduce leakage risk.
- CORS default `*` is convenient for local use; tighten in hosted deployments.
- Dashboard should be password-protected when exposed publicly.

---

## Maintenance Priorities

1. Add smoke tests for: non-stream, stream, tool calls, auth failure path, dashboard endpoints.
2. Remove `jsoup` if still unused.
3. Add optional `/health` endpoint.
4. Optionally expose `/v1/models` with static model metadata for better client compatibility.

---

*This index reflects the current codebase state and runtime findings as of 2026-05-19.*
