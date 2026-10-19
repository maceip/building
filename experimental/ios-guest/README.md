# iOS guest load spike (research only)

Goal: can Building's iOS host load and run a guest IPA in-process?

## Spike A - Prove load

- MachOGuestPatcher (ide-core): MH_EXECUTE -> MH_DYLIB and __PAGEZERO rewrite for dlopen.
- GuestAppLoader.swift (iosApp): unpack IPA, dlopen patched binary, call guest_entry_point.
- Unit tests: MachOGuestPatcherTest (JVM, no device).

## Spike B - Open guest after xtool

- RunAction.onSuccess logs `Open guest:` and fires RuntimeBuildEnvironment.onGuestIpaReady(ipa).
- iOS host unpacks that path into a guest container and calls GuestAppLoader.

## Not yet

- Live dlopen + UIApplicationMain on a physical iPhone.
- Multi-app list / data containers / JIT notes.
