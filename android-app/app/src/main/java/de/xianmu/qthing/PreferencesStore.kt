package de.xianmu.qthing

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "qthing_settings")

class PreferencesStore(
    private val appContext: Context,
) {
    private val notesTreeUriKey: Preferences.Key<String> = stringPreferencesKey("notes_tree_uri")
    private val lastOpenedFileUriKey: Preferences.Key<String> = stringPreferencesKey("last_opened_file_uri")

    val notesTreeUriFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[notesTreeUriKey] }
    val lastOpenedFileUriFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[lastOpenedFileUriKey] }

    suspend fun setNotesTreeUri(uri: String?) {
        appContext.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(notesTreeUriKey)
            } else {
                prefs[notesTreeUriKey] = uri
            }
        }
    }

    suspend fun setLastOpenedFileUri(uri: String?) {
        appContext.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(lastOpenedFileUriKey)
            } else {
                prefs[lastOpenedFileUriKey] = uri
            }
        }
    }
}
