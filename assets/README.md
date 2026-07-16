# Brand assets (sources)

Vector sources for all app artwork. Everything raster is rendered from
these — edit the SVG, re-render, never edit the rasters.

| File | What it is | Rendered outputs |
|---|---|---|
| `icon-512.svg` | App icon: launcher fish on the launcher background (`#0D1B2A`) | iOS `iosApp/OpenFugu/Assets.xcassets/AppIcon.appiconset/AppIcon.png` (1024×1024), Play listing icon (512×512) |
| `feature-graphic.svg` | Google Play feature graphic | Play listing graphic (1024×500) |

Re-render with rsvg-convert (Debian: `librsvg2-bin`), e.g.:

```bash
rsvg-convert -w 1024 -h 1024 assets/icon-512.svg \
  -o iosApp/OpenFugu/Assets.xcassets/AppIcon.appiconset/AppIcon.png
```

The Android launcher icon is separate (vector drawables in
`app/src/main/res/`, Android's adaptive-icon format).
