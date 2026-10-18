# Use case: your own Home Assistant dashboards on the F6 wall panel

This guide shows how to run a dashboard you build yourself on the F6 panel,
with Home Assistant as both the web server and the data source, and the
panel's rotary knob driving it. It is based on a real installation.

## How the pieces fit together

**1. Home Assistant (your server)**

- Serves your dashboard as static files from `/config/www`, which Home
  Assistant publishes under `/local/`.
- Provides live entity states and service calls over its WebSocket API.

**2. Open Kiosk (on the panel)**

- Loads the dashboard URL full screen and locks navigation to your Home
  Assistant origin.
- Lets knob turns and presses through to the page as ordinary key events.
- Reloads after network drops and starts again after a reboot.

**3. F6 hardware**

| Part | What it shows | Status |
|---|---|---|
| Main touch screen, 960×480 CSS px | Rooms, lights, climate, camera snapshots | Works with Open Kiosk v0.1 |
| Rotary knob | Turn to switch rooms, press to act | No adapter needed: keys reach the page |
| Round screen, 240×240 | Clock and weather, or the selected room and its set temperature | Needs the F6 hardware adapter (KSK-032) |

There is no extra server: Home Assistant already hosts the files and the
data.

## 1. Build and host the dashboard

Any static web app works: plain HTML, React, Svelte. Build it, then copy the
output into Home Assistant:

```bash
# On the machine running Home Assistant; the path depends on your install.
mkdir -p /config/www/wall-panel
cp -r dist/* /config/www/wall-panel/
```

It is now at `http://homeassistant.local:8123/local/wall-panel/index.html`.

Files under `/local/` are served **without authentication**. Never put
tokens or other secrets in them. Sign in at runtime instead (step 2).

### Design for the F6 screen

- The viewport is **960×480 CSS pixels** (1440×720 at device-pixel ratio 1.5).
- The system WebView is **Chromium 61**. Build for `chrome61`, and use
  `grid-gap` rather than `gap` in CSS grid. See
  [the hardware notes](../hardware/f6.md) for the full list.

## 2. Connect to Home Assistant

Use the official
[`home-assistant-js-websocket`](https://github.com/home-assistant/home-assistant-js-websocket)
library. Its OAuth flow signs the panel in through Home Assistant's own login
page and keeps the tokens in the browser's local storage, so no credential is
ever baked into the dashboard files.

```js
import { getAuth, createConnection, subscribeEntities, callService } from 'home-assistant-js-websocket';

const auth = await getAuth({ hassUrl: 'http://homeassistant.local:8123' });
const connection = await createConnection({ auth });

subscribeEntities(connection, (entities) => render(entities));

// For example, from a button:
callService(connection, 'light', 'toggle', { entity_id: 'light.living_room' });
```

## 3. Drive it with the knob

The knob arrives as function keys:

| Action | `event.key` |
|---|---|
| Turn left | `F1` |
| Turn right | `F2` |
| Press | `F3` |
| Long press | `F8` |

```js
const rooms = ['overview', 'living', 'bedroom', 'office'];
let index = 0;

window.addEventListener('keydown', (event) => {
  const step = event.key === 'F1' ? -1 : event.key === 'F2' ? 1 : 0;
  if (step) {
    event.preventDefault();
    index = (index + step + rooms.length) % rooms.length;
    showRoom(rooms[index]);
  } else if (event.key === 'F3') {
    event.preventDefault();
    toggleCurrentRoomLights();
  }
});
```

## 4. Point Open Kiosk at it

1. Install Open Kiosk: `adb install open-kiosk.apk`.
2. Open it and enter `http://homeassistant.local:8123/local/wall-panel/index.html`.
3. Choose Open Kiosk when Android asks for the Home app, so the panel boots
   straight into the dashboard.

Sign in to Home Assistant once on the panel. The session persists in the
WebView's storage across reboots.

## 5. The round screen (planned)

The round screen is a separate 240×240 framebuffer, not an Android display
(details in [the hardware notes](../hardware/f6.md)). Open Kiosk will expose
it to trusted origins as one typed capability, planned under KSK-032:

```js
// Planned API. The names may change before release.
if (window.openKiosk?.secondaryScreen) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 240;
  drawClock(canvas.getContext('2d'));
  openKiosk.secondaryScreen.draw(canvas);   // the app handles pixel format and mirroring
  openKiosk.secondaryScreen.backlight(true);
}
```

Typical content: the time and outdoor weather on the overview, and the
selected room's name and set temperature while a room is selected. Render
only when the content changes; each frame is about 115 KB.

## Troubleshooting

- **Blank page or layout broken only on the panel:** a CSS or JavaScript
  feature newer than Chromium 61. Check `adb logcat` for console errors.
- **Knob does nothing:** the page has no focus. Tap the screen once, and make
  sure no other app is in front of Open Kiosk.
- **Signed out after a reboot:** the WebView's storage was cleared. Sign in
  again; check that the device is not clearing app data.
