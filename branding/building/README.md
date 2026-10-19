# building artwork

`icon-dark.png` (`sq_512.png`) supplies the color square launcher, themed monochrome mask,
and Play Store icon. `icon-clear-dark.png` (`rd_512.png`) supplies the round launcher variant.
`sources.json` records the source files and image checksums from `use.zip`.

Run `python3 tools/generate-android-branding.py` with Pillow installed to regenerate
the checked-in Android resources and opaque 512 px Play Store icon. Adaptive layers
use a 108 dp canvas with the artwork centered at 66 dp, following the
[Android adaptive icon dimensions](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
The notification icon isolates the play emblem for status bar sizes.
iOS app icons are managed via `app/ide-ios/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`.
