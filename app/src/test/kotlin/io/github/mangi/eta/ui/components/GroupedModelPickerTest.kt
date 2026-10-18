package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupedModelPickerTest {
    private val providers = listOf(
        provider("relay-a", "Relay A", models = listOf(model("a-1", "A One"), model("a-off", "A Off", enabled = false))),
        provider("relay-b", "Relay B", models = listOf(model("b-1", "B One"), model("b-2", "B Two"))),
        provider("no-key", "No Key", apiKey = " ", models = listOf(model("k-1", "K One"))),
        provider("disabled", "Disabled", enabled = false, models = listOf(model("d-1", "D One"))),
    )

    @Test
    fun groupsFollowTheChatPickerFilteringAndOrder() {
        val groups = modelChoiceGroups(providers)

        assertEquals(listOf("relay-a", "relay-b"), groups.map { it.providerId })
        assertEquals(listOf("a-1"), groups[0].models.map { it.id })
        assertEquals(listOf("b-1", "b-2"), groups[1].models.map { it.id })
        assertEquals("Relay B", groups.findModel("b-2")?.providerName)
        assertNull(groups.findModel("a-off"))
        assertNull(groups.findModel(null))
    }

    @Test
    fun unavailableSelectionsKeepTheirNameUntilDeleted() {
        assertEquals("Relay A / A Off", unavailableModelLabel(providers, "a-off"))
        assertEquals("No Key / K One", unavailableModelLabel(providers, "k-1"))
        assertEquals("Disabled / D One", unavailableModelLabel(providers, "d-1"))
        assertNull(unavailableModelLabel(providers, "deleted"))
    }

    private fun provider(
        id: String,
        name: String,
        enabled: Boolean = true,
        apiKey: String = "test-key",
        models: List<Model>,
    ) = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://example.com/$id",
        apiKey = apiKey,
        isEnabled = enabled,
        models = models,
        sortOrder = when (id) {
            "relay-a" -> 0
            "relay-b" -> 1
            else -> 2
        },
    )

    private fun model(id: String, displayName: String, enabled: Boolean = true) =
        Model(id = id, modelId = id, displayName = displayName, isEnabled = enabled)
}
