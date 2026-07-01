package com.example.minicpm_v_demo

import android.content.Context

object GenerationSettingsStore {
    data class Settings(
        val temperature: Float = DEFAULT_TEMPERATURE,
        val topP: Float = DEFAULT_TOP_P,
        val topK: Int = DEFAULT_TOP_K,
        val predictLength: Int = DEFAULT_PREDICT_LENGTH
    )

    private const val PREFS_NAME = "generation_settings"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_TOP_P = "top_p"
    private const val KEY_TOP_K = "top_k"
    private const val KEY_PREDICT_LENGTH = "predict_length"

    const val DEFAULT_TEMPERATURE = 0.7f
    const val DEFAULT_TOP_P = 1.0f
    const val DEFAULT_TOP_K = 0
    const val DEFAULT_PREDICT_LENGTH = 1024
    const val MAX_PREDICT_LENGTH = 4090

    fun get(context: Context): Settings {
        val prefs = prefs(context)
        return Settings(
            temperature = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
                .coerceIn(0.0f, 1.5f),
            topP = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P)
                .coerceIn(0.1f, 1.0f),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K)
                .coerceIn(0, 100),
            predictLength = prefs.getInt(KEY_PREDICT_LENGTH, DEFAULT_PREDICT_LENGTH)
                .coerceIn(32, MAX_PREDICT_LENGTH)
        )
    }

    fun save(context: Context, settings: Settings) {
        prefs(context).edit()
            .putFloat(KEY_TEMPERATURE, settings.temperature.coerceIn(0.0f, 1.5f))
            .putFloat(KEY_TOP_P, settings.topP.coerceIn(0.1f, 1.0f))
            .putInt(KEY_TOP_K, settings.topK.coerceIn(0, 100))
            .putInt(KEY_PREDICT_LENGTH, settings.predictLength.coerceIn(32, MAX_PREDICT_LENGTH))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
