package com.procutstudio.editor.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.FrameRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val defaultResolution: ExportResolution = ExportResolution.FHD_1080,
    val defaultFrameRate: FrameRate = FrameRate.FPS_30,
    val keepScreenOnWhileEditing: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val showSafeArea: Boolean = false,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "procut_settings")

/** User preferences backed by DataStore. Failures degrade to defaults instead of crashing. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val RESOLUTION = stringPreferencesKey("default_resolution")
        val FRAME_RATE = stringPreferencesKey("default_frame_rate")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SAFE_AREA = booleanPreferencesKey("safe_area")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                defaultResolution = prefs[Keys.RESOLUTION]
                    ?.let { runCatching { ExportResolution.valueOf(it) }.getOrNull() }
                    ?: ExportResolution.FHD_1080,
                defaultFrameRate = prefs[Keys.FRAME_RATE]
                    ?.let { runCatching { FrameRate.valueOf(it) }.getOrNull() }
                    ?: FrameRate.FPS_30,
                keepScreenOnWhileEditing = prefs[Keys.KEEP_SCREEN_ON] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
                showSafeArea = prefs[Keys.SAFE_AREA] ?: false,
            )
        }

    suspend fun setDefaultResolution(resolution: ExportResolution) {
        context.settingsDataStore.edit { it[Keys.RESOLUTION] = resolution.name }
    }

    suspend fun setDefaultFrameRate(frameRate: FrameRate) {
        context.settingsDataStore.edit { it[Keys.FRAME_RATE] = frameRate.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    suspend fun setShowSafeArea(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SAFE_AREA] = enabled }
    }
}
