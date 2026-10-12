package io.github.mangi.eta.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 等待用户回答的问题；Runtime 服务与 App 会话各自持有，回答后清除。 */
internal data class PendingQuestion(
    val runId: String,
    val questionId: String,
    val question: AgentUserQuestion,
)

/**
 * ask_user 的选项面板：App 输入框上方与状态胶囊共用。多题时逐题作答，最后一题提交全部选择；
 * 自由文字回答由各自宿主的输入框承接，面板只负责选项、跳过与提交。
 */
@Composable
internal fun AgentQuestionPanel(
    questionId: String,
    question: AgentUserQuestion,
    onSubmit: (List<List<String>>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    val count = question.items.size
    var step by remember(questionId) { mutableIntStateOf(0) }
    val selections = remember(questionId) {
        mutableStateListOf<List<String>>().apply { repeat(count) { add(emptyList()) } }
    }
    val item = question.items[step.coerceIn(0, count - 1)]
    val current = selections[step]
    val bodySize = if (compact) 13.sp else 15.sp
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.header.ifBlank { stringResource(R.string.question_title) },
                fontSize = if (compact) 11.sp else 12.sp,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (count > 1) {
                Text(
                    text = stringResource(R.string.question_progress, step + 1, count),
                    fontSize = if (compact) 11.sp else 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = item.question,
            fontSize = bodySize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        item.options.forEach { option ->
            val isSelected = option.label in current
            QuestionOption(
                option = option,
                isSelected = isSelected,
                compact = compact,
                onClick = {
                    selections[step] = when {
                        !item.multiSelect -> listOf(option.label)
                        isSelected -> current - option.label
                        else -> current + option.label
                    }
                },
            )
        }
        footer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EtaTextButton(
                text = stringResource(R.string.question_skip),
                onClick = onSkip,
                minHeight = if (compact) 34.dp else 40.dp,
            )
            val last = step >= count - 1
            EtaTextButton(
                text = stringResource(if (last) R.string.question_submit else R.string.question_next),
                onClick = { if (last) onSubmit(selections.toList()) else step += 1 },
                enabled = current.isNotEmpty(),
                minHeight = if (compact) 34.dp else 40.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun QuestionOption(
    option: AgentUserQuestion.Option,
    isSelected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val accent = MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) accent.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (isSelected) accent else MiuixTheme.colorScheme.surfaceContainerHigh, shape)
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                fontSize = if (compact) 13.sp else 14.sp,
                color = if (isSelected) accent else MiuixTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            )
            if (option.description.isNotBlank()) {
                Text(
                    text = option.description,
                    fontSize = if (compact) 11.sp else 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp),
            )
        }
    }
}
