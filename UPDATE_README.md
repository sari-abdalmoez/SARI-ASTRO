# SARI-ASTRO Professional Update

Base commit: `0d37076b81b03d5dbbf1f3ef372dd4dc299ec310`

This overlay updates the existing SARI-ASTRO project without replacing its architecture or stacking engine.

Changes:
- One professional Astro Start/Stop camera control.
- Triangle icon when idle; square icon with translucent red active state.
- Hard 4-minute automatic stop.
- Compact bold `SARI` (white) + `ASTRO` (blue) branding.
- Independent COLOR/MONO/NATURAL Stretch and Final Noise Reduction settings.
- Preview path accepts a real denoise parameter.
- Final denoise response is stronger while remaining conservative.
- Sensor timestamp metadata and duplicate timestamp protection for RAW frames.
- Explicit stacking percentage, tiles and ETA.

Apply from the root of the existing repository:

```bash
python apply_update.py
git diff --check
git status --short
```

Use the existing GitHub Actions workflow for the Android build.
