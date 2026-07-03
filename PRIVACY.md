# OpenFugu Privacy Policy

**Last updated: 3 July 2026**

OpenFugu does not collect, transmit, or share any personal data. The app does not
request internet access at all, so it is technically incapable of sending anything
off your device.

## Data stored on your device

The app stores the following data locally, in its private app storage:

- **User profiles** — names you enter and pressure calibration values
- **Saved devices** — Bluetooth addresses, nicknames, and colors of your eFugu devices
- **Training sessions** — recorded pressure traces, scores, and statistics

This data never leaves your device unless you explicitly share it (see below). It is
deleted when you uninstall the app. If you have Android's system backup enabled, the
data may be included in your own device backup (e.g. to your Google account), like any
app's data — this is handled by Android, not by OpenFugu.

## Bluetooth

OpenFugu uses Bluetooth Low Energy solely to find and communicate with eFugu pressure
devices. Bluetooth scanning is declared with Android's `neverForLocation` flag: the app
does not use scan results to derive your location, and it does not request any location
permission.

## Sharing

Session data leaves the app only when you tap its share button, which opens the
standard Android share sheet. You choose what is shared and with which app; OpenFugu
itself sends nothing anywhere.

## No accounts, no analytics, no ads

The app has no user accounts, no analytics or crash-reporting frameworks, no
advertising, and no tracking of any kind.

## Contact

OpenFugu is an open-source project. Questions and concerns:
<https://github.com/hubikj/openfugu-android> (issues) or the contact listed on the
project page. Changes to this policy are published in this repository.
