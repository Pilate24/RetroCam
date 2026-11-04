package com.retrocam.app.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persisting and loading the user's active Retro Camera Body Theme.
 */
object CameraSkinManager {
    private const val PREFS_NAME = "retrocam_camera_skins"
    private const val KEY_ACTIVE_SKIN = "active_camera_skin_id"

    @Volatile
    private var cachedSkin: CameraSkin? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the currently saved [CameraSkin], or [CameraSkin.DEFAULT] if unset.
     */
    fun getSavedSkin(context: Context): CameraSkin {
        cachedSkin?.let { return it }
        val id = getPrefs(context).getString(KEY_ACTIVE_SKIN, CameraSkin.DEFAULT.id)
        val skin = CameraSkin.fromId(id)
        cachedSkin = skin
        return skin
    }

    /**
     * Saves the selected [CameraSkin] to SharedPreferences.
     */
    fun saveSkin(context: Context, skin: CameraSkin) {
        cachedSkin = skin
        getPrefs(context).edit().putString(KEY_ACTIVE_SKIN, skin.id).apply()
    }

    /**
     * Resets any in-memory cache (primarily for unit tests).
     */
    fun clearCache() {
        cachedSkin = null
    }
}
