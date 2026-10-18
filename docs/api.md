# REST API

A local HTTP API for controlling the kiosk from Home Assistant, n8n, scripts
or anything else that speaks HTTP.

## Enabling it

The API is **off by default**. In the kiosk, tap the top-left corner five
times, enter the PIN, then under **REST API**:

1. Tick **Enable the REST API**.
2. Tap **Generate a new API token** and copy it. It is shown once; the app
   keeps only a hash. Generating another token revokes the old one.
3. Tick **Allow access from the network** only if another machine needs to
   reach the panel. Otherwise the API listens on `127.0.0.1` only.
4. **Save and restart kiosk.**

Default port: `2323`.

## Security model

- Every call needs `Authorization: Bearer <token>`, except `GET /health`
  from the device itself.
- The token is 256 random bits, compared by hash in constant time.
- Plain HTTP: on a LAN, anyone who can watch traffic can read the token.
  Keep LAN access off unless needed, and prefer a trusted network segment.
- No CORS headers are sent, so web pages in a browser cannot call the API.
- Requests are capped (8 KB lines, 64 headers, 64 KB bodies, 10 s
  timeout, 4 concurrent connections).
- Configuration can be read but not changed remotely; changes need the PIN
  on the device.

## Responses

Success: `{"ok": true}` or `{"ok": true, "data": {…}}`.

Errors use the matching HTTP status and one body shape:

```json
{ "error": { "code": "NOT_ALLOWED", "message": "The URL is outside the dashboard's origin" } }
```

| Code | Status | Meaning |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | Missing or malformed field |
| `UNAUTHORIZED` | 401 | Missing or wrong token |
| `NOT_ALLOWED` | 403 | Refused by policy (origin lock, launch allowlist) |
| `NOT_FOUND` | 404 | No such endpoint, or the app to launch is not installed |
| `UNKNOWN_COMMAND` | 404 | No such command |
| `PAYLOAD_TOO_LARGE` | 413 | Body over the limit |
| `UNSUPPORTED_CAPABILITY` | 501 | The device cannot do this |
| `UNAVAILABLE` | 503 | The kiosk screen is not running |
| `TIMEOUT` | 504 | The kiosk did not answer within 5 s |

## Endpoints

Base path: `/api/v1`.

### Reading state

| Method | Path | Returns |
|---|---|---|
| GET | `/health` | `{"status":"ok"}` |
| GET | `/device` | App, Android and WebView versions, model, capabilities |
| GET | `/telemetry` | Uptime, memory, storage, network, battery, URL, screen |
| GET | `/browser` | `{"url": …}` |
| GET | `/screen` | `{"brightness": …, "screensaver": …}` |
| GET | `/config` | Configuration without secrets |

### Commands

All are `POST` with an optional JSON body.

| Path | Body | Effect |
|---|---|---|
| `/browser/navigate` | `{"url":"http://ha.local:8123/local/panel/rooms.html"}` | Open a URL on the dashboard's origin |
| `/browser/reload` | | Reload the dashboard |
| `/screen/brightness` | `{"brightness":40}` (0–100, or -1 for system) | Set brightness. `PUT` also accepted |
| `/screen/wake` | | Close the screensaver, restart the idle timer |
| `/screen/sleep` | | Black screen at minimum brightness |
| `/screensaver/activate` | | Show the configured screensaver |
| `/screensaver/deactivate` | | Close it |
| `/audio/tts` | `{"text":"Dinner is ready","language":"en-GB"}` | Speak; text up to 1,000 characters |
| `/audio/stop` | | Stop speaking and clear the queue |
| `/apps/launch` | `{"package":"io.homeassistant.companion.android"}` | Start an app from the launch allowlist |

`screen/sleep` does not switch the panel off: that needs device-owner rights,
and `GET /device` reports `screenOff: false`.

## Examples

```bash
export OPEN_KIOSK_TOKEN=...   # from the settings screen
PANEL=http://192.0.2.10:2323

curl -s -H "Authorization: Bearer $OPEN_KIOSK_TOKEN" $PANEL/api/v1/telemetry

curl -s -X POST -H "Authorization: Bearer $OPEN_KIOSK_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"brightness":30}' $PANEL/api/v1/screen/brightness
```

### Home Assistant `rest_command`

```yaml
# configuration.yaml; put the token in secrets.yaml, never inline.
rest_command:
  panel_wake:
    url: "http://192.0.2.10:2323/api/v1/screen/wake"
    method: post
    headers:
      Authorization: !secret open_kiosk_bearer   # "Bearer <token>"
  panel_say:
    url: "http://192.0.2.10:2323/api/v1/audio/tts"
    method: post
    headers:
      Authorization: !secret open_kiosk_bearer
    content_type: "application/json"
    payload: '{"text": "{{ message }}"}'
```

For most Home Assistant setups, [MQTT](mqtt.md) is simpler: the panel shows
up as a device with no YAML at all.
