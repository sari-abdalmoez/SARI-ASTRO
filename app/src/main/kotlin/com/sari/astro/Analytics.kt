package com.sari.astro

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Thin wrapper around Google Analytics for Firebase.
 * Every call is non-fatal: if google-services.json is missing or Firebase cannot start,
 * tracking is silently skipped and the app keeps working.
 */
object Analytics {
    @Volatile private var instance: FirebaseAnalytics? = null

    private fun get(ctx: Context): FirebaseAnalytics? {
        instance?.let { return it }
        return runCatching { FirebaseAnalytics.getInstance(ctx.applicationContext) }
            .getOrNull()?.also { instance = it }
    }

    /** Logs a screen view. Event names must be lowercase snake_case, up to 40 characters. */
    fun screen(ctx: Context, screenName: String) {
        runCatching {
            get(ctx)?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName)
            })
        }
    }

    /** Logs a custom event with optional string parameters. */
    fun event(ctx: Context, name: String, params: Map<String, String> = emptyMap()) {
        runCatching {
            get(ctx)?.logEvent(name, Bundle().apply { params.forEach { (k, v) -> putString(k, v) } })
        }
    }
}
