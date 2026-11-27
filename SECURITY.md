# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Report privately via GitHub Security Advisories on this repository
(**Security → Report a vulnerability**). Include a description, reproduction
steps, and the affected component (accessibility service, parser, updater,
local storage).

This is a hobby project maintained in spare time: expect an acknowledgement
within a few days rather than hours. Coordinated disclosure is appreciated —
give a reasonable window to ship a fix before publishing details.

## Scope

Shiftlit is an Android app with no backend. There is no account, no server,
no sync and no token to attack.

| In scope | Out of scope |
|---|---|
| Local data exposure — anything that lets another app read Shiftlit's private storage | Attacks requiring physical access to an unlocked device |
| Accessibility service escaping its declared package scope | Vulnerabilities in the gig platforms themselves |
| Parsing of malicious screen content (crafted labels, extreme node counts) | DoS against GitHub, or against the update mechanism by withholding releases |
| Updater — accepting an APK that is not from this repository's releases | Issues that require a rooted or compromised device |
| Intent handling, exported components, file paths | Informational scanner output with no impact |

## Design notes

- **On-device only.** Offers, diagnostics, earnings, mileage and settings are
  stored in app-private storage. Nothing is uploaded.
- **Scoped accessibility.** The service is restricted to the watched driver-app
  packages declared in `accessibility_service_config.xml`; events from other
  packages are ignored.
- **No screen exfiltration.** Screen text is parsed in memory. The stored
  snapshot is capped and stays local; the only way it leaves the phone is if
  the user exports a report and sends it themselves.
- **Updater.** The app reads this repository's latest GitHub release and only
  accepts an asset whose name starts with `shiftlit-`. Installs go through the
  system installer, which verifies the signing certificate, so an APK signed by
  another key is rejected by Android.
- **No dynamic code.** No plugins, no remote configuration, no downloaded
  scripts.

## Known limitations

- **Heuristic parsing.** A crafted or unusual layout can make the parser read
  the wrong number. That is a correctness issue with a visible trail in
  Diagnostics, not a compromise; still worth reporting if it can be triggered.
- **Accessibility automation.** Accept/decline gestures are synthetic taps
  restricted to the watched apps. Enabling automation is the user's decision
  and may conflict with a platform's terms of service.
- **Signed builds only.** Debug builds are signed with a throwaway key and
  should not be installed as a daily driver.

## Supported versions

Only the latest release is supported. Check
[Releases](https://github.com/amvuser81/Shiftlit/releases) for the current
version.
