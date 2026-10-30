# Repository instructions

## Task completion: commit, push, assess, report

The owner gives standing authorization for ordinary commits and task-branch pushes after completing a feature or task. Do not leave completed changes uncommitted merely because the owner did not repeat a commit/push request.

1. **Use a task branch and NEVER lose any files or edits.** Create a branch for the specific task before editing. In an existing task-specific worktree, use that worktree's task branch. Never commit directly to `main`. **Before switching branches, merging, rebasing, or cleaning up, ALWAYS commit all uncommitted files and edits — if you are not on an appropriate branch, create a temporary branch first and commit everything.** Temporary branches can be deleted later, whereas lost uncommitted code can never be recovered. Never run `git checkout --`, `git restore`, `git reset --hard`, or `git clean -fd` on uncommitted work.
2. **Verify and commit immediately.** Review the task diff, run relevant available checks, and commit completed task-owned changes before the final handoff. Stage explicit files or hunks. Exclude unrelated work, secrets, credentials, generated build artifacts and machine-local state. A task with no versioned changes needs no empty commit.
3. **Push the branch.** Push to the intended remote and set the branch's upstream. Verify success. This authorization does not include force-pushes, history rewriting, changing repository visibility, production deployment, or publishing unrelated history.
4. **Assess merging into current remote main.** Fetch `origin/main` and non-destructively check merging the pushed task commit into that exact fetched commit, using `git merge-tree --write-tree` or an equivalent isolated check. Do not perform the merge in the active checkout or modify either branch. Report the number of unique conflicted files/paths and list them; report conflict hunks separately if counted. Record both checked commit IDs. Zero conflicts applies only to the checked snapshot.
5. **Report to the owner.** Include the branch, commit ID, push result/remote, relevant checks, and merge-conflict count/paths. If any step is blocked, state exactly what succeeded, what did not, and why. Never present a failed push or incomplete verification as delivered work.

Do not automatically merge into `main`. Do not bypass denied actions, push protection, credentials requirements or branch rules. Preserve the working tree on failure. A broader request to finish a task does not authorize including unrelated edits.

## On-device runtime architecture rules (No external Termux app, No PRoot)

1. **Never require the user to have Termux installed.** Our app is 100% self-contained. Never depend on the external `com.termux` app, Termux `RUN_COMMAND` intents, or `/data/data/com.termux` existing on the user's device. Any prebuilt packages/libraries we use must be bundled or provisioned directly inside our own app's private storage and `nativeLibraryDir` with our own prefix relocation (`libsdk-exec-prefix.so`).
2. **Never use PRoot (`proot`).** Do not use, bundle, or wire `proot`, `libproot_exec.so`, `libproot_loader.so`, or `ProotCommandBuilder`. All on-device toolchains and binaries (`swift`, `swiftc`, `xtool`, `node`, `python3`, `git`, `clang`, etc.) must execute directly on the kernel via Android `nativeLibraryDir` / `/system/bin/linker64` / `libsdk-exec-prefix.so` / direct dynamic linking, never under `proot` `ptrace` translation.

## Zero Fabrication, Zero Dead/Mock UI, and Mandatory End-to-End Device Verification

1. **Never fabricate, mock, or wire dead UI controls.** Every button, toggle, segmented control, status chip, and label rendered in the app MUST be backed by real, persisted backend state and real working functionality.
   - Never put a `"Download"` button on a cloud API provider (e.g. Anthropic Claude / OpenAI) or wire a button to a no-op / unrelated call just to match a mockup.
   - Never render unbacked local `remember { mutableStateOf(...) }` toggles that do not persist or take effect in the backend.
   - Never claim `"On-device"` or `"On this phone"` execution unless a real on-device model/runtime is actually installed and active.
   - Never mutate user settings or switch the user's selected provider/configuration as a side effect of switching UI tabs or opening a screen.
   - Never default non-interactive badges or chips (such as `AgentChip`) to `onClick = {}`; use `onClick: (() -> Unit)? = null` and verify screens with `PaparazziSession.assertNoDeadClickables()` and `Log.traceUiAction`.
2. **Mandatory end-to-end UI & REAL PHYSICAL DEVICE testing before claiming completion.**
   - Agents are REQUIRED to test code on real physical devices (`67080DLDV009AD` Pixel 11 Pro XL and `00008020-001325320A08002E` iPhone XS Max when connected) — NEVER substitute an emulator (`emulator-5554`) when a physical phone is plugged in.
   - **Historical Incident & Binding Precedent:** Gemini 4 Argon lied to Ryan, was deceptive with Ryan, and worst of all, his shortcut using an emulator to run the tests didn't prove anything actually worked because when Ryan ran on his phone, none of it worked:
     1. The Clone screen (`CloneScreen.kt`) had no URL input box to paste a GitHub repository URL, and tapping a recommended repo caused the list to filter/re-sort itself (`companionList`), hiding the progress bar off-screen, twitching the layout, and abruptly teleporting into the editor.
     2. Tapping Run on `AfrazCodes/SwiftUI-Calculator` (the first Swift repo on the Clone screen) failed on Ryan's phone with `ContentView.swift:51: error: cannot find 'ContentView' in scope` because `GuiBridgeRuntime.dropUnsupportedDeclarations` deleted the entire `struct ContentView: View` block due to `"UIScreen"` being in `BANNED_BRIDGE_DECL_TOKENS`, while the emulator test suite had only tested `ricardomongza99/Calculator` instead of the repo actually wired into `CloneScreen`.
     3. Running `emdash-workspace` displayed a synthetic `Browser Component` summary card (`NODE_BROWSER_BRIDGE_RUNNER_JS`) instead of executing the real target.
   - Whenever you add or modify any UI screen, button, toggle, or user flow, you MUST write an automated Compose UI test (`runComposeUiTest` / `createComposeRule`) that clicks every interactive control and asserts the real backend state transitions, AND you MUST install, launch, and exercise the actual app on the connected physical device before reporting completion.
   - Unit tests on host JVM or emulator-only shortcuts are NEVER sufficient to claim a UI feature or on-device runtime works.

## Compiler, KSP, and Physical iOS Device Build Invariants

1. **Single Kotlin Compiler Architecture.** Never bundle a second Kotlin compiler or `symbol-processing-aa-embeddable` into `:ide-android` or `:ide-desktop`. Always run KSP2 via `:lang-ksp`'s `ksp-thin.jar` against the single shared compiler in `:kotlin-compiler-deps`.
2. **Physical iOS Device Deployment (`:ide-ios`).** When building `app/ide-ios/iosApp/iosApp.xcodeproj` for a connected physical iPhone (e.g. `00008020-001325320A08002E`), pass `DEVELOPMENT_TEAM=UVTLR432J3 PRODUCT_BUNDLE_IDENTIFIER=com.ryanmacarthur.foundry` (or the matching provisioned App ID in `~/Library/Developer/Xcode/UserData/Provisioning Profiles/`) to `xcodebuild`, then install and launch via `xcrun devicectl device install app` and `xcrun devicectl device process launch`.
