package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.agent.browser.AgentBrowserSession

internal val LocalAgentBrowserSession = staticCompositionLocalOf<AgentBrowserSession?> { null }
