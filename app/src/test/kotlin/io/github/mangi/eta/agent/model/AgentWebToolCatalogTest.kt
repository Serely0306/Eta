package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONArray

class AgentWebToolCatalogTest {
    @Test
    fun questionParameterIsPublishedOnlyWhenAnExtractionModelIsConfigured() {
        fun fetchProperties(includeQuestion: Boolean): JSONObject {
            val tools = JSONArray()
            AgentWebToolCatalog.appendTo(tools, includeQuestion = includeQuestion)
            val fetch = (0 until tools.length())
                .map(tools::getJSONObject)
                .first { it.getJSONObject("function").getString("name") == "fetch_url" }
            return fetch.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        }

        assertFalse(fetchProperties(false).has("question"))
        assertTrue(fetchProperties(true).has("question"))
    }

    @Test
    fun publicWebToolsNeedNeitherRootNorAccessibility() {
        listOf("web_search", "fetch_url").forEach { name ->
            val requirement = requireNotNull(AgentToolRequirements.find(name))
            assertEquals(RootRequirement.NONE, requirement.rootRequirement)
            assertEquals(false, requirement.accessibility)
        }
    }

}
