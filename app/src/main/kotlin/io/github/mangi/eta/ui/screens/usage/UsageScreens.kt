package io.github.mangi.eta.ui.screens.usage

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ModelUsageRecordEntity
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.usage.ModelCostCalculator
import io.github.mangi.eta.data.usage.ModelIds
import io.github.mangi.eta.data.usage.ModelPrice
import io.github.mangi.eta.data.usage.ModelUsageLedger
import io.github.mangi.eta.data.usage.PriceCatalog
import io.github.mangi.eta.data.usage.QuotaUsage
import io.github.mangi.eta.data.usage.UsageGroup
import io.github.mangi.eta.data.usage.UsagePriceResolver
import io.github.mangi.eta.data.usage.UsagePricingSettings
import io.github.mangi.eta.data.usage.UsageQuota
import io.github.mangi.eta.data.usage.UsageRange
import io.github.mangi.eta.data.usage.UsageReport
import io.github.mangi.eta.data.usage.UsageReports
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCheckboxPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.navigation.AppRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 用量看板：时间范围内的请求、token、缓存命中率与估算费用（美元），以及额度进度。 */
@Composable
internal fun UsageScreen(onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(UsageRange.DAYS_7) }
    var selectedBucket by remember(range) { mutableStateOf(-1) }
    var editingQuotaProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(range) { System.currentTimeMillis() }
    val bounds = remember(range, now) { UsageReports.bounds(range, now, zone) }
    val records by remember(bounds) { ModelUsageLedger.recordsFlow(bounds.first, bounds.second) }
        .collectAsState(initial = emptyList())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val pricing by remember { SettingsDataStore.usagePricingFlow() }.collectAsState(initial = UsagePricingSettings())
    val resolver = remember(providers, pricing) { UsagePriceResolver(providers, pricing) }
    val report = remember(records, resolver, range, now) { UsageReports.build(records, range, now, zone, resolver::cost) }
    // 额度周期可能比当前时间范围长（例如月额度配“今天”），单独取最早周期起点之后的记录。
    val quotaStart = remember(pricing, now) {
        pricing.quotas.minOfOrNull { UsageReports.quotaPeriodStart(it.period, now, zone) } ?: now
    }
    val quotaRecords by remember(quotaStart) { ModelUsageLedger.recordsFlow(quotaStart, now + 1) }
        .collectAsState(initial = emptyList())
    val quotaUsages = remember(quotaRecords, pricing, resolver) {
        pricing.quotas.map { UsageReports.quotaUsage(it, quotaRecords, now, zone, resolver::cost) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("no output")
                    output.use { ModelUsageLedger.exportCsv(it, resolver::cost) }
                }
            }.isSuccess
            Toast.makeText(
                context,
                resources.getString(if (ok) R.string.usage_exported else R.string.usage_export_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.usage_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { exportLauncher.launch("eta-usage.csv") }) {
                Icon(imageVector = Icons.Rounded.Download, contentDescription = stringResource(R.string.usage_export_csv))
            }
        },
    ) {
        item(key = "range") {
            RangeSelector(range = range, onSelect = { range = it })
        }
        item(key = "summary") {
            SummaryCard(report = report, cnyPerUsd = pricing.cnyPerUsd)
        }
        item(key = "chart") {
            EtaPreferenceGroupTitle(
                stringResource(if (range == UsageRange.TODAY) R.string.usage_chart_title_hourly else R.string.usage_chart_title_daily),
            )
            CostChart(report = report, range = range, selected = selectedBucket, onSelect = { selectedBucket = it })
        }
        item(key = "quota") {
            EtaPreferenceGroupTitle(stringResource(R.string.usage_quota_title))
            EtaPreferenceGroup {
                val usedProviderIds = records.mapTo(mutableSetOf()) { it.providerId }
                providers.filter { it.isEnabled && it.inUse(pricing, usedProviderIds) }.forEachIndexed { index, provider ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    val usage = quotaUsages.firstOrNull { it.quota.providerId == provider.id }
                    QuotaRow(provider = provider, usage = usage, onEdit = { editingQuotaProvider = provider })
                }
            }
        }
        groupSection("by_model", R.string.usage_by_model, report.byModel) { it.label }
        groupSection("by_source", R.string.usage_by_source, report.bySource) { null }
        groupSection("by_purpose", R.string.usage_by_purpose, report.byPurpose) { null }
        item(key = "links") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp)) {
                EtaArrowPreference(title = stringResource(R.string.usage_logs), onClick = { onNavigate(AppRoute.UsageLogs) })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.usage_pricing), onClick = { onNavigate(AppRoute.UsagePricing) })
            }
        }
    }

    editingQuotaProvider?.let { provider ->
        QuotaDialog(
            provider = provider,
            initial = pricing.quotas.firstOrNull { it.providerId == provider.id },
            onDismiss = { editingQuotaProvider = null },
            onSave = { quota ->
                editingQuotaProvider = null
                val others = pricing.quotas.filterNot { it.providerId == provider.id }
                scope.launch { SettingsDataStore.setUsagePricing(pricing.copy(quotas = others + listOfNotNull(quota))) }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupSection(
    key: String,
    titleRes: Int,
    groups: List<UsageGroup>,
    label: (UsageGroup) -> String?,
) {
    if (groups.isEmpty()) return
    item(key = key) {
        EtaPreferenceGroupTitle(stringResource(titleRes))
        EtaPreferenceGroup {
            val maxTokens = groups.maxOf { it.tokens }.coerceAtLeast(1)
            groups.forEachIndexed { index, group ->
                if (index > 0) EtaPreferenceDivider(hasLeading = false)
                GroupRow(
                    title = label(group) ?: groupLabel(key, group.key),
                    group = group,
                    share = group.tokens.toFloat() / maxTokens,
                )
            }
        }
    }
}

@Composable
private fun groupLabel(section: String, key: String): String = when (section) {
    "by_source" -> sourceLabel(key)
    else -> purposeLabel(key)
}

@Composable
internal fun sourceLabel(source: String): String = stringResource(
    when (source) {
        "agent_ui" -> R.string.usage_source_agent_ui
        "xiaoai" -> R.string.usage_source_xiaoai
        "breeno" -> R.string.usage_source_breeno
        "eta_voice" -> R.string.usage_source_eta_voice
        "pc_mcp" -> R.string.usage_source_pc_mcp
        "automation" -> R.string.usage_source_automation
        else -> R.string.usage_source_unknown
    },
)

@Composable
internal fun purposeLabel(purpose: String): String = stringResource(
    when (purpose) {
        "compaction" -> R.string.usage_purpose_compaction
        "reply_rewrite" -> R.string.usage_purpose_reply_rewrite
        "transcript_refine" -> R.string.usage_purpose_transcript_refine
        UsageReports.PURPOSE_SUBAGENT -> R.string.usage_purpose_subagent
        else -> R.string.usage_purpose_chat
    },
)

@Composable
internal fun RangeSelector(range: UsageRange, onSelect: (UsageRange) -> Unit) {
    val labels = listOf(
        UsageRange.TODAY to R.string.usage_range_today,
        UsageRange.DAYS_7 to R.string.usage_range_7d,
        UsageRange.DAYS_30 to R.string.usage_range_30d,
        UsageRange.MONTH to R.string.usage_range_month,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { (value, label) ->
            val on = value == range
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) MiuixTheme.colorScheme.surface else MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    style = MiuixTheme.textStyles.body2,
                    color = if (on) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(report: UsageReport, cnyPerUsd: Double) {
    val total = report.total
    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.usage_cost_title),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(text = formatUsd(total.costUsd ?: 0.0), style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = stringResource(R.string.usage_cost_note, formatNumber(cnyPerUsd)),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (report.unpricedRequests > 0) {
                Text(
                    text = stringResource(R.string.usage_unpriced, report.unpricedRequests),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat(R.string.usage_stat_requests, total.requests.toString(), Modifier.weight(1f))
                Stat(R.string.usage_stat_input, compactTokens(total.promptTokens), Modifier.weight(1f))
                Stat(R.string.usage_stat_cache_hit, total.cachedRatio?.let(::formatPercent) ?: "—", Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat(R.string.usage_stat_output, compactTokens(total.outputTokens), Modifier.weight(1f))
                Stat(R.string.usage_stat_reasoning, compactTokens(report.reasoningTokens), Modifier.weight(1f))
                Stat(R.string.usage_stat_no_usage, total.requestsWithoutUsage.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(labelRes: Int, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(text = stringResource(labelRes), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** 单一数据系列的柱状图：点一根柱子在上方显示数值；柱高按费用，没有价格时按 token。 */
@Composable
private fun CostChart(report: UsageReport, range: UsageRange, selected: Int, onSelect: (Int) -> Unit) {
    val byCost = report.buckets.any { it.costUsd > 0 }
    val values = report.buckets.map { if (byCost) it.costUsd else it.tokens.toDouble() }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val pattern = if (range == UsageRange.TODAY) "HH:mm" else "MM/dd"
    val formatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()) }
    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val bucket = report.buckets.getOrNull(selected)
            Text(
                text = bucket?.let {
                    stringResource(
                        R.string.usage_chart_readout,
                        formatter.format(Instant.ofEpochMilli(it.start)),
                        formatUsd(it.costUsd),
                        compactTokens(it.tokens),
                    )
                } ?: stringResource(R.string.usage_chart_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEachIndexed { index, value ->
                    val label = report.buckets[index]
                    val description = "${formatter.format(Instant.ofEpochMilli(label.start))} ${formatUsd(label.costUsd)} ${compactTokens(label.tokens)}"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(role = Role.Button) { onSelect(index) }
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((value / max).toFloat().coerceIn(0.015f, 1f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    MiuixTheme.colorScheme.primary.copy(alpha = if (selected < 0 || selected == index) 1f else 0.5f),
                                ),
                        )
                    }
                }
            }
            if (report.buckets.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        text = formatter.format(Instant.ofEpochMilli(report.buckets.first().start)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatter.format(Instant.ofEpochMilli(report.buckets.last().start)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/**
 * 用量页只列用户在用的服务商：自己添加的、填了 API Key 的，或已经有额度、倍率或本期用量的。
 * 内置服务商默认是启用的，不筛选会把没配置过的都列出来。
 */
private fun ProviderSetting.inUse(pricing: UsagePricingSettings, usedProviderIds: Set<String> = emptySet()): Boolean =
    !isBuiltIn || apiKey.isNotBlank() || id in usedProviderIds ||
        pricing.quotas.any { it.providerId == id } || id in pricing.providerMultipliers

@Composable
private fun QuotaRow(provider: ProviderSetting, usage: QuotaUsage?, onEdit: () -> Unit) {
    if (usage == null) {
        EtaArrowPreference(
            title = provider.name,
            summary = stringResource(R.string.usage_quota_none),
            onClick = onEdit,
        )
        return
    }
    val quota = usage.quota
    val percent = (usage.ratio * 100).toInt()
    val limit = quota.limitUsd?.let(::formatUsd) ?: quota.limitTokens?.let { "${compactTokens(it)} tokens" }.orEmpty()
    val used = if (quota.limitUsd != null) formatUsd(usage.usedUsd) else "${compactTokens(usage.usedTokens)} tokens"
    EtaPreference(
        title = "${provider.name} · ${periodLabel(quota.period)}",
        summary = stringResource(if (percent >= WARN_PERCENT) R.string.usage_quota_warning else R.string.usage_quota_used, percent),
        onClick = onEdit,
        endActions = {
            Text(text = "$used / $limit", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        },
        bottomAction = {
            ProgressBar(fraction = usage.ratio.toFloat(), warning = percent >= WARN_PERCENT)
        },
    )
}

@Composable
private fun ProgressBar(fraction: Float, warning: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(if (warning) StatusError else MiuixTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun GroupRow(title: String, group: UsageGroup, share: Float) {
    val summary = if (group.requestsWithoutUsage == group.requests) {
        stringResource(R.string.usage_group_no_usage, group.requests)
    } else {
        stringResource(
            R.string.usage_group_sub,
            group.requests,
            compactTokens(group.tokens),
            group.cachedRatio?.let(::formatPercent) ?: "—",
        )
    }
    EtaPreference(
        title = title,
        summary = summary,
        endActions = {
            Text(
                text = group.costUsd?.let(::formatUsd) ?: stringResource(R.string.usage_unpriced_short),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        bottomAction = { ProgressBar(fraction = share, warning = false) },
    )
}

@Composable
private fun periodLabel(period: String): String = stringResource(
    when (period) {
        UsageQuota.PERIOD_FIVE_HOURS -> R.string.usage_quota_period_five_hours
        UsageQuota.PERIOD_DAY -> R.string.usage_quota_period_day
        UsageQuota.PERIOD_WEEK -> R.string.usage_quota_period_week
        else -> R.string.usage_quota_period_month
    },
)

/** 额度设置：周期、上限（美元或 token）与提醒；保存为空表示删除。 */
@Composable
private fun QuotaDialog(
    provider: ProviderSetting,
    initial: UsageQuota?,
    onDismiss: () -> Unit,
    onSave: (UsageQuota?) -> Unit,
) {
    var period by remember { mutableStateOf(initial?.period ?: UsageQuota.PERIOD_MONTH) }
    var byTokens by remember { mutableStateOf(initial?.limitTokens != null) }
    var limit by remember {
        mutableStateOf(initial?.limitUsd?.let(::formatNumber) ?: initial?.limitTokens?.toString().orEmpty())
    }
    var notify80 by remember { mutableStateOf(initial?.notifyAt80 ?: true) }
    var notify100 by remember { mutableStateOf(initial?.notifyAt100 ?: true) }
    val value = limit.trim().toDoubleOrNull()?.takeIf { it > 0 }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.usage_quota_dialog_title, provider.name),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EtaDropdownPreference(
                title = stringResource(R.string.usage_quota_period),
                items = UsageQuota.PERIODS.map { DropdownItem(text = periodLabel(it)) },
                selectedIndex = UsageQuota.PERIODS.indexOf(period).coerceAtLeast(0),
                onSelectedIndexChange = { period = UsageQuota.PERIODS[it] },
            )
            EtaDropdownPreference(
                title = stringResource(R.string.usage_quota_unit),
                items = listOf(
                    DropdownItem(text = stringResource(R.string.usage_quota_unit_usd)),
                    DropdownItem(text = stringResource(R.string.usage_quota_unit_tokens)),
                ),
                selectedIndex = if (byTokens) 1 else 0,
                onSelectedIndexChange = { byTokens = it == 1 },
            )
            TextField(
                value = limit,
                onValueChange = { limit = it.filter { char -> char.isDigit() || char == '.' }.take(12) },
                label = stringResource(R.string.usage_quota_limit),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            EtaCheckboxPreference(title = stringResource(R.string.usage_quota_notify80), checked = notify80, onCheckedChange = { notify80 = it })
            EtaCheckboxPreference(title = stringResource(R.string.usage_quota_notify100), checked = notify100, onCheckedChange = { notify100 = it })
            Text(
                text = stringResource(R.string.usage_quota_note),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (initial != null) {
                EtaTextButton(text = stringResource(R.string.usage_quota_remove), onClick = { onSave(null) })
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.usage_save),
                confirmEnabled = value != null,
                onCancel = onDismiss,
                onConfirm = {
                    val amount = value ?: return@MiuixDialogActions
                    onSave(
                        UsageQuota(
                            providerId = provider.id,
                            period = period,
                            limitUsd = amount.takeUnless { byTokens },
                            limitTokens = amount.toLong().takeIf { byTokens },
                            notifyAt80 = notify80,
                            notifyAt100 = notify100,
                        ),
                    )
                },
            )
        }
    }
}

/** 使用记录：每次请求一行，点开看计价明细；与 new-api 的使用日志对应。 */
@Composable
internal fun UsageLogsScreen(onBack: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(UsageRange.TODAY) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(range) { System.currentTimeMillis() }
    val bounds = remember(range, now) { UsageReports.bounds(range, now, zone) }
    val records by remember(bounds) { ModelUsageLedger.recordsFlow(bounds.first, bounds.second, LOG_ROWS) }
        .collectAsState(initial = emptyList())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val pricing by remember { SettingsDataStore.usagePricingFlow() }.collectAsState(initial = UsagePricingSettings())
    val resolver = remember(providers, pricing) { UsagePriceResolver(providers, pricing) }
    val timeFormat = remember(range) {
        DateTimeFormatter.ofPattern(if (range == UsageRange.TODAY) "HH:mm:ss" else "MM/dd HH:mm").withZone(zone)
    }
    // 筛选项只取当前时间范围里出现过的服务商与用途；换范围后原选项不在了就当作“全部”。
    var providerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var purposeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val providerOptions = remember(records) {
        records.distinctBy { it.providerId }.map { it.providerId to it.providerName }.sortedBy { it.second.lowercase() }
    }
    val purposeOptions = remember(records) {
        records.map(UsageReports::purposeKey).distinct().sortedBy { PURPOSE_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }
    val activeProvider = providerFilter?.takeIf { id -> providerOptions.any { it.first == id } }
    val activePurpose = purposeFilter?.takeIf { it in purposeOptions }
    val filtered = remember(records, activeProvider, activePurpose) {
        records.filter { record ->
            (activeProvider == null || record.providerId == activeProvider) &&
                (activePurpose == null || UsageReports.purposeKey(record) == activePurpose)
        }
    }
    val filteredCost = remember(filtered, resolver) { filtered.sumOf { resolver.cost(it) ?: 0.0 } }
    MiuixScaffoldPage(title = stringResource(R.string.usage_logs), onBack = onBack) {
        item(key = "range") { RangeSelector(range = range, onSelect = { range = it }) }
        if (records.isNotEmpty()) {
            item(key = "filters") {
                val all = stringResource(R.string.usage_filter_all)
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    EtaDropdownPreference(
                        title = stringResource(R.string.usage_filter_provider),
                        items = listOf(DropdownItem(text = all)) + providerOptions.map { DropdownItem(text = it.second) },
                        selectedIndex = providerOptions.indexOfFirst { it.first == activeProvider } + 1,
                        onSelectedIndexChange = { index -> providerFilter = providerOptions.getOrNull(index - 1)?.first },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    EtaDropdownPreference(
                        title = stringResource(R.string.usage_filter_purpose),
                        items = listOf(DropdownItem(text = all)) + purposeOptions.map { DropdownItem(text = purposeLabel(it)) },
                        selectedIndex = purposeOptions.indexOf(activePurpose) + 1,
                        onSelectedIndexChange = { index -> purposeFilter = purposeOptions.getOrNull(index - 1) },
                    )
                }
                Text(
                    text = pluralStringResource(R.plurals.usage_filter_summary, filtered.size, filtered.size, formatUsd(filteredCost)),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 8.dp),
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    EtaPreference(summary = stringResource(R.string.usage_empty))
                }
            }
        }
        filtered.forEach { record ->
            item(key = "record-${record.id}") {
                val open = record.id in expanded
                val cost = resolver.cost(record)
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                    EtaPreference(
                        title = "${timeFormat.format(Instant.ofEpochMilli(record.createdAt))}  ${record.modelDisplayName}",
                        summary = logSummary(record),
                        onClick = { expanded = if (open) expanded - record.id else expanded + record.id },
                        endActions = {
                            Text(
                                text = cost?.let(::formatUsd) ?: stringResource(R.string.usage_unpriced_short),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        bottomAction = if (open) {
                            { LogDetail(record, resolver.price(record), pricing) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun logSummary(record: ModelUsageRecordEntity): String {
    val tags = buildList {
        add(sourceLabel(record.source))
        if (record.subagentIndex != null || record.purpose != "chat") add(purposeLabel(UsageReports.purposeKey(record)))
        when (record.status) {
            ModelUsageRecordEntity.STATUS_FAILED -> add(stringResource(R.string.usage_status_failed))
            ModelUsageRecordEntity.STATUS_CANCELLED -> add(stringResource(R.string.usage_status_cancelled))
        }
    }.joinToString(" · ")
    val tokens = if (record.hasUsage) {
        stringResource(
            R.string.usage_log_tokens,
            compactTokens(record.promptTokens),
            compactTokens(record.cachedTokens),
            compactTokens(record.outputTokens),
        )
    } else {
        stringResource(R.string.usage_log_no_usage)
    }
    return "$tags\n$tokens"
}

@Composable
private fun LogDetail(record: ModelUsageRecordEntity, price: ModelPrice?, pricing: UsagePricingSettings) {
    val rows = buildList {
        add(stringResource(R.string.usage_detail_provider) to "${record.providerName} · ${record.endpoint}")
        add(stringResource(R.string.usage_detail_effort) to record.reasoningEffort)
        add(stringResource(R.string.usage_detail_duration) to "%.1f s".format(Locale.ROOT, record.durationMs / 1000.0))
        if (record.hasUsage) {
            val uncached = (record.promptTokens - record.cachedTokens - record.cacheWriteTokens).coerceAtLeast(0)
            add(stringResource(R.string.usage_detail_uncached) to compactTokens(uncached))
            add(stringResource(R.string.usage_detail_cache_read) to compactTokens(record.cachedTokens))
            if (record.cacheWriteTokens > 0) add(stringResource(R.string.usage_detail_cache_write) to compactTokens(record.cacheWriteTokens))
            add(stringResource(R.string.usage_detail_output, compactTokens(record.reasoningTokens)) to compactTokens(record.outputTokens))
        }
        add(stringResource(R.string.usage_detail_multiplier) to "× ${formatNumber(pricing.multiplier(record.providerId))}")
        add(stringResource(R.string.usage_detail_price_source) to (price?.source?.ifBlank { stringResource(R.string.usage_price_custom) }
            ?: stringResource(R.string.usage_detail_unpriced)))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { (key, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = key, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** 价格、倍率与汇率：汇率与倍率即时保存；模型价格可覆盖预置价，或给没有预置的模型填写。 */
@Composable
internal fun UsagePricingScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val pricing by remember { SettingsDataStore.usagePricingFlow() }.collectAsState(initial = UsagePricingSettings())
    // 正在编辑的模型 ID；空串表示新增定价。
    var editing by remember { mutableStateOf<String?>(null) }
    var paid by remember { mutableStateOf("1") }
    var credit by remember { mutableStateOf("1") }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(setOf(PriceCatalog.UNPRICED, PriceCatalog.CUSTOM)) }
    fun save(next: UsagePricingSettings) {
        scope.launch { SettingsDataStore.setUsagePricing(next) }
    }
    val enabled = providers.filter { it.isEnabled && it.inUse(pricing) }
    val since = remember { System.currentTimeMillis() - UNPRICED_LOOKBACK_MS }
    val recent by remember(since) { ModelUsageLedger.recordsFlow(since, Long.MAX_VALUE) }.collectAsState(initial = emptyList())
    val resolver = remember(providers, pricing) { UsagePriceResolver(providers, pricing) }
    // 在用的模型：近 30 天账本里出现过的，加上在用服务商里启用的模型；查不到价格的列进“未定价”。
    val unpriced = remember(recent, enabled, resolver) {
        (recent.map { it.providerId to it.model } +
            enabled.flatMap { provider -> provider.models.filter { it.isEnabled }.map { provider.id to it.modelId } })
            .distinct()
            .filter { (providerId, model) -> resolver.price(probe(providerId, model)) == null }
            .map { it.second }
    }
    val groups = remember(pricing, unpriced, query) { PriceCatalog.groups(pricing, unpriced, query) }
    MiuixScaffoldPage(title = stringResource(R.string.usage_pricing), onBack = onBack) {
        item(key = "fx") {
            EtaPreferenceGroupTitle(stringResource(R.string.usage_fx_title))
            EtaPreferenceGroup {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = stringResource(R.string.usage_fx_label),
                        initial = formatNumber(pricing.cnyPerUsd),
                        key = pricing.cnyPerUsd,
                        onValid = { save(pricing.copy(cnyPerUsd = it)) },
                    )
                    Text(
                        text = stringResource(R.string.usage_fx_note),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "multipliers") {
            EtaPreferenceGroupTitle(stringResource(R.string.usage_multiplier_title))
            EtaPreferenceGroup {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    enabled.forEach { provider ->
                        NumberField(
                            label = provider.name,
                            initial = formatNumber(pricing.multiplier(provider.id)),
                            key = pricing.multiplier(provider.id),
                            onValid = { value ->
                                save(pricing.copy(providerMultipliers = pricing.providerMultipliers + (provider.id to value)))
                            },
                        )
                    }
                    Text(
                        text = stringResource(R.string.usage_multiplier_note),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.usage_recharge_title),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = paid,
                            onValueChange = { paid = it.filter { char -> char.isDigit() || char == '.' }.take(10) },
                            label = stringResource(R.string.usage_recharge_paid),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        TextField(
                            value = credit,
                            onValueChange = { credit = it.filter { char -> char.isDigit() || char == '.' }.take(10) },
                            label = stringResource(R.string.usage_recharge_credit),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val result = ModelCostCalculator.rechargeMultiplier(
                        paid.toDoubleOrNull() ?: 0.0,
                        credit.toDoubleOrNull() ?: 0.0,
                        pricing.cnyPerUsd,
                    )
                    Text(
                        text = result?.let { stringResource(R.string.usage_recharge_result, formatNumber(it)) } ?: "—",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item(key = "prices") {
            EtaPreferenceGroupTitle(stringResource(R.string.usage_prices_title))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    label = stringResource(R.string.usage_price_search),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.usage_price_scope_note),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                EtaTextButton(text = stringResource(R.string.usage_price_add), onClick = { editing = "" })
            }
        }
        if (groups.isEmpty()) {
            item(key = "prices-empty") {
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    EtaPreference(summary = stringResource(R.string.usage_price_no_match))
                }
            }
        }
        groups.forEach { group ->
            item(key = "price-group-${group.key}") {
                // 搜索时全部展开；否则“未定价”“自定义”默认展开，各厂商默认收起。
                val open = query.isNotBlank() || group.key in expandedGroups
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    EtaPreference(
                        title = priceGroupLabel(group.key, providers),
                        summary = pluralStringResource(R.plurals.usage_price_group_count, group.rows.size, group.rows.size),
                        onClick = {
                            expandedGroups = if (group.key in expandedGroups) expandedGroups - group.key else expandedGroups + group.key
                        },
                        endActions = {
                            Icon(
                                imageVector = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                    )
                    if (open) {
                        group.rows.forEach { row ->
                            EtaPreferenceDivider(hasLeading = false)
                            EtaPreference(
                                title = row.modelId,
                                summary = row.price?.let { priceLine(it) + if (row.custom) " · " + stringResource(R.string.usage_price_custom) else "" }
                                    ?: stringResource(R.string.usage_price_unset),
                                onClick = { editing = row.modelId },
                            )
                        }
                    }
                }
            }
        }
    }
    editing?.let { modelId ->
        val builtin = PriceCatalog.builtinPrice(modelId)
        PriceDialog(
            modelId = modelId,
            initial = pricing.override(modelId) ?: builtin,
            builtin = builtin,
            hasOverride = modelId.isNotEmpty() && pricing.override(modelId) != null,
            onDismiss = { editing = null },
            onSave = { id, price ->
                editing = null
                val key = ModelIds.normalize(id)
                if (key.isNotEmpty()) {
                    val overrides = if (price == null) pricing.priceOverrides - key else pricing.priceOverrides + (key to price)
                    save(pricing.copy(priceOverrides = overrides))
                }
            },
        )
    }
}

@Composable
private fun priceGroupLabel(key: String, providers: List<ProviderSetting>): String = when (key) {
    PriceCatalog.UNPRICED -> stringResource(R.string.usage_price_group_unpriced)
    PriceCatalog.CUSTOM -> stringResource(R.string.usage_price_group_custom)
    // 预置价格按厂商来源分组，组名用对应内置服务商的名字。
    else -> providers.firstOrNull { it.isBuiltIn && ProviderSourceRegistry.resolve(it) == key }?.name ?: key
}

/** 只用于查价格的占位记录。 */
private fun probe(providerId: String, model: String) = ModelUsageRecordEntity(
    createdAt = 0, durationMs = 0, source = "", runId = "", purpose = "chat", subagentIndex = null,
    providerId = providerId, providerName = "", model = model, modelDisplayName = model, endpoint = "",
    reasoningEffort = "", status = ModelUsageRecordEntity.STATUS_OK, hasUsage = false,
    promptTokens = 0, cachedTokens = 0, cacheWriteTokens = 0, outputTokens = 0, reasoningTokens = 0,
)

@Composable
private fun priceLine(price: ModelPrice): String = stringResource(
    R.string.usage_price_line,
    formatNumber(price.input),
    price.cachedInput?.let(::formatNumber) ?: "—",
    formatNumber(price.output),
    price.currency,
)

/** 文本框只在输入有效时保存；外部值变化（[key]）时重置内容。 */
@Composable
private fun NumberField(label: String, initial: String, key: Any, onValid: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(initial) }
    TextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() || it == '.' }.take(10)
            text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onValid)
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 编辑一个模型 ID 的价格（对所有服务商生效）。[modelId] 为空时是新增，先填模型 ID；
 * 预置模型显示价格来源，改过的可以恢复预置价，自定义的可以删除。
 */
@Composable
private fun PriceDialog(
    modelId: String,
    initial: ModelPrice?,
    builtin: ModelPrice?,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, ModelPrice?) -> Unit,
) {
    var id by remember { mutableStateOf(modelId) }
    var currency by remember { mutableStateOf(initial?.currency ?: ModelPrice.USD) }
    var input by remember { mutableStateOf(initial?.input?.let(::formatNumber).orEmpty()) }
    var cached by remember { mutableStateOf(initial?.cachedInput?.let(::formatNumber).orEmpty()) }
    var write by remember { mutableStateOf(initial?.cacheWrite?.let(::formatNumber).orEmpty()) }
    var output by remember { mutableStateOf(initial?.output?.let(::formatNumber).orEmpty()) }
    val inputValue = input.toDoubleOrNull()?.takeIf { it >= 0 }
    val outputValue = output.toDoubleOrNull()?.takeIf { it >= 0 }
    val currencies = listOf(ModelPrice.USD, ModelPrice.CNY)
    val normalizedId = ModelIds.normalize(id)
    EtaWindowDialog(
        show = true,
        title = if (modelId.isEmpty()) stringResource(R.string.usage_price_add)
            else stringResource(R.string.usage_price_dialog_title, modelId),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (modelId.isEmpty()) {
                TextField(
                    value = id,
                    onValueChange = { id = it.take(120) },
                    label = stringResource(R.string.usage_price_model_id),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (normalizedId.isNotEmpty() && normalizedId != id.trim()) {
                    Text(
                        text = stringResource(R.string.usage_price_normalized, normalizedId),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            builtin?.let { price ->
                Text(
                    text = listOfNotNull(
                        price.source.takeIf(String::isNotBlank),
                        price.longContextThreshold?.let { threshold ->
                            stringResource(
                                R.string.usage_price_long_context,
                                compactTokens(threshold),
                                formatNumber(price.longInput ?: price.input),
                                formatNumber(price.longOutput ?: price.output),
                            )
                        },
                    ).joinToString("\n"),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            EtaDropdownPreference(
                title = stringResource(R.string.usage_price_currency),
                items = currencies.map { DropdownItem(text = it) },
                selectedIndex = currencies.indexOf(currency).coerceAtLeast(0),
                onSelectedIndexChange = { currency = currencies[it] },
            )
            PriceField(stringResource(R.string.usage_price_input), input) { input = it }
            PriceField(stringResource(R.string.usage_price_cached), cached) { cached = it }
            PriceField(stringResource(R.string.usage_price_write), write) { write = it }
            PriceField(stringResource(R.string.usage_price_output), output) { output = it }
            if (hasOverride) {
                EtaTextButton(
                    text = stringResource(if (builtin != null) R.string.usage_price_reset else R.string.usage_price_delete),
                    onClick = { onSave(modelId, null) },
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.usage_save),
                confirmEnabled = inputValue != null && outputValue != null && normalizedId.isNotEmpty(),
                onCancel = onDismiss,
                onConfirm = {
                    onSave(
                        id,
                        ModelPrice(
                            currency = currency,
                            input = inputValue ?: return@MiuixDialogActions,
                            cachedInput = cached.toDoubleOrNull(),
                            cacheWrite = write.toDoubleOrNull(),
                            output = outputValue ?: return@MiuixDialogActions,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = { onChange(it.filter { char -> char.isDigit() || char == '.' }.take(10)) },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val LOG_ROWS = 500

/** “在用但未定价”看近 30 天的账本。 */
private const val UNPRICED_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1_000

/** 用途筛选的显示顺序。 */
private val PURPOSE_ORDER = listOf("chat", UsageReports.PURPOSE_SUBAGENT, "compaction", "reply_rewrite", "transcript_refine")
private const val WARN_PERCENT = 80

internal fun formatUsd(value: Double): String = when {
    value == 0.0 -> "$0.00"
    value < 0.01 -> "$" + "%.4f".format(Locale.ROOT, value)
    else -> "$" + "%.2f".format(Locale.ROOT, value)
}

internal fun formatPercent(ratio: Double): String = "%.0f%%".format(Locale.ROOT, ratio * 100)

internal fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && value < 1e9) "%.0f".format(Locale.ROOT, value)
    else "%.4f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

internal fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(Locale.ROOT, value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(Locale.ROOT, value / 1_000.0)
    else -> value.toString()
}
