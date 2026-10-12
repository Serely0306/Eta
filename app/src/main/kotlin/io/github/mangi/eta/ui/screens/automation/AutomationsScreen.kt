package io.github.mangi.eta.ui.screens.automation

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentAutomation
import io.github.mangi.eta.agent.automation.AgentAutomationRunner
import io.github.mangi.eta.agent.automation.AgentAutomationScheduler
import io.github.mangi.eta.agent.automation.AgentAutomationStore
import io.github.mangi.eta.agent.automation.AutomationSchedule
import io.github.mangi.eta.agent.automation.AutomationTrigger
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 自动任务列表：开关、编辑、立即运行；精确闹钟与通知访问缺失时给出入口。 */
@Composable
internal fun AutomationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val automations by remember { AgentAutomationStore.flow() }.collectAsState()
    var editing by remember { mutableStateOf<AgentAutomation?>(null) }
    var creating by remember { mutableStateOf(false) }
    val exactAllowed = remember(automations) { AgentAutomationScheduler.exactAlarmsAllowed(context) }
    val needsNotificationAccess = automations.any { it.enabled && it.trigger is AutomationTrigger.OnNotification } &&
        !AgentNotificationHistoryService.isEnabled(context)

    fun save(automation: AgentAutomation) {
        if (AgentAutomationStore.upsert(automation)) AgentAutomationScheduler.reschedule(context)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_automations),
        onBack = onBack,
        actions = {
            IconButton(onClick = { creating = true }) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = stringResource(R.string.automation_add))
            }
        },
    ) {
        if (!exactAllowed || needsNotificationAccess) {
            item(key = "warnings") {
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    if (!exactAllowed) {
                        EtaArrowPreference(
                            title = stringResource(R.string.automation_exact_alarm_title),
                            summary = stringResource(R.string.automation_exact_alarm_summary),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                    if (needsNotificationAccess) {
                        EtaArrowPreference(
                            title = stringResource(R.string.automation_notification_access_title),
                            summary = stringResource(R.string.automation_notification_access_summary),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "automations") {
            EtaPreferenceGroupTitle(stringResource(R.string.automation_list_title, automations.size))
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (automations.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.automation_empty_title),
                        summary = stringResource(R.string.automation_empty_summary),
                        action = { EtaTextButton(text = stringResource(R.string.automation_add), onClick = { creating = true }) },
                    )
                } else {
                    automations.forEach { automation ->
                        EtaSwitchPreference(
                            title = automation.name,
                            summary = automationSummary(automation),
                            checked = automation.enabled,
                            onCheckedChange = { enabled -> save(automation.copy(enabled = enabled)) },
                            bottomAction = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EtaTextButton(text = stringResource(R.string.automation_edit), onClick = { editing = automation })
                                    EtaTextButton(
                                        text = stringResource(R.string.automation_run_now),
                                        enabled = automation.enabled,
                                        onClick = {
                                            val started = AgentAutomationRunner.fire(context, automation.id, trigger = null)
                                            Toast.makeText(
                                                context,
                                                context.getString(if (started) R.string.automation_started else R.string.automation_busy),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        AutomationEditor(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { automation ->
                save(automation)
                creating = false
                editing = null
            },
            onDelete = editing?.let { target ->
                {
                    AgentAutomationScheduler.cancel(context, target.id)
                    AgentAutomationStore.delete(target.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun automationSummary(automation: AgentAutomation): String {
    val trigger = triggerSummary(automation.trigger)
    val last = automation.lastRunAt?.let { at ->
        val status = when (automation.lastStatus) {
            AgentAutomation.STATUS_COMPLETED -> stringResource(R.string.automation_status_completed)
            AgentAutomation.STATUS_FAILED -> stringResource(R.string.automation_status_failed)
            else -> stringResource(R.string.automation_status_running)
        }
        stringResource(R.string.automation_last_run, format(at), status)
    }
    return listOfNotNull(trigger, last).joinToString("\n")
}

@Composable
private fun triggerSummary(trigger: AutomationTrigger): String = when (trigger) {
    is AutomationTrigger.Daily -> {
        val time = "%02d:%02d".format(trigger.hour, trigger.minute)
        when (trigger.daysOfWeek) {
            emptySet<Int>() -> stringResource(R.string.automation_summary_daily, time)
            WORKDAYS -> stringResource(R.string.automation_summary_workdays, time)
            WEEKEND -> stringResource(R.string.automation_summary_weekend, time)
            else -> stringResource(R.string.automation_summary_days, trigger.daysOfWeek.sorted().joinToString(","), time)
        }
    }
    is AutomationTrigger.Interval -> stringResource(R.string.automation_summary_interval, trigger.minutes)
    is AutomationTrigger.Once -> stringResource(R.string.automation_summary_once, format(trigger.atMillis))
    is AutomationTrigger.OnNotification -> stringResource(
        R.string.automation_summary_notification,
        listOf(trigger.packageName, trigger.keyword).filter(String::isNotBlank).joinToString(" · "),
    )
}

private val WORKDAYS = setOf(1, 2, 3, 4, 5)
private val WEEKEND = setOf(6, 7)

private fun format(millis: Long): String = DISPLAY_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private enum class TriggerKind { DAILY, INTERVAL, ONCE, NOTIFICATION }

private enum class DayPreset(val days: Set<Int>) { EVERY_DAY(emptySet()), WORKDAYS(setOf(1, 2, 3, 4, 5)), WEEKEND(setOf(6, 7)) }

@Composable
private fun AutomationEditor(
    initial: AgentAutomation?,
    onDismiss: () -> Unit,
    onSave: (AgentAutomation) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val resources = LocalResources.current
    val trigger = initial?.trigger
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var kind by remember {
        mutableStateOf(
            when (trigger) {
                is AutomationTrigger.Interval -> TriggerKind.INTERVAL
                is AutomationTrigger.Once -> TriggerKind.ONCE
                is AutomationTrigger.OnNotification -> TriggerKind.NOTIFICATION
                else -> TriggerKind.DAILY
            },
        )
    }
    var time by remember {
        mutableStateOf((trigger as? AutomationTrigger.Daily)?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "08:00")
    }
    var dayPreset by remember {
        mutableStateOf(DayPreset.entries.firstOrNull { it.days == (trigger as? AutomationTrigger.Daily)?.daysOfWeek } ?: DayPreset.EVERY_DAY)
    }
    var minutes by remember { mutableStateOf(((trigger as? AutomationTrigger.Interval)?.minutes ?: 60).toString()) }
    var at by remember {
        mutableStateOf(format((trigger as? AutomationTrigger.Once)?.atMillis ?: (System.currentTimeMillis() + 3_600_000L)))
    }
    val notification = trigger as? AutomationTrigger.OnNotification
    var packageName by remember { mutableStateOf(notification?.packageName.orEmpty()) }
    var keyword by remember { mutableStateOf(notification?.keyword.orEmpty()) }
    var cooldown by remember { mutableStateOf((notification?.cooldownMinutes ?: AutomationTrigger.DEFAULT_COOLDOWN_MINUTES).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): AgentAutomation? {
        val trimmedName = name.trim().take(AgentAutomation.MAX_NAME_CHARS)
        val trimmedPrompt = prompt.trim().take(AgentAutomation.MAX_PROMPT_CHARS)
        if (trimmedName.isEmpty() || trimmedPrompt.isEmpty()) {
            error = resources.getString(R.string.automation_error_required)
            return null
        }
        val built: AutomationTrigger = when (kind) {
            TriggerKind.DAILY -> {
                val (hour, minute) = AutomationSchedule.parseTime(time)
                    ?: return null.also { error = resources.getString(R.string.automation_error_time) }
                AutomationTrigger.Daily(hour, minute, dayPreset.days)
            }
            TriggerKind.INTERVAL -> {
                val value = minutes.trim().toIntOrNull()?.takeIf { it >= AutomationTrigger.MIN_INTERVAL_MINUTES }
                    ?: return null.also { error = resources.getString(R.string.automation_error_interval, AutomationTrigger.MIN_INTERVAL_MINUTES) }
                AutomationTrigger.Interval(value)
            }
            TriggerKind.ONCE -> {
                val millis = AutomationSchedule.parseLocalDateTime(at)?.takeIf { it > System.currentTimeMillis() }
                    ?: return null.also { error = resources.getString(R.string.automation_error_once) }
                AutomationTrigger.Once(millis)
            }
            TriggerKind.NOTIFICATION -> {
                if (packageName.isBlank() && keyword.isBlank()) {
                    error = resources.getString(R.string.automation_error_notification)
                    return null
                }
                AutomationTrigger.OnNotification(
                    packageName = packageName.trim(),
                    keyword = keyword.trim(),
                    cooldownMinutes = cooldown.trim().toIntOrNull()?.coerceIn(1, 24 * 60) ?: AutomationTrigger.DEFAULT_COOLDOWN_MINUTES,
                )
            }
        }
        return AgentAutomation(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = trimmedName,
            prompt = trimmedPrompt,
            trigger = built,
            enabled = initial?.enabled ?: true,
            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = initial?.lastRunAt,
            lastStatus = initial?.lastStatus,
            lastSummary = initial?.lastSummary,
        )
    }

    EtaWindowDialog(
        show = true,
        title = stringResource(if (initial == null) R.string.automation_add else R.string.automation_edit),
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        ) {
            TextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.automation_name),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = prompt, onValueChange = { prompt = it }, label = stringResource(R.string.automation_prompt),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
            val kindLabels = listOf(
                stringResource(R.string.automation_trigger_daily),
                stringResource(R.string.automation_trigger_interval),
                stringResource(R.string.automation_trigger_once),
                stringResource(R.string.automation_trigger_notification),
            )
            EtaDropdownPreference(
                title = stringResource(R.string.automation_trigger),
                items = kindLabels.map { DropdownItem(text = it) },
                selectedIndex = kind.ordinal,
                onSelectedIndexChange = { kind = TriggerKind.entries[it] },
            )
            when (kind) {
                TriggerKind.DAILY -> {
                    TextField(value = time, onValueChange = { time = it }, label = stringResource(R.string.automation_time),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    EtaDropdownPreference(
                        title = stringResource(R.string.automation_days),
                        items = listOf(
                            stringResource(R.string.automation_days_every),
                            stringResource(R.string.automation_days_workdays),
                            stringResource(R.string.automation_days_weekend),
                        ).map { DropdownItem(text = it) },
                        selectedIndex = dayPreset.ordinal,
                        onSelectedIndexChange = { dayPreset = DayPreset.entries[it] },
                    )
                }
                TriggerKind.INTERVAL -> TextField(
                    value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(5) },
                    label = stringResource(R.string.automation_interval_minutes), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                TriggerKind.ONCE -> TextField(value = at, onValueChange = { at = it }, label = stringResource(R.string.automation_once_at),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                TriggerKind.NOTIFICATION -> {
                    TextField(value = packageName, onValueChange = { packageName = it }, label = stringResource(R.string.automation_package),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = keyword, onValueChange = { keyword = it }, label = stringResource(R.string.automation_keyword),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = cooldown, onValueChange = { cooldown = it.filter(Char::isDigit).take(4) },
                        label = stringResource(R.string.automation_cooldown), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
            error?.let {
                Text(text = it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
            }
            onDelete?.let { delete ->
                EtaTextButton(text = stringResource(R.string.automation_delete), onClick = delete)
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.automation_save),
                onCancel = onDismiss,
                onConfirm = { build()?.let(onSave) },
            )
        }
    }
}
