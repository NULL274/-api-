package com.localmodel.gateway

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

object Prefs {
    private const val NAME = "localmodel_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean("api_key_initialized", false)) {
            sp.edit()
                .putString("api_key", randomApiKey())
                .putBoolean("api_key_initialized", true)
                .apply()
        }
    }

    fun randomApiKey(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return "sk-" + (1..24).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    var modelPath: String?
        get() = sp.getString("model_path", null)
        set(value) = sp.edit().putString("model_path", value).apply()

    var modelName: String
        get() = sp.getString("model_name", "") ?: ""
        set(value) = sp.edit().putString("model_name", value).apply()

    var accelerator: String
        get() = sp.getString("accelerator", "GPU") ?: "GPU"
        set(value) = sp.edit().putString("accelerator", value).apply()

    var maxTokens: Int
        get() = sp.getInt("max_tokens", 2048)
        set(value) = sp.edit().putInt("max_tokens", value).apply()

    var topK: Int
        get() = sp.getInt("top_k", 64)
        set(value) = sp.edit().putInt("top_k", value).apply()

    var topP: Float
        get() = sp.getFloat("top_p", 0.95f)
        set(value) = sp.edit().putFloat("top_p", value).apply()

    var temperature: Float
        get() = sp.getFloat("temperature", 1.0f)
        set(value) = sp.edit().putFloat("temperature", value).apply()

    var systemPrompt: String
        get() = sp.getString("system_prompt", "") ?: ""
        set(value) = sp.edit().putString("system_prompt", value).apply()

    var enableThinking: Boolean
        get() = sp.getBoolean("enable_thinking", false)
        set(value) = sp.edit().putBoolean("enable_thinking", value).apply()

    var enableVision: Boolean
        get() = sp.getBoolean("enable_vision", false)
        set(value) = sp.edit().putBoolean("enable_vision", value).apply()

    var serverEnabled: Boolean
        get() = sp.getBoolean("server_enabled", false)
        set(value) = sp.edit().putBoolean("server_enabled", value).apply()

    var serverPort: Int
        get() = sp.getInt("server_port", 8080)
        set(value) = sp.edit().putInt("server_port", value).apply()

    var serverLan: Boolean
        get() = sp.getBoolean("server_lan", true)
        set(value) = sp.edit().putBoolean("server_lan", value).apply()

    fun engineParams(): LlmEngine.Params = LlmEngine.Params(
        accelerator = runCatching { LlmEngine.Accelerator.valueOf(accelerator) }
            .getOrDefault(LlmEngine.Accelerator.GPU),
        maxTokens = maxTokens,
        topK = topK,
        topP = topP.toDouble(),
        temperature = temperature.toDouble(),
        systemPrompt = systemPrompt,
        enableThinking = enableThinking,
        enableVision = enableVision,
    )

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value).apply()
}
