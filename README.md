<div align="center">

<img src="icon.png" width="128" height="128" alt="Shiftlit" />

# Shiftlit

**Your shift. One decision.**

Based on the MIT-licensed driver assistant by Emre Karaaslan (w0wzahh), rebuilt around a driver-utility interface.

An open-source Android app for rideshare and delivery drivers: reads offers
on-screen, scores them against your rules, optionally accepts or declines for
you, and tracks the rest of your work automatically — mileage, earnings, and
where you actually drive.

[![License: MIT](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](android)
[![Release](https://img.shields.io/github/v/release/amvuser81/Shiftlit)](https://github.com/amvuser81/Shiftlit/releases)

</div>

---

## What it is

Shiftlit is a **free, open-source alternative to paid driver-assistant
apps** like Mystro. It uses Android's accessibility APIs — the same approach
the paid apps use — to watch the driver apps you choose, read each offer card
as it appears, score it against thresholds you set, and show a floating
verdict. Enable automation and it can accept the good ones (on a cancellable
countdown) or decline the bad ones — hands-free.

Everything runs on-device. No account required, no subscription, no tracking.

> Shiftlit is not affiliated with, endorsed by, or connected to Uber, Lyft,
> DoorDash, Wolt, foodora, or any other platform. All names are trademarks of
> their owners, used only to identify compatibility.

## Features

- **On-screen offer scoring** — watches Uber Driver, Lyft Driver, Dasher,
  Instacart Shopper, Amazon Flex, Spark Driver, Wolt Courier Partner, and
  foodora rider; reads payout, distance, duration; scores against your rules
- **Floating verdict overlay** — GOOD OFFER / SKIP IT with
  $/distance and $/hour, plus tap-to-accept and tap-to-decline shortcuts
- **Opt-in automation** — auto-accept on a 3/5/8/10s countdown you can
  cancel, auto-decline, voice alerts. Off by default; you're in control
- **Per-app rules** — different thresholds per platform (Wolt can have a
  lower bar than Uber); reserved/scheduled offers are detected and handled
  separately
- **Automatic GPS mileage** — a foreground "shift" tracker with jitter and
  teleport filtering; can start itself when a driver app opens
- **Automatic earnings capture** — post-trip summary screens become earning
  records without typing anything
- **Diagnostics** — see exactly what the assistant last read inside each
  driver app, so parse misses are debuggable instead of silent
- **Audit trail** — every skipped offer carries the rule that failed and both
  numbers it compared (`payout $6.10 < $8.00 floor`), in the offer log, the
  read history and the overlay, so a bad parse is never silently a skip
- **Self-updating** — checks GitHub Releases for a newer APK (at most once
  per 6h) and installs it through the system installer when you confirm
- **100% local** — every record lives in on-device SQLite; the only
  network call is the anonymous GitHub update check

## Install

Grab the latest APK from
[Releases](https://github.com/amvuser81/Shiftlit/releases) — Android 8.0+.

Setup takes under a minute — the app walks you through it in the Assist tab:

1. **Enable the accessibility service** (required — this is what lets
   Shiftlit see offer cards in your driver apps)
2. **Allow location** (for automatic mileage tracking)
3. **Allow notifications** (the shift tracker runs as a foreground service)

Then open a driver app and watch the verdict card appear on offers.
Automation is off by default — enable it in **Assist → Automation** when
you're ready.

Or build it yourself:

```bash
cd android
# android/local.properties must point at your SDK:
#   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## Installing and updating on a phone

**Coming from GigFlow.** The rename changed the package id from
`app.gigflow.driver` to `app.shiftlit.driver`, so Android sees Shiftlit as a
new app, not an update. Install it alongside the old build, check that offers
and tracking work, then uninstall GigFlow. Settings, the offer log and the
diagnostics history live in the old app's private storage and do not migrate.

1. On the phone, open the repo's **Releases** page and download the
   `Shiftlit-x.y.z.apk` you want.
2. Chrome warns about APK downloads — tap **Download anyway**.
3. When prompted, allow installs from that app: **Settings → Apps → Special
   access → Install unknown apps → Chrome → Allow from this source**.
4. Open the downloaded APK and tap **Install**. If Play Protect warns about
   an unknown developer, choose **Install anyway** — the APK is signed by
   your key, not distributed through Play.
5. On first launch, enable the accessibility service for Shiftlit again —
   the grant belongs to the old package — and re-check your rules. Then
   uninstall GigFlow (long-press its icon → App info → Uninstall).

**After that, updates are in place.** The app checks GitHub Releases
itself (one anonymous GET), downloads the new APK, and hands it to the
system installer. Same package, same key, so it replaces the installed
copy and keeps your data. You only confirm the install prompt.

**The rule that keeps this working:** always sign with the same
`release.jks`. Android refuses to install an update signed by a different
key ("App not installed") — recovery means uninstalling and losing local
data. If the machine is ever lost, restore `release.jks` and `password.txt`
from your backup and re-add the four repository secrets.

## Releasing

Releases are cut on purpose, never on a push. Pushing to `master` only
compiles a debug APK as a check; to publish, run the workflow by hand from
**Actions → Build & Release APK → Run workflow**, or:

```bash
gh workflow run build-apk.yml -f version=1.8.0   # omit -f version to bump the patch
```

The run stamps `versionName` / `versionCode` into `android/app/build.gradle.kts`,
builds a release APK signed with the repo's release key, verifies the
signature, commits the bump back to `master`, tags it, and publishes the
release.

Signing is pinned to four repository secrets. The key must stay the same
forever — a different key means installed copies can no longer update in
place:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | key password |

Create them once:

```bash
keytool -genkeypair -v -keystore release.jks -alias shiftlit \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

Keep `release.jks` backed up somewhere safe and out of the repo.

## How it works

- **Accessibility service** scoped to the watched driver-app packages —
  it can't see anything else on your phone
- **Heuristic text parsing** of offer cards (layout-independent, but
  layouts change — Diagnostics shows what it read when a screen is missed)
- **`dispatchGesture` taps** for accept/decline — only when you enable it,
  only inside the driver apps you watch
- **Foreground location service** for shift mileage — visible in the
  notification shade the whole time it runs
- **GitHub Releases updater** — one anonymous GET to `api.github.com` to
  check for a newer version; the APK installs via the system installer
  (you confirm, Android asks "allow this source" once)

## Project structure

```
android/           the app — Kotlin, plain Views, near-zero dependencies
  app/src/main/
    java/.../      accessibility service, mileage tracker, parser, overlay,
                   rule engine, local DB, driver-utility UI
    res/           icons, service config, strings
docs/              legal docs (privacy, terms, cookies)
```

## Honest limitations

- **Not an official integration.** No gig platform publishes a driver API;
  this reads what's on screen, like every app in this category.
- **Heuristic parsing.** Driver apps change layouts; if a card can't be
  read, Shiftlit errs toward silence and never guesses at buttons.
- **Automation is opt-in** and only fires when a button was found
  unambiguously.
- **Policy note.** Third-party assistants may conflict with gig platforms'
  terms — your call.

## Privacy

Screen content never leaves the phone — the only network traffic is the
anonymous update check against GitHub. Automation is
opt-in and cancellable. Full details:
[`docs/legal/privacy-policy.md`](docs/legal/privacy-policy.md).

## Contributing

Issues and PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). The most
useful contributions are offer-parser fixes: driver apps change layouts
constantly.

## License

[MIT](LICENSE) — free to use, fork, and build on.

---

<div align="center">
Based on the original project by
<a href="https://github.com/w0wzahh">w0wzahh</a>, MIT licensed.
</div>
