package dev.ide.android.workbench.publish

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.ext.ScreenBackHandler
import dev.ide.workbench.publish.AabArtifact
import dev.ide.workbench.publish.CommitPreview
import dev.ide.workbench.publish.DataSafetyPreview
import dev.ide.workbench.publish.ImageAsset
import dev.ide.workbench.publish.InternalSharingPreview
import dev.ide.workbench.publish.PlayDistributionGoal
import dev.ide.workbench.publish.PlayEdit
import dev.ide.workbench.publish.PlayGateEvidence
import dev.ide.workbench.publish.PlayImageType
import dev.ide.workbench.publish.PlayReleaseChecklist
import dev.ide.workbench.publish.PlayReleaseStatus
import dev.ide.workbench.publish.PlayTrack
import java.text.DateFormat
import java.util.Date

private enum class PublishStep(val title: String, val tag: String) {
    BUNDLE("Bundle", "bundle"), ACCESS("Access", "account"), RELEASE("Release", "release"), REVIEW("Review", "review")
}

/** The host owns navigation/insets. This pane owns only its scrolling step and fixed action footer. */
@Composable
fun PlayPublishPanel(backend: IdeBackend, fileActions: FileActions, modifier: Modifier = Modifier) {
    SecurePlayPublishSurface()
    val native = rememberPlayAuthorization()
    val auth = native.state
    val scope = rememberCoroutineScope()
    // Keep these outside the step branches: navigating never recreates credentials or the form.
    val state = remember(backend, auth) { PlayPublishState(auth, scope) }
    val buildState by backend.build.buildState.collectAsState()
    var tasks by remember(backend) { mutableStateOf<List<RunTaskOption>>(emptyList()) }
    var selectedTaskId by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(PublishStep.BUNDLE) }
    var dialog by remember { mutableStateOf<PublishDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showEditDetails by remember { mutableStateOf(false) }
    var showMonitor by remember { mutableStateOf(false) }
    val busy = state.busy != null
    val editable = !state.locked
    val authorized = auth.connected && auth.tokens.hasToken && !auth.authorizing
    val accessReady = authorized && state.accessChecked
    val canRequest = authorized && !busy
    val availableTracks = state.tracks.filter { track -> when (state.form.destination) {
        PublishDestination.DRAFT -> true
        PublishDestination.INTERNAL -> track.track.substringAfterLast(':') == "internal"
        PublishDestination.PRODUCTION -> track.track.substringAfterLast(':') == "production"
        PublishDestination.CLOSED -> track.track.substringAfterLast(':') !in setOf("internal", "production", "beta")
    } }

    DisposableEffect(state) { onDispose { state.close() } }
    LaunchedEffect(auth.identityKey, auth.connected) {
        if (auth.connected && !auth.authorizing && state.busy == null) state.synchronizeIdentity()
        dialog = null // A consent snapshot must not survive an authorization identity transition.
    }
    LaunchedEffect(backend, backend.project.rootPath, buildState.status) {
        if (buildState.status != RunStatus.Running && backend.project.rootPath.isNotBlank()) {
            tasks = runCatching { publishBundleTasks(backend.build.runTasks()) }.getOrDefault(emptyList())
            if (tasks.none { it.id == selectedTaskId }) selectedTaskId = tasks.firstOrNull()?.id.orEmpty()
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    ScreenBackHandler(enabled = busy || dialog != null || menuOpen || step != PublishStep.BUNDLE) {
        when {
            dialog != null -> dialog = null
            menuOpen -> menuOpen = false
            busy -> state.reportLocalError(IllegalStateException())
            step != PublishStep.BUNDLE -> step = PublishStep.entries[step.ordinal - 1]
        }
    }

    fun review(action: () -> PublishDialog) {
        try { dialog = action() } catch (failure: Exception) { state.reportLocalError(failure) }
    }
    fun selectBundle() {
        fileActions.pickFile(listOf("aab")) { path -> if (path != null) state.inspectBundle(path) }
    }

    val committed = state.committedEdit != null && state.edit == null
    val primaryLabel = when (step) {
        PublishStep.BUNDLE -> if (state.artifact == null) "Select AAB" else "Continue"
        PublishStep.ACCESS -> "Continue"
        PublishStep.RELEASE -> when {
            state.artifact == null -> "Select AAB"
            !accessReady -> if (authorized) "Check access" else "Add access"
            state.edit == null -> "Create edit"
            else -> "Review changes"
        }
        PublishStep.REVIEW -> when {
            !accessReady -> if (authorized) "Check access" else "Add access"
            committed -> "Refresh status"
            state.edit == null -> "Back to release"
            state.resultUncertain -> "Inspect in More"
            state.stagedReview == null -> "Upload changes"
            !state.stagedCompletely -> "Inspect in More"
            state.commitPreview == null -> "Validate"
            state.stagedReview?.replacementTrack?.releases?.lastOrNull()?.status == PlayReleaseStatus.DRAFT -> "Commit draft"
            else -> "Submit for review"
        }
    }
    val primaryTag = when (step) {
        PublishStep.BUNDLE -> if (state.artifact == null) "play-pick-bundle" else "play-continue-bundle"
        PublishStep.ACCESS -> "play-continue-access"
        PublishStep.RELEASE -> when {
            state.artifact == null -> "play-select-bundle-step"
            !accessReady -> "play-access-required"
            state.edit == null -> "play-create-edit"
            else -> "play-review-changes"
        }
        PublishStep.REVIEW -> when {
            !accessReady -> "play-access-required"
            committed -> "play-refresh-releases"
            state.edit == null -> "play-return-release"
            state.stagedReview == null -> "play-review-stage"
            state.commitPreview == null -> "play-validate-edit"
            else -> "play-review-commit"
        }
    }
    val primaryEnabled = !busy && !auth.authorizing && when (step) {
        PublishStep.BUNDLE -> state.artifact != null || (fileActions.canPickFile && editable)
        PublishStep.ACCESS -> accessReady
        PublishStep.RELEASE -> when {
            state.artifact == null || !accessReady -> true
            state.resultUncertain -> false
            state.edit == null -> state.form.packageName.isNotBlank()
            else -> availableTracks.any { it.track == state.form.trackId }
        }
        PublishStep.REVIEW -> when {
            !accessReady -> true
            state.resultUncertain -> false
            committed -> state.form.packageName.isNotBlank() && state.form.trackId.isNotBlank()
            state.edit == null -> true
            state.stagedReview == null -> state.artifact != null
            else -> state.stagedCompletely
        }
    }

    Column(modifier.fillMaxSize().testTag("play-publish-panel")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${step.ordinal + 1} of 4 · ${step.title}", style = MaterialTheme.typography.titleMedium)
                Text("Bundle · Access · Release · Review", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = !busy, modifier = Modifier.testTag("play-more")) { Text("More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Access") }, onClick = { menuOpen = false; step = PublishStep.ACCESS })
                    DropdownMenuItem(text = { Text("Edit details & recovery") }, onClick = { menuOpen = false; showEditDetails = !showEditDetails })
                    DropdownMenuItem(text = { Text("Release status") }, onClick = { menuOpen = false; showMonitor = !showMonitor })
                    DropdownMenuItem(text = { Text("Other publishing tools") }, onClick = { menuOpen = false; showAdvanced = !showAdvanced })
                    DropdownMenuItem(text = { Text("Open Play Console") }, enabled = fileActions.canOpenUrl,
                        onClick = { menuOpen = false; fileActions.openUrl("https://play.google.com/console/") })
                    if (auth.connected || auth.authorizing) DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menuOpen = false; dialog = PublishDialog.Disconnect })
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.busy.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                if (state.canCancel) TextButton(onClick = state::cancel, modifier = Modifier.testTag("play-cancel-operation")) { Text("Stop waiting") }
            }
            state.error?.let { PublishNotice(it, error = true) }
            state.feedback?.let { PublishNotice(it) }
            if (state.resultUncertain) PublishNotice("Write result unknown. No retry or commit. Use More to inspect the edit or explicitly discard it.", error = true)

            Column(Modifier.fillMaxWidth().testTag("Play-step-${step.tag}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    PublishStep.BUNDLE -> {
                        Text("Choose a signed app bundle. No account is needed to check it.", style = MaterialTheme.typography.bodyMedium)
                        state.artifact?.let { artifact ->
                            ArtifactSummary(artifact)
                            TextButton(onClick = ::selectBundle, enabled = fileActions.canPickFile && editable && state.edit == null,
                                modifier = Modifier.testTag("play-pick-bundle")) { Text("Change bundle") }
                        }
                        if (backend.project.rootPath.isNotBlank()) PublishDisclosure("Build from this project") {
                            if (tasks.isEmpty()) Text("No bundle task is available. Select an existing AAB instead.", style = MaterialTheme.typography.bodySmall)
                            else {
                                PublishChoice("Bundle task", selectedTaskId, tasks.map { it.id to it.label }, editable && buildState.status != RunStatus.Running) { selectedTaskId = it }
                                TextButton(onClick = { tasks.firstOrNull { it.id == selectedTaskId }?.let { state.buildBundle(backend, it) } },
                                    enabled = editable && buildState.status != RunStatus.Running && tasks.any { it.id == selectedTaskId },
                                    modifier = Modifier.testTag("play-build-bundle")) { Text("Build bundle") }
                                Text("Build: ${buildState.status}. Choose the exact output after it finishes.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        state.bundleCandidates.forEach { candidate ->
                            OutlinedButton(onClick = { state.inspectBundle(candidate.file.path) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(candidate.file.name)
                                    Text("${if (candidate.reportedByBuild) "Build output" else "Found output"} · ${publishDate(candidate.modifiedAt)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    PublishStep.ACCESS -> {
                        PlayAccountSection(native = native, state = state, fileActions = fileActions, enabled = !busy)
                        TextButton(onClick = { step = PublishStep.RELEASE }, enabled = !busy && !auth.authorizing,
                            modifier = Modifier.testTag("play-prepare-release")) { Text("Set up release first") }
                    }
                    PublishStep.RELEASE -> {
                        ReleaseFields(state, fileActions, editable, availableTracks)
                        if (!accessReady) Text(if (authorized) "Check API read access for this app and track before continuing." else "You can prepare this form now. Add access before creating an edit.", style = MaterialTheme.typography.bodySmall)
                    }
                    PublishStep.REVIEW -> {
                        if (committed) {
                            Text("Commit accepted", style = MaterialTheme.typography.titleMedium)
                            Text("Google accepted the edit. This does not mean the app is live.", style = MaterialTheme.typography.bodyMedium)
                            ReleaseStatus(state)
                            PublishOfficialLink(fileActions, "Open Play Console", "https://play.google.com/console/")
                        } else {
                            val staged = state.stagedReview
                            if (staged != null) {
                                StageReviewSummary(staged)
                                Text(when {
                                    !state.stagedCompletely -> "Upload incomplete. Inspect or discard the edit in More."
                                    state.commitPreview != null -> "Validated. Final submission still needs your confirmation."
                                    else -> "Changes uploaded to the edit. Validate before final submission."
                                }, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                state.artifact?.let { ArtifactSummary(it) }
                                Text("${state.form.trackId.ifBlank { "Choose a track" }} · ${if (state.form.destination == PublishDestination.DRAFT) "draft" else state.form.status.wireValue}", style = MaterialTheme.typography.titleSmall)
                                Text(if (state.form.updateListing) "Store listing will be replaced · ${state.form.language}" else "Store listing unchanged", style = MaterialTheme.typography.bodyMedium)
                                if (state.images.isNotEmpty()) Text("${state.images.size} image(s) · ${if (state.form.replaceSelectedImageTypes) "replace selected types" else "append"}", style = MaterialTheme.typography.bodySmall)
                                ManualPublishChecklist(state, editable, fileActions)
                            }
                        }
                    }
                }
            }
            if (showEditDetails) PublishSection("Edit details & recovery") {
                Text("Credentials stay in memory and clear when this screen closes. Closing or stopping a request cannot undo a Google write.", style = MaterialTheme.typography.bodySmall)
                state.edit?.let { current ->
                    Text("${current.packageName}\nEdit: ${current.id}\nExpires: ${publishDate(current.expiryTimeSeconds * 1000)}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = state::refreshEdit, enabled = canRequest) { Text("Inspect edit & refresh tracks") }
                    TextButton(onClick = { dialog = PublishDialog.Discard(current, auth.identityKey, auth.accountLabel) }, enabled = canRequest,
                        modifier = Modifier.testTag("play-discard-edit")) { Text("Discard edit…") }
                }
                if (state.edit == null && state.recoveryEdits.isEmpty()) Text("No active edit.", style = MaterialTheme.typography.bodySmall)
                state.recoveryEdits.forEach { previous ->
                    Text("${previous.accountLabel}\n${previous.edit.packageName} · ${previous.edit.id}", style = MaterialTheme.typography.bodySmall)
                    Text("Only its authorized owner can discard it. Nothing is deleted automatically.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { dialog = PublishDialog.Discard(previous.edit, auth.identityKey, auth.accountLabel) }, enabled = canRequest) { Text("Discard previous edit…") }
                }
                state.committedEdit?.let { Text("Accepted commit: ${it.packageName} · ${it.id}", style = MaterialTheme.typography.bodySmall) }
            }
            if (showMonitor) PublishSection("Release status") {
                Text("${state.form.packageName.ifBlank { "Select a bundle" }} · ${state.form.trackId.ifBlank { "Choose a track" }}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = state::monitorReleases, enabled = canRequest && state.form.packageName.isNotBlank() && state.form.trackId.isNotBlank(),
                    modifier = Modifier.testTag("play-refresh-releases")) { Text("Refresh status") }
                ReleaseStatus(state)
                PublishOfficialLink(fileActions, "Open Play Console", "https://play.google.com/console/")
            }
            if (showAdvanced) PublishSection("Other publishing tools") {
                Text("Independent writes; discarding an edit cannot undo them.", style = MaterialTheme.typography.bodySmall)
                if (state.artifact == null && state.edit == null) PublishField("Existing Play app package", state.form.packageName,
                    { state.form = state.form.copy(packageName = it) }, editable)
                TextButton(onClick = { review {
                    val artifact = requireNotNull(state.artifact)
                    PublishDialog.InternalSharing(artifact, state.sharingPreview(), auth.identityKey, auth.accountLabel)
                } }, enabled = canRequest && state.artifact != null) { Text("Internal app sharing…") }
                state.internalSharing?.let { sharing ->
                    PublishOfficialLink(fileActions, "Open sharing link", sharing.downloadUrl)
                    PublishDisclosure("Sharing details") {
                        Text("${sharing.downloadUrl}\nCertificate: ${sharing.certificateFingerprint}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { fileActions.pickFile(listOf("csv")) { path -> if (path != null) state.readDataSafety(path) } },
                    enabled = canRequest && fileActions.canPickFile && state.form.packageName.isNotBlank()) { Text("Select Data safety CSV") }
                state.dataSafetyFile?.let { file ->
                    Text(file.fileName, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { review { PublishDialog.DataSafety(file, state.safetyPreview(file), auth.identityKey, auth.accountLabel) } }, enabled = canRequest) { Text("Review Data safety submission…") }
                }
                Text("Use the current Console CSV template. Review all answers; none are inferred from your app.", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != PublishStep.BUNDLE) TextButton(onClick = { step = PublishStep.entries[step.ordinal - 1] }, enabled = !busy && !auth.authorizing,
                modifier = Modifier.testTag("play-back")) { Text("Back") }
            Box(Modifier.weight(1f).testTag("play-next")) {
                Button(onClick = {
                    when (step) {
                        PublishStep.BUNDLE -> if (state.artifact == null) selectBundle() else step = PublishStep.ACCESS
                        PublishStep.ACCESS -> step = PublishStep.RELEASE
                        PublishStep.RELEASE -> when {
                            state.artifact == null -> step = PublishStep.BUNDLE
                            !accessReady -> step = PublishStep.ACCESS
                            state.edit == null -> dialog = PublishDialog.CreateEdit(state.form.packageName.trim(), auth.identityKey, auth.accountLabel)
                            else -> step = PublishStep.REVIEW
                        }
                        PublishStep.REVIEW -> when {
                            !accessReady -> step = PublishStep.ACCESS
                            committed -> state.monitorReleases()
                            state.edit == null -> step = PublishStep.RELEASE
                            state.stagedReview == null -> review { PublishDialog.Stage(state.makeStageReview()) }
                            state.commitPreview == null -> state.validateEdit()
                            else -> state.commitPreview?.let { dialog = PublishDialog.Commit(it, auth.identityKey, auth.accountLabel, state.stagedReview) }
                        }
                    }
                }, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().testTag(primaryTag)) { Text(primaryLabel) }
            }
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
                PublishDialog.Disconnect -> auth.disconnect("Session credentials cleared. Form details remain; no Google write was undone.")
            }
        })
    }
}

@Composable
private fun ReleaseFields(state: PlayPublishState, fileActions: FileActions, enabled: Boolean, tracks: List<PlayTrack>) {
    if (state.artifact == null) PublishField("App package", state.form.packageName, { state.form = state.form.copy(packageName = it) }, enabled && state.edit == null)
    else Text(state.form.packageName, style = MaterialTheme.typography.bodyMedium)
    PublishChoice("Destination", state.form.destination, PublishDestination.entries.map { it to it.label }, enabled) { destination ->
        state.form = state.form.copy(destination = destination,
            trackId = when (destination) { PublishDestination.PRODUCTION -> "production"; PublishDestination.CLOSED -> ""; else -> "internal" },
            status = PlayReleaseStatus.DRAFT)
    }
    if (state.edit != null) {
        PublishChoice("Track", state.form.trackId, tracks.map { it.track to it.track }, enabled) { id ->
            state.form = state.form.copy(trackId = id, status = if (id.substringAfterLast(':') == "internal" && state.form.status == PlayReleaseStatus.IN_PROGRESS) PlayReleaseStatus.DRAFT else state.form.status)
        }
        if (tracks.isEmpty()) Text("No matching track. Create it in Play Console, then refresh tracks in More.", style = MaterialTheme.typography.bodySmall)
    } else Text("Create an edit to load the actual tracks. Nothing is uploaded yet.", style = MaterialTheme.typography.bodySmall)
    if (state.form.destination != PublishDestination.DRAFT) {
        val statuses = if (state.form.trackId.substringAfterLast(':') == "internal") listOf(PlayReleaseStatus.DRAFT, PlayReleaseStatus.COMPLETED)
            else listOf(PlayReleaseStatus.DRAFT, PlayReleaseStatus.IN_PROGRESS, PlayReleaseStatus.COMPLETED)
        PublishChoice("Status", state.form.status, statuses.map { it to it.wireValue }, enabled) { state.form = state.form.copy(status = it) }
        if (state.form.status == PlayReleaseStatus.IN_PROGRESS) PublishField("Rollout %", state.form.rolloutPercent, { state.form = state.form.copy(rolloutPercent = it) }, enabled, keyboard = KeyboardType.Decimal)
    }
    PublishField("Language", state.form.language, { state.form = state.form.copy(language = it) }, enabled)
    PublishField("Release notes · up to 500 characters", state.form.releaseNotes, { state.form = state.form.copy(releaseNotes = it) }, enabled, lines = 3)
    PublishDisclosure("Release options") {
        PublishField("Release name (optional)", state.form.releaseName, { state.form = state.form.copy(releaseName = it) }, enabled)
        PublishCheck("Keep existing track releases", state.form.retainExistingReleases, enabled) { state.form = state.form.copy(retainExistingReleases = it) }
        Text("Track updates replace the release list. Review retained and removed releases before upload.", style = MaterialTheme.typography.bodySmall)
        Text("Draft does not distribute the bundle. Completed requests distribution, subject to Google's review and eligibility.", style = MaterialTheme.typography.bodySmall)
    }
    PublishCheck("Update store listing", state.form.updateListing, enabled) { state.form = state.form.copy(updateListing = it) }
    if (state.form.updateListing) {
        Text("Replace this language's text. Other languages stay unchanged.", style = MaterialTheme.typography.bodySmall)
        PublishField("App title · up to 30 characters", state.form.title, { state.form = state.form.copy(title = it) }, enabled)
        PublishField("Short description · up to 80", state.form.shortDescription, { state.form = state.form.copy(shortDescription = it) }, enabled, lines = 2)
        PublishField("Full description · up to 4,000", state.form.fullDescription, { state.form = state.form.copy(fullDescription = it) }, enabled, lines = 5)
    } else Text("Your existing store listing stays unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PublishDisclosure(if (state.images.isEmpty()) "Store images (optional)" else "Store images · ${state.images.size} selected") {
        var imageType by remember { mutableStateOf(PlayImageType.PHONE_SCREENSHOTS) }
        PublishChoice("Image type", imageType, PlayImageType.entries.map { it to it.publishLabel() }, enabled) { imageType = it }
        TextButton(onClick = {
            val type = imageType
            fileActions.pickFile(listOf("png", "jpg", "jpeg")) { path -> if (path != null) state.inspectImage(path, type) }
        }, enabled = fileActions.canPickFile && enabled) { Text("Add image") }
        Text("Use real app screenshots. Icon: 512 × 512 RGBA PNG; feature graphic: 1024 × 500.", style = MaterialTheme.typography.bodySmall)
        state.images.forEach { image ->
            Text("${image.file.name} · ${image.type.publishLabel()} · ${image.width} × ${image.height}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { state.removeImage(image) }, enabled = enabled) { Text("Remove image") }
        }
        PublishCheck("Replace existing images of selected types", state.form.replaceSelectedImageTypes, enabled) { state.form = state.form.copy(replaceSelectedImageTypes = it) }
        Text("Otherwise files are appended. With no files selected, existing images stay unchanged.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManualPublishChecklist(state: PlayPublishState, enabled: Boolean, fileActions: FileActions) {
    val answers = state.form.attestations
    val external = state.form.goal != PlayDistributionGoal.INTERNAL_TESTING
    Text("Before uploading", style = MaterialTheme.typography.titleSmall)
    PublishCheck("My Play account is registered and verified", answers.accountRegisteredAndVerified, enabled) { state.form = state.form.copy(attestations = answers.copy(accountRegisteredAndVerified = it)) }
    PublishCheck("This app is created and initialized in Play Console", answers.appCreatedAndInitializedInConsole, enabled) { state.form = state.form.copy(attestations = answers.copy(appCreatedAndInitializedInConsole = it)) }
    PublishCheck("I checked this AAB's upload certificate against Play App Signing", answers.uploadKeyAndPlayAppSigningConfigured, enabled) { state.form = state.form.copy(attestations = answers.copy(uploadKeyAndPlayAppSigningConfigured = it)) }
    if (external) {
        PublishCheck("I reviewed all required Console policy declarations", answers.requiredPolicyDeclarationsReviewed, enabled) { state.form = state.form.copy(attestations = answers.copy(requiredPolicyDeclarationsReviewed = it)) }
        PublishCheck("Listing, contact, distribution and privacy settings are ready", answers.storeListingAndDistributionConfigured, enabled) { state.form = state.form.copy(attestations = answers.copy(storeListingAndDistributionConfigured = it)) }
        PublishCheck("Data safety covers this app, its SDKs and backend", answers.dataSafetyReviewed, enabled) { state.form = state.form.copy(attestations = answers.copy(dataSafetyReviewed = it)) }
        PublishField("Privacy policy URL (Console only)", state.form.privacyPolicyUrl, { state.form = state.form.copy(privacyPolicyUrl = it) }, enabled, keyboard = KeyboardType.Uri)
        Text("Verify this URL in Console; the listing API does not upload it.", style = MaterialTheme.typography.bodySmall)
    }
    if (state.form.goal in setOf(PlayDistributionGoal.PRODUCTION, PlayDistributionGoal.OPEN_TESTING)) {
        PublishChoice("Personal-account testing requirement", answers.hasNewPersonalAccountTestingRequirement,
            listOf(null to "Unknown — check Console", true to "Applies", false to "Does not apply"), enabled) {
            state.form = state.form.copy(attestations = answers.copy(hasNewPersonalAccountTestingRequirement = it))
        }
        if (answers.hasNewPersonalAccountTestingRequirement != false) PublishCheck("Google approved production access", answers.productionAccessApproved, enabled) { state.form = state.form.copy(attestations = answers.copy(productionAccessApproved = it)) }
    }
    PublishDisclosure("Requirements & official sources") {
        Text("These are your confirmations, not API-certified eligibility. This app never adds testers or bypasses Google's permissions.", style = MaterialTheme.typography.bodySmall)
        Text("Where required: 12 genuine testers continuously opted in for 14 days, followed by Google's production-access approval.", style = MaterialTheme.typography.bodySmall)
        if (!external) PublishField("Privacy policy URL (optional, Console only)", state.form.privacyPolicyUrl, { state.form = state.form.copy(privacyPolicyUrl = it) }, enabled, keyboard = KeyboardType.Uri)
        PlayReleaseChecklist.evaluate(state.artifact, state.form.goal, answers).forEach { item ->
            val evidence = when (item.evidence) {
                PlayGateEvidence.VERIFIED_LOCAL -> "Verified locally"
                PlayGateEvidence.USER_ATTESTED -> "Your confirmation"
                PlayGateEvidence.ACTION_REQUIRED -> "Review required"
                PlayGateEvidence.NOT_APPLICABLE -> "Not applicable"
            }
            Text("${item.title} · $evidence", style = MaterialTheme.typography.titleSmall)
            Text(item.explanation, style = MaterialTheme.typography.bodySmall)
            item.officialUrl?.let { PublishOfficialLink(fileActions, "Official requirement", it) }
        }
    }
}

@Composable
private fun ReleaseStatus(state: PlayPublishState) {
    state.releases?.let { releases ->
        if (releases.isEmpty()) Text("No release returned. This is not proof of publication.", style = MaterialTheme.typography.bodySmall)
        releases.forEach { release ->
            Text("${release.releaseName.ifBlank { "Unnamed release" }} · ${release.track}", style = MaterialTheme.typography.titleSmall)
            Text("Versions ${release.versionCodes.joinToString()} · ${release.releaseLifecycleState}", style = MaterialTheme.typography.bodySmall)
        }
    }
    PublishDisclosure("Status details") {
        Text("Review, managed publishing, device/country eligibility, rollout and account policies can delay distribution. Console is authoritative. A failed commit response is never retried automatically.", style = MaterialTheme.typography.bodySmall)
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
        is PublishDialog.CreateEdit -> "Create edit?"
        is PublishDialog.Stage -> "Upload these changes?"
        is PublishDialog.Commit -> "Commit to Google Play?"
        is PublishDialog.Discard -> "Discard this edit?"
        is PublishDialog.InternalSharing -> "Upload for sharing?"
        is PublishDialog.DataSafety -> "Replace Data safety answers?"
        PublishDialog.Disconnect -> "Disconnect?"
    }
    val action = when (pending) {
        is PublishDialog.CreateEdit -> "Create edit"
        is PublishDialog.Stage -> "Upload only"
        is PublishDialog.Commit -> "Confirm commit"
        is PublishDialog.Discard -> "Discard edit"
        is PublishDialog.InternalSharing -> "Upload"
        is PublishDialog.DataSafety -> "Submit answers"
        PublishDialog.Disconnect -> "Disconnect"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).testTag("play-confirmation-summary"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (pending) {
                    is PublishDialog.CreateEdit -> {
                        Text("${pending.account}\n${pending.packageName}")
                        Text("Creates a Google edit and reads its tracks. This may invalidate an older edit for this app.")
                        Text("No bundle is uploaded or committed.", style = MaterialTheme.typography.bodySmall)
                    }
                    is PublishDialog.Stage -> {
                        StageReviewSummary(pending.review)
                        Text("Uploads only to this edit. Validate and confirm separately before committing.")
                        PublishDisclosure("Upload protocol") {
                            Text("This is a Google write, not a dry run. The track is rechecked before upload. Partial or uncertain uploads are not retried automatically; stopping cannot roll them back.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is PublishDialog.Commit -> {
                        pending.review?.let { StageReviewSummary(it) } ?: Text("${pending.account}\n${pending.preview.edit.packageName}")
                        Text("Sends one final commit. Acceptance does not mean the app is live.")
                        pending.preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        PublishDisclosure("Commit protocol & validated changes") {
                            Text("Edit: ${pending.preview.edit.id}", style = MaterialTheme.typography.bodySmall)
                            pending.preview.changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("ONE commit with ERROR_IF_IN_REVIEW. Existing reviews are not cancelled. Google review and managed publishing still apply. Cancellation cannot undo a committed mutation.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is PublishDialog.Discard -> {
                        Text("${pending.account}\n${pending.edit.packageName}\nEdit: ${pending.edit.id}")
                        Text("Permanently deletes this edit's uncommitted changes. It cannot undo an accepted commit or independent writes.")
                        PublishDisclosure("Deletion details") { Text("Google can reject deletion if the edit expired, was committed, or belongs to another account.", style = MaterialTheme.typography.bodySmall) }
                    }
                    is PublishDialog.InternalSharing -> {
                        Text(pending.account)
                        ArtifactSummary(pending.artifact)
                        pending.preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    is PublishDialog.DataSafety -> {
                        Text("${pending.account}\n${pending.preview.packageName}\n${pending.file.fileName}")
                        pending.preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        PublishDisclosure("CSV preview & fingerprint") {
                            Text("SHA-256: ${pending.file.sha256}", style = MaterialTheme.typography.bodySmall)
                            Text("First 6,000 characters; review the complete source file.", style = MaterialTheme.typography.labelMedium)
                            Text(pending.file.draft.csv.take(6000), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PublishDialog.Disconnect -> {
                        Text("Clears this session's credentials. Your form stays; no Google write is undone.")
                        PublishDisclosure("Details") { Text("The in-memory token/input and native cached token are cleared. OAuth grants remain. Edit IDs remain available for reauthorization and recovery.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                PublishCheck("I reviewed this account, app and change", acknowledged, true) { acknowledged = it }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = acknowledged, modifier = Modifier.testTag("play-confirm-action")) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StageReviewSummary(review: PublishStageReview) {
    Text(review.accountLabel, style = MaterialTheme.typography.bodyMedium)
    ArtifactSummary(review.artifact)
    Text("Track: ${review.replacementTrack.track}", style = MaterialTheme.typography.titleSmall)
    val removed = review.previousTrack.releases.count { it !in review.replacementTrack.releases }
    Text("Existing releases: ${review.previousTrack.releases.size - removed} kept, $removed removed", style = MaterialTheme.typography.bodySmall)
    review.replacementTrack.releases.lastOrNull()?.let { release ->
        Text("${release.status.wireValue}${release.userFraction?.let { " · ${it * 100}%" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
    }
    val listing = review.listing
    Text(if (listing == null) "Store listing unchanged" else "Replace listing · ${listing.language}", style = MaterialTheme.typography.bodyMedium)
    Text(if (review.images.isEmpty()) "Images unchanged" else "${review.images.size} image(s) · ${review.imageLanguage} · ${if (review.replaceImageTypes) "replace selected types" else "append"}", style = MaterialTheme.typography.bodySmall)
    if (review.images.isNotEmpty() && review.replaceImageTypes) Text("All existing images of the selected types will be deleted from this locale.", style = MaterialTheme.typography.bodySmall)
    PublishDisclosure("Exact changes & edit details") {
        Text("App: ${review.edit.packageName}\nEdit: ${review.edit.id}", style = MaterialTheme.typography.bodySmall)
        if (listing != null) {
            Text(listing.title, style = MaterialTheme.typography.titleSmall)
            Text(listing.shortDescription, style = MaterialTheme.typography.bodySmall)
            Text(listing.fullDescription, style = MaterialTheme.typography.bodySmall)
        }
        Text("Privacy policy: ${review.privacyPolicyUrl.ifBlank { "Not supplied (internal only)" }}\nConsole checklist only; not uploaded by the listing endpoint.", style = MaterialTheme.typography.bodySmall)
        review.images.forEach { ImageReviewSummary(it) }
        TrackReviewSummary("Previous releases", review.previousTrack)
        TrackReviewSummary("Exact replacement releases", review.replacementTrack)
    }
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
            Text("Countries: ${countries.countries.joinToString()} · rest of world: ${countries.includeRestOfWorld}", style = MaterialTheme.typography.bodySmall)
            if (countries.additionalFields.isNotEmpty()) Text("Retained country fields: ${countries.additionalFields}", style = MaterialTheme.typography.bodySmall)
        }
        if (release.additionalFields.isNotEmpty()) Text("Retained Google fields: ${release.additionalFields}", style = MaterialTheme.typography.bodySmall)
    }
    if (track.additionalFields.isNotEmpty()) Text("Retained track fields: ${track.additionalFields}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ArtifactSummary(artifact: AabArtifact) {
    Text(artifact.file.name, style = MaterialTheme.typography.titleSmall)
    Text("${artifact.packageName} · ${artifact.versionName ?: "Version"} (${artifact.versionCode})", style = MaterialTheme.typography.bodyMedium)
    PublishDisclosure("Bundle details") {
        Text("${artifact.file.path}\n${artifact.sizeBytes} bytes\nMin SDK: ${artifact.minSdk ?: "not specified"} · target SDK: ${artifact.targetSdk ?: "not specified"}", style = MaterialTheme.typography.bodySmall)
        Text("SHA-256: ${artifact.sha256}\nUpload certificate SHA-256: ${artifact.uploadCertificateSha256}", style = MaterialTheme.typography.bodySmall)
        Text("Manifest permissions: ${artifact.permissions.sorted().joinToString().ifBlank { "none declared" }}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImageReviewSummary(asset: ImageAsset) {
    Text("${asset.type.publishLabel()} · ${asset.width} × ${asset.height}\n${asset.file.path}\nSHA-256: ${asset.sha256}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PublishSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun PublishDisclosure(label: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "$label · Hide" else label) }
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun PublishNotice(message: String, error: Boolean = false) {
    var expanded by remember(message) { mutableStateOf(false) }
    Text(message, modifier = Modifier.fillMaxWidth().testTag(if (error) "play-error" else "play-feedback"),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    if (message.length > 100) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "Details") }
}

@Composable
private fun PublishField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, lines: Int = 1, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else lines + 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
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
