package com.tribalflix.app.api

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the visitor JWT across app launches, mirroring `client.py`'s
 * `~/.moviebox_session.json` cache. Without it every launch performs a fresh
 * visitor-login, which is the single call the gateway is most protective of.
 */
object SessionCache {

    private const val PREFS = "moviebox_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EXPIRES_AT = "expires_at"

    private var prefs: SharedPreferences? = null

    var token: String? = null
        private set

    var userId: String? = null
        private set

    /** Token expiry in epoch seconds, 0 when unknown. */
    var expiresAt: Long = 0
        private set

    /** Called once from [com.tribalflix.app.MovieBoxApplication]. */
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store

        val cachedToken = store.getString(KEY_TOKEN, null)
        val cachedExp = store.getLong(KEY_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis() / 1000
        val stillValid = !cachedToken.isNullOrBlank() &&
            (cachedExp == 0L || now + 60 < cachedExp)
        if (stillValid) {
            token = cachedToken
            userId = store.getString(KEY_USER_ID, null)
            expiresAt = cachedExp
        }
    }

    fun save(token: String?, userId: String?, expiresAt: Long) {
        this.token = token
        this.userId = userId
        this.expiresAt = expiresAt

        val editor = prefs?.edit() ?: return
        if (token.isNullOrBlank()) {
            editor.remove(KEY_TOKEN)
            editor.remove(KEY_USER_ID)
            editor.remove(KEY_EXPIRES_AT)
        } else {
            editor.putString(KEY_TOKEN, token)
            editor.putString(KEY_USER_ID, userId)
            editor.putLong(KEY_EXPIRES_AT, expiresAt)
        }
        editor.apply()
    }
}