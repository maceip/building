# building artwork

`icon-dark.png` is the supplied `building_app.png` Android/player artwork. It supplies
the color launcher, themed monochrome mask, and Play Store icon. `icon-clear-dark.png`
retains the original ClearDark artwork from `Archive.zip` solely for the existing
notification symbol. `sources.json` records the source files and image checksums.

Run `python3 tools/generate-android-branding.py` with Pillow installed to regenerate
the checked-in Android resources and opaque 512 px Play Store icon. Adaptive layers
use a 108 dp canvas with the artwork centered at 66 dp, following the
[Android adaptive icon dimensions](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
The notification icon isolates the supplied speech/sparkle emblem for small sizes.
