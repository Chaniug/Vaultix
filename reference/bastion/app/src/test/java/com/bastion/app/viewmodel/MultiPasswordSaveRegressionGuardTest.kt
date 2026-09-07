package com.bastion.app.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPasswordSaveRegressionGuardTest {

    @Test
    fun saveAcrossTargetsDoesNotDeleteSameTargetMultiPasswordRowsAsDuplicateReplicas() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()
        val saveAcrossTargetsBody = source.substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")

        assertTrue(
            "Existing entries must be grouped by target so all password rows for that target are passed back into saveGroupedPasswordsInternal.",
            saveAcrossTargetsBody.contains(".groupBy { it.toStorageTarget().stableKey }")
        )
        assertFalse(
            "Same-target entries can be valid multi-password rows; do not delete them as duplicate replicas.",
            saveAcrossTargetsBody.contains("duplicateReplicaIds")
        )
        assertFalse(
            "Same-target entries can be valid multi-password rows; cleanup must only remove deselected targets.",
            saveAcrossTargetsBody.contains("sameTargetEntries.filterNot")
        )
    }

    @Test
    fun detailScreenUsesResolvedGroupMembersEvenWhenReplicaGroupIdExists() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/PasswordDetailScreen.kt"
        ).readText()

        assertFalse(
            "Detail screen must not collapse replicaGroupId entries to only the current entry; multi-password rows can share the same replica group.",
            source.contains("if (!entry.replicaGroupId.isNullOrBlank()) {\n            listOf(entry)")
        )
        assertFalse(
            "Detail screen must not collapse replicaGroupId entries to only the current entry; multi-password rows can share the same replica group.",
            source.contains("if (!entry.replicaGroupId.isNullOrBlank()) {\r\n            listOf(entry)")
        )
        assertTrue(
            "Detail screen should use resolved group members for the password card.",
            source.contains("val detailPasswords = resolvedGroupPasswords.ifEmpty { listOf(entry) }")
        )
        assertTrue(
            "Detail screen should use groupPasswords when rendering the password card.",
            source.contains("groupPasswords.ifEmpty { listOf(entry) }")
        )
    }

    @Test
    fun deletingKeePassDatabaseDeletesCachedRowsInsteadOfConvertingThemToLocal() {
        val viewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val deleteDatabaseBody = viewModelSource.substringAfter("fun deleteDatabase(")
            .substringBefore("fun exportDatabase")
        val passwordDaoSource = projectFile(
            "app/src/main/java/com/bastion/app/data/PasswordEntryDao.kt"
        ).readText()
        val secureItemDaoSource = projectFile(
            "app/src/main/java/com/bastion/app/data/SecureItemDao.kt"
        ).readText()

        assertTrue(
            "Removing a KeePass database must delete its cached password rows; clearing the binding makes them appear as Bastion-local duplicates.",
            deleteDatabaseBody.contains("passwordEntryDao().deleteByKeePassDatabaseId(databaseId)")
        )
        assertTrue(
            "Removing a KeePass database must delete its cached secure-item rows; clearing the binding makes TOTP/cards/notes appear as Bastion-local data.",
            deleteDatabaseBody.contains("secureItemDao().deleteByKeePassDatabaseId(databaseId)")
        )
        assertFalse(
            "KeePass database deletion must not convert password cache rows into Bastion-local rows.",
            deleteDatabaseBody.contains("passwordEntryDao().clearKeePassBindingForDatabase(databaseId)")
        )
        assertFalse(
            "KeePass database deletion must not convert secure-item cache rows into Bastion-local rows.",
            deleteDatabaseBody.contains("secureItemDao().clearKeePassBindingForDatabase(databaseId)")
        )
        assertTrue(
            "Password DAO needs a delete path scoped to the removed KeePass database.",
            passwordDaoSource.contains("DELETE FROM password_entries WHERE keepassDatabaseId = :databaseId")
        )
        assertTrue(
            "Secure item DAO needs a delete path scoped to the removed KeePass database.",
            secureItemDaoSource.contains("DELETE FROM secure_items WHERE keepass_database_id = :databaseId")
        )
    }

    @Test
    fun inlineTotpPreviewMatchesSimplePasswordPreviewAndKeepsCountdownInSync() {
        val previewSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/InlineTotpPreviewCard.kt"
        ).readText()
        val addTotpSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AddEditTotpScreen.kt"
        ).readText()
        val addPasswordSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AddEditPasswordScreen.kt"
        ).readText()

        assertTrue(
            "Inline TOTP previews should keep the compact code plus a one-second Material Expressive shape animation while the number uses the synchronized countdown.",
            previewSource.contains("fun InlineTotpPreviewCard(") &&
                previewSource.contains("val synchronizedRemainingSeconds") &&
                previewSource.contains("rememberInfiniteTransition(label = \"inline_totp_badge_shape_transition\")") &&
                previewSource.contains("durationMillis = 1000") &&
                previewSource.contains("MaterialExpressiveLoadingIndicator(") &&
                previewSource.contains("progress = { shapeProgress }") &&
                previewSource.contains("modifier = Modifier.size(60.dp)") &&
                previewSource.contains("color = if (isHotp) containerColor else contentColor") &&
                previewSource.contains("LoadingIndicatorDefaults.IndeterminateIndicatorPolygons") &&
                addTotpSource.contains("showHeader = false") &&
                addTotpSource.contains("showProgress = false") &&
                addPasswordSource.contains("showHeader = false") &&
                addPasswordSource.contains("showProgress = false")
        )
        assertFalse(
            "Inline TOTP preview must not bring back the visible TOTP/Steam header, shield icon, bottom progress bar, circular progress-ring badge, or determinate LoadingIndicator that freezes into one rotating shape.",
            previewSource.contains("LinearProgressIndicator") ||
                previewSource.contains("Icons.Default.Shield") ||
                previewSource.contains("Icons.Default.Games") ||
                previewSource.contains("\"TOTP\"") ||
                previewSource.contains("\"Steam\"") ||
                previewSource.contains("drawArc(") ||
                previewSource.contains("Canvas(") ||
                previewSource.contains("progress = { progress") ||
                previewSource.contains("progress = { animatedShapeProgress }") ||
                previewSource.contains("DeterminateIndicatorPolygons")
        )
    }

    @Test
    fun addPasswordAuthenticatorKeyFieldHasInlineScanAction() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/com/bastion/app/MainActivity.kt"
        ).readText()
        val simpleMainSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/SimpleMainScreen.kt"
        ).readText()
        val passwordTabPaneSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/PasswordTabPane.kt"
        ).readText()
        val mainScreenFabSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/MainScreenFab.kt"
        ).readText()
        val securitySection = source.substringAfter("// Security Card (TOTP)")
            .substringBefore("// Organization Card")
        val authenticatorKeyField = securitySection.substringAfter("value = authenticatorSecret")
            .substringBefore("ExposedDropdownMenuBox(")

        assertTrue(
            "The Add Password authenticator key field should expose QR scanning as the field trailing action so scanned secrets fill the same form directly.",
            authenticatorKeyField.contains("trailingIcon = {") &&
                authenticatorKeyField.contains("if (onScanAuthenticatorQrCode != null)") &&
                authenticatorKeyField.contains("IconButton(onClick = onScanAuthenticatorQrCode)") &&
                authenticatorKeyField.contains("Icons.Default.QrCodeScanner") &&
                source.contains("pendingQrResult?.let { qrValue ->") &&
                source.contains("applyScannedAuthenticator(qrValue)")
        )
        assertFalse(
            "Do not bring back the separate full-width Scan QR button below the authenticator key field.",
            securitySection.contains("FilledTonalButton(\n                                    onClick = onScanAuthenticatorQrCode")
        )
        assertTrue(
            "The main password page must pass the QR scanner action/result into inline and FAB Add Password sheets, otherwise the trailing scan icon disappears outside the standalone route.",
            mainActivitySource.contains("val mainQrResult = navController.currentBackStackEntry") &&
                mainActivitySource.contains("pendingPasswordAuthenticatorQrResult = mainQrResult") &&
                mainActivitySource.contains("onScanPasswordAuthenticatorQrCode = {") &&
                mainActivitySource.contains("navController.navigate(Screen.QrScanner.route)") &&
                simpleMainSource.contains("pendingPasswordAuthenticatorQrResult: String? = null") &&
                simpleMainSource.contains("onScanPasswordAuthenticatorQrCode: () -> Unit = {}") &&
                simpleMainSource.contains("pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult") &&
                simpleMainSource.contains("onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode") &&
                passwordTabPaneSource.contains("pendingQrResult = pendingPasswordAuthenticatorQrResult") &&
                passwordTabPaneSource.contains("onScanAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode") &&
                mainScreenFabSource.contains("pendingPasswordAuthenticatorQrResult: String? = null") &&
                mainScreenFabSource.contains("onScanPasswordAuthenticatorQrCode: () -> Unit = {}")
        )
    }

    @Test
    fun swipeableAddFabUsesEasyNotesStyleFullScreenTransition() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/SwipeableAddFab.kt"
        ).readText()
        val mainScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/SimpleMainScreen.kt"
        ).readText()
        val fabTransition = source.substringAfter("AnimatedVisibility(\n            visible = !isExpanded")
            .substringBefore("Box(\n                modifier = Modifier")
        val renderMainSurface = mainScreenSource.substringAfter("fun RenderMainSurface() {")
            .substringBefore("val prepareTotpAddStorageDefaults")
        val scaledMainSurfaceLayer = renderMainSurface.substringAfter("Box(\n        modifier = Modifier")
            .substringBefore("if (useDraggableNav")
        val overlayCallIndex = mainScreenSource.indexOf("MainScreenFabOverlay(")
        val renderCallIndex = mainScreenSource.indexOf("RenderMainSurface()", startIndex = overlayCallIndex)

        assertTrue(
            "FAB add should keep the lightweight button transition and delegate full-screen editing to the page-level inline editor.",
            source.contains("onClick: () -> Unit") &&
                source.contains("onClick = onClick") &&
                fabTransition.contains("fadeIn(animationSpec = tween(160))") &&
                fabTransition.contains("scaleIn(initialScale = 0.9f, animationSpec = tween(180))") &&
                fabTransition.contains("fadeOut(animationSpec = tween(120))") &&
                fabTransition.contains("scaleOut(targetScale = 0.9f, animationSpec = tween(140))") &&
                renderMainSurface.contains("Box(modifier = Modifier.fillMaxSize())") &&
                scaledMainSurfaceLayer.contains(".matchParentSize()") &&
                overlayCallIndex in 0 until renderCallIndex
        )
        assertFalse(
            "Do not bring back the FAB-to-fullscreen resize animation; EasyNotes uses a screen transition instead.",
            source.contains("Animatable(0f)") ||
                source.contains("expandProgress") ||
                source.contains("lerp(fabSize") ||
                source.contains("requiredSize(fullWidth, fullHeight)") ||
                source.contains("offset { IntOffset")
        )
    }

    @Test
    fun keepassCompatibilityRefreshEntrypointsUseSyncTaskRunner() {
        val passwordViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()
        val noteViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/NoteViewModel.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TotpViewModel.kt"
        ).readText()
        val bankCardViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/BankCardViewModel.kt"
        ).readText()
        val documentViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/DocumentViewModel.kt"
        ).readText()

        val passwordSyncEntrypoint = passwordViewModelSource
            .substringAfter("private fun syncKeePassDatabase(")
            .substringBefore("private suspend fun syncKeePassDatabaseNow(")
        assertTrue(
            "Password KeePass filter/manual refresh must run through SyncTaskRunner instead of launching a direct workspace scan.",
            passwordSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                passwordSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                passwordSyncEntrypoint.contains("SyncItemKind.PASSWORD") &&
                passwordSyncEntrypoint.contains("SyncItemKind.TOTP") &&
                !passwordSyncEntrypoint.contains("viewModelScope.launch(Dispatchers.Default)")
        )

        val noteSyncEntrypoint = noteViewModelSource
            .substringAfter("fun syncKeePassNotes(")
            .substringBefore("private suspend fun syncKeePassNotesNow(")
        assertTrue(
            "Note KeePass filter refresh must run through SyncTaskRunner so rapid filter changes do not spawn parallel scans.",
            noteSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                noteSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                noteSyncEntrypoint.contains("SyncItemKind.NOTE")
        )

        val totpSyncEntrypoint = totpViewModelSource
            .substringAfter("private fun syncKeePassTotp(")
            .substringBefore("private suspend fun syncKeePassTotpNow(")
        assertTrue(
            "TOTP KeePass filter refresh must run through SyncTaskRunner so repeated page/filter entry stays single-flight.",
            totpSyncEntrypoint.contains("SyncTaskRunner.request(") &&
                totpSyncEntrypoint.contains("SyncTarget.KeePassCompatibilityIndex(") &&
                totpSyncEntrypoint.contains("SyncItemKind.TOTP")
        )

        val cardAllEntrypoint = bankCardViewModelSource
            .substringAfter("fun syncAllKeePassCards(")
            .substringBefore("suspend fun syncAllKeePassCardsNow(")
        val cardSingleEntrypoint = bankCardViewModelSource
            .substringAfter("fun syncKeePassCards(")
            .substringBefore("suspend fun syncKeePassCardsNow(")
        assertTrue(
            "Bank-card KeePass compatibility refresh wrappers must also use SyncTaskRunner when called outside CardWalletScreen.",
            cardAllEntrypoint.contains("SyncTaskRunner.request(") &&
                cardAllEntrypoint.contains("SyncItemKind.BANK_CARD") &&
                cardSingleEntrypoint.contains("SyncTaskRunner.request(") &&
                cardSingleEntrypoint.contains("SyncItemKind.BANK_CARD")
        )

        val documentAllEntrypoint = documentViewModelSource
            .substringAfter("fun syncAllKeePassDocuments(")
            .substringBefore("suspend fun syncAllKeePassDocumentsNow(")
        val documentSingleEntrypoint = documentViewModelSource
            .substringAfter("fun syncKeePassDocuments(")
            .substringBefore("suspend fun syncKeePassDocumentsNow(")
        assertTrue(
            "Document KeePass compatibility refresh wrappers must also use SyncTaskRunner when called outside CardWalletScreen.",
            documentAllEntrypoint.contains("SyncTaskRunner.request(") &&
                documentAllEntrypoint.contains("SyncItemKind.DOCUMENT") &&
                documentSingleEntrypoint.contains("SyncTaskRunner.request(") &&
            documentSingleEntrypoint.contains("SyncItemKind.DOCUMENT")
        )
    }

    @Test
    fun keepassRemoteManualAndVisibleSyncShareCoordinatorQueue() {
        val localKeePassViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val remoteUploadWorkerSource = projectFile(
            "app/src/main/java/com/bastion/app/workers/KeePassRemoteUploadWorker.kt"
        ).readText()
        val syncContractsSource = projectFile(
            "app/src/main/java/com/bastion/app/sync/SyncContracts.kt"
        ).readText()

        val manualSyncEntrypoint = localKeePassViewModelSource
            .substringAfter("fun syncRemoteDatabase(")
            .substringBefore("fun autoSyncVisibleRemoteDatabase(")
        val visibleSyncEntrypoint = localKeePassViewModelSource
            .substringAfter("fun autoSyncVisibleRemoteDatabase(")
            .substringBefore("private suspend fun syncRemoteDatabaseInternal(")

        assertTrue(
            "Manual/silent KeePass remote sync must await SyncTaskRunner so UI buttons cannot race visible auto-sync for the same remote KDBX.",
            manualSyncEntrypoint.contains("SyncTaskRunner.requestAndAwait(") &&
                manualSyncEntrypoint.contains("SyncTarget.KeePassDatabase(databaseId)") &&
                manualSyncEntrypoint.contains("dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY)") &&
                manualSyncEntrypoint.contains("SyncTrigger.MANUAL") &&
                manualSyncEntrypoint.contains("SyncTrigger.RETRY") &&
                manualSyncEntrypoint.contains("SyncPriority.MANUAL") &&
                manualSyncEntrypoint.contains("SyncPriority.REPAIR") &&
                manualSyncEntrypoint.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Visible KeePass remote auto-sync must stay on the same coordinator dedupe queue as manual sync.",
            visibleSyncEntrypoint.contains("SyncTaskRunner.request(") &&
            visibleSyncEntrypoint.contains("dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY)") &&
                visibleSyncEntrypoint.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Background KeePass remote upload worker must share the same coordinator queue as foreground/visible remote sync.",
            syncContractsSource.contains("const val KEEPASS_REMOTE_SYNC_DEDUPE_KEY = \"keepass_visible_remote\"") &&
                localKeePassViewModelSource.contains("VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY = KEEPASS_REMOTE_SYNC_DEDUPE_KEY") &&
                remoteUploadWorkerSource.contains("SyncTaskRunner.requestAndAwait(") &&
                remoteUploadWorkerSource.contains("SyncTarget.KeePassDatabase(targetDatabaseId)") &&
                remoteUploadWorkerSource.contains("dedupeKey = SyncKey(KEEPASS_REMOTE_SYNC_DEDUPE_KEY)") &&
                remoteUploadWorkerSource.contains("SyncTrigger.WORKER_RECOVERY") &&
                remoteUploadWorkerSource.contains("SyncNetworkPolicy.REQUIRED")
        )
        assertTrue(
            "Background KeePass remote upload worker must be a single WorkManager drain task: KEEP avoids chain storms, and the worker loops pending databases itself.",
            remoteUploadWorkerSource.contains("while (drainSteps < MAX_DRAIN_STEPS)") &&
                remoteUploadWorkerSource.contains("resolveTargetDatabaseId(requestedDatabaseId, skippedDatabaseIds)") &&
                remoteUploadWorkerSource.contains("requestedDatabaseId = null") &&
                remoteUploadWorkerSource.contains("ExistingWorkPolicy.KEEP") &&
                !remoteUploadWorkerSource.contains("ExistingWorkPolicy.APPEND_OR_REPLACE") &&
                !remoteUploadWorkerSource.contains("private suspend fun enqueueNextPendingIfAny") &&
                !remoteUploadWorkerSource.substringAfter("is UploadStepResult.Completed ->")
                    .substringBefore("is UploadStepResult.Merged ->")
                    .contains("enqueueIfPending(applicationContext)")
        )
    }

    @Test
    fun keepassRemoteWritesStayVisibleToOtherClients() {
        val kdbxServiceSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/KeePassKdbxService.kt"
        ).readText()
        val webDavFileSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavKeePassFileSource.kt"
        ).readText()
        val oneDriveFileSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/OneDriveKeePassFileSource.kt"
        ).readText()
        val localKeePassViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/LocalKeePassViewModel.kt"
        ).readText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()
        val workspaceRepositorySource = projectFile(
            "app/src/main/java/com/bastion/app/repository/KeePassWorkspaceRepository.kt"
        ).readText()
        val compatibilityBridgeSource = projectFile(
            "app/src/main/java/com/bastion/app/repository/KeePassCompatibilityBridge.kt"
        ).readText()
        val fileSourceContract = projectFile(
            "app/src/main/java/com/bastion/app/utils/KeePassFileSource.kt"
        ).readText()
        val localKeePassDatabaseSource = projectFile(
            "app/src/main/java/com/bastion/app/data/LocalKeePassDatabase.kt"
        ).readText()
        val webDavBrowserSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/LocalKeePassWebDavBrowser.kt"
        ).readText()
        val oneDriveBrowserSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/LocalKeePassOneDriveBrowser.kt"
        ).readText()
        val googleDriveBrowserSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/LocalKeePassGoogleDriveBrowser.kt"
        ).readText()
        val writeDatabaseBody = kdbxServiceSource
            .substringAfter("private suspend fun writeDatabase(")
            .substringBefore("private fun keePassPendingChangeRepository(")
        val webDavWriteBody = webDavFileSource
            .substringAfter("override suspend fun write(")
            .substringBefore("override suspend fun listChildren(")
        val webDavCreateBody = webDavFileSource
            .substringAfter("suspend fun createFileInDirectory(")
            .substringBefore("private fun resolveResource(")
        val manualSyncBody = localKeePassViewModelSource
            .substringAfter("private suspend fun syncRemoteDatabaseInternal(")
            .substringBefore("private suspend fun handleSyncRemoteFailure(")
        val passwordSyncBody = passwordViewModelSource
            .substringAfter("private suspend fun syncKeePassDatabaseNow(")
            .substringBefore("private suspend fun refreshAllKeePassDatabases(")
        val writeExternalBytesBody = kdbxServiceSource
            .substringAfter("private fun writeExternalBytes(")
            .substringBefore("private fun openExternalOutputStream(")
        val openExternalOutputStreamBody = kdbxServiceSource
            .substringAfter("private fun openExternalOutputStream(")
            .substringBefore("suspend fun resolveRemoteConflict(")

        assertTrue(
            "Saving a remote KeePass working copy must try a foreground remote upload before falling back to WorkManager; otherwise Bastion can show local-only data that other KeePass clients cannot see.",
            writeDatabaseBody.contains("val syncOutcome = syncRemoteWorkingCopy(") &&
                writeDatabaseBody.contains("if (syncOutcome != null)") &&
                writeDatabaseBody.contains("resolvedDatabase = syncOutcome.finalDatabase") &&
                writeDatabaseBody.contains("} else {") &&
                writeDatabaseBody.indexOf("val syncOutcome = syncRemoteWorkingCopy(") <
                    writeDatabaseBody.indexOf("markRemoteWritePending(database, bytes)") &&
                writeDatabaseBody.indexOf("markRemoteWritePending(database, bytes)") <
                    writeDatabaseBody.indexOf("enqueueRemoteWorkingCopyUpload(database.id)")
        )
        assertTrue(
            "Remote KeePass writes must read the remote bytes back and decode them before marking sync success, so a bad WebDAV/OneDrive write cannot be reported as synchronized.",
            kdbxServiceSource.contains("private suspend fun verifyRemoteKdbxWrite(") &&
                kdbxServiceSource.contains("val remoteBytes = fileSource.read()") &&
                kdbxServiceSource.contains("remoteHash != expectedHash") &&
                kdbxServiceSource.contains("decodeDatabase(") &&
                kdbxServiceSource.contains("Remote KDBX write verified") &&
                kdbxServiceSource.contains("Remote KDBX write verification failed") &&
                kdbxServiceSource.contains("val verifiedRemote = verifyRemoteKdbxWrite(")
        )
        assertTrue(
            "Manual KeePass remote sync must use the same read-back/decode verification before marking a WebDAV/OneDrive write synchronized.",
            kdbxServiceSource.contains("internal suspend fun verifyRemoteKdbxWrite(") &&
                kdbxServiceSource.contains("sourceLabel = \"service-sync-merge\"") &&
                kdbxServiceSource.contains("sourceLabel = \"service-sync-upload\"") &&
                kdbxServiceSource.contains("baseHash = verifiedRemote.hash") &&
                kdbxServiceSource.contains("workingHash = verifiedRemote.hash") &&
                manualSyncBody.contains("kdbxService.syncRemoteDatabase(databaseId)")
        )
        assertFalse(
            "LocalKeePassViewModel should not keep a second copy of remote hash/version merge logic; all remote manual, visible, and password-page refreshes must share KeePassKdbxService.syncRemoteDatabase.",
            manualSyncBody.contains("fileSource.read()") ||
                manualSyncBody.contains("knownRemoteVersion") ||
                manualSyncBody.contains("sourceLabel = \"manual-sync-")
        )
        assertTrue(
            "Manual/visible KeePass remote refresh must compare the actual downloaded remote KDBX hash, not only provider etag/version. Some WebDAV providers return unchanged or empty version metadata, which made Bastion B miss Bastion A's writes.",
            kdbxServiceSource.contains("suspend fun syncRemoteDatabase(databaseId: Long): Result<KeePassRemoteSyncResult>") &&
                kdbxServiceSource.contains("val remoteBytes = fileSource.read()") &&
                kdbxServiceSource.contains("val remoteHash = GoogleDriveKeePassSupport.sha256Hex(remoteBytes)") &&
                kdbxServiceSource.contains("val remoteHasChanges =") &&
                kdbxServiceSource.contains("baseHash != remoteHash") &&
                kdbxServiceSource.contains("localChanged=${'$'}localHasChanges remoteChanged=${'$'}remoteHasChanges") &&
                kdbxServiceSource.indexOf("val remoteBytes = fileSource.read()") <
                    kdbxServiceSource.indexOf("if (remoteHasChanges)") &&
                !manualSyncBody.contains("knownRemoteVersion != currentRemoteVersion")
        )
        assertTrue(
            "Password-page KeePass force refresh must pull the remote working copy before rebuilding the Room projection. Otherwise Bastion B can keep showing stale cached entries until it performs a local write.",
            workspaceRepositorySource.contains("suspend fun syncRemoteDatabase(databaseId: Long)") &&
                compatibilityBridgeSource.contains("suspend fun syncLegacyRemoteDatabase(databaseId: Long)") &&
                passwordSyncBody.contains("if (forceRefresh)") &&
                passwordSyncBody.contains("bridge.syncLegacyRemoteDatabase(databaseId)") &&
                passwordSyncBody.indexOf("bridge.syncLegacyRemoteDatabase(databaseId)") <
                    passwordSyncBody.indexOf(".loadLegacyWorkspace(databaseId")
        )
        assertTrue(
            "KeePass WebDAV writes should use a compatibility-first direct PUT. Hidden temp-file MOVE overwrites are rejected by common providers and leave only Bastion's local working copy updated.",
            webDavWriteBody.contains("sardine.put(remoteUrl, bytes, KEEPASS_KDBX_MIME_TYPE)") &&
                webDavCreateBody.contains("sardine.put(targetUrl, bytes, KEEPASS_KDBX_MIME_TYPE)") &&
                !webDavFileSource.contains("sardine.move(") &&
                !webDavFileSource.contains("buildSiblingTempPath(") &&
                !webDavFileSource.contains(".bastion-tmp")
        )
        assertTrue(
            "Remote KeePass providers should upload KDBX bytes with the KeePass MIME type instead of generic octet-stream, so cloud document providers keep the file editable for other clients.",
            fileSourceContract.contains("const val KEEPASS_KDBX_MIME_TYPE = \"application/x-keepass2\"") &&
                webDavFileSource.contains("KEEPASS_KDBX_MIME_TYPE") &&
                oneDriveFileSource.contains("contentType: String = KEEPASS_KDBX_MIME_TYPE") &&
                oneDriveFileSource.contains("toRequestBody(KEEPASS_KDBX_MIME_TYPE.toMediaType())")
        )
        assertTrue(
            "New remote KeePass databases should default to the broadest compatibility profile for Bastion features: KDBX4 + AES cipher + AES-KDF rounds. Argon2 remains available only through advanced options.",
            localKeePassDatabaseSource.contains("const val DEFAULT_AES_KDF_ROUNDS = 600_000L") &&
                localKeePassDatabaseSource.contains("fun remoteCompatibilityDefaults()") &&
                localKeePassDatabaseSource.contains("formatVersion = KeePassFormatVersion.KDBX4") &&
                localKeePassDatabaseSource.contains("kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF") &&
                localKeePassDatabaseSource.contains("transformRounds = DEFAULT_AES_KDF_ROUNDS") &&
                webDavBrowserSource.contains("val defaultOptions = remember { KeePassDatabaseCreationOptions.remoteCompatibilityDefaults() }") &&
                oneDriveBrowserSource.contains("val defaultOptions = remember { KeePassDatabaseCreationOptions.remoteCompatibilityDefaults() }") &&
                googleDriveBrowserSource.contains("creationOptions = KeePassDatabaseCreationOptions.remoteCompatibilityDefaults()") &&
                !googleDriveBrowserSource.contains("creationOptions = KeePassDatabaseCreationOptions(),")
        )
        assertTrue(
            "When switching remote-create KDF algorithms, the rounds field should follow the algorithm default only while it is still untouched; AES-KDF must never inherit Argon2's 8 iterations.",
            localKeePassDatabaseSource.contains("fun defaultTransformRoundsFor(kdfAlgorithm: KeePassKdfAlgorithm): Long") &&
                webDavBrowserSource.contains("defaultTransformRoundsFor(kdfAlgorithm)") &&
                webDavBrowserSource.contains("defaultTransformRoundsFor(it)") &&
                oneDriveBrowserSource.contains("defaultTransformRoundsFor(kdfAlgorithm)") &&
                oneDriveBrowserSource.contains("defaultTransformRoundsFor(it)")
        )
        assertTrue(
            "SAF-backed KeePass writes should prefer provider-friendly truncate-write mode like mature KeePass clients, falling back to rwt only when wt is unavailable.",
            writeExternalBytesBody.contains("openExternalOutputStream(uri)?.use") &&
                openExternalOutputStreamBody.indexOf("openOutputStream(uri, \"wt\")") <
                    openExternalOutputStreamBody.indexOf("openOutputStream(uri, \"rwt\")") &&
                !kdbxServiceSource.contains("private fun openExternalFileDescriptor(")
        )
    }

    @Test
    fun quickFilterChipsMorphToSelectedShapeWhilePressed() {
        val expressiveChipSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/BastionExpressiveFilterChip.kt"
        ).readText()
        val quickFilterChipSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordQuickFilterChips.kt"
        ).readText()

        assertTrue(
            "Quick filter chips should use the shared expressive chip implementation so pressed/selected shape behavior stays consistent.",
            quickFilterChipSource.contains("BastionExpressiveFilterChip(") &&
                quickFilterChipSource.contains("interactionSource = interactionSource")
        )
        assertTrue(
            "A non-selected quick filter chip should morph to the selected chip corner radius while pressed, matching Material Expressive state continuity.",
            expressiveChipSource.contains("collectIsPressedAsState()") &&
                expressiveChipSource.contains("val targetCornerRadius = if (selected || isPressed) 12.dp else 20.dp") &&
                expressiveChipSource.contains("animateDpAsState(") &&
                expressiveChipSource.contains("label = \"bastionExpressiveFilterChipCornerRadius\"")
        )
    }

    @Test
    fun createCategoryDialogKeepsInputReachableWhenCategoryListIsLong() {
        val dialogSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/CreateCategoryDialog.kt"
        ).readText()
        val dialogTextBody = dialogSource.substringAfter("text = {")
            .substringBefore("confirmButton = {")

        assertTrue(
            "CreateCategoryDialog content must scroll vertically so long local/KeePass folder lists cannot push the name field off-screen.",
            dialogSource.contains("import androidx.compose.foundation.verticalScroll") &&
                dialogSource.contains("val createDialogContentScroll = rememberScrollState()") &&
                dialogTextBody.contains(".verticalScroll(createDialogContentScroll)") &&
                dialogTextBody.contains("OutlinedTextField(") &&
                dialogTextBody.indexOf(".verticalScroll(createDialogContentScroll)") <
                    dialogTextBody.indexOf("OutlinedTextField(")
        )
    }

    @Test
    fun normalPasswordPageShowsBatchTransferInQuickStatusBar() {
        val trackerSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordBatchTransferProgressTracker.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordQuickFolderSections.kt"
        ).readText()
        val quickStatusTransferSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/QuickStatusTransferBar.kt"
        ).readText()
        val listContentSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListContent.kt"
        ).readText()
        val mainPaneSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListMainPane.kt"
        ).readText()
        val quickStatusDialogsSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListQuickStatusDialogs.kt"
        ).readText()
        val moveSupportSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordBatchMoveSupport.kt"
        ).readText()
        val unifiedMoveSheetSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/UnifiedMoveToCategoryBottomSheet.kt"
        ).readText()
        val passwordRepositorySource = projectFile(
            "app/src/main/java/com/bastion/app/repository/PasswordRepository.kt"
        ).readText()
        val dialogStateSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListDialogState.kt"
        ).readText()

        assertTrue(
            "The transfer tracker must keep a short success phase so the quick status bar can show the completed result before returning to breadcrumbs.",
            trackerSource.contains("enum class PasswordBatchTransferPhase") &&
                trackerSource.contains("val operationId: Long") &&
                // 计数器已从 `private var Long` 加固为 `private val AtomicLong`（线程安全）。
                trackerSource.contains("private val nextOperationId = AtomicLong") &&
                trackerSource.contains("RUNNING") &&
                trackerSource.contains("SUCCESS") &&
                trackerSource.contains("fun complete(") &&
                trackerSource.contains("delay(1300)")
        )
        assertTrue(
            "The normal password page must show transfer progress in the quick status bar when the path banner is enabled, and auto-open the progress dialog when the banner is disabled.",
            listContentSource.contains("PasswordBatchTransferProgressTracker.progress.collectAsState()") &&
                dialogStateSource.contains("var showQuickStatusTransferDialog by mutableStateOf(false)") &&
                dialogStateSource.contains("var backgroundedTransferOperationId by mutableStateOf<Long?>(null)") &&
                listContentSource.contains("val dialogState = rememberPasswordListDialogState()") &&
                listContentSource.contains("val quickStatusBannerEnabled = quickFolderPathBannerEnabledForCurrentFilter") &&
                listContentSource.contains("LaunchedEffect(quickStatusTransferState?.operationId, quickStatusBannerEnabled)") &&
                listContentSource.contains("if (!quickStatusBannerEnabled && state.operationId != dialogState.backgroundedTransferOperationId)") &&
                listContentSource.contains("val hasQuickStatusProgress =") &&
                listContentSource.contains("quickStatusBannerEnabled &&") &&
                listContentSource.contains("quickStatusTransferState = quickStatusTransferState") &&
                listContentSource.contains("onQuickStatusTransferClick = {") &&
                listContentSource.contains("dialogState.backgroundedTransferOperationId = null") &&
                listContentSource.contains("dialogState.backgroundedTransferOperationId = quickStatusTransferState?.operationId") &&
                listContentSource.contains("PasswordListQuickStatusDialogs(") &&
                quickStatusDialogsSource.contains("quickStatusTransferState?.toDialogUiState()?.let") &&
                mainPaneSource.contains("quickStatusTransferState: PasswordBatchTransferGlobalProgressState? = null") &&
                mainPaneSource.contains("onQuickStatusTransferClick: (() -> Unit)? = null") &&
                mainPaneSource.contains("transferState = quickStatusTransferState") &&
                mainPaneSource.contains("onTransferStatusClick = onQuickStatusTransferClick")
        )
        assertTrue(
            "The transfer animation should live in a reusable quick status component, not as password-page-only UI.",
            quickStatusTransferSource.contains("data class QuickStatusTransferState(") &&
                quickStatusTransferSource.contains("enum class QuickStatusTransferPhase") &&
                quickStatusTransferSource.contains("fun QuickStatusTransferBar(") &&
                quickStatusTransferSource.contains("Icons.AutoMirrored.Filled.Send") &&
                quickStatusTransferSource.contains("val sourceWeight") &&
                quickStatusTransferSource.contains("val targetWeight") &&
                quickStatusTransferSource.contains("QuickStatusTransferSuccessStatus") &&
                quickStatusTransferSource.contains("\"移动\"") &&
                quickStatusTransferSource.contains("\"复制\"") &&
                quickFolderSource.contains("QuickStatusTransferBar(") &&
                quickFolderSource.contains("toQuickStatusTransferState(") &&
                quickFolderSource.contains("targetState = statusMode") &&
                quickFolderSource.contains("PasswordQuickStatusMode") &&
                quickFolderSource.contains("Modifier.clickable(enabled = onTransferStatusClick != null)") &&
                !quickFolderSource.contains("private fun PasswordQuickTransferStatusBar(") &&
                !quickFolderSource.contains("private fun PasswordQuickTransferSuccessStatus(") &&
                !quickFolderSource.contains("targetState = transferState")
        )
        assertTrue(
            "Completed password batch moves/copies should publish success to the quick status bar instead of clearing the state immediately, without auto-opening the old blocking progress dialog.",
            moveSupportSource.contains("var completedCleanly = false") &&
                moveSupportSource.contains("mutableStateOf(false)") &&
                moveSupportSource.contains("completedCleanly = true") &&
                moveSupportSource.contains("internal fun PasswordBatchTransferGlobalProgressState.toDialogUiState()") &&
                moveSupportSource.contains("PasswordBatchTransferProgressTracker.complete(") &&
                moveSupportSource.contains("PasswordBatchTransferProgressTracker.clear()") &&
                !moveSupportSource.contains("showProgressDialog = true")
        )
        assertTrue(
            "After the user confirms a move/copy target, multi-select mode should close immediately while the transfer continues in the quick status bar.",
            moveSupportSource.indexOf("onProgressUpdate(if (totalCount > 1) 1 else 0, totalCount)").let { progressIndex ->
                val dismissIndex = moveSupportSource.indexOf("onDismiss()", progressIndex.coerceAtLeast(0))
                val clearIndex = moveSupportSource.indexOf("onSelectionCleared()", dismissIndex.coerceAtLeast(0))
                val launchIndex = moveSupportSource.indexOf("viewModel.viewModelScope.launch {")
                progressIndex >= 0 &&
                    dismissIndex > progressIndex &&
                    clearIndex > dismissIndex &&
                    launchIndex > clearIndex
            }
        )
    }

    @Test
    fun passwordCategoryQuickFilterRowKeepsHorizontalScrollStateOutsideLazyHeader() {
        val quickFolderRowSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordQuickFolderFlow.kt"
        ).readText()
        val scrollableContentSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListScrollableContent.kt"
        ).readText()
        val vaultV2Source = projectFile(
            "app/src/main/java/com/bastion/app/ui/vaultv2/VaultV2Pane.kt"
        ).readText()
        val chipRowBody = quickFolderRowSource.substringAfter("internal fun PasswordQuickFolderChipRow(")
            .substringBefore("private fun PasswordQuickFolderShortcut.resolveLeadingIcon")
        val passwordListBody = scrollableContentSource.substringAfter("fun PasswordListScrollableContent(")
            .substringBefore("if (quickFolderShortcuts.isNotEmpty())")
        val vaultV2ListBody = vaultV2Source.substringAfter("private fun VaultV2List(")
            .substringBefore("if (sections.isEmpty() && showLoadingIndicator)")

        assertTrue(
            "The folder chip row below password quick filters must receive a hoisted ScrollState so returning from detail keeps its horizontal position.",
            quickFolderRowSource.contains("import androidx.compose.foundation.ScrollState") &&
                chipRowBody.contains("scrollState: ScrollState") &&
                chipRowBody.contains(".horizontalScroll(scrollState)") &&
                !chipRowBody.contains("rememberScrollState()") &&
                passwordListBody.contains("val categoryQuickFilterScrollState = rememberScrollState()") &&
                passwordListBody.contains("scrollState = categoryQuickFilterScrollState") &&
                vaultV2ListBody.contains("val categoryQuickFilterScrollState = rememberScrollState()") &&
                vaultV2ListBody.contains("scrollState = categoryQuickFilterScrollState")
        )
    }

    @Test
    fun normalPasswordPageRunsBatchDeleteThroughQuickStatusBar() {
        val deleteTrackerSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordBatchDeleteProgressTracker.kt"
        ).readText()
        val quickDeleteSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/QuickStatusDeleteBar.kt"
        ).readText()
        val quickFolderSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordQuickFolderSections.kt"
        ).readText()
        val listContentSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListContent.kt"
        ).readText()
        val mainPaneSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListMainPane.kt"
        ).readText()
        val quickStatusDialogsSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListQuickStatusDialogs.kt"
        ).readText()
        val dialogsSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListDialogs.kt"
        ).readText()
        val dialogStateSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/password/PasswordListDialogState.kt"
        ).readText()

        assertTrue(
            "Batch delete progress must keep a short success state so the quick status bar can show the completed result before returning to breadcrumbs.",
            deleteTrackerSource.contains("enum class PasswordBatchDeletePhase") &&
                deleteTrackerSource.contains("val operationId: Long") &&
                // 计数器已从 `private var Long` 加固为 `private val AtomicLong`（线程安全）。
                deleteTrackerSource.contains("private val nextOperationId = AtomicLong") &&
                deleteTrackerSource.contains("SUCCESS") &&
                deleteTrackerSource.contains("fun complete(") &&
                deleteTrackerSource.contains("delay(1300)")
        )
        assertTrue(
            "The normal password page must collect delete progress, show it in the quick status bar when enabled, and auto-open details when the path banner is disabled.",
            listContentSource.contains("PasswordBatchDeleteProgressTracker.progress.collectAsState()") &&
                dialogStateSource.contains("var showQuickStatusDeleteDialog by mutableStateOf(false)") &&
                dialogStateSource.contains("var backgroundedDeleteOperationId by mutableStateOf<Long?>(null)") &&
                listContentSource.contains("LaunchedEffect(quickStatusDeleteState?.operationId, quickStatusBannerEnabled)") &&
                listContentSource.contains("if (!quickStatusBannerEnabled && state.operationId != dialogState.backgroundedDeleteOperationId)") &&
                listContentSource.contains("quickStatusDeleteState != null") &&
                listContentSource.contains("quickStatusDeleteState = quickStatusDeleteState") &&
                listContentSource.contains("onQuickStatusDeleteClick = {") &&
                listContentSource.contains("dialogState.backgroundedDeleteOperationId = null") &&
                listContentSource.contains("dialogState.backgroundedDeleteOperationId = quickStatusDeleteState?.operationId") &&
                listContentSource.contains("PasswordListQuickStatusDialogs(") &&
                quickStatusDialogsSource.contains("quickStatusDeleteState?.toDialogUiState()?.let") &&
                mainPaneSource.contains("quickStatusDeleteState: PasswordBatchDeleteGlobalProgressState? = null") &&
                mainPaneSource.contains("onQuickStatusDeleteClick: (() -> Unit)? = null") &&
                mainPaneSource.contains("deleteState = quickStatusDeleteState") &&
                mainPaneSource.contains("onDeleteStatusClick = onQuickStatusDeleteClick")
        )
        assertTrue(
            "Delete status UI should live in the shared quick status area, not in the old blocking progress dialog path.",
            quickDeleteSource.contains("data class QuickStatusDeleteState(") &&
                quickDeleteSource.contains("enum class QuickStatusDeletePhase") &&
                quickDeleteSource.contains("fun QuickStatusDeleteBar(") &&
                quickDeleteSource.contains("QuickStatusDeleteSuccessStatus") &&
                quickDeleteSource.contains("正在删除") &&
                quickDeleteSource.contains("删除成功，已删除") &&
                quickFolderSource.contains("QuickStatusDeleteBar(") &&
                quickFolderSource.contains("toQuickStatusDeleteState(") &&
                quickFolderSource.contains("DELETE_RUNNING") &&
                quickFolderSource.contains("DELETE_SUCCESS")
        )
        assertTrue(
            "After confirming batch delete, the page must snapshot the selection before clearing multi-select so background deletion does not lose selected items.",
            dialogsSource.contains("onBatchDeleteStarted: () -> Unit = {}") &&
                dialogsSource.contains("onShowBatchDeleteDialogChange(false)") &&
                dialogsSource.contains("PasswordBatchDeleteProgressTracker.complete(successCount)") &&
                listContentSource.contains("val selectedPasswordIdsSnapshot = selectedPasswords.toSet()") &&
                listContentSource.contains("val selectedSupplementaryItemsSnapshot = selectedSupplementaryItems.toList()") &&
                listContentSource.contains("val selectedItemKeysSnapshot = selectionState.selectedItemKeys.toList()") &&
                listContentSource.contains("onBatchDeleteStarted = {") &&
                listContentSource.contains("selectionState.selectedItemKeys = emptySet()")
        )
        assertFalse(
            "The old confirmation-owned batch delete progress dialog must not come back; fallback auto-open is driven by the global quick status state.",
            dialogsSource.contains("showBatchDeleteProgressDialog")
        )
    }

    @Test
    fun editingPasswordWithAuthenticatorReusesBoundTotpAndDoesNotClearPasswordWhenDeletingDuplicates() {
        val addPasswordSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TotpViewModel.kt"
        ).readText()
        val saveTotpSection = addPasswordSource
            .substringAfter("// Save TOTP if authenticatorKey is provided")
            .substringBefore("} else if (currentAuthKey.isEmpty()")
        val savePasswordBoundTotpBody = totpViewModelSource
            .substringAfter("fun savePasswordBoundTotp(")
            .substringBefore("/**\r\n     * 根据ID获取TOTP项目")
            .ifBlank {
                totpViewModelSource
                    .substringAfter("fun savePasswordBoundTotp(")
                    .substringBefore("/**\n     * 根据ID获取TOTP项目")
            }
        val deleteTotpBody = totpViewModelSource
            .substringAfter("fun deleteTotpItem(")
            .substringBefore("// Virtual TOTP items are derived from password.authenticatorKey")

        assertTrue(
            "Editing a password with an authenticator must go through the bound-TOTP save path, which searches persisted rows by password id before updating.",
            saveTotpSection.contains("totpViewModel.savePasswordBoundTotps(") &&
                saveTotpSection.contains("passwordIds = savedPasswordIds.ifEmpty { listOf(firstPasswordId) }") &&
                savePasswordBoundTotpBody.contains("repository.getItemsByType(ItemType.TOTP).first()") &&
                savePasswordBoundTotpBody.contains("data.boundPasswordId == passwordId")
        )
        assertFalse(
            "Password editing must not use findTotpBySecret here; that method reads the filtered authenticator UI state and can miss the existing bound item, creating duplicates.",
            saveTotpSection.contains("findTotpBySecret(")
        )
        assertTrue(
            "The password editor must reuse a selected real TOTP first and create the first real bound TOTP when none exists, so the authenticator page can display and edit the saved name/key instead of opening an empty virtual item.",
            savePasswordBoundTotpBody.contains("val activeStoredItems = existingStoredTotps.mapNotNull") &&
                savePasswordBoundTotpBody.contains("val preferredItem = selectedSourceItem") &&
                savePasswordBoundTotpBody.contains("preferredTotpId != null && item.id == preferredTotpId") &&
                savePasswordBoundTotpBody.contains("id = preferredItem?.first?.id") &&
                savePasswordBoundTotpBody.contains("title = metadataSource?.first?.title ?: title") &&
                !savePasswordBoundTotpBody.contains("No persisted bound TOTP for passwordId=")
        )
        assertTrue(
            "The bound-TOTP save path should soft-delete extra persisted bindings for the same password.",
            savePasswordBoundTotpBody.contains("removeOtherBoundTotpsForPassword(") &&
                totpViewModelSource.contains("private suspend fun removeOtherBoundTotpsForPassword(") &&
                totpViewModelSource.contains("Soft-deleting extra bound TOTP")
        )
        assertTrue(
            "The authenticator list should collapse already-existing duplicate bound rows so users do not keep seeing one card per bad edit.",
            // 只匹配函数调用，不写死参数名：参数重命名不应让这条守卫失效
            totpViewModelSource.contains("collapseDuplicateBoundStoredTotps(") &&
                totpViewModelSource.contains("private fun collapseDuplicateBoundStoredTotps(") &&
                totpViewModelSource.contains("val key = \"\$boundPasswordId|")
        )
        assertTrue(
            "Deleting one duplicated bound authenticator must not clear password.authenticatorKey while another equivalent bound item still exists.",
            deleteTotpBody.contains("hasEquivalentBoundItem") &&
                deleteTotpBody.contains("candidate.id == item.id") &&
                deleteTotpBody.contains("candidateData.boundPasswordId == boundId") &&
                deleteTotpBody.contains("&& !hasEquivalentBoundItem")
        )
    }

    @Test
    fun deletingPasswordBoundAuthenticatorWarnsAndStillDeletesPersistedTotp() {
        val totpViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TotpViewModel.kt"
        ).readText()
        val totpListContentSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/totp/TotpListContent.kt"
        ).readText()
        val deleteTotpBody = totpViewModelSource
            .substringAfter("fun deleteTotpItem(")
            .substringBefore("// Virtual TOTP items are derived from password.authenticatorKey")

        assertFalse(
            "Deleting a real bound authenticator must not abort just because the bound password row is missing.",
            deleteTotpBody.contains("passwordRepository.getPasswordEntryById(boundId) ?: return@launch")
        )
        assertTrue(
            "Batch deletion must exclude every item in the same delete request when deciding whether password.authenticatorKey should remain.",
            totpViewModelSource.contains("fun deleteTotpItems(") &&
                totpViewModelSource.contains("deletingItemIds: Set<Long> = emptySet()") &&
                deleteTotpBody.contains("candidate.id in deletingItemIds")
        )
        assertTrue(
            "The authenticator page must warn before deleting password-bound authenticators, including multi-select batches.",
            totpListContentSource.contains("BoundTotpDeleteWarningDialog(") &&
                totpListContentSource.contains("pendingBoundSingleDelete") &&
                totpListContentSource.contains("pendingBoundBatchDelete") &&
                totpListContentSource.contains("viewModel.deleteTotpItems(toDelete)")
        )
    }

    @Test
    fun saveFailuresAreReportedWithNonSecretDiagnostics() {
        val passwordViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TotpViewModel.kt"
        ).readText()
        val savePasswordsAcrossTargetsBody = passwordViewModelSource
            .substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")
        val saveGroupedBody = passwordViewModelSource
            .substringAfter("private suspend fun saveGroupedPasswordsInternal(")
            .substringBefore("fun getCustomFieldsByEntryId(")
        val saveTotpItemBody = totpViewModelSource
            .substringAfter("fun saveTotpItem(")
            .substringBefore("fun saveTotpAcrossTargets(")
        val saveTotpAcrossTargetsBody = totpViewModelSource
            .substringAfter("fun saveTotpAcrossTargets(")
            .substringBefore("private suspend fun saveTotpItemInternal(")

        assertTrue(
            "Password saves must catch storage-layer exceptions, log target/id diagnostics, and still invoke the UI callback with null.",
            savePasswordsAcrossTargetsBody.contains("requestedTargetKeys") &&
                savePasswordsAcrossTargetsBody.contains("catch (e: Exception)") &&
                savePasswordsAcrossTargetsBody.contains("savePasswordsAcrossTargets crashed") &&
                // 首个 id 已收进 PasswordSaveAcrossTargetsResult，回调参数随之变为
                // saveResult.firstPasswordId。守卫只关心"回调仍被调用且带首个 id"。
                savePasswordsAcrossTargetsBody.contains("onComplete(saveResult.firstPasswordId)")
        )
        assertTrue(
            "Grouped password updates must not silently report success when an existing row update is rejected.",
            // 调用点已展开为多行具名参数，断言拆成"接收返回值"+"传入的是 updatedEntry"两段。
            saveGroupedBody.contains("val updated = updatePasswordEntryInternal(") &&
                saveGroupedBody.contains("entry = updatedEntry") &&
                saveGroupedBody.contains("saveGroupedPasswords aborted due to password update failure") &&
                saveGroupedBody.contains("return null")
        )
        assertTrue(
            "Legacy TOTP saves must use Log.e diagnostics instead of printStackTrace so rare user logs identify the failed storage target.",
            saveTotpItemBody.contains("Log.e(") &&
                saveTotpItemBody.contains("saveTotpItem failed id=") &&
                !saveTotpItemBody.contains("printStackTrace()")
        )
        assertTrue(
            "Multi-target TOTP saves must log empty targets, current target failures, and caught exceptions without logging TOTP secrets.",
            saveTotpAcrossTargetsBody.contains("target list is empty") &&
                saveTotpAcrossTargetsBody.contains("failed current target=") &&
                saveTotpAcrossTargetsBody.contains("saveTotpAcrossTargets crashed") &&
                !saveTotpAcrossTargetsBody.contains("secret=")
        )
    }

    @Test
    fun editingPasswordReplicasPreservesExistingTargets() {
        val viewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()
        val pickerSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/components/MultiStorageTargetPickerBottomSheet.kt"
        ).readText()
        val saveAcrossTargetsBody = viewModelSource
            .substringAfter("fun savePasswordsAcrossTargets(")
            .substringBefore("private suspend fun canWriteKeePassTargets")

        assertFalse(
            "Editing a replica group must not delete existing targets just because they are absent from the edited selection; the UI contract says existing targets are preserved.",
            saveAcrossTargetsBody.contains("deletePasswordEntriesBatch(staleReplicas)")
        )
        assertTrue(
            "The storage target picker must treat existing targets as locked while editing, otherwise a missed click can remove a storage replica and look like data loss.",
            pickerSource.contains("val singleModeAllowed = lockedTargetKeys.isEmpty()") &&
                pickerSource.contains("if (!singleModeAllowed) return") &&
                pickerSource.contains("it.stableKey in lockedTargetKeys") &&
                pickerSource.contains("targetLocked = chip.target.stableKey in lockedTargetKeys") &&
                pickerSource.contains("if (targetLocked)")
        )
    }

    @Test
    fun totpQrScanFillsAddScreenAndQuickScanUsesCurrentStorageTarget() {
        val addTotpSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AddEditTotpScreen.kt"
        ).readText()
        val mainActivitySource = projectFile(
            "app/src/main/java/com/bastion/app/MainActivity.kt"
        ).readText()
        val quickScanRoute = mainActivitySource
            .substringAfter("composable(Screen.QuickTotpScan.route)")
            .substringBefore("// 导出数据")

        assertTrue(
            "AddEditTotpScreen must consume raw QR results inside the form so existing rememberSaveable state is updated after returning from scanner.",
            addTotpSource.contains("pendingQrResult: String? = null") &&
                addTotpSource.contains("onConsumePendingQrResult: () -> Unit = {}") &&
                addTotpSource.contains("LaunchedEffect(pendingQrResult)") &&
                addTotpSource.contains("importTotpFromUri(qrValue)") &&
                mainActivitySource.contains("pendingQrResult = qrResult") &&
                mainActivitySource.contains("onConsumePendingQrResult = {")
        )
        assertTrue(
            "Quick TOTP scan must save to the current validator filter target instead of always creating Bastion-local items.",
            quickScanRoute.contains("fun quickScanTargetsForCurrentFilter(): List<StorageTarget>") &&
                quickScanRoute.contains("totpViewModel.saveTotpAcrossTargets(") &&
                quickScanRoute.contains("targets = quickScanTargetsForCurrentFilter()")
        )
        assertFalse(
            "Quick TOTP scan must not call saveTotpItem directly because that bypasses KeePass/Bitwarden targets.",
            quickScanRoute.contains("totpViewModel.saveTotpItem(")
        )
    }

    @Test
    fun webDavBastionConfigBackupIncludesSecurityAutofillAndBlacklistSettings() {
        val webDavSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val settingsSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/SettingsManager.kt"
        ).readText()
        val backupScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/WebDavBackupScreen.kt"
        ).readText()

        assertTrue(
            "Page-adjustment backup must include the user-facing security/autofill switches, otherwise WebDAV config restore silently loses them.",
            settingsSource.contains("val securityAnalysisAutoEnabled: Boolean = false") &&
                settingsSource.contains("val passwordDetailSecurityAnalysisEnabled: Boolean = true") &&
                settingsSource.contains("val autofillAuthRequired: Boolean = true") &&
                settingsSource.contains("securityAnalysisAutoEnabled = settings.securityAnalysisAutoEnabled") &&
                settingsSource.contains("passwordDetailSecurityAnalysisEnabled = settings.passwordDetailSecurityAnalysisEnabled") &&
                settingsSource.contains("autofillAuthRequired = settings.autofillAuthRequired") &&
                settingsSource.contains("preferences[SECURITY_ANALYSIS_AUTO_ENABLED_KEY] = snapshot.securityAnalysisAutoEnabled") &&
                settingsSource.contains("preferences[PASSWORD_DETAIL_SECURITY_ANALYSIS_ENABLED_KEY]") &&
                settingsSource.contains("preferences[AUTOFILL_AUTH_REQUIRED_KEY] = snapshot.autofillAuthRequired")
        )
        assertTrue(
            "WebDAV page-adjustment JSON must pass these switch fields through both export and restore layers.",
            webDavSource.contains("val securityAnalysisAutoEnabled: Boolean = false") &&
                webDavSource.contains("val passwordDetailSecurityAnalysisEnabled: Boolean = true") &&
                webDavSource.contains("val autofillAuthRequired: Boolean = true") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.securityAnalysisAutoEnabled") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.passwordDetailSecurityAnalysisEnabled") &&
                webDavSource.contains("pageAdjustmentSettingsSnapshot.autofillAuthRequired") &&
                webDavSource.contains("pageAdjustmentBackup.securityAnalysisAutoEnabled") &&
                webDavSource.contains("pageAdjustmentBackup.passwordDetailSecurityAnalysisEnabled") &&
                webDavSource.contains("pageAdjustmentBackup.autofillAuthRequired")
        )
        assertTrue(
            "Autofill blacklist is distinct from save-blocked targets and must be backed up as its own Bastion config file.",
            webDavSource.contains("private data class AutofillBlacklistBackupEntry(") &&
                webDavSource.contains("val enabled: Boolean = true") &&
                webDavSource.contains("val packages: List<String> = emptyList()") &&
                webDavSource.contains("val autofillBlacklistEnabled = autofillPreferences.isBlacklistEnabled.first()") &&
                webDavSource.contains("val autofillBlacklistPackages = autofillPreferences.blacklistPackages.first()") &&
                webDavSource.contains("File(bastionConfigDir, \"autofill_blacklist.json\")") &&
                webDavSource.contains("json.encodeToString(") &&
                webDavSource.contains("AutofillBlacklistBackupEntry.serializer()") &&
                webDavSource.contains("normalizedEntryName == \"bastion_config/autofill_blacklist.json\"") &&
                webDavSource.contains("setBlacklistEnabled(autofillBlacklistBackup.enabled)") &&
                webDavSource.contains("setBlacklistPackages(normalizedPackages)") &&
                backupScreenSource.contains("\"autofill_blacklist.json\" -> \"自动填充黑名单\"")
        )
        assertTrue(
            "Legacy aggregate Bastion config restore should understand blacklist fields when older backups carry them there.",
            webDavSource.contains("val autofillBlacklistEnabled: Boolean? = null") &&
                webDavSource.contains("val autofillBlacklistPackages: List<String>? = null") &&
                webDavSource.contains("bastionConfigBackup.autofillBlacklistEnabled != null") &&
                webDavSource.contains("bastionConfigBackup.autofillBlacklistPackages != null") &&
                webDavSource.contains("bastionConfigBackup.autofillBlacklistEnabled?.let") &&
                webDavSource.contains("normalizedPackages?.let")
        )
        assertFalse(
            "Autofill blacklist must not be collapsed into the save-blocked-targets backup; these are different settings in the UI.",
            webDavSource.contains("AutofillSaveBlockedTargetsBackupEntry(\n    val blockedTargets: List<String> = emptyList(),\n    val packages")
        )
    }

    @Test
    fun webDavBackupsUseBastionLocalContentScope() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val webDavScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val autoBackupWorkerSource = projectFile(
            "app/src/main/java/com/bastion/app/workers/AutoBackupWorker.kt"
        ).readText()

        assertTrue(
            "WebDAV helper must forward the requested backup content scope into createBackupZip.",
            webDavHelperSource.contains("contentScope: BackupContentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                webDavHelperSource.contains("contentScope = contentScope")
        )
        assertTrue(
            "Manual WebDAV backup must use the Bastion-local scope so external caches are not exported as Bastion-local entries.",
            webDavScreenSource.contains("import com.bastion.app.utils.BackupContentScope") &&
                webDavScreenSource.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY")
        )
        assertTrue(
            "Automatic WebDAV backup must use the same Bastion-local scope as manual WebDAV backup.",
            autoBackupWorkerSource.contains("import com.bastion.app.utils.BackupContentScope") &&
            autoBackupWorkerSource.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY")
        )
    }

    @Test
    fun localZipExportDoesNotInheritRemoteBackupEncryption() {
        val dataExportSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/DataExportImportViewModel.kt"
        ).readText()
        val webDavHelperSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val exportScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/ExportDataScreen.kt"
        ).readText()
        val prepareZipBackupBody = dataExportSource.substringAfter("suspend fun prepareZipBackup(")
            .substringBefore("suspend fun writePreparedZipBackup(")
        val copyZipBody = dataExportSource.substringAfter("private suspend fun copyZipFileToOutputUri(")
            .substringBefore("private fun zipBackupExportMessage(")
        val exportZipBackupBody = dataExportSource.substringAfter("suspend fun exportZipBackup(")
            .substringBefore("suspend fun importZipBackup(")

        assertTrue(
            "Local export writes a .zip document, so it must force createBackupZip to return a plain ZIP even when remote backup encryption is enabled.",
            prepareZipBackupBody.contains("allowBackupEncryption = false")
        )
        assertTrue(
            "Local export must validate the generated ZIP and the bytes written to the selected document before reporting success.",
            prepareZipBackupBody.contains("validatePlainZipFile(zipFile)") &&
                copyZipBody.contains("validatePlainZipStream") &&
                copyZipBody.contains("openExportOutputStream(outputUri)") &&
                copyZipBody.contains("copiedBytes <= 0L") &&
                copyZipBody.contains("copiedBytes != expectedBytes")
        )
        assertTrue(
            "The export screen should prepare and validate the ZIP before ACTION_CREATE_DOCUMENT so a generation failure does not leave a 0B user-visible file.",
            exportScreenSource.contains("var pendingPreparedZipBackup") &&
                exportScreenSource.contains("onPrepareZip(backupPreferences)") &&
                exportScreenSource.contains("pendingPreparedZipBackup = backup") &&
                exportScreenSource.contains("onWritePreparedZip(safeUri, preparedZipBackup.first, preparedZipBackup.second)") &&
                exportScreenSource.indexOf("onPrepareZip(backupPreferences)") <
                    exportScreenSource.lastIndexOf("launchCreateDocument()")
        )
        assertTrue(
            "The legacy one-step export API should clean up its prepared temp ZIP after copying.",
            exportZipBackupBody.contains("prepareZipBackup(preferences).getOrThrow()") &&
                exportZipBackupBody.contains("writePreparedZipBackup(outputUri, zipFile, message)") &&
                exportZipBackupBody.contains("preparedFile?.delete()")
        )
        assertTrue(
            "Backup ZIP creation should only return an encrypted .enc.zip when the caller allows backup encryption and an encryption password exists.",
            webDavHelperSource.contains("allowBackupEncryption: Boolean = true") &&
                webDavHelperSource.contains("val shouldEncryptBackup = allowBackupEncryption && enableEncryption && encryptionPassword.isNotEmpty()") &&
                webDavHelperSource.contains("val finalFile = if (shouldEncryptBackup)")
        )
        assertFalse(
            "Returning .enc.zip solely because the persisted WebDAV encryption switch is on breaks local .zip export.",
            webDavHelperSource.contains("val finalFile = if (enableEncryption)")
        )
    }

    @Test
    fun trashScopeSelectorUsesUnifiedChipMenuInsteadOfLegacyBottomSheet() {
        val timelineSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/TimelineScreen.kt"
        ).readText()

        assertFalse(
            "Trash/category scope selection should use the compact chip menu like other pages, not the legacy bottom sheet.",
            timelineSource.contains("UnifiedCategoryFilterBottomSheet")
        )
        assertTrue(
            "The history/trash folder buttons should anchor the compact category chip menu next to the top-bar action.",
            timelineSource.contains("UnifiedCategoryFilterChipMenuDropdown") &&
                timelineSource.contains("UnifiedCategoryFilterChipMenu(") &&
                timelineSource.contains("private fun TrashScopeFilterChipMenu(") &&
                timelineSource.contains("scopeMenu: @Composable () -> Unit = {}")
        )
    }

    @Test
    fun webDavBackupWorkerSharesCoordinatorQueue() {
        val autoBackupWorkerSource = projectFile(
            "app/src/main/java/com/bastion/app/workers/AutoBackupWorker.kt"
        ).readText()

        assertTrue(
            "WebDAV manual and scheduled backup workers must share SyncTaskRunner so two WorkManager entries cannot run two real backups at once.",
            autoBackupWorkerSource.contains("SyncTarget.Backup(SyncBackupProvider.WEBDAV)") &&
                autoBackupWorkerSource.contains("SyncTaskRunner.requestAndAwait(request)") &&
                autoBackupWorkerSource.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                autoBackupWorkerSource.contains("SyncTrigger.MANUAL") &&
                autoBackupWorkerSource.contains("SyncTrigger.BACKUP_SCHEDULE") &&
                autoBackupWorkerSource.contains("is SyncTaskAwaitResult.Merged") &&
                autoBackupWorkerSource.contains("merged_with_running_backup")
        )
    }

    @Test
    fun webDavBackupScreenManualCreateSharesCoordinatorQueue() {
        val webDavScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val manualCreateBody = webDavScreenSource
            .substringAfter("val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)")
            .substringBefore("Text(stringResource(R.string.webdav_create_new_backup))")

        assertTrue(
            "WebDAV screen manual create must share the same backup:webdav coordinator queue as AutoBackupWorker.",
            webDavScreenSource.contains("val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)") &&
                manualCreateBody.contains("SyncTaskRunner.requestAndAwait(") &&
                manualCreateBody.contains("trigger = SyncTrigger.MANUAL") &&
                manualCreateBody.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                manualCreateBody.contains("WEBDAV_SCREEN_MANUAL") &&
                manualCreateBody.contains("merged_with_running_backup")
        )
        assertTrue(
            "WebDAV screen manual create should keep existing permanent Bastion-local backup behavior while moving scheduling into the coordinator.",
            manualCreateBody.contains("isPermanent = true") &&
                manualCreateBody.contains("isManualTrigger = true") &&
                manualCreateBody.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                manualCreateBody.contains(".getOrThrow()")
        )
        assertTrue(
            "WebDAV screen manual create must release UI loading state even when coordinator skips, blocks, or fails.",
            manualCreateBody.contains("finally") &&
                manualCreateBody.contains("isLoading = false") &&
                manualCreateBody.contains("isBackupInProgress = false")
        )
    }

    @Test
    fun webDavManualBackupWorkerDoesNotReplaceRunningWorker() {
        val autoBackupManagerSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/AutoBackupManager.kt"
        ).readText()
        val triggerBody = autoBackupManagerSource.substringAfter("fun triggerBackupNow(): Boolean")
            .substringBefore("fun getLastBackupStatus()")

        assertTrue(
            "Manual WebDAV backup must not use REPLACE because it can cancel a running Worker while SyncTaskRunner owns the actual backup.",
            triggerBody.contains("ExistingWorkPolicy.KEEP")
        )
        assertFalse(
            "Do not bring back REPLACE for manual WebDAV backup; duplicate taps should coalesce, not cancel the running backup.",
            triggerBody.contains("ExistingWorkPolicy.REPLACE")
        )
    }

    @Test
    fun oneDriveBackupScreenManualCreateUsesCoordinatorQueue() {
        val oneDriveScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/OneDriveBackupScreen.kt"
        ).readText()
        val manualCreateBody = oneDriveScreenSource
            .substringAfter("val backupTarget = SyncTarget.Backup(SyncBackupProvider.ONEDRIVE)")
            .substringBefore("Text(if (creatingBackup) stringResource(R.string.webdav_backup_in_progress)")

        assertTrue(
            "OneDrive screen manual backup must be represented as backup:onedrive work in SyncTaskRunner.",
            oneDriveScreenSource.contains("val backupTarget = SyncTarget.Backup(SyncBackupProvider.ONEDRIVE)") &&
                manualCreateBody.contains("SyncTaskRunner.requestAndAwait(") &&
                manualCreateBody.contains("trigger = SyncTrigger.MANUAL") &&
                manualCreateBody.contains("networkPolicy = SyncNetworkPolicy.REQUIRED") &&
                manualCreateBody.contains("ONEDRIVE_SCREEN_MANUAL") &&
                manualCreateBody.contains("merged_with_running_backup")
        )
        assertTrue(
            "OneDrive backup must use the same Bastion-local backup scope as WebDAV, so cached external database rows are not included.",
            manualCreateBody.contains("val localPasswords = passwordRepository.getAllLocalPasswordEntries()") &&
                manualCreateBody.contains("val localSecureItems = secureItemRepository.getAllLocalItems()") &&
                manualCreateBody.contains("contentScope = BackupContentScope.MONICA_LOCAL_ONLY") &&
                !manualCreateBody.contains("contentScope = BackupContentScope.ALL_OFFLINE") &&
                manualCreateBody.contains("backupHelper.uploadBackup(file, isPermanent = true).getOrThrow()") &&
                manualCreateBody.contains("file.delete()")
        )
        assertTrue(
            "OneDrive screen manual backup must release its loading state after completed, skipped, blocked, canceled, or failed coordinator outcomes.",
            manualCreateBody.contains("finally") &&
                manualCreateBody.contains("creatingBackup = false")
        )
        assertTrue(
            "OneDrive backup counts must match the Bastion-local backup scope rather than counting external database cache rows.",
            oneDriveScreenSource.contains("passwordCount = passwordRepository.getLocalEntriesCount()") &&
                oneDriveScreenSource.contains("authenticatorCount = secureItemRepository.getLocalItemCountByType") &&
                oneDriveScreenSource.contains("passkeyDao().getLocalPasskeyCount()")
        )
    }

    @Test
    fun webDavBackupContentCountsMatchBastionLocalBackupScope() {
        val webDavScreenSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/WebDavBackupScreen.kt"
        ).readText()
        val launchedEffectBody = webDavScreenSource.substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("Scaffold(")

        assertTrue(
            "WebDAV backup count labels must reflect the same Bastion-local dataset that WebDAV actually backs up.",
            launchedEffectBody.contains("passwordCount = passwordRepository.getLocalEntriesCount()") &&
                launchedEffectBody.contains("authenticatorCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("documentCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("bankCardCount = secureItemRepository.getLocalItemCountByType") &&
                launchedEffectBody.contains("noteCount = secureItemRepository.getLocalItemCountByType") &&
                !launchedEffectBody.contains("passwordRepository.getAllPasswordEntries().first()") &&
                !launchedEffectBody.contains("secureItemRepository.getAllItems().first()")
        )
    }

    @Test
    fun webDavReplaceRestoreClearsLocalDataOnlyAfterBackupIsParsedAndValidated() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val restoreBody = webDavHelperSource.substringAfter("suspend fun restoreFromBackupFile(")
            .substringBefore("/**\n     * 下载并恢复备份")
        val beforeZipScan = restoreBody.substringBefore("ZipInputStream(FileInputStream(zipFile)).use")
        val afterRestoreCounts = restoreBody.substringAfter("val restoredCounts = ItemCounts(")
            .substringBefore("val report = RestoreReport(")

        assertFalse(
            "Replace-local restore must not clear existing local data before the backup zip has been parsed. A corrupt or empty WebDAV backup could otherwise erase user data.",
            beforeZipScan.contains("deleteAllLocalPasswordEntries()") ||
                beforeZipScan.contains("deleteAllLocalItemsByType") ||
                beforeZipScan.contains("deleteAllLocalPasskeys()")
        )
        assertTrue(
            "Replace-local restore must clear local data only after parse succeeds and the backup contains core restorable data.",
            webDavHelperSource.contains("private suspend fun clearLocalDataForOverwriteRestore") &&
                afterRestoreCounts.contains("val hasRestorableCoreData") &&
                afterRestoreCounts.contains("failedItems.isNotEmpty()") &&
                afterRestoreCounts.contains("!hasRestorableCoreData") &&
                afterRestoreCounts.contains("clearLocalDataForOverwriteRestore(backupFile.name)") &&
                afterRestoreCounts.indexOf("failedItems.isNotEmpty()") <
                    afterRestoreCounts.indexOf("clearLocalDataForOverwriteRestore(backupFile.name)") &&
                afterRestoreCounts.indexOf("!hasRestorableCoreData") <
                    afterRestoreCounts.indexOf("clearLocalDataForOverwriteRestore(backupFile.name)")
        )
    }

    @Test
    fun webDavUploadBlocksIncompleteBackupReportsBeforeRemoteOverwrite() {
        val webDavHelperSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val uploadBody = webDavHelperSource.substringAfter("suspend fun createAndUploadBackup(")
            .substringBefore("/**\n     * 导出密码到CSV文件")
        val afterCreateResult = uploadBody.substringAfter("val (backupFile, report) = createResult.getOrThrow()")
        val beforeUpload = afterCreateResult.substringBefore("val uploadResult = uploadBackup(backupFile, isPermanent)")

        assertTrue(
            "WebDAV must never upload an incomplete backup over the remote backup. Failed serialization can otherwise turn a good full backup into a tiny partial one.",
            beforeUpload.contains("!report.success || report.failedItems.isNotEmpty()") &&
                beforeUpload.contains("Backup upload blocked because generated backup is incomplete") &&
                beforeUpload.contains("备份文件不完整，已阻止上传覆盖远端备份")
        )
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, startIndex = index + needle.length)
        }
        return count
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
