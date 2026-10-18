package io.github.mangi.eta.ui.preview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.usage.ModelUsageLedger
import io.github.mangi.eta.data.usage.UsagePricingSettings
import io.github.mangi.eta.data.usage.UsageQuota
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.usage.UsageLogsScreen
import io.github.mangi.eta.ui.screens.usage.UsagePricingScreen
import io.github.mangi.eta.ui.screens.usage.UsageScreen
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 装机前的界面预览：用示例账本渲染用量页面，以及工具与思考的行内界面，保存 PNG。
 * 只在设置了 ETA_SCREENSHOT_DIR 时运行（PR Check 设置并上传为 ui-preview 产物）；本机单测跳过。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
class UiPreviewScreenshotTest {
    private val context = RuntimeEnvironment.getApplication()
    private val outputDir = System.getenv("ETA_SCREENSHOT_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    @Before
    fun setUp() {
        assumeTrue(outputDir != null)
        outputDir!!.mkdirs()
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        ModelUsageLedger.init(context)
        runBlocking {
            ProviderRepository.ensureBuiltInsMerged()
            EtaDatabase.get(context).modelUsageDao().insertAll(sampleRecords(System.currentTimeMillis()))
            SettingsDataStore.setUsagePricing(UsagePricingSettings(
                providerMultipliers = mapOf(RELAY_ID to 0.3),
                quotas = listOf(
                    UsageQuota(BuiltinProviders.OPENAI_ID, UsageQuota.PERIOD_DAY, limitUsd = 2.0),
                    UsageQuota(BuiltinProviders.DEEPSEEK_ID, UsageQuota.PERIOD_MONTH, limitTokens = 5_000_000),
                ),
            ))
        }
        // 页面从这些 Flow 读数据；先在后台线程确认它们都能出数据，截图为空时能区分是数据还是界面的问题。
        runBlocking(Dispatchers.IO) {
            withTimeout(FLOW_TIMEOUT_MS) {
                ProviderRepository.providersFlow().first { it.isNotEmpty() }
                ModelUsageLedger.recordsFlow(0, Long.MAX_VALUE).first { it.isNotEmpty() }
                SettingsDataStore.usagePricingFlow().first { it.quotas.isNotEmpty() }
            }
        }
    }

    @Test
    fun englishScreens() = captureAll("en")

    @Test
    @Config(qualifiers = "b+zh+Hans-w411dp-h1400dp-xxhdpi")
    fun simplifiedChineseScreens() = captureAll("zh-Hans")

    private fun captureAll(locale: String) {
        capture("usage-dashboard-$locale") { UsageScreen(onNavigate = {}, onBack = {}) }
        capture("usage-logs-$locale") { UsageLogsScreen(onBack = {}) }
        capture("usage-pricing-$locale") { UsagePricingScreen(onBack = {}) }
        capture("agent-timeline-$locale") { AgentTimelinePreview() }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(R.style.Theme_Eta)
        val activity = controller.setup().get()
        activity.setContent {
            AgentAppTheme(
                appearance = AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT, blurEnabled = false),
                applyInterfaceScale = false,
                content = content,
            )
        }
        val view = activity.window.decorView
        // Robolectric 不会给窗口真实尺寸，按配置的屏幕大小手动布局。
        val metrics = activity.resources.displayMetrics
        fun layout() {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        // Flow 在后台线程查询，结果投递回主线程；按真实时间等一段，期间不断推进主线程的帧。
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            layout()
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        layout()
        // miuix 的控件用 RuntimeShader 绘制，软件画布不支持，改用 PixelCopy 走 Robolectric 的硬件渲染。
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var copyResult = -1
        PixelCopy.request(activity.window, bitmap, { copyResult = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(copyResult == PixelCopy.SUCCESS) { "PixelCopy failed: $copyResult" }
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun sampleRecords(now: Long): List<ModelUsageRecordEntity> {
        val hour = 3_600_000L
        val rows = mutableListOf<ModelUsageRecordEntity>()
        fun add(
            ago: Long, providerId: String, providerName: String, model: String, source: String,
            prompt: Long, cached: Long, output: Long, purpose: String = "chat", subagent: Int? = null,
            hasUsage: Boolean = true, status: String = ModelUsageRecordEntity.STATUS_OK,
        ) {
            rows += ModelUsageRecordEntity(
                createdAt = now - ago, durationMs = 4_200, source = source, runId = "run-${rows.size / 3}",
                purpose = purpose, subagentIndex = subagent, providerId = providerId, providerName = providerName,
                model = model, modelDisplayName = model, endpoint = "responses", reasoningEffort = "high",
                status = status, hasUsage = hasUsage, promptTokens = if (hasUsage) prompt else 0,
                cachedTokens = if (hasUsage) cached else 0, cacheWriteTokens = 0,
                outputTokens = if (hasUsage) output else 0, reasoningTokens = if (hasUsage) output / 3 else 0,
                sessionId = "conversation-${rows.size / 6}",
            )
        }
        for (day in 0 until 7) {
            val base = day * 24 * hour + hour / 2
            add(base, RELAY_ID, "API A", "gpt-6-astra", "agent_ui", 22_700, 22_500, 300)
            add(base + hour, RELAY_ID, "API A", "gpt-6-astra", "agent_ui", 31_200, 30_100, 1_800)
            add(base + 2 * hour, RELAY_ID, "API A", "gpt-6-astra", "agent_ui", 12_000, 0, 900, purpose = "compaction")
            add(base + 3 * hour, BuiltinProviders.OPENAI_ID, "OpenAI", "gpt-6-sol", "pc_mcp", 18_400, 15_900, 2_400)
            add(base + 3 * hour + 60_000, BuiltinProviders.OPENAI_ID, "OpenAI", "gpt-6-sol", "pc_mcp", 9_800, 4_100, 1_100, subagent = 0)
            add(base + 4 * hour, BuiltinProviders.DEEPSEEK_ID, "DeepSeek", "deepseek-v4-flash", "xiaoai", 6_200, 5_800, 420)
            add(base + 5 * hour, BuiltinProviders.ANTHROPIC_ID, "Anthropic", "claude-opus-5-5", "automation", 14_000, 11_000, 2_000)
            if (day % 2 == 0) add(base + 6 * hour, "relay-b", "API B", "gpt-6-astra-mini", "agent_ui", 0, 0, 0, hasUsage = false)
            if (day == 1) add(base + 7 * hour, RELAY_ID, "API A", "gpt-6-astra", "agent_ui", 8_000, 0, 0,
                status = ModelUsageRecordEntity.STATUS_FAILED)
        }
        return rows
    }

    private companion object {
        const val RELAY_ID = "relay-a"
        const val FLOW_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 4_000L
    }
}
