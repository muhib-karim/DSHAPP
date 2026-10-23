package com.dsh.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

@Suppress("UNCHECKED_CAST")
class ProfilePatchTest {
    private fun entries(doc: String): List<Map<String, Any?>> = (Yaml().load<Any?>(doc) as List<Map<String, Any?>>?) ?: emptyList()
    private fun entry(doc: String, id: String) = entries(doc).single { it["id"] == id }
    private fun config(doc: String, id: String) = entry(doc, id)["config"] as Map<String, Any?>

    private val engineTemplate = """
        # Your patch layer for this dsh profile, applied after every bundle layer:
        # a top-level YAML array of loader patch entries.
        []
    """.trimIndent() + "\n"

    private val appSeeded = """
        # seeded by the app
        - id: ui-theme
          name: "@deepseek-ai/dsh-client-ui-theme"
          config:
            fontSize: 15
        - id: agent-default-model
          name: "@deepseek-ai/dsh-agent-default-model"
          config:
            provider: agnes
            model: agnes-3.0-flash
    """.trimIndent() + "\n"

    @Test fun defaultModelOnTheEngineTemplate() {
        val out = ProfilePatch.setDefaultModel(engineTemplate, "deepseek-official", "deepseek-flash", null)
        assertEquals(mapOf("provider" to "deepseek-official", "model" to "deepseek-flash"), config(out, "agent-default-model"))
        assertTrue(out.startsWith("# Your patch layer"))
    }

    @Test fun defaultModelOnAnEmptyFile() {
        val out = ProfilePatch.setDefaultModel("", "deepseek-official", "deepseek-flash", null)
        assertEquals(1, entries(out).size)
    }

    @Test fun replacesTheAppSeededAgnesDefault() {
        val out = ProfilePatch.setDefaultModel(appSeeded, "deepseek-official", "deepseek-flash", setOf("agnes"))
        assertEquals(1, entries(out).count { it["id"] == "agent-default-model" })
        assertEquals("deepseek-flash", config(out, "agent-default-model")["model"])
        assertEquals(15, config(out, "ui-theme")["fontSize"])
    }

    @Test fun keepsUserChosenDefaultModel() {
        val user = appSeeded.replace("provider: agnes", "provider: my-gw").replace("agnes-3.0-flash", "gpt-x")
        assertEquals(user, ProfilePatch.setDefaultModel(user, "deepseek-official", "deepseek-flash", setOf("agnes")))
    }

    @Test fun readsTheDefaultProvider() {
        assertEquals("agnes", ProfilePatch.defaultModelProvider(appSeeded))
        assertNull(ProfilePatch.defaultModelProvider(engineTemplate))
    }

    private val route = """
        displayName: DeepSeek (web account)
        api: openai-completions
        baseURL: http://127.0.0.1:41234/v1
        apiKeyEnv: DEEPSEEK_WEB_BRIDGE_KEY
        models:
          - id: deepseek-web-chat
            name: DeepSeek chat (web)
            contextWindow: 128000
            maxTokens: 8192
            input: [text]
    """.trimIndent()

    @Test fun routeIntoAFileWithoutThePiAiEntry() {
        val out = ProfilePatch.upsertPiAiRoute(appSeeded, "deepseek-web", route)
        val providers = config(out, "llm-pi-ai")["providers"] as Map<String, Any?>
        val web = providers["deepseek-web"] as Map<String, Any?>
        assertEquals("http://127.0.0.1:41234/v1", web["baseURL"])
        assertEquals("deepseek-web-chat", ((web["models"] as List<Map<String, Any?>>)[0])["id"])
    }

    @Test fun routeUpsertIsIdempotentAndKeepsOtherProviders() {
        val withGw = """
            - id: llm-pi-ai
              config:
                providers:
                  my-gw:
                    api: openai-completions
                    baseURL: https://gw.example/v1
                    models:
                      - id: m
        """.trimIndent() + "\n"
        val once = ProfilePatch.upsertPiAiRoute(withGw, "deepseek-web", route)
        val twice = ProfilePatch.upsertPiAiRoute(once, "deepseek-web", route.replace("41234", "50000"))
        val providers = config(twice, "llm-pi-ai")["providers"] as Map<String, Any?>
        assertEquals(setOf("my-gw", "deepseek-web"), providers.keys)
        assertEquals("http://127.0.0.1:50000/v1", (providers["deepseek-web"] as Map<String, Any?>)["baseURL"])
        assertEquals(once, ProfilePatch.upsertPiAiRoute(once, "deepseek-web", route))
    }

    @Test fun routeIntoInlineEmptyProviders() {
        val inline = "- id: llm-pi-ai\n  config:\n    providers: {}\n"
        val out = ProfilePatch.upsertPiAiRoute(inline, "deepseek-web", route)
        assertEquals(setOf("deepseek-web"), (config(out, "llm-pi-ai")["providers"] as Map<String, Any?>).keys)
    }

    @Test fun removeRoute() {
        val out = ProfilePatch.upsertPiAiRoute(appSeeded, "deepseek-web", route)
        val removed = ProfilePatch.removePiAiRoute(out, "deepseek-web")
        val providers = config(removed, "llm-pi-ai")["providers"] as Map<String, Any?>?
        assertTrue(providers == null || providers.isEmpty())
        assertEquals(appSeeded.trimEnd(), ProfilePatch.removePiAiRoute(appSeeded, "deepseek-web").trimEnd())
    }
}
