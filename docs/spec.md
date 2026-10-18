# Open Kiosk: Software Requirements Specification and Product Backlog

> Version: 1.0 draft · Date: 2026-10-18
> Target: **Android 8.1+ (API 27+)**, including the F6 wall-mounted smart-home panel
> License: **Apache-2.0**

## 1. Vision and scope

Build a free, self-hostable, open-source alternative to Fully Kiosk Browser PLUS for smart-home dashboards and unattended Android displays. No paid unlock, mandatory cloud account, subscription, root dependency, or vendor analytics.

**Primary use cases:**
- Display Home Assistant or custom web dashboards on an Android wall panel.
- Remotely control brightness, navigation, screensaver, TTS, and compatible companion apps through REST or MQTT.
- Run reliably as an unattended device, recovering from network and WebView failures.
- Optionally manage a fleet of panels from a self-hosted management server.

**Non-goals:** custom Android firmware; bypassing OS security or device-management policies; creating a new smart-home automation engine; obtaining protected HomeKit Secure Video streams; guaranteeing support for vendor-specific hardware without reverse engineering.

## 2. Technical decisions

| Area | Decision |
|---|---|
| Client | Kotlin on the Android framework (no AndroidX or Compose) and System WebView |
| Minimum supported platform | Android 8.1 / API 27 |
| Reference device | F6, 2 GB RAM / 8 GB storage, 1440×720 display |
| Kiosk modes | Standard, immersive, optionally managed Lock Task |
| Protocols | Versioned local REST, MQTT 3.1.1; optional manager connection |
| Configuration | Android persistent preferences, encrypted secret storage where supported |
| Management service | Optional Node.js/TypeScript API + Web UI, containerized |
| Plugin strategy | Compiled-in capability adapters initially; versioned extension interfaces at v1.0 |
| Distribution | Signed APK releases and source builds; no requirement for Play Store |

**Compatibility rule:** `minSdkVersion` is not the same as `targetSdkVersion`. Select and maintain a current supported target SDK while retaining runtime fallbacks for API 27. OEM limitations must be feature-detected, not assumed.

**Language rule:** performance and security come first. The app is plain Kotlin on the framework: the WebView dominates CPU and memory, its API is JVM-only, and Kotlin is memory-safe. Native code (for example Rust) is admitted only for components where profiling shows a real gain, such as network-facing parsers, and only behind a narrow, tested interface.

### 2.1 Logical architecture

Components, from the outside in:

1. **Sources of commands:** Home Assistant, n8n or a custom dashboard, and the optional self-hosted manager.
2. **Adapters:** REST and MQTT, plus the JavaScript bridge inside the page.
3. **Authorized command bus:** every command from every adapter passes through it.
4. **Executors:** the WebView runtime; screen and screensaver; TTS, app launcher and presence.
5. **Android APIs and hardware adapters**, which the executors use.

Shared commands must have consistent authorization, validation, error reporting, and capability checking regardless of whether they arrive through REST, MQTT, or the JavaScript bridge.

## 3. Functional requirements

All features require capability detection, safe failure, configuration persistence, and appropriate audit/error logging.

| ID | Feature | Release | Priority | Requirements and acceptance criteria |
|---|---|---|---|---|
| FR-01 | Fullscreen WebView | v0.1 | P0 | Start URL; JS/CSS/HTML5; WebSocket; navigation allowlist; controlled cookies/cache; offline/error page; browser compatibility reporting. A configured dashboard loads without browser chrome. |
| FR-02 | Autostart | v0.1 | P0 | `BOOT_COMPLETED`, restoration, optional Home launcher, OEM-aware foreground-start handling; no reboot loop. Validate on F6. |
| FR-03 | Kiosk / immersive | v0.1 / v0.2 | P0 | Immersive system bars; guarded exit; optional managed Lock Task where policy allows. Do not claim a tamper-proof kiosk without Device Owner/DPC. |
| FR-04 | Screen control | v0.1 | P0 | Brightness, keep-awake, idle timeout, restore previous brightness; wake/sleep only where APIs or adapters permit. Return unsupported error otherwise. |
| FR-05 | PIN-protected settings | v0.1 | P0 | Hidden entry gesture, salted slow PIN hash, retry throttling, session timeout, documented recovery. |
| FR-06 | Autorecovery | v0.1 | P0 | Connectivity observation, retry with backoff, WebView renderer crash handling, offline page, crash-loop prevention, health checks. |
| FR-07 | Remote REST API | v0.2 | P1 | Disabled by default, token authentication, scoped permissions, OpenAPI, JSON schemas, consistent errors; binding restricted by default. |
| FR-08 | MQTT client | v0.2 | P1 | MQTT 3.1.1, TLS credentials, reconnect, state/availability/ACK, expiring commands, optional HA MQTT discovery. |
| FR-09 | JavaScript bridge | v0.2 | P1 | Explicit opt-in, trusted-origin and frame restrictions, typed capability-limited messages, no arbitrary intents/shell/filesystem. |
| FR-10 | Screensaver | v0.3 | P1 | Clock, black, URL, slideshow, dimmed mode; idle/scheduled/API triggers; restore dashboard on interaction. |
| FR-11 | Motion/presence | v0.3 | P1 | MQTT/HA sensor triggers; optional front-camera detection with permission, cooldown and local processing; graceful hardware fallback. |
| FR-12 | TTS/audio | v0.2 | P1 | Android TTS, queues, cancel, language/rate/volume, audio-focus handling, API/MQTT trigger. |
| FR-13 | Remote Admin UI | v0.4 | P1 | Authenticated dashboard for device state, configuration, reload, brightness, logs and updates. |
| FR-14 | Multi-device management | v0.4 | P2 | Enrollment, inventory, grouping, policies/templates, per-device overrides, online status, acknowledgments. |
| FR-15 | Companion app launcher | v0.2 | P1 | Allowlisted package launch and validated deep links; optional return to kiosk; Android package visibility and Lock Task limitations. |
| FR-16 | OTA updates | v0.4 | P2 | Signed manifest, APK digest and signing verification, channels, download status, install handoff; no promised silent install without policy privileges. |
| FR-17 | Device telemetry | v0.1 / v0.4 | P1 | Health, Android/WebView/app version, memory, storage, network, charging, uptime, brightness, errors; optional thermal metrics if exposed. |
| FR-18 | Plugin system | v1.0 | P2 | Versioned capability/adapter interfaces; lifecycle and failure isolation; first iteration uses compiled-in adapters, not arbitrary downloaded executable code. |

### 3.1 Kiosk operation modes

- **Standard:** ordinary application; operating system navigation remains accessible.
- **Immersive:** hide system UI where supported; users may still escape using OS gestures or vendor mechanisms.
- **Managed:** device-provisioned Lock Task / launcher policies on eligible Android installations. Provisioning may require a reset and separate deployment instructions.

### 3.2 F6-specific investigation

Before promising full support, verify:
1. WebView provider/version and its update path.
2. Boot launch and default launcher behavior.
3. Display brightness and wake permissions.
4. Whether the circular secondary screen is exposed by `DisplayManager` or controlled by a proprietary interface.
5. Hardware control/button events and audio output.
6. RAM usage and thermal stability under continuous operation.
7. Ability to install and launch third-party APKs through ADB.

F6 hardware support is **experimental** until these tests pass. Avoid modifying the factory firmware for the initial release. Findings so far are in [hardware/f6.md](hardware/f6.md).

## 4. API specification

Base path: `/api/v1`. JSON errors: `{ "error": { "code": "UNSUPPORTED_CAPABILITY", "message": "...", "correlationId": "..." } }`.

| Method | Path | Scope | Purpose |
|---|---|---|---|
| GET | `/health` | health | Health status (minimal, optionally unauthenticated on loopback only) |
| GET | `/device` | read | Device metadata and capabilities |
| GET | `/telemetry` | read | Metrics |
| GET | `/browser` | read | Current navigation state |
| POST | `/browser/navigate` | control | Navigate to allowlisted URL |
| POST | `/browser/reload` | control | Refresh |
| GET | `/screen` | read | Screen settings/state |
| PUT | `/screen/brightness` | control | Set brightness |
| POST | `/screen/wake` | control | Attempt permitted wake |
| POST | `/screen/sleep` | control | Attempt permitted sleep |
| POST | `/audio/tts` | control | Speak text |
| POST | `/apps/launch` | control | Start allowlisted installed package |
| GET | `/config` | admin | Read non-secret configuration |
| PATCH | `/config` | admin | Validate and update configuration |
| POST | `/screensaver/activate` | control | Start screensaver |
| POST | `/screensaver/deactivate` | control | Return to dashboard |

**Security:** bind to loopback by default; LAN access only by explicit configuration. Prefer TLS or an authenticated outbound agent when crossing a trusted network boundary. Never embed unrestricted administrative credentials in dashboard JavaScript.

Example:

```bash
curl -X POST http://PANEL_IP:8080/api/v1/browser/navigate \
  -H "Authorization: Bearer $OPEN_KIOSK_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://home.local/dashboard"}'
```

## 5. MQTT contract

Topic root: `kiosk/{deviceId}/`.

```text
kiosk/{deviceId}/availability
kiosk/{deviceId}/state
kiosk/{deviceId}/telemetry
kiosk/{deviceId}/browser/state
kiosk/{deviceId}/screen/state
kiosk/{deviceId}/cmd/browser/navigate
kiosk/{deviceId}/cmd/browser/reload
kiosk/{deviceId}/cmd/screen/brightness
kiosk/{deviceId}/cmd/screen/wake
kiosk/{deviceId}/cmd/screen/sleep
kiosk/{deviceId}/cmd/audio/tts
kiosk/{deviceId}/cmd/apps/launch
kiosk/{deviceId}/ack/{commandId}
```

Commands use versioned JSON with command ID, timestamp, optional expiration, and payload. Reject stale, unauthorized or unknown commands. Report availability via Last Will and Testament.

## 6. Non-functional requirements

These are **initial engineering targets**, not measured results on F6.

| ID | Category | Target |
|---|---|---|
| NFR-01 | Performance | APK <15 MB target excluding WebView; process idle PSS <180 MB; app + WebView renderer <350 MB on reference page; cold app start <5 s excluding page/network. |
| NFR-02 | Availability | Recover from temporary Wi-Fi disruption and renderer death; 72h unattended F6 MVP soak; 7d at v1.0. |
| NFR-03 | Security | API off by default, scope-based auth, origin-checked JS messaging, TLS where appropriate, secrets protected, no root requirement. |
| NFR-04 | Compatibility | Test on Android 8.1, 10, 12, 14 and a current Android version; runtime capability probing. |
| NFR-05 | Privacy | No external analytics; optional camera processing local-only by default; configurable logs retention. |
| NFR-06 | Maintainability | Kotlin modules, CI, tests, linting, versioned contracts, changelog, contribution docs. |

## 7. Repository layout

Target layout as the project grows (v0.1 starts with a single `android/app` module):

```text
open-kiosk/
├── android/
│   ├── app/
│   ├── core/{browser,kiosk,security,screen,settings,telemetry}/
│   ├── integrations/{rest,mqtt,javascript}/
│   ├── features/{audio,presence,screensaver,app-launcher}/
│   └── hardware/{standard,f6}/
├── manager/{api,web,docker}/
├── contracts/{openapi,mqtt,json-schema}/
├── docs/
├── examples/{home-assistant,n8n,mqtt}/
├── site/
└── .github/
```

## 8. Product backlog

Story points are preliminary relative-complexity estimates, not guaranteed elapsed time. `KSK-032` is an unestimated research spike.

| ID | Release | Epic | Story | Priority | SP |
|---|---|---|---|---|---:|
| KSK-001 | v0.1 | Foundation | Android Gradle project, Kotlin modules, CI | P0 | 5 |
| KSK-002 | v0.1 | Browser | Fullscreen WebView with URL configuration | P0 | 5 |
| KSK-003 | v0.1 | Browser | Navigation allowlist, errors, cache, cookies | P0 | 5 |
| KSK-004 | v0.1 | Kiosk | Immersive mode and navigation handling | P0 | 5 |
| KSK-005 | v0.1 | Kiosk | Boot receiver and launcher integration | P0 | 8 |
| KSK-006 | v0.1 | Security | Settings screen, PIN, recovery flow | P0 | 5 |
| KSK-007 | v0.1 | Screen | Brightness and keep-awake controls | P0 | 3 |
| KSK-008 | v0.1 | Reliability | Network recovery and WebView renderer handling | P0 | 8 |
| KSK-009 | v0.1 | Observability | Health and basic telemetry | P0 | 3 |
| KSK-010 | v0.1 | Compatibility | F6 Android 8.1 hardware validation | P0 | 5 |
| KSK-011 | v0.2 | Remote API | Authenticated REST server | P1 | 8 |
| KSK-012 | v0.2 | Remote API | OpenAPI schemas and REST commands | P1 | 5 |
| KSK-013 | v0.2 | MQTT | Broker connection, TLS, reconnection | P1 | 5 |
| KSK-014 | v0.2 | MQTT | Commands, states, ACK, availability | P1 | 5 |
| KSK-015 | v0.2 | Bridge | Secure JavaScript bridge | P1 | 8 |
| KSK-016 | v0.2 | Audio | TTS engine and command queue | P1 | 5 |
| KSK-017 | v0.2 | Apps | Allowlisted companion app launcher | P1 | 5 |
| KSK-018 | v0.2 | Kiosk | Optional managed Lock Task provisioning | P1 | 8 |
| KSK-019 | v0.3 | Screensaver | Idle/screensaver state machine | P1 | 5 |
| KSK-020 | v0.3 | Screensaver | Clock, black, URL, photo modes | P1 | 5 |
| KSK-021 | v0.3 | Presence | MQTT/HA-based presence adapter | P1 | 5 |
| KSK-022 | v0.3 | Presence | Camera motion detection and permission UX | P1 | 13 |
| KSK-023 | v0.3 | Automation | Scheduling and device wake policies | P1 | 8 |
| KSK-024 | v0.4 | Manager | Registration and secure enrollment | P1 | 8 |
| KSK-025 | v0.4 | Manager | Node.js API and device state registry | P1 | 8 |
| KSK-026 | v0.4 | Manager | Web administration and device inventory | P1 | 13 |
| KSK-027 | v0.4 | Manager | Groups, config templates and overrides | P2 | 8 |
| KSK-028 | v0.4 | Updates | Signed APK manifests and verification | P1 | 8 |
| KSK-029 | v0.4 | Updates | Android update installation flow | P1 | 8 |
| KSK-030 | v0.4 | Telemetry | Advanced telemetry and log diagnostics | P2 | 5 |
| KSK-031 | v1.0 | Plugins | Versioned plugin capability interfaces | P2 | 8 |
| KSK-032 | v1.0 | Plugins | F6 secondary display/hardware adapter research | Spike | TBD |
| KSK-033 | v1.0 | Quality | Security audit, soak tests, compatibility suite | P0 | 13 |
| KSK-034 | v1.0 | OSS | Docs, examples, release automation, contributing | P1 | 8 |

### Release planning

| Release | Scope | Est. SP | Provisional solo-developer timeline* |
|---|---|---:|---|
| v0.1 | Functional Kiosk | 52 | 2–4 weeks |
| v0.2 | Connected Kiosk | 49 | 2–3 weeks |
| v0.3 | Smart Kiosk | 36 | 2–4 weeks |
| v0.4 | Managed Kiosk | 58 | 3–5 weeks |
| v1.0 | Extensible Platform / Quality | 29 + spike | 2–4 weeks |
| **Total** | | **224 + spike** | |

\* Planning ranges assume experienced Android development with AI assistance; not a commitment and do not cover unpredictable OEM limitations or extensive external security audits.

## 9. Acceptance tests and Definition of Done

### v0.1 — F6 Daily Driver

- [ ] APK installs on unmodified F6 Android 8.1 via ADB.
- [ ] Configured dashboard starts and fills the main display.
- [ ] Screen stays awake or dims according to configuration.
- [ ] PIN prevents unauthorized settings changes.
- [ ] Temporary network loss recovers automatically.
- [ ] Three reboot cycles restore configured kiosk behavior under documented F6 setup.
- [ ] 72-hour unattended soak test passes without manual intervention.
- [ ] Known limitations are documented, including secondary display behavior.

### v0.2 — Connected Kiosk

- [ ] Raspberry Pi / n8n can securely navigate and reload F6 over REST.
- [ ] MQTT disconnect/reconnect, ACK and command expiry pass automated tests.
- [ ] TTS works where an engine/voice is installed.
- [ ] Native app launcher rejects packages not on allowlist.
- [ ] Untrusted web origins cannot access privileged native capabilities.

### v0.3 — Smart Kiosk

- [ ] Configured idle timeout activates and deactivates screensaver.
- [ ] MQTT presence state triggers specified screen behavior.
- [ ] Camera motion is opt-in and handles missing permissions.

### v0.4 — Managed Kiosk

- [ ] At least 10 test devices can be registered and managed independently.
- [ ] Offline devices do not block healthy devices.
- [ ] Versioned device configuration is acknowledged or rolled back on error.
- [ ] APK verification rejects corrupted or incorrectly signed updates.
- [ ] Update flows respect Android installation approval requirements.

### v1.0 — Production Release

- [ ] 7-day unattended soak test on supported reference devices.
- [ ] Security review of REST, MQTT, JavaScript bridge and update pipeline.
- [ ] API and plugin interfaces documented and versioned.
- [ ] Compatibility matrix published.
- [ ] License, contribution guidelines, code of conduct and disclosure process published.

**Common Definition of Done:** code reviewed; acceptance criteria met; unit/instrumentation tests added where appropriate; CI green; documentation and schemas updated; no regression on API 27 reference device; unsupported hardware operations fail explicitly and safely.

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Old WebView on F6 | Probe version, reference-page compatibility tests; publish minimum engine requirements and graceful error UI. |
| OEM blocks background autostart | Offer default launcher/device-managed paths and document constraints. |
| Power-off/wake unavailable without privileges | Treat as optional capability; use brightness/screensaver fallback. |
| Local API is exposed on LAN | Disabled by default, authenticated scopes, narrow binding, optional management agent. |
| Arbitrary JS controls native APIs | Strict origin and frame validation, opt-in capabilities, no shell/filesystem access. |
| Unattended camera permission/background limits | Explicit foreground/permission behavior and sensor-based alternative. |
| Silent APK upgrades not permitted | Use installer confirmation or provisioned device-management capabilities only. |
| F6 proprietary secondary display | Isolate in experimental hardware adapter and postpone until validated. |
| Google Play policy for kiosk-type apps | Avoid restricted permissions (for example `QUERY_ALL_PACKAGES`) unless the feature needs them and the policy declaration is ready; keep a sideload APK channel. |
