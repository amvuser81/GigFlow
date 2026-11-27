# Shiftlit — Android companion

A native Android app in the iOS design language: dashboard, offer feed,
quick-add tracking, and the offer assistant — all on-device, zero
third-party dependencies (plain Views, no Compose/Material).

## The app

Five tabs behind a floating frosted tab bar:

- **Home** — today's net/gross/hours/per-hour rate plus a full analytics
  section: week/month toggle, per-hour and per-distance rates, platform
  breakdown, daily gross chart, and week-over-week deltas — all computed
  from on-device records.
- **Offers** — the assistant's scored-offer feed: verdict pill (Good /
  Borderline / Skipped), per-distance and per-hour rates, grouped by day,
  filterable by a segmented control; tap a row for full metrics.
- **Plan** — recurring shift blocks with day-of-week picker, start/end
  times, and an optional earnings target; saved on-device.
- **Track** — quick-add earnings, expenses, and mileage in grouped iOS
  forms, plus a heatmap of the GPS trails kept by tracked shifts (drawn
  offline — no map tiles, no network). Everything saves to a local SQLite
  store and works fully offline.
- **Assistant** — accessibility-service status, Monitoring / Auto-accept /
  Auto-decline switches (both automation switches off by default), offer
  rules, and diagnostics.

## The assistant (honest version)

- Watches the offer screens of the driver apps you choose (Uber Driver,
  Lyft Driver, Dasher, Instacart Shopper, Amazon Flex, Spark Driver, Wolt
  Courier Partner, foodora rider) via `AccessibilityService` — scoped by
  package name, blind to everything else.
- Reads the offer card off the screen (payout, distance, duration,
  accept/decline buttons, the offer's own countdown when shown) with
  text-pattern parsing — layout-independent.
- **Localized UI aware**: Wolt and foodora ship in 30+ languages, so
  accept/decline, duration ("25 perc"), tip, and reservation wording are
  matched in English plus Hungarian, German, Polish, French, Spanish,
  Italian, Swedish, Norwegian, Danish, Finnish, Czech/Slovak, Romanian,
  Turkish, Ukrainian, and the Baltic languages.
- **Per-app affordances**: Uber and Lyft accept on a tap *anywhere* on the
  request card (no labelled button) — the parser resolves the card itself.
  Amazon Flex instant offers use a *swipe* to accept, which the gesture
  layer performs; reserved blocks claim via the Schedule button, only when
  "Include reserved offers" is on.
- **Multi-currency**: `Assistant → Offer rules → Currency` offers ~80
  currencies (`$ € £ Ft zł kr ₺ ₹ ₩ د.إ R$ …`) plus a custom-symbol escape
  hatch. The parser matches the built-in symbol set *and* whatever currency
  you picked — symbol or ISO code, prefix or suffix, locale-aware amounts
  (`1.850 Ft`, `12,50 EUR`, `R$ 9,90` all parse). Display follows each
  currency's convention (`1 850 Ft`, `CHF 12.50`, `¥1,200`) and voice
  alerts speak the real name ("forints", "zloty").
- Scores against your thresholds and floats a verdict card over the app:
  `GOOD OFFER` / `BORDERLINE` / `SKIP IT` with per-distance and per-hour
  rates, tap shortcuts, and a ticking "Expires in Ns" line when the card
  shows one.
- Per-app rule overrides (Uber can differ from Wolt) and reservation
  detection — scheduled work is badged and auto-accept is separately gated.
- Optionally taps Accept/Decline via `dispatchGesture` — opt-in switches.
- **Diagnostics** (Assistant tab): per-app "last seen" status plus the
  exact texts the service read — with `[tap]` and `(view-id)` markers per
  node — so a parse miss is debuggable in seconds. With an AI key added, a
  missed screen also offers **Ask … to fix this screen**: the provider
  gets that capture plus the app's recent reads, explains the miss (including
  when the same card keeps being missed) and points at the payout, distance,
  duration and accept/decline nodes. The mapping is test-parsed against
  that capture, and the sheet previews the parsed payout, distance and
  duration before it can be saved. Any pick can be corrected by hand, and
  local checks refuse a tip/fee as the payout or one node doing two jobs.
- **AI controls are not trusted for automation.** An AI-picked accept or
  decline reads the card, but never drives auto-accept/auto-decline until
  you confirm the buttons on a **newer** Diagnostics capture — the snapshot
  the mapping was built from can never confirm itself. Accept and decline
  picks must also resolve to clickable nodes, or Save is blocked. Unnamed
  controls need an explicit tick before the mapping can be saved at all. Saved
  mappings show their confirmation and last successful read in
  **AI setup → Saved mappings**, where they can be forgotten. The setup
  sheet also has **Test connection** and a review of exactly what will be
  sent, with the earlier-reads history toggleable.
- **Send a test offer** (Assistant → Automation): dry-runs score → overlay
  → voice without logging anything, so you can verify setup while parked.

## Honest limitations

- **Not an official integration.** No major platform publishes a driver API;
  this reads what's on screen, like every app in this category.
- **Heuristic parsing.** Driver apps change layouts often. If a card can't
  be parsed, nothing is shown — the app errs toward silence and never
  guesses at buttons.
- **Android only.** iOS doesn't permit this class of screen-reading.
- **Automation is opt-in** and only fires when the button was found
  unambiguously.
- **Policy note.** Google Play restricts accessibility services that aren't
  primarily accessibility tools; this app is distributed as an APK. Using
  third-party assistants may conflict with gig platforms' terms — your call.

## Build

JDK 17+ and Android SDK platform 36.

```bash
# local.properties must point at your SDK (gitignored):
#   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
gradle assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with the committed `app/debug.keystore` (public,
password `android`) so successive CI debug APKs update in place instead of
requiring an uninstall. It is a debug-only key and must never sign a release.
CI verifies every debug APK against the pinned certificate SHA-256, so
replacing the keystore fails the build instead of silently wiping testers'
data on the next update.

### Debug seed for the AI preview

A debuggable build accepts a seeded Wolt capture that the parser deliberately
misses, so the AI screen-fix preview can be tried without a live offer. Works
whether the debug app is running or not:

```bash
adb shell am start -n app.shiftlit.driver.debug/app.shiftlit.driver.MainActivity --activity-single-top --ez seed_diag true
```

`--activity-single-top` is what delivers the extra to the already-running
activity; a plain start only brings its task forward. Add
`--es seed_scenario money` (or `reservation`, `buttons`, `unreadable`) to seed
a different fixture. A debug build also exposes **About → Debug seed
scenarios**, so a tester can switch fixtures on the phone. Then open
Assistant → Diagnostics → Wolt Courier Partner and tap
"Ask … to fix this screen". The seed only selects OpenRouter as the provider;
the debug app still needs its own API key.

## Data handling

- Everything is on-device: screen content, offers, records, GPS. The network
  calls are the anonymous GitHub Releases update check (and the APK download
  if you update) plus, only if you add your own key, the AI provider you
  choose in AI setup — and only for the captured screen you explicitly hand
  it.
- Records live in `shiftlit.db` (SQLite, private app storage).
