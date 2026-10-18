# JavaScript bridge

Lets the dashboard itself use device features through `window.openKiosk`:
the same commands as the [REST API](api.md), plus hardware such as a
secondary screen.

## Enabling it

Off by default. In settings (five taps top-left, then the PIN), under
**JavaScript bridge**, tick **Let the dashboard use window.openKiosk**, and
for secondary-screen drawing also **Allow drawing on the secondary screen**.

## Security model

- Only pages on the dashboard's origin (scheme, host, port of the start URL)
  get an answer. Every call re-checks the page currently shown.
- Typed capabilities only: no shell, no files, no arbitrary intents, no
  configuration changes.
- **Limitation:** Android cannot tell which frame made a call. A
  cross-origin `<iframe>` inside your dashboard could reach the bridge. Only
  enable it for dashboards whose iframes you trust.

## Waiting for it

The app injects `window.openKiosk` after each page load and then fires
`openkioskready`:

```js
function whenKioskReady(callback) {
  if (window.openKiosk) callback(window.openKiosk);
  else window.addEventListener('openkioskready', () => callback(window.openKiosk), { once: true });
}
```

Outside Open Kiosk, or with the bridge disabled, `window.openKiosk` never
appears, so dashboards keep working in a normal browser.

## API

Calls are synchronous. Failures throw an `Error` with a `code` matching the
[REST error codes](api.md#responses).

```js
openKiosk.capabilities();
// → { secondaryScreen: true, textToSpeech: true, screenOff: false, wakeScreen: true, secondaryScreenEnabled: true }

openKiosk.command('screen/brightness', { brightness: 30 });
openKiosk.command('audio/tts', { text: 'Front door opened' });
openKiosk.command('screensaver/activate');
```

### Secondary screen

On hardware with one, such as the F6 panel's round 240×240 screen:

```js
whenKioskReady((kiosk) => {
  if (!kiosk.capabilities().secondaryScreen) return;

  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 240;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#111';
  ctx.fillRect(0, 0, 240, 240);
  ctx.fillStyle = '#e1e1e1';
  ctx.font = '600 72px Roboto, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(new Date().toTimeString().slice(0, 5), 120, 120);

  kiosk.secondaryScreen.draw(canvas);   // scaling, pixel format and mirroring are handled
  kiosk.secondaryScreen.backlight(true);
});
```

Draw only when the content changes. Images over about 500 KB are refused.
