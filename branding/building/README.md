# building artwork

The original Dark and ClearDark images came from the supplied `Archive.zip`.
`sources.json` records the archive and image checksums. Dark supplies the color
launcher artwork; ClearDark supplies the monochrome and notification masks.

Run `python3 tools/generate-android-branding.py` with Pillow installed to regenerate
the checked-in Android resources and opaque 512 px Play Store icon. Adaptive layers
use a 108 dp canvas with the artwork centered at 66 dp, following the
[Android adaptive icon dimensions](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
The notification icon isolates the supplied speech/sparkle emblem for small sizes.
