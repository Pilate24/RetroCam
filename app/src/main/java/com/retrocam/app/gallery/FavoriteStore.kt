package com.retrocam.app.gallery

import android.content.Context
import android.net.Uri

/**
 * Manages photo and video favorites using shared preferences.
 */
object FavoriteStore {

    private const val PREFS_NAME = "retrocam_gallery_prefs"
    private const val KEY_FAVORITES = "favorite_uris"

    fun isFavorite(context: Context, uri: Uri): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        return favorites.contains(uri.toString())
    }

    fun setFavorite(context: Context, uri: Uri, isFav: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isFav) {
            favorites.add(uri.toString())
        } else {
            favorites.remove(uri.toString())
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun toggleFavorite(context: Context, uri: Uri): Boolean {
        val current = isFavorite(context, uri)
        val next = !current
        setFavorite(context, uri, next)
        return next
    }

    fun getFavorites(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun remove(context: Context, uri: Uri) {
        setFavorite(context, uri, false)
    }

    fun removeMultiple(context: Context, uris: Collection<Uri>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: return
        var changed = false
        for (uri in uris) {
            if (favorites.remove(uri.toString())) {
                changed = true
            }
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
        }
    }
}
