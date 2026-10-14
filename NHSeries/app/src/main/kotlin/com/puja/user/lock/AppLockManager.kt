package com.puja.user.lock

/**
 * Tracks whether the app has been unlocked in the current session.
 * After LOCK_TIMEOUT_MS in background, requires re-authentication on next foreground.
 */
object AppLockManager {

    private var unlockedAt: Long = 0L
    private var backgroundTime: Long = 0L

    /** How long in background before requiring re-authentication (30 seconds) */
    private const val LOCK_TIMEOUT_MS = 30_000L

    /** Call after successful authentication */
    fun unlock() {
        unlockedAt = System.currentTimeMillis()
        backgroundTime = 0L
    }

    /** Call to force a re-lock (e.g. on lock disable) */
    fun lock() {
        unlockedAt = 0L
        backgroundTime = 0L
    }

    /** Returns true if the session is currently authenticated */
    fun isUnlocked(): Boolean = unlockedAt > 0L

    /**
     * Call in Activity.onPause() — records when the app went to background.
     */
    fun onBackground() {
        backgroundTime = System.currentTimeMillis()
    }

    /**
     * Call in Activity.onResume() — returns true if authentication is required.
     * Returns true if:
     *   - Never been unlocked (first open), OR
     *   - App was in background longer than LOCK_TIMEOUT_MS
     */
    fun needsRelock(): Boolean {
        if (!isUnlocked()) return true
        if (backgroundTime == 0L) return false
        val elapsed = System.currentTimeMillis() - backgroundTime
        backgroundTime = 0L
        return elapsed >= LOCK_TIMEOUT_MS
    }
}
