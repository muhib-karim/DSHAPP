package com.dsh.android.account

/**
 * What each sign-in mode means in dsh's profile patch (pure, see [render]).
 *
 * - API key: the official provider `deepseek-official` reads ref DEEPSEEK_API_KEY; default model `deepseek-flash`.
 * - Web account (Continue with Google): an `llm-pi-ai` route `deepseek-web` pointing at the app's local
 *   OpenAI-compatible bridge on 127.0.0.1 (random port per app process, so it is rewritten before every launch).
 * - None / later: no bridge route. Without upstream's key pool the app-seeded `agnes` default has no key, so it is
 *   replaced by `deepseek-official` and dsh asks for a key in Settings > Models instead of failing every turn.
 *
 * The default model's provider is switched only while it names a provider the app set ([APP_PROVIDERS]) other than
 * the mode's own; a model the user picked (another provider, or another model of the same provider) is never overwritten.
 */
object AccountConfig {
    enum class Mode { NONE, API_KEY, WEB }

    const val OFFICIAL_PROVIDER = "deepseek-official"
    const val OFFICIAL_MODEL = "deepseek-flash"
    const val WEB_ROUTE = "deepseek-web"
    const val WEB_MODEL_CHAT = "deepseek-web-chat"
    const val WEB_MODEL_REASONER = "deepseek-web-reasoner"
    private const val KEY_POOL_PROVIDER = "agnes"
    private val APP_PROVIDERS = setOf(KEY_POOL_PROVIDER, OFFICIAL_PROVIDER, WEB_ROUTE)

    fun render(body: String, mode: Mode, bridgePort: Int?, hasKeyPool: Boolean): String {
        val effective = if (mode == Mode.WEB && bridgePort == null) Mode.NONE else mode
        var out = body
        if (effective == Mode.WEB) {
            out = ProfilePatch.upsertPiAiRoute(out, WEB_ROUTE, webRouteYaml(bridgePort!!))
            out = ProfilePatch.setDefaultModel(out, WEB_ROUTE, WEB_MODEL_CHAT, APP_PROVIDERS)
        } else {
            out = ProfilePatch.removePiAiRoute(out, WEB_ROUTE)
            val current = ProfilePatch.defaultModelProvider(out)
            val keepKeyPool = effective == Mode.NONE && hasKeyPool && current == KEY_POOL_PROVIDER
            if (!keepKeyPool) out = ProfilePatch.setDefaultModel(out, OFFICIAL_PROVIDER, OFFICIAL_MODEL, APP_PROVIDERS)
        }
        return out
    }

    fun webRouteYaml(port: Int) = """
        displayName: DeepSeek (web account)
        api: openai-completions
        baseURL: http://127.0.0.1:$port/v1
        apiKeyEnv: ${DshCredentials.WEB_BRIDGE_KEY}
        models:
          - id: $WEB_MODEL_CHAT
            name: DeepSeek (web)
            contextWindow: 128000
            maxTokens: 8192
            input: [text]
          - id: $WEB_MODEL_REASONER
            name: DeepSeek DeepThink (web)
            contextWindow: 128000
            maxTokens: 8192
            input: [text]
    """.trimIndent()
}
