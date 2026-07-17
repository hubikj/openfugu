#!/usr/bin/env python3
"""Generate the SideStore/AltStore source.json for the rolling iOS dev release.

Usage: make_sidestore_source.py <version> <ipa-path> <output-path>

The source identifier and download URLs must stay stable forever — SideStore
keys the source by identifier, and users' phones poll the same URL for
updates. Only asset *contents* change between builds.
"""
import json
import os
import sys
from datetime import datetime, timezone

REPO = "hubikj/openfugu"
RELEASE_TAG = "ios-dev-latest"

version, ipa_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

source = {
    "name": "OpenFugu Dev Builds",
    "identifier": "org.hubik.openfugu.source",
    "apps": [
        {
            "name": "OpenFugu",
            "bundleIdentifier": "org.hubik.openfugu",
            "developerName": "Jan Hubík",
            "subtitle": "Equalization training with the eFugu device.",
            "localizedDescription": (
                "Development builds of OpenFugu, the open-source app for the "
                "eFugu freediving pressure equalization training device. "
                "Published automatically from the main branch."
            ),
            "iconURL": (
                f"https://raw.githubusercontent.com/{REPO}/main/"
                "iosApp/OpenFugu/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
            ),
            "tintColor": "#FF8C00",
            "versions": [
                {
                    "version": version,
                    "date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    "size": os.path.getsize(ipa_path),
                    "downloadURL": (
                        f"https://github.com/{REPO}/releases/download/"
                        f"{RELEASE_TAG}/OpenFugu.ipa"
                    ),
                    "localizedDescription": "Automated development build from main.",
                    "minOSVersion": "16.0",
                }
            ],
        }
    ],
}

with open(out_path, "w") as f:
    json.dump(source, f, indent=2, ensure_ascii=False)
print(f"wrote {out_path}: version {version}, {os.path.getsize(ipa_path)} bytes")
