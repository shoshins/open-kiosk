# MQTT and Home Assistant

With MQTT enabled, the panel appears in Home Assistant as a device, with no
YAML: reload and wake buttons, a brightness slider, a screensaver switch, a
URL field, a "Speak" field and diagnostic sensors.

## Enabling it

In the kiosk settings (five taps top-left, then the PIN), under **MQTT**:

- **Broker host** and **Port**: for the Mosquitto add-on, your Home
  Assistant host and `1883`.
- **Use TLS** if the broker offers it (usually port `8883`). The certificate
  must chain to a CA Android trusts, and its name must match the host.
- **Username** and **Password**: create a dedicated Home Assistant user for
  the panel. The password is encrypted with a key held in the Android
  Keystore.
- **Home Assistant discovery**: on by default.

Then **Save and restart kiosk**. The device shows up as *Open Kiosk ok-…* under
**Settings → Devices & services → MQTT**.

## Entities

| Entity | Type | Does |
|---|---|---|
| Reload | button | Reload the dashboard |
| Wake screen | button | Close the screensaver |
| Sleep screen | button | Black screen at minimum brightness |
| Brightness | number 0–100 | Set brightness |
| Screensaver | switch | Show or hide the screensaver |
| Dashboard URL | text | Navigate (same origin only) |
| Speak | text | Text-to-speech |
| Free memory, Uptime, Battery | sensors | Diagnostics, updated every minute |

### Wake the panel when someone walks up

```yaml
automation:
  - alias: Wake the hallway panel on motion
    triggers:
      - trigger: state
        entity_id: binary_sensor.hallway_motion
        to: "on"
    actions:
      - action: button.press
        target:
          entity_id: button.open_kiosk_ok_0123456789_wake
```

Use the entity ID Home Assistant shows for your panel.

## Topics

Root: `kiosk/{deviceId}/`, where the device ID is shown in settings under
**Device** (for example `ok-0123456789`).

| Topic | Direction | Retained | Content |
|---|---|---|---|
| `availability` | out | yes | `online` / `offline` (Last Will) |
| `state` | out | yes | `{"running":true}` |
| `screen/state` | out | yes | `{"brightness":…,"screensaver":…}` |
| `browser/state` | out | yes | `{"url":…}` |
| `telemetry` | out | yes | Same as `GET /api/v1/telemetry`, every minute |
| `cmd/<command>` | in | | A command; names as in the [REST API](api.md) |
| `ack/<id>` | out | | The outcome of a command that carried an `id` |

`cmd/screensaver/set` additionally accepts `ON`/`OFF`.

## Command payloads

**Plain values**, as Home Assistant sends them:

| Topic | Payload |
|---|---|
| `cmd/browser/reload` | anything, for example `PRESS` |
| `cmd/screen/brightness` | `40` |
| `cmd/browser/navigate` | `http://ha.local:8123/local/panel/rooms.html` |
| `cmd/audio/tts` | `Dinner is ready` |

**The versioned envelope**, for automations that need confirmation:

```json
{
  "v": 1,
  "id": "a1b2c3",
  "expires": 1792340000000,
  "payload": { "brightness": 40 }
}
```

- `expires` is epoch milliseconds; late commands are rejected, not run.
- With an `id` (letters, digits, `-`, `_`; up to 64), the outcome is
  published to `kiosk/{deviceId}/ack/{id}` in the [REST response
  shape](api.md#responses).

## Security notes

- Anyone who can publish to `kiosk/{deviceId}/cmd/#` can control the panel.
  Restrict that with broker ACLs, especially on shared brokers.
- Use TLS when the broker is not on a trusted local network.
- Navigation commands are still bound by the origin lock.
