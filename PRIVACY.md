# OpenFugu Privacy Policy

**Last updated: 17 July 2026**

This policy applies to OpenFugu on both Android and iOS.

OpenFugu does not collect, transmit, or share any personal data. The app never
connects to the internet. On Android this is enforced by the system: the app does
not request the internet permission at all, so it is technically incapable of
sending anything off your device.

## Data stored on your device

The app stores the following data locally, in its private app storage:

- **User profiles** — names you enter and pressure calibration values
- **Saved devices** — Bluetooth addresses, nicknames, and colors of your eFugu devices
- **Training sessions** — recorded pressure traces, scores, and statistics

This data never leaves your device unless you explicitly share it (see below). It is
deleted when you uninstall the app. If you have your device's system backup enabled
(Android backup to your Google account, or an iOS device or iCloud backup), the data
may be included in your own backup, like any app's data — this is handled by the
operating system, not by OpenFugu.

## Bluetooth

OpenFugu uses Bluetooth Low Energy solely to find and communicate with eFugu pressure
devices. The app does not request any location permission on either platform. On
Android, Bluetooth scanning is additionally declared with the `neverForLocation` flag:
the app does not use scan results to derive your location. On iOS, the app asks for
the standard Bluetooth permission before its first scan and uses it for nothing else.

## Sharing

Session data leaves the app only when you tap its share button, which opens your
system's standard share sheet. You choose what is shared and with which app; OpenFugu
itself sends nothing anywhere.

## No accounts, no analytics, no ads

The app has no user accounts, no analytics or crash-reporting frameworks, no
advertising, and no tracking of any kind.

## Contact

OpenFugu is an open-source project. Questions and concerns: <openfugu@hubik.org>.
Changes to this policy are published at <https://openfugu.hubik.org/privacy>.
