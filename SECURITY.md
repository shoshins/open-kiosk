# Security policy

Open Kiosk runs unattended on home and business networks, so security reports
are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private vulnerability
reporting: the **Security** tab of this repository, then **Report a
vulnerability**.

Include what you found, how to reproduce it, the affected version, and the
impact as you understand it. You can expect an acknowledgment within 7 days.
Please give us a reasonable time to release a fix before disclosing publicly.

## Supported versions

Until v1.0, only the latest release receives security fixes.

## Scope

In scope: the Android app, the JavaScript bridge, the REST and MQTT
interfaces, the update pipeline and the self-hosted manager.

Out of scope: vulnerabilities in Android System WebView itself (report them
to the Chromium project), and devices whose firmware is already compromised.
