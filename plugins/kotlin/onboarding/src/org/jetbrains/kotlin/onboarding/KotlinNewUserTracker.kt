// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.onboarding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.stats.completion.tracker.InstallationIdProvider
import org.jetbrains.kotlin.idea.KotlinFileType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class KotlinNewUserTrackerState : BaseState() {
    // Unix time seconds
    var firstKtFileOpened by property(0L)
    var lastKtFileOpened by property(0L)
    var newKtUserSince by property(0L)
    // When the user either dismissed the dialog or chose to fill out the survey
    var newKtUserDialogProcessed by property(false)
}

@State(name = "KotlinNewUserTracker", storages = [Storage("kotlin-onboarding.xml")])
class KotlinNewUserTracker : PersistentStateComponent<KotlinNewUserTrackerState> {
    companion object {
        // Offer survey after one week of using Kotlin
        private val NEW_USER_SURVEY_DELAY = Duration.ofDays(7)
        private val NEW_IDEA_USER_DATE = LocalDate.of(2023, 10, 19)
        // How long we will classify a user as new
        private val NEW_USER_DURATION = Duration.ofDays(30)
        // After how long of a period of not using Kotlin at all we consider the user a new user again
        private val NEW_USER_RESET = Duration.ofDays(90)

        private val LOG = Logger.getInstance(KotlinNewUserTracker::class.java)

        fun getInstance(): KotlinNewUserTracker {
            return service()
        }
    }

    private var currentState: KotlinNewUserTrackerState = KotlinNewUserTrackerState()

    private fun getInstallationDate(): LocalDate? {
        val installationId = serviceOrNull<InstallationIdProvider>()?.installationId() ?: return null
        val dateSubstring = installationId.take(6).takeIf { it.length == 6 } ?: return null
        val day = dateSubstring.substring(0..1).toIntOrNull() ?: return null
        val month = dateSubstring.substring(2..3).toIntOrNull() ?: return null
        val year = dateSubstring.substring(4..5).toIntOrNull() ?: return null

        // installationId is 000000 in case of error (i.e. year 2000). In that case, return null.
        return LocalDate.of(year + 2000, month, day).takeIf { it.year > 2000 }
    }

    /**
     * This is needed temporarily so that the survey is only shown to users who are entirely new to IDEA.
     * We will change it to also show it to old users who are new to Kotlin with an upcoming release.
     */
    private fun isNewIdeaUser(): Boolean {
        val installationDate = getInstallationDate()
        if (installationDate == null) {
            LOG.debug("Could not get InstallationId for IDEA installation")
            return false
        }
        LOG.debug("Got user installation date: $installationDate")
        return installationDate > NEW_IDEA_USER_DATE
    }

    override fun getState(): KotlinNewUserTrackerState = currentState

    override fun loadState(state: KotlinNewUserTrackerState) {
        currentState = state
    }

    private var newUserDialogShownThisSession = false
    internal fun onNewUserDialogShown() {
        LOG.debug("New user dialog was shown, disabling showing it again for this session")
        newUserDialogShownThisSession = true
    }

    private fun isNewKtUser(): Boolean {
        if (state.newKtUserSince == 0L) return false
        val newUserStart = Instant.ofEpochSecond(state.newKtUserSince)
        return Duration.between(newUserStart, Instant.now()) <= NEW_USER_DURATION
    }

    internal fun shouldShowNewUserDialog(): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return false

        if (currentState.firstKtFileOpened == 0L) return false
        if (currentState.newKtUserDialogProcessed) {
            LOG.debug("Not showing new user dialog because it has already been processed")
            return false
        }
        if (newUserDialogShownThisSession) {
            LOG.debug("Not showing new user dialog because it has already been shown this session")
            return false
        }
        if (!isNewKtUser()) {
            LOG.debug("Not showing new user dialog because the user is not a new Kotlin user")
            return false
        }

        val newKtUserInstant = Instant.ofEpochSecond(currentState.newKtUserSince)
        val durationSinceNewKtUser = Duration.between(newKtUserInstant, Instant.now())

        LOG.debug("Duration since user became a new Kotlin user: ${durationSinceNewKtUser.toDays()} day(s)")
        return durationSinceNewKtUser > NEW_USER_SURVEY_DELAY
    }

    internal fun markNewKtUserDialogProcessed() {
        LOG.debug("Marked new user dialog as processed")
        currentState.newKtUserDialogProcessed = true
    }

    private fun checkForNewKtUser() {
        if (isNewKtUser()) {
            // No need to check if the user is already new
            return
        }
        val currentEpoch = Instant.now()
        // This part marks users who open a Kotlin file for the first time as new users
        // TODO: This condition should remove the isNewIdeaUser() part in a future release
        if (currentState.newKtUserSince == 0L && currentState.firstKtFileOpened == 0L && isNewIdeaUser()) {
            currentState.newKtUserSince = currentEpoch.epochSecond
            LOG.debug("Marking user as new Kotlin user because they are new to IDEA")
            return
        }
        // This part marks users as new Kotlin users, if they have not edited a Kotlin file in the past few months
        if (currentState.lastKtFileOpened == 0L) return
        val lastKtFileOpenedInstant = Instant.ofEpochSecond(currentState.lastKtFileOpened)
        val durationSinceLastKtFileOpened = Duration.between(lastKtFileOpenedInstant, currentEpoch)
        if (durationSinceLastKtFileOpened > NEW_USER_RESET) {
            LOG.debug("Marking user as new Kotlin user because they have not edited a Kotlin file in the past 3 months")
            currentState.newKtUserSince = currentEpoch.epochSecond
        }
    }

    internal fun onKtFileOpened() {
        checkForNewKtUser()

        val currentEpoch =  Instant.now()
        currentState.lastKtFileOpened = currentEpoch.epochSecond
        if (currentState.firstKtFileOpened == 0L) {
            currentState.firstKtFileOpened = currentEpoch.epochSecond
            LOG.debug("Kotlin file opened by user for the first time")
        }
    }
}

class KotlinNewUserTrackerFileListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return

        if (file.nameSequence.endsWith(KotlinFileType.DOT_DEFAULT_EXTENSION)) {
            val newUserTracker = KotlinNewUserTracker.getInstance()
            newUserTracker.onKtFileOpened()
        }
    }
}