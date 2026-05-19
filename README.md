# QoderAPI

OpenAI-compatible `/v1/chat/completions` bridge for Qoder backend, with built-in dashboard, optional API-key protection, and default-on CORS.

## What This Project Does

- Accepts OpenAI chat completion requests
- Translates request/response protocol to Qoder internal API
- Supports streaming (`stream: true`) and non-streaming responses
- Supports tool calls and tool-call fallback parsing
- Exposes a simple dark dashboard for testing and runtime logs

## Quick Start

### Minimum Required Env Var

Only one variable is required:

- `QODER_PAT` = your Qoder personal access token

### Docker Run

```bash
docker run -d \
  --name qoder2api \
  -p 8963:8963 \
  -e QODER_PAT=your-pat \
  ghcr.io/foxy1402/qoderapi:latest
```

### Docker Compose

```yaml
services:
  qoder2api:
    image: ${QODER_IMAGE:-ghcr.io/foxy1402/qoderapi:latest}
    ports:
      - "8963:8963"
    environment:
      - QODER_PAT=${QODER_PAT:?QODER_PAT is required}
    restart: unless-stopped
```

## Endpoints

### API

- `POST /v1/chat/completions`

### Dashboard (enabled by default)

- `GET /` -> redirects to `/dashboard`
- `GET /dashboard`
- `GET /dashboard/api/info`
- `GET /dashboard/api/logs/requests`
- `GET /dashboard/api/logs/system`

## GHCR Publishing

This repo now includes GitHub Actions workflow:

- `.github/workflows/ghcr-publish.yml`
- Trigger: push to `main` or `master` (and manual dispatch)
- Output image: `ghcr.io/<owner>/<repo>:latest`

After you create the GitHub repo, push this code and GitHub will publish the `latest` tag automatically using `GITHUB_TOKEN`.

## Dashboard Features

- Dark theme web UI
- Copyable endpoint URL
- Warning for HTTP vs HTTPS container deployments
- Chat playground for quick testing
- Request logs and system logs (in-memory only)
- No model switch in UI (bridge does not strictly enforce model IDs)

## Auth Modes

### Upstream Auth (required)

- `QODER_PAT` is used to authenticate bridge -> Qoder upstream.

### Proxy Auth (optional, recommended for public deployment)

For client -> bridge auth on `/v1/chat/completions`:

- If `QODER_API_KEY` is set, it is required.
- If `QODER_API_KEY` is not set, auth key defaults to `QODER_PAT`.

Accepted headers:

- `Authorization: Bearer <key>`
- `X-API-Key: <key>`

### Dashboard Auth (optional)

Set one of:

- `DASHBOARD_PASSWORD`
- `DASHBOARD_PASSWORDS` (comma-separated)

Accepted via HTTP Basic password or `X-Dashboard-Password`.

## CORS

CORS is enabled by default.

- Handles preflight `OPTIONS`
- Default origin: `*`
- Override with `CORS_ALLOW_ORIGIN`

## Environment Variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `QODER_PAT` | Yes | - | Required PAT for upstream Qoder auth |
| `QODER_HOST` | No | `127.0.0.1` | Bind host |
| `QODER_PORT` | No | `8963` | Bind port |
| `DASHBOARD_ENABLED` | No | `true` | Enable/disable dashboard routes |
| `QODER_API_KEY` | No | falls back to `QODER_PAT` | Proxy API auth key |
| `DASHBOARD_PASSWORD` | No | unset | Single dashboard password |
| `DASHBOARD_PASSWORDS` | No | unset | Multiple dashboard passwords (comma-separated) |
| `CORS_ENABLED` | No | `true` | Enable/disable CORS headers and preflight |
| `CORS_ALLOW_ORIGIN` | No | `*` | Value for `Access-Control-Allow-Origin` |
| `QODER_DEBUG_LOG` | No | `false` | Verbose message-level bridge logs |

## Example Requests

### Non-stream

```bash
curl -X POST "http://localhost:8963/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-proxy-key" \
  -d '{
    "model": "auto",
    "stream": false,
    "messages": [{"role":"user","content":"Hello"}]
  }'
```

### Stream

```bash
curl -N -X POST "http://localhost:8963/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-proxy-key" \
  -d '{
    "model": "auto",
    "stream": true,
    "messages": [{"role":"user","content":"Count 1 to 5"}]
  }'
```

## Notes on Model IDs

Runtime tests show:

- Unknown model IDs are accepted by bridge path
- Missing `model` defaults to `lite`
- For online proxy usage, prefer `model: "auto"` as the default. In testing, `auto` was more consistent while `lite` sometimes returned empty content on otherwise successful requests.

So model routing should be considered loosely validated at the bridge layer.

## Known Limitations

- Stateless per-request behavior (clients should send full message history each turn)
- Only `/v1/chat/completions` is implemented
- Token usage fields are currently zeroed
- No persistent storage (logs are in-memory and reset on restart)

## Security Recommendations

- Set `QODER_API_KEY` explicitly if exposing the service publicly
- Set dashboard password(s) before exposing `/dashboard`
- Restrict `CORS_ALLOW_ORIGIN` in production
- Keep `QODER_DEBUG_LOG=false` in production to avoid content leakage in logs

## Build from Source

```bash
mvn clean package
QODER_PAT=your-pat java -jar target/qoder-client-0.1.0.jar
```

For deeper architecture and reverse-engineering notes, see [codebase_index.md](codebase_index.md).
