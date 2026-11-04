package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ModelPromptRules
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按模型的提示词（AG-40）：列出所有规则（模式与内容开头），可以新增、编辑、删除。存法照价格表：
 * 规整后的模式为键，存在设置里，随 Eta 备份导出与恢复。
 * [initialPattern] 不为空时直接打开那条规则；还没有这条规则时以它为模式新建（从模型编辑弹窗跳过来）。
 */
@Composable
internal fun ModelPromptsScreen(initialPattern: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 读到设置之前不显示列表和弹窗，免得从模型编辑跳过来时把已有规则当成新建。
    val rules = remember { SettingsDataStore.modelPromptsFlow() }.collectAsState(initial = null).value
    var editing by rememberSaveable { mutableStateOf(initialPattern) }
    fun save(next: Map<String, String>) {
        scope.launch {
            SettingsDataStore.setModelPrompts(next)
            // 小布入口读 RemotePreferences 里的模型配置快照，规则变了要同步一次。
            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
        }
    }
    MiuixScaffoldPage(title = stringResource(R.string.model_prompts_title), onBack = onBack) {
        if (rules == null) return@MiuixScaffoldPage
        item(key = "note") {
            Text(
                text = stringResource(R.string.model_prompts_note),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        item(key = "rules") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.model_prompts_add),
                    onClick = { editing = "" },
                )
                if (rules.isEmpty()) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(summary = stringResource(R.string.model_prompts_empty))
                }
                rules.toSortedMap().forEach { (pattern, prompt) ->
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        title = pattern,
                        summary = promptPreview(prompt),
                        onClick = { editing = pattern },
                    )
                }
            }
        }
    }
    if (rules == null) return
    editing?.let { original ->
        val existing = rules[original]
        ModelPromptDialog(
            pattern = original,
            initialPrompt = existing,
            existingPatterns = rules.keys,
            onDismiss = { editing = null },
            onSave = { pattern, prompt ->
                editing = null
                save((if (existing != null) rules - original else rules) + (pattern to prompt))
            },
            onDelete = {
                editing = null
                save(rules - original)
            },
        )
    }
}

/**
 * 编辑一条规则。[initialPrompt] 为 null 时是新建，[pattern] 是预填的模式（可以为空）。
 * 模式按 [ModelPromptRules.normalizePattern] 规整后保存；改成已有的模式时替换那一条。
 */
@Composable
private fun ModelPromptDialog(
    pattern: String,
    initialPrompt: String?,
    existingPatterns: Set<String>,
    onDismiss: () -> Unit,
    onSave: (pattern: String, prompt: String) -> Unit,
    onDelete: () -> Unit,
) {
    var patternText by remember { mutableStateOf(pattern) }
    var prompt by remember { mutableStateOf(initialPrompt.orEmpty()) }
    val normalized = ModelPromptRules.normalizePattern(patternText)
    EtaWindowDialog(
        show = true,
        title = if (initialPrompt == null) stringResource(R.string.model_prompts_add)
            else stringResource(R.string.model_prompts_edit_title, pattern),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = patternText,
                onValueChange = { patternText = it.take(MAX_PATTERN_CHARS) },
                label = stringResource(R.string.model_prompts_pattern),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val hint = when {
                normalized == null ->
                    if (patternText.isBlank()) null else stringResource(R.string.model_prompts_pattern_invalid)
                normalized != pattern && normalized in existingPatterns -> stringResource(R.string.model_prompts_pattern_exists)
                normalized != patternText.trim() -> stringResource(R.string.usage_price_normalized, normalized)
                else -> null
            }
            hint?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (normalized == null) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = stringResource(R.string.model_prompts_content),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().height(PROMPT_FIELD_HEIGHT),
            )
            if (initialPrompt != null) {
                EtaTextButton(text = stringResource(R.string.model_prompts_delete), onClick = onDelete)
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.page_save_fadf24),
                confirmEnabled = normalized != null && prompt.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onSave(normalized ?: return@MiuixDialogActions, prompt.trim()) },
            )
        }
    }
}

private fun promptPreview(prompt: String): String {
    val line = prompt.replace(WHITESPACE, " ").trim()
    return if (line.length > PREVIEW_CHARS) line.take(PREVIEW_CHARS) + "..." else line
}

/** 列表里只显示提示词开头的字数。 */
private const val PREVIEW_CHARS = 40

/** 与价格表新增定价的模型 ID 长度一致。 */
private const val MAX_PATTERN_CHARS = 120

private val PROMPT_FIELD_HEIGHT = 180.dp
private val WHITESPACE = Regex("\\s+")
