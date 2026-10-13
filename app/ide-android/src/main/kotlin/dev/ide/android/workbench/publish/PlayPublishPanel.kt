package dev.ide.android.workbench.publish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.workbench.publish.AabArtifact
import dev.ide.workbench.publish.CommitPreview
import dev.ide.workbench.publish.DataSafetyPreview
import dev.ide.workbench.publish.ImageAsset
import dev.ide.workbench.publish.InternalSharingPreview
import dev.ide.workbench.publish.PlayEdit
import dev.ide.workbench.publish.PlayGateEvidence
import dev.ide.workbench.publish.PlayImageType
import dev.ide.workbench.publish.PlayReleaseChecklist
import dev.ide.workbench.publish.PlayReleaseStatus
import dev.ide.workbench.publish.PlayTrack
import java.text.DateFormat
import java.util.Date

/** Embedded workbench tab: deliberately no app scaffold, top bar, Console automation, or OAuth WebView. */
@Composable
fun PlayPublishPanel(backend: IdeBackend, fileActions: FileActions, modifier: Modifier = Modifier) {
    SecurePlayPublishSurface()
    val native = rememberPlayAuthorization()
    val auth = native.state
    val scope = rememberCoroutineScope()
    val state = remember(backend, auth) { PlayPublishState(auth, scope) }
    val buildState by backend.build.buildState.collectAsState()
    var tasks by remember(backend) { mutableStateOf<List<RunTaskOption>>(emptyList()) }
    var selectedTaskId by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<PublishDialog?>(null) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showManualToken by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var selectedImageType by rememberSaveable { mutableStateOf(PlayImageType.PHONE_SCREENSHOTS) }
    val busy = state.busy != null
    val editable = !state.locked

    DisposableEffect(state) { onDispose { state.close() } }
    LaunchedEffect(auth.identityKey, auth.connected) {
        if (auth.connected && !auth.authorizing && state.busy == null) state.synchronizeIdentity()
        dialog = null // A consent snapshot never survives an authorization identity transition.
    }
    LaunchedEffect(backend, backend.project.rootPath, buildState.status) {
        if (buildState.status != RunStatus.Running && backend.project.rootPath.isNotBlank()) {
            tasks = runCatching { publishBundleTasks(backend.build.runTasks()) }.getOrDefault(emptyList())
            if (tasks.none { it.id == selectedTaskId }) selectedTaskId = tasks.firstOrNull()?.id.orEmpty()
        }
    }
    // Navigation cannot be mistaken for cancel/rollback; tab changes may still dispose the screen.
    BackHandler(enabled = busy) { state.reportLocalError(IllegalStateException()) }

    fun review(action: () -> PublishDialog) {
        try { dialog = action() } catch (failure: Exception) { state.reportLocalError(failure) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("play-publish-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Google Play publishing", style = MaterialTheme.typography.titleLarge)
        Text("Build or select a signed AAB, stage a reviewed edit, validate it, then separately confirm commit. No step proves that your app is live.", style = MaterialTheme.typography.bodyMedium)
        Text("This screen hides screenshots/screen recording while open. Credentials stay in memory only and are cleared on disconnect or leaving/destroying this screen. Finish or explicitly discard edits before leaving; closing cannot undo a Google write.", style = MaterialTheme.typography.bodySmall)
        ManualPlayConsoleEntry(fileActions, enabled = !busy && !auth.authorizing)
        if (busy) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.busy.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (state.canCancel) TextButton(onClick = state::cancel, modifier = Modifier.testTag("play-cancel-operation")) { Text("Cancel local request / stop waiting") }
                    Text("Do not switch accounts or leave during a write. Cancellation is not rollback.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.error?.let { PublishNotice(it, error = true) }
        state.feedback?.let { PublishNotice(it) }
        if (state.resultUncertain) PublishNotice("A write result is uncertain. Commit and automatic retries are blocked. Inspect the edit/release or Play Console, then explicitly discard if it still exists.", error = true)

        PublishSection("1 · Authorize the publishing account") {
            Text(if (auth.connected) "Connected: ${auth.accountLabel}" else "No active session access token", style = MaterialTheme.typography.bodyMedium)
            Text("Authorization uses the androidpublisher OAuth scope. Google account sign-in alone, an ID token, or a Play developer-account ID is not publishing authorization.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = native.authorize,
                enabled = native.available && !busy && !auth.authorizing,
                modifier = Modifier.fillMaxWidth().testTag("play-google-authorize"),
            ) { Text(if (auth.authorizing) "Waiting for native Google authorization…" else if (auth.connected) "Renew / choose Google account" else "Authorize with Google") }
            if (auth.connected || auth.authorizing) OutlinedButton(
                onClick = { dialog = PublishDialog.Disconnect },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect and clear session token") }
            auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide OAuth setup" else "OAuth package / SHA-1 setup") }
            if (showSetup) {
                Text("Register the INSTALLED IDE below as an Android OAuth client in your Google Cloud project. This is not the package name of the app you are uploading. Enable the Google Play Android Developer API, configure Google Auth consent/branding and test users where required, and grant this Google account the appropriate Play Console app/release permissions.")
                Text("IDE package: ${native.registration?.packageName ?: "Reading installed package…"}", style = MaterialTheme.typography.bodySmall)
                Text("Installed signing SHA-1:\n${native.registration?.signingSha1?.joinToString("\n").orEmpty().ifBlank { "Unavailable; obtain it from the APK signing certificate / keytool." }}", style = MaterialTheme.typography.bodySmall)
                Text("Each debug/release signing certificate needs the matching registration. For a Play-distributed IDE use its Play App Signing certificate. This screen does not create a Cloud OAuth client, a Play app, or grant production eligibility.", style = MaterialTheme.typography.bodySmall)
                PublishOfficialLink(fileActions, "Official Android authorization setup", "https://developer.android.com/identity/authorization")
            }
            TextButton(onClick = {
                showManualToken = !showManualToken
                if (!showManualToken) auth.manualTokenInput = ""
            }, enabled = !busy && !auth.authorizing) { Text(if (showManualToken) "Hide session-token alternative" else "Advanced: session-only access token") }
            if (showManualToken) {
                Text("If this IDE package/SHA-1 is not registered, use a short-lived user OAuth ACCESS token authorized for androidpublisher, obtained through your own trusted OAuth tooling. Never paste a service-account JSON/private key, refresh token, password, or ID token. The account label below is user-provided, not identity verification.", style = MaterialTheme.typography.bodySmall)
                PublishField("Account label (not verified)", auth.manualAccountLabel, { auth.manualAccountLabel = it }, enabled = !busy && !auth.authorizing)
                OutlinedTextField(
                    value = auth.manualTokenInput,
                    onValueChange = { auth.manualTokenInput = it.take(16384) },
                    label = { Text("OAuth access token · memory only") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    enabled = !busy && !auth.authorizing,
                    modifier = Modifier.fillMaxWidth().testTag("play-session-token"),
                )
                Button(onClick = {
                    try {
                        auth.acceptManual()
                        state.synchronizeIdentity()
                        showManualToken = false
                    } catch (_: Exception) {
                        auth.manualTokenInput = ""
                        auth.error = "Enter an account label and a valid OAuth access token without a Bearer prefix, whitespace, or JSON. No token was saved."
                    }
                }, enabled = !busy && !auth.authorizing && auth.manualTokenInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use for this screen session only") }
            }
        }

        PublishSection("2 · Select or build the release bundle") {
            PublishField("Existing Play app package", state.form.packageName, { state.form = state.form.copy(packageName = it) }, enabled = editable && state.edit == null)
            Text("The app must already be created and initialized in Play Console. Package/version/signing data below come from the actual AAB, not guessed Gradle metadata.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { fileActions.pickFile(listOf("aab")) { path -> if (path != null) state.inspectBundle(path) } }, enabled = fileActions.canPickFile && editable, modifier = Modifier.fillMaxWidth().testTag("play-pick-bundle")) { Text("Select existing .aab") }
            if (backend.project.rootPath.isBlank()) {
                Text("No project open. Select an existing AAB, or choose an Android project in the workbench to build it.", style = MaterialTheme.typography.bodySmall)
            } else if (tasks.isEmpty()) {
                Text("No explicit BUNDLE task is available for this project. Assemble/install is not used as a substitute.", style = MaterialTheme.typography.bodySmall)
            } else {
                PublishChoice("BUNDLE task", selectedTaskId, tasks.map { it.id to it.label }, editable && buildState.status != RunStatus.Running) { selectedTaskId = it }
                OutlinedButton(onClick = { tasks.firstOrNull { it.id == selectedTaskId }?.let { state.buildBundle(backend, it) } }, enabled = editable && buildState.status != RunStatus.Running && tasks.any { it.id == selectedTaskId }, modifier = Modifier.fillMaxWidth().testTag("play-build-bundle")) { Text("Build selected bundle and wait") }
                Text("Current build: ${buildState.status}. Only successful completion triggers a bounded project-output scan. Cached output dates are shown; you choose the exact file.", style = MaterialTheme.typography.bodySmall)
            }
            state.bundleCandidates.forEach { candidate ->
                OutlinedButton(onClick = { state.inspectBundle(candidate.file.path) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(candidate.file.name)
                        Text(candidate.file.path, style = MaterialTheme.typography.bodySmall)
                        Text("${if (candidate.reportedByBuild) "Reported by completed build" else "Found after successful build"} · ${publishDate(candidate.modifiedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.artifact?.let { ArtifactSummary(it) }
        }

        PublishSection("3 · Store listing and real image assets") {
            PublishField("Listing locale (BCP-47)", state.form.language, { state.form = state.form.copy(language = it) }, editable)
            PublishField("App title · max 30 characters", state.form.title, { state.form = state.form.copy(title = it) }, editable)
            PublishField("Short description · max 80", state.form.shortDescription, { state.form = state.form.copy(shortDescription = it) }, editable, lines = 2)
            PublishField("Full description · max 4,000", state.form.fullDescription, { state.form = state.form.copy(fullDescription = it) }, editable, lines = 5)
            PublishField("Privacy policy URL (Console checklist only)", state.form.privacyPolicyUrl, { state.form = state.form.copy(privacyPolicyUrl = it) }, editable, keyboard = KeyboardType.Uri)
            Text("This API listing update does not set the privacy-policy field. Confirm the correct public URL separately in Play Console. Existing locales not selected here are left alone; this locale's text is replaced.", style = MaterialTheme.typography.bodySmall)
            PublishChoice("Image type", selectedImageType, PlayImageType.entries.map { it to it.publishLabel() }, editable) { selectedImageType = it }
            OutlinedButton(onClick = {
                val type = selectedImageType
                fileActions.pickFile(listOf("png", "jpg", "jpeg")) { path -> if (path != null) state.inspectImage(path, type) }
            }, enabled = fileActions.canPickFile && editable, modifier = Modifier.fillMaxWidth()) { Text("Choose an actual image file") }
            Text("Icons: 512 × 512 RGBA PNG. Feature graphic: 1024 × 500. Screenshots: actual app captures in JPEG or RGB PNG, validated locally. No automatic or invented store screenshots.", style = MaterialTheme.typography.bodySmall)
            state.images.forEach { image ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${image.type.publishLabel()} · ${image.width} × ${image.height}", style = MaterialTheme.typography.bodyMedium)
                    Text(image.file.name, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state.removeImage(image) }, enabled = editable) { Text("Remove from upload selection") }
                }
            }
            PublishCheck("Replace all existing images of each selected type in this locale (otherwise append)", state.form.replaceSelectedImageTypes, editable) { state.form = state.form.copy(replaceSelectedImageTypes = it) }
            Text("No selected files means existing image assets are unchanged. Replacements delete that entire selected type inside the edit, and the exact types/files are shown before staging.", style = MaterialTheme.typography.bodySmall)
        }

        PublishSection("4 · Distribution and manual declarations") {
            PublishChoice("Destination", state.form.destination, PublishDestination.entries.map { it to it.label }, editable) { destination ->
                state.form = state.form.copy(
                    destination = destination,
                    trackId = when (destination) { PublishDestination.PRODUCTION -> "production"; PublishDestination.CLOSED -> ""; else -> "internal" },
                    status = PlayReleaseStatus.DRAFT,
                )
            }
            val availableTracks = state.tracks.filter { track -> when (state.form.destination) {
                PublishDestination.DRAFT -> true
                PublishDestination.INTERNAL -> track.track.substringAfterLast(':') == "internal"
                PublishDestination.PRODUCTION -> track.track.substringAfterLast(':') == "production"
                PublishDestination.CLOSED -> track.track.substringAfterLast(':') !in setOf("internal", "production", "beta")
            } }
            if (state.edit != null) {
                PublishChoice("Actual Google track ID", state.form.trackId, availableTracks.map { it.track to it.track }, editable) { id ->
                    state.form = state.form.copy(trackId = id, status = if (id.substringAfterLast(':') == "internal" && state.form.status == PlayReleaseStatus.IN_PROGRESS) PlayReleaseStatus.DRAFT else state.form.status)
                }
                if (availableTracks.isEmpty()) Text("No matching track was returned. Configure the intended closed/form-factor track in Play Console and refresh the edit. This screen does not invent track IDs.", style = MaterialTheme.typography.bodySmall)
            } else Text("Create an edit below to load the real track IDs. Draft is a release status, not a separate track; it defaults to the internal track.", style = MaterialTheme.typography.bodySmall)
            if (state.form.destination != PublishDestination.DRAFT) {
                val statuses = if (state.form.trackId.substringAfterLast(':') == "internal") listOf(PlayReleaseStatus.DRAFT, PlayReleaseStatus.COMPLETED) else listOf(PlayReleaseStatus.DRAFT, PlayReleaseStatus.IN_PROGRESS, PlayReleaseStatus.COMPLETED)
                PublishChoice("Release status", state.form.status, statuses.map { it to it.wireValue }, editable) { state.form = state.form.copy(status = it) }
                if (state.form.status == PlayReleaseStatus.IN_PROGRESS) PublishField("Staged rollout % · greater than 0, less than 100", state.form.rolloutPercent, { state.form = state.form.copy(rolloutPercent = it) }, editable, keyboard = KeyboardType.Decimal)
            }
            Text("draft does not distribute this bundle. completed requests full eligibility on this track; it does not prove review approval or live availability. Existing rollout halts and promotions should be reviewed in Play Console.", style = MaterialTheme.typography.bodySmall)
            PublishField("Release name (optional, max 50)", state.form.releaseName, { state.form = state.form.copy(releaseName = it) }, editable)
            PublishField("Release notes · max 500 characters for this locale", state.form.releaseNotes, { state.form = state.form.copy(releaseNotes = it) }, editable, lines = 3)
            PublishCheck("Retain all releases currently on this track in the replacement payload", state.form.retainExistingReleases, editable) { state.form = state.form.copy(retainExistingReleases = it) }
            Text("Track updates replace the release list. The final review lists every retained/removed release, version code, status and rollout fraction. No tester enrollment or production-gate bypass is performed.", style = MaterialTheme.typography.bodySmall)
            ManualPublishChecklist(state, editable, fileActions)
        }

        PublishSection("5 · Explicit edit lifecycle") {
            if (state.edit == null) {
                Text("Creating an edit is a Google API write and can invalidate your older edit for the same app. It does not upload or commit content. Do not create a second edit while another publisher is working.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { dialog = PublishDialog.CreateEdit(state.form.packageName.trim(), auth.identityKey, auth.accountLabel) }, enabled = auth.connected && !auth.authorizing && !busy && state.form.packageName.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("play-create-edit")) { Text("Review creating an edit / load tracks") }
            }
            state.edit?.let { current ->
                Text("Edit ${current.id}\nApp: ${current.packageName}\nExpires: ${publishDate(current.expiryTimeSeconds * 1000)}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = state::refreshEdit, enabled = auth.connected && !auth.authorizing && !busy, modifier = Modifier.fillMaxWidth()) { Text("Inspect edit and refresh tracks") }
                if (state.stagedReview == null) Button(onClick = { review { PublishDialog.Stage(state.makeStageReview()) } }, enabled = auth.connected && !auth.authorizing && !busy && state.artifact != null && !state.resultUncertain, modifier = Modifier.fillMaxWidth().testTag("play-review-stage")) { Text("Review upload and staging changes") }
                else {
                    Text(if (state.stagedCompletely) "Confirmed content has been staged; the form is locked to that snapshot." else "Staging is incomplete. Inspect or discard; a partial edit cannot be committed here.")
                    Button(onClick = state::validateEdit, enabled = auth.connected && !auth.authorizing && !busy && state.stagedCompletely && !state.resultUncertain, modifier = Modifier.fillMaxWidth().testTag("play-validate-edit")) { Text("Validate staged edit with Google") }
                }
                state.commitPreview?.let { preview ->
                    Button(onClick = { dialog = PublishDialog.Commit(preview, auth.identityKey, auth.accountLabel, state.stagedReview) }, enabled = auth.connected && !auth.authorizing && !busy && !state.resultUncertain, modifier = Modifier.fillMaxWidth().testTag("play-review-commit")) { Text("Review final commit…") }
                }
                OutlinedButton(onClick = { dialog = PublishDialog.Discard(current, auth.identityKey, auth.accountLabel) }, enabled = auth.connected && !auth.authorizing && !busy, modifier = Modifier.fillMaxWidth().testTag("play-discard-edit")) { Text("Review deleting / discarding this edit") }
            }
            state.recoveryEdits.forEach { previous ->
                Text("Previous authorization: ${previous.accountLabel}\nUncommitted/recovery edit: ${previous.edit.packageName} / ${previous.edit.id}", style = MaterialTheme.typography.bodySmall)
                Text("Old confirmation is invalid. Only the authorized owner may delete it; expired or already committed edits may no longer exist. No deletion is automatic.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { dialog = PublishDialog.Discard(previous.edit, auth.identityKey, auth.accountLabel) }, enabled = auth.connected && !auth.authorizing && !busy, modifier = Modifier.fillMaxWidth()) { Text("Review discarding prior edit ${previous.edit.id}") }
            }
            state.committedEdit?.let { Text("Commit accepted for ${it.packageName}, edit ${it.id}. Monitor status below; do not upload it again because the app is not visible yet.") }
        }

        PublishSection("6 · Monitor release lifecycle") {
            Text("App: ${state.form.packageName.ifBlank { "Choose a package" }}\nTrack: ${state.form.trackId.ifBlank { "Choose a real track" }}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = state::monitorReleases, enabled = auth.connected && !auth.authorizing && !busy && state.form.packageName.isNotBlank() && state.form.trackId.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("play-refresh-releases")) { Text("Read release status from Google") }
            state.releases?.let { releases ->
                if (releases.isEmpty()) Text("No release was returned for this app/track. This is not evidence of live publication.")
                releases.forEach { release ->
                    Text("${release.releaseName.ifBlank { "Unnamed release" }} · ${release.track}")
                    Text("Version codes: ${release.versionCodes.joinToString()}\n${release.releaseLifecycleState}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Review, managed publishing, country/device eligibility, staged rollout and policy/account gates can delay or prevent distribution. Check the authoritative Console state. A network error after commit is never retried automatically.", style = MaterialTheme.typography.bodySmall)
            PublishOfficialLink(fileActions, "Open Play Console in your browser", "https://play.google.com/console/")
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) { Text(if (showAdvanced) "Hide independent advanced actions" else "Advanced · internal app sharing / Data safety") }
        if (showAdvanced) PublishSection("Independent writes · not edit rollback") {
            Text("These actions are separate from an edit and each needs its own confirmation. They are never automatically run with upload, validation, commit or discard.")
            OutlinedButton(onClick = { review {
                val verified = requireNotNull(state.artifact)
                PublishDialog.InternalSharing(verified, state.sharingPreview(), auth.identityKey, auth.accountLabel)
            } }, enabled = auth.connected && !auth.authorizing && !busy && state.artifact != null, modifier = Modifier.fillMaxWidth()) { Text("Review internal app sharing upload") }
            state.internalSharing?.let { sharing ->
                Text("Internal-sharing install link: ${sharing.downloadUrl}", style = MaterialTheme.typography.bodySmall)
                Text("Google's sharing certificate: ${sharing.certificateFingerprint}", style = MaterialTheme.typography.bodySmall)
                PublishOfficialLink(fileActions, "Open returned internal-sharing link", sharing.downloadUrl)
            }
            HorizontalDivider()
            OutlinedButton(onClick = { fileActions.pickFile(listOf("csv")) { path -> if (path != null) state.readDataSafety(path) } }, enabled = auth.connected && !auth.authorizing && !busy && fileActions.canPickFile && state.form.packageName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Select completed Data safety CSV") }
            state.dataSafetyFile?.let { file ->
                Text("${file.fileName}\nApp: ${file.draft.packageName}\nSHA-256: ${file.sha256}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { review { PublishDialog.DataSafety(file, state.safetyPreview(file), auth.identityKey, auth.accountLabel) } }, enabled = auth.connected && !auth.authorizing && !busy, modifier = Modifier.fillMaxWidth()) { Text("Review independent Data safety submission") }
            }
            Text("Use the current Play Console CSV template. No privacy answers are inferred from permissions, SDK scans, or a generated template. Review every answer yourself; this does not complete other Console declarations.", style = MaterialTheme.typography.bodySmall)
        }
    }

    dialog?.let { pending ->
        PublishConfirmationDialog(pending, onDismiss = { dialog = null }, onConfirm = {
            dialog = null
            when (pending) {
                is PublishDialog.CreateEdit -> state.createEditAfterConfirmation(pending.packageName, pending.identity)
                is PublishDialog.Stage -> state.stageAfterConfirmation(pending.review)
                is PublishDialog.Commit -> state.commitAfterConfirmation(pending.preview, pending.identity)
                is PublishDialog.Discard -> state.discardAfterConfirmation(pending.edit, pending.identity)
                is PublishDialog.InternalSharing -> state.shareAfterConfirmation(pending.artifact, pending.preview, pending.identity)
                is PublishDialog.DataSafety -> state.submitSafetyAfterConfirmation(pending.file, pending.preview, pending.identity)
                PublishDialog.Disconnect -> auth.disconnect("Session credentials cleared. Nonsecret form/edit details remain here. Google permission grants are not revoked, and no remote edit or committed release was undone.")
            }
        })
    }
}

@Composable
private fun ManualPublishChecklist(state: PlayPublishState, enabled: Boolean, fileActions: FileActions) {
    val answers = state.form.attestations
    Text("Manual evidence · never auto-completed", style = MaterialTheme.typography.titleSmall)
    PublishCheck("I completed Play account registration and verification", answers.accountRegisteredAndVerified, enabled) { state.form = state.form.copy(attestations = answers.copy(accountRegisteredAndVerified = it)) }
    PublishCheck("I created and initialized this exact app in Play Console", answers.appCreatedAndInitializedInConsole, enabled) { state.form = state.form.copy(attestations = answers.copy(appCreatedAndInitializedInConsole = it)) }
    PublishCheck("I checked the displayed AAB upload certificate against Play App Signing / upload-key setup", answers.uploadKeyAndPlayAppSigningConfigured, enabled) { state.form = state.form.copy(attestations = answers.copy(uploadKeyAndPlayAppSigningConfigured = it)) }
    PublishCheck("I reviewed every applicable Console policy declaration (app access, ads, audience, rating, permissions and others)", answers.requiredPolicyDeclarationsReviewed, enabled) { state.form = state.form.copy(attestations = answers.copy(requiredPolicyDeclarationsReviewed = it)) }
    PublishCheck("I reviewed listing/assets, support contact, category, countries, pricing and privacy policy in Console", answers.storeListingAndDistributionConfigured, enabled) { state.form = state.form.copy(attestations = answers.copy(storeListingAndDistributionConfigured = it)) }
    PublishCheck("I verified Data safety against the app, all SDKs and backend behavior", answers.dataSafetyReviewed, enabled) { state.form = state.form.copy(attestations = answers.copy(dataSafetyReviewed = it)) }
    PublishChoice("New personal-account testing requirement", answers.hasNewPersonalAccountTestingRequirement, listOf(null to "Unknown — check Console", true to "Applies to this account", false to "I confirmed it does not apply"), enabled) { state.form = state.form.copy(attestations = answers.copy(hasNewPersonalAccountTestingRequirement = it)) }
    PublishCheck("Google approved production access where required", answers.productionAccessApproved, enabled) { state.form = state.form.copy(attestations = answers.copy(productionAccessApproved = it)) }
    Text("For affected personal accounts, 12 genuine testers must stay opted in continuously for 14 days, then Google must approve the production-access application. This app does not add testers, fake engagement or bypass that gate.", style = MaterialTheme.typography.bodySmall)
    var showEvidence by remember { mutableStateOf(false) }
    TextButton(onClick = { showEvidence = !showEvidence }) { Text(if (showEvidence) "Hide evidence details" else "Show requirements and official sources") }
    if (showEvidence) PlayReleaseChecklist.evaluate(state.artifact, state.form.goal, answers).forEach { item ->
        val evidence = when (item.evidence) {
            PlayGateEvidence.VERIFIED_LOCAL -> "Verified locally"
            PlayGateEvidence.USER_ATTESTED -> "User attestation only"
            PlayGateEvidence.ACTION_REQUIRED -> "Action/review required"
            PlayGateEvidence.NOT_APPLICABLE -> "Limited exception — see explanation"
        }
        Text("${item.title} · $evidence", style = MaterialTheme.typography.titleSmall)
        Text(item.explanation, style = MaterialTheme.typography.bodySmall)
        item.officialUrl?.let { PublishOfficialLink(fileActions, "Official requirement", it) }
    }
}

private sealed interface PublishDialog {
    data class CreateEdit(val packageName: String, val identity: String, val account: String) : PublishDialog
    data class Stage(val review: PublishStageReview) : PublishDialog
    data class Commit(val preview: CommitPreview, val identity: String, val account: String, val review: PublishStageReview?) : PublishDialog
    data class Discard(val edit: PlayEdit, val identity: String, val account: String) : PublishDialog
    data class InternalSharing(val artifact: AabArtifact, val preview: InternalSharingPreview, val identity: String, val account: String) : PublishDialog
    data class DataSafety(val file: PublishDataSafetyFile, val preview: DataSafetyPreview, val identity: String, val account: String) : PublishDialog
    data object Disconnect : PublishDialog
}

@Composable
private fun PublishConfirmationDialog(pending: PublishDialog, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember(pending) { mutableStateOf(false) }
    val title = when (pending) {
        is PublishDialog.CreateEdit -> "Create Google Play edit?"
        is PublishDialog.Stage -> "Stage these exact changes?"
        is PublishDialog.Commit -> "Final commit to Google Play?"
        is PublishDialog.Discard -> "Delete this remote edit?"
        is PublishDialog.InternalSharing -> "Upload for internal sharing?"
        is PublishDialog.DataSafety -> "Replace Data safety answers?"
        PublishDialog.Disconnect -> "Disconnect this screen session?"
    }
    val action = when (pending) {
        is PublishDialog.CreateEdit -> "Create edit only"
        is PublishDialog.Stage -> "Upload and stage, not commit"
        is PublishDialog.Commit -> "Confirm and commit once"
        is PublishDialog.Discard -> "Delete this edit"
        is PublishDialog.InternalSharing -> "Upload and create sharing link"
        is PublishDialog.DataSafety -> "Submit these CSV answers"
        PublishDialog.Disconnect -> "Clear session credentials"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).testTag("play-confirmation-summary"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (pending) {
                    is PublishDialog.CreateEdit -> {
                        Text("Account: ${pending.account}\nPackage: ${pending.packageName}")
                        Text("Create an edit and read its real tracks. This is a Google write, can invalidate your older edit for this app, and is not a dry run. No bundle/listing/image/track update or commit is authorized by this button.")
                    }
                    is PublishDialog.Stage -> StageReviewSummary(pending.review)
                    is PublishDialog.Commit -> {
                        Text("Account: ${pending.account}\nPackage: ${pending.preview.edit.packageName}\nEdit: ${pending.preview.edit.id}")
                        pending.review?.let { StageReviewSummary(it) }
                        Text("Validated edit changes", style = MaterialTheme.typography.titleSmall)
                        pending.preview.changes.forEach { Text("• $it") }
                        pending.preview.warnings.forEach { Text(it) }
                        Text("This final button sends ONE commit with ERROR_IF_IN_REVIEW. Existing reviews are not silently cancelled. Accepted commit is not proof of live distribution; monitor status and Console. Cancellation cannot undo a committed mutation.", style = MaterialTheme.typography.bodyMedium)
                    }
                    is PublishDialog.Discard -> {
                        Text("Account now authorized: ${pending.account}\nPackage: ${pending.edit.packageName}\nEdit to delete: ${pending.edit.id}")
                        Text("Permanently discard the uncommitted changes in this edit. Google may reject deletion if it has expired, was committed, or belongs to another account. Deletion cannot undo committed releases, internal-sharing uploads or separate Data safety writes.")
                    }
                    is PublishDialog.InternalSharing -> {
                        Text("Account: ${pending.account}")
                        ArtifactSummary(pending.artifact)
                        pending.preview.warnings.forEach { Text(it) }
                    }
                    is PublishDialog.DataSafety -> {
                        Text("Account: ${pending.account}\nApp: ${pending.preview.packageName}\nCSV: ${pending.file.fileName}\nSHA-256: ${pending.file.sha256}")
                        pending.preview.warnings.forEach { Text(it) }
                        Text("Selected CSV preview (up to 6,000 characters; review the complete source file)", style = MaterialTheme.typography.titleSmall)
                        Text(pending.file.draft.csv.take(6000), style = MaterialTheme.typography.bodySmall)
                    }
                    PublishDialog.Disconnect -> Text("Clear the in-memory token/input and the native cached access token. OAuth permission grants remain. The nonsecret form and edit IDs stay on this screen for reauthorization and recovery. No Google edit is deleted, and no prior write is rolled back.")
                }
                PublishCheck("I reviewed the account, app, target and consequences above", acknowledged, true) { acknowledged = it }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = acknowledged, modifier = Modifier.testTag("play-confirm-action")) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back without sending") } },
    )
}

@Composable
private fun StageReviewSummary(review: PublishStageReview) {
    Text("Account: ${review.accountLabel}\nApp: ${review.edit.packageName}\nEdit: ${review.edit.id}")
    ArtifactSummary(review.artifact)
    Text("Replacement listing · ${review.listing.language}", style = MaterialTheme.typography.titleSmall)
    Text(review.listing.title)
    Text(review.listing.shortDescription)
    Text(review.listing.fullDescription, style = MaterialTheme.typography.bodySmall)
    Text("Privacy policy checklist URL: ${review.privacyPolicyUrl.ifBlank { "Not supplied (internal-only)" }}\nThis URL is NOT uploaded by the listing endpoint.", style = MaterialTheme.typography.bodySmall)
    Text(if (review.replaceImageTypes) "Image action: delete all existing images of selected types, then upload these files" else "Image action: append these files; existing images remain", style = MaterialTheme.typography.titleSmall)
    if (review.images.isEmpty()) Text("No image changes")
    review.images.forEach { asset -> ImageReviewSummary(asset) }
    TrackReviewSummary("Previous track release list", review.previousTrack)
    TrackReviewSummary("Exact replacement track release list", review.replacementTrack)
    Text("Uploading/staging writes to Google's edit. It is not a dry run, but does NOT commit. After successful staging you must validate and separately confirm final commit. A partial upload/error is not retried automatically.")
}

@Composable
private fun TrackReviewSummary(label: String, track: PlayTrack) {
    Text("$label · ${track.track}", style = MaterialTheme.typography.titleSmall)
    if (track.releases.isEmpty()) Text("No releases")
    track.releases.forEach { release ->
        Text("${release.name ?: "Unnamed"} · versions ${release.versionCodes.joinToString()} · ${release.status.wireValue}${release.userFraction?.let { " · ${it * 100}%" }.orEmpty()}")
        release.releaseNotes.forEach { note -> Text("${note.language}: ${note.text}", style = MaterialTheme.typography.bodySmall) }
        release.inAppUpdatePriority?.let { Text("In-app update priority: $it", style = MaterialTheme.typography.bodySmall) }
        release.countryTargeting?.let { countries ->
            Text("Country targeting: ${countries.countries.joinToString()} · rest of world: ${countries.includeRestOfWorld}", style = MaterialTheme.typography.bodySmall)
            if (countries.additionalFields.isNotEmpty()) Text("Retained country-targeting fields: ${countries.additionalFields}", style = MaterialTheme.typography.bodySmall)
        }
        if (release.additionalFields.isNotEmpty()) Text("Retained Google release fields: ${release.additionalFields}", style = MaterialTheme.typography.bodySmall)
    }
    if (track.additionalFields.isNotEmpty()) Text("Retained Google track fields: ${track.additionalFields}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ArtifactSummary(artifact: AabArtifact) {
    Text("${artifact.packageName} · version ${artifact.versionCode}${artifact.versionName?.let { " ($it)" }.orEmpty()}", style = MaterialTheme.typography.titleSmall)
    Text("File: ${artifact.file.path}\nSize: ${artifact.sizeBytes} bytes\nMin SDK: ${artifact.minSdk ?: "not specified"} · target SDK: ${artifact.targetSdk ?: "not specified"}\nSHA-256: ${artifact.sha256}\nUpload certificate SHA-256: ${artifact.uploadCertificateSha256}", style = MaterialTheme.typography.bodySmall)
    Text("Permissions in manifest: ${artifact.permissions.sorted().joinToString().ifBlank { "none declared" }}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ImageReviewSummary(asset: ImageAsset) {
    Text("${asset.type.publishLabel()} · ${asset.width} × ${asset.height}\n${asset.file.path}\nSHA-256: ${asset.sha256}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PublishSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PublishNotice(message: String, error: Boolean = false) {
    Text(message, modifier = Modifier.fillMaxWidth().testTag(if (error) "play-error" else "play-feedback"), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PublishField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, lines: Int = 1, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else lines + 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PublishCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> PublishChoice(label: String, selected: T, options: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == selected }?.second ?: "Choose…"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}

@Composable
private fun PublishOfficialLink(fileActions: FileActions, label: String, url: String) {
    if (fileActions.canOpenUrl) TextButton(onClick = { fileActions.openUrl(url) }) { Text(label) }
    else Text(url, style = MaterialTheme.typography.bodySmall)
}

private fun PlayImageType.publishLabel(): String = when (this) {
    PlayImageType.ICON -> "Store icon"
    PlayImageType.FEATURE_GRAPHIC -> "Feature graphic"
    PlayImageType.PHONE_SCREENSHOTS -> "Phone screenshot"
    PlayImageType.SEVEN_INCH_SCREENSHOTS -> "7-inch tablet screenshot"
    PlayImageType.TEN_INCH_SCREENSHOTS -> "10-inch tablet screenshot"
    PlayImageType.TV_SCREENSHOTS -> "TV screenshot"
    PlayImageType.WEAR_SCREENSHOTS -> "Wear screenshot"
    PlayImageType.TV_BANNER -> "TV banner"
}

private fun publishDate(timestamp: Long): String = if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)) else "unknown"
