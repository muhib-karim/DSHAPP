package com.dsh.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

@Suppress("UNCHECKED_CAST")
class AccountConfigTest {
    private val seeded = """
        - id: ui-theme
          config:
            fontSize: 15
        - id: agent-default-model
          name: "@deepseek-ai/dsh-agent-default-model"
          config:
            provider: agnes
            model: agnes-3.0-flash
    """.trimIndent() + "\n"

    private fun entries(doc: String) = Yaml().load<List<Map<String, Any?>>>(doc)
    private fun defaultModel(doc: String) =
        entries(doc).single { it["id"] == "agent-default-model" }["config"] as Map<String, Any?>
    private fun providers(doc: String) =
        (entries(doc).singleOrNull { it["id"] == "llm-pi-ai" }?.get("config") as Map<String, Any?>?)?.get("providers") as Map<String, Any?>?

    @Test fun apiKeyModeUsesTheOfficialProvider() {
        val out = AccountConfig.render(seeded, AccountConfig.Mode.API_KEY, bridgePort = null, hasKeyPool = false)
        assertEquals(mapOf("provider" to "deepseek-official", "model" to "deepseek-flash"), defaultModel(out))
        assertTrue(providers(out).isNullOrEmpty())
    }

    @Test fun webModeAddsTheBridgeRouteAndSelectsIt() {
        val out = AccountConfig.render(seeded, AccountConfig.Mode.WEB, bridgePort = 41234, hasKeyPool = false)
        assertEquals(mapOf("provider" to "deepseek-web", "model" to "deepseek-web-chat"), defaultModel(out))
        val route = providers(out)!!["deepseek-web"] as Map<String, Any?>
        assertEquals("http://127.0.0.1:41234/v1", route["baseURL"])
        assertEquals("openai-completions", route["api"])
        assertEquals("DEEPSEEK_WEB_BRIDGE_KEY", route["apiKeyEnv"])
        assertEquals(listOf("deepseek-web-chat", "deepseek-web-reasoner"), (route["models"] as List<Map<String, Any?>>).map { it["id"] })
    }

    @Test fun webModeWithoutARunningBridgeFallsBackToNone() {
        val out = AccountConfig.render(seeded, AccountConfig.Mode.WEB, bridgePort = null, hasKeyPool = false)
        assertEquals("deepseek-official", defaultModel(out)["provider"])
        assertTrue(providers(out).isNullOrEmpty())
    }

    @Test fun switchingFromWebToApiKeyRemovesTheRoute() {
        val web = AccountConfig.render(seeded, AccountConfig.Mode.WEB, 41234, false)
        val api = AccountConfig.render(web, AccountConfig.Mode.API_KEY, null, false)
        assertEquals("deepseek-official", defaultModel(api)["provider"])
        assertFalse(api.contains("deepseek-web:"))
    }

    @Test fun signedOutWithoutKeyPoolLeavesNoAgnesDefault() {
        val out = AccountConfig.render(seeded, AccountConfig.Mode.NONE, null, hasKeyPool = false)
        assertEquals("deepseek-official", defaultModel(out)["provider"])
    }

    @Test fun keyPoolBuildsKeepAgnesWhenSignedOut() {
        assertEquals(seeded, AccountConfig.render(seeded, AccountConfig.Mode.NONE, null, hasKeyPool = true))
    }

    @Test fun aUserPickedModelSurvivesEveryMode() {
        val user = seeded.replace("provider: agnes", "provider: my-gw")
        for (mode in AccountConfig.Mode.values()) {
            assertEquals(mode.name, "my-gw", defaultModel(AccountConfig.render(user, mode, 41234, false))["provider"])
        }
    }

    @Test fun anotherModelOfTheSameProviderIsKept() {
        val api = AccountConfig.render(seeded, AccountConfig.Mode.API_KEY, null, false).replace("model: deepseek-flash", "model: deepseek-v4-pro")
        assertEquals("deepseek-v4-pro", defaultModel(AccountConfig.render(api, AccountConfig.Mode.API_KEY, null, false))["model"])
        assertEquals("deepseek-v4-pro", defaultModel(AccountConfig.render(api, AccountConfig.Mode.NONE, null, false))["model"])
        val web = AccountConfig.render(seeded, AccountConfig.Mode.WEB, 41234, false).replace("model: deepseek-web-chat", "model: deepseek-web-reasoner")
        assertEquals("deepseek-web-reasoner", defaultModel(AccountConfig.render(web, AccountConfig.Mode.WEB, 50000, false))["model"])
    }

    @Test fun switchingProviderKeepsTheRowsOtherKeys() {
        val withExtra = seeded.replace("    model: agnes-3.0-flash\n", "    model: agnes-3.0-flash\n    reasoningEffort: high\n")
        val out = AccountConfig.render(withExtra, AccountConfig.Mode.API_KEY, null, false)
        assertEquals(mapOf("provider" to "deepseek-official", "model" to "deepseek-flash", "reasoningEffort" to "high"), defaultModel(out))
    }

    @Test fun idempotent() {
        val once = AccountConfig.render(seeded, AccountConfig.Mode.WEB, 41234, false)
        assertEquals(once, AccountConfig.render(once, AccountConfig.Mode.WEB, 41234, false))
    }
}
