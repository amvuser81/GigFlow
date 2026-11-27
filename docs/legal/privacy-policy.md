# Shiftlit Privacy Policy

**Last updated: November 2026**

Shiftlit is an Android app that runs entirely on your phone. There is no
account, no sign-in, no server and no sync. This policy describes exactly what
the app touches, where it stays, and what leaves the device (almost nothing).

## 1. What Shiftlit stores, and where

Everything below lives in the app's private storage on your device. Private
storage means other apps cannot read it, and it is removed when you uninstall.

| Data | Why |
|---|---|
| Settings and rules — thresholds, currency, units, per-app overrides, automation toggles | So the app behaves the way you set it |
| Offer log — up to 200 scored offers: payout, distance, duration, verdict, action, the rules that failed | So you can review what was offered and why it was skipped |
| Diagnostics — the last screen read per app plus up to 20 recent reads per app: the text on screen, the parser's decision trail, which window was used | So a parse miss can be debugged instead of guessed at |
| Taught mappings — the screen signature and the nodes you pointed at | So a layout the parser can't read can be taught once |
| Records you create — earnings, expenses, mileage and schedules | The app's own bookkeeping |
| GPS breadcrumbs — only while a shift is running | To compute distance for that shift |

## 2. Permissions and what they allow

- **Accessibility service** — reads the offer cards rendered by the driver
  apps you select (Uber, Lyft, Dasher, Instacart, Amazon Flex, Spark, Wolt and
  foodora riders). It is scoped to those packages and cannot see other apps.
  It also performs the accept/decline taps, but only when you enable
  automation.
- **Location** — used only while a shift is recording, to measure distance.
  Never outside a shift.
- **Notifications** — the shift tracker runs as a foreground service and must
  show a notification while it does.
- **Install unknown apps** — only if you use the in-app updater; Android asks
  you to allow it once.

## 3. What leaves the phone

There are exactly two kinds of network request, both to GitHub:

1. **Update check** — a GET to `api.github.com` for this project's latest
   release, at most once every six hours (or when you tap Check for updates).
   It contains no personal data; GitHub sees the request and your IP address
   as any web request would.
2. **APK download** — only if you choose to install an update.

If you tap **Send report**, the app builds a JSON file from the diagnostics
you selected and hands it to your own email app. Nothing is uploaded by
Shiftlit. You can read the report before sending it, and you choose whether to
send it at all.

There is no analytics, no advertising, no crash reporting, no telemetry and no
remote configuration.

## 4. What Shiftlit does not do

- No account, no password, no email required.
- No cookies — the app has no web interface (see the cookie policy).
- No sale or sharing of data.
- No reading of apps you did not select.
- No collection of contacts, messages, photos or files.

## 5. Retention and deletion

Data stays until you remove it:

- Clear the offer log or a diagnostics snapshot inside the app.
- Delete individual earnings, expenses or shifts in their tabs.
- Uninstall the app to remove everything, including the database and all
  preferences. Android deletes private storage on uninstall; there is no
  backup on our side because there is no server.

## 6. Security

Data is protected by Android's app sandbox. There are no server credentials to
leak because there is no server. The app is distributed as an APK signed with
the project's release key, and the updater verifies that it is downloading
from the project's own GitHub releases. Details: [SECURITY.md](../../SECURITY.md).

## 7. Your rights

Because the developer never receives your data, access/correction/erasure
requests are satisfied on the device: everything is visible, exportable or
deletable in the app, and uninstalling removes it. If you believe a report you
sent by email should be deleted, open an issue and say so.

## 8. Children

Shiftlit is intended for people legally able to perform gig work (18+). It is
not directed at children and collects nothing from them.

## 9. Changes

Material changes are committed to this repository and noted in the release
notes. The date above reflects the current version.

## Contact

Privacy questions: open an issue at
<https://github.com/amvuser81/Shiftlit/issues>.
