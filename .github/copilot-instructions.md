# Copilot instructions for QoderAPI

## Build, test, lint
- Build: `mvn clean package`
- Run the jar: `QODER_PAT=your-token java -jar target/qoder-client-0.1.0.jar`
- Tests: no automated tests are present in the repository.
- Lint: no lint task is configured.

## High-level architecture
- `OpenAiBridge` is the main server, built on the JDK `HttpServer`, and owns all HTTP routes (`/v1/chat/completions` plus optional `/dashboard` and `/dashboard/api/*`).
- Startup flow: `OpenAiBridge` exchanges `QODER_PAT` via `SignatureApiClient`, then `BearerBuilder` creates a session context used by `BearerApiClient` for all upstream requests.
- Request translation: incoming OpenAI-style requests are merged into `baseprompt.json`, then mapped to Qoder’s internal schema; upstream responses are SSE deltas that get re-emitted as OpenAI streaming chunks or accumulated for non-stream responses.
- State and logging: the service is stateless per request; dashboard request/system logs are stored in-memory only.

## Key conventions
- All Qoder upstream requests are encoded and signed: `QoderEncoding.encode(...)` + `BearerBuilder.signRequest(...)` (for SSE) or `Signature.sign(...)` (for center APIs).
- Proxy auth is optional: if `QODER_API_KEY` is unset, it falls back to `QODER_PAT`. Dashboard auth is enabled only when password env vars are present.
- CORS and preflight handling are enforced in every route via `handleCorsPreflight`/`applyCors`; new endpoints should follow the same pattern.
- Non-stream responses are built by buffering the upstream SSE stream; upstream is always called with `stream=true`.
