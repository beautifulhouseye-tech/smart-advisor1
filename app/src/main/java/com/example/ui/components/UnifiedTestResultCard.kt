package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.network.ResponseOutcomeAnalyzer.ResponseStatus

data class UnifiedTestResultCardModel(
    val status: ResponseStatus,
    val title: String,
    val statusCode: Int,
    val durationMs: Long,
    val serverMessage: String,
    val sourceLabel: String = "",
    val trustLabel: String = "",
    val extractedText: String = "",
    val reasonSummary: String = "",
    val guidance: String = "",
    val rawSnippet: String = "",
    val truncated: Boolean = false
)

/**
 * Shared test result card used by both Webhook (`ServerScreen`) and Smart Advisor (`SaSettingsScreen`).
 *
 * Visual semantics:
 * - SUCCESS -> Emerald primary palette
 * - WARNING -> Amber tertiary palette
 * - FAILURE -> Red error palette
 *
 * Keeps Arabic explanatory prose strictly RTL in system typography, and places raw JSON / excerpts
 * inside a separate LTR Monospace horizontally scrollable container.
 */
@Composable
fun UnifiedTestResultCard(
    model: UnifiedTestResultCardModel,
    modifier: Modifier = Modifier,
    cardTestTag: String = "test_result_card"
) {
    val containerColor = when (model.status) {
        ResponseStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ResponseStatus.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.60f)
        ResponseStatus.FAILURE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    val contentTint = when (model.status) {
        ResponseStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ResponseStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        ResponseStatus.FAILURE -> MaterialTheme.colorScheme.error
    }
    val statusIcon = when (model.status) {
        ResponseStatus.SUCCESS -> Icons.Filled.CheckCircle
        ResponseStatus.WARNING -> Icons.Filled.Warning
        ResponseStatus.FAILURE -> Icons.Filled.Error
    }
    val statusBadgeText = when (model.status) {
        ResponseStatus.SUCCESS -> "SUCCESS"
        ResponseStatus.WARNING -> "WARNING"
        ResponseStatus.FAILURE -> "FAILURE"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(cardTestTag),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: Status Icon + Arabic title | Code + Latency badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusBadgeText,
                        tint = contentTint
                    )
                    Text(
                        text = BidiDisplayFormatter.isolateTokensInRtlText(model.title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDirection = TextDirection.Rtl
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = contentTint
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val codeText = if (model.statusCode > 0) "HTTP ${model.statusCode}" else "LOCAL"
                        Text(
                            text = "$codeText • ${model.durationMs} ms",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                textDirection = TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("${cardTestTag}_metrics")
                        )
                    }
                }
            }

            // Arabic explanatory details section (RTL, normal system font)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (model.sourceLabel.isNotBlank() || model.trustLabel.isNotBlank()) {
                        val sourceInfo = buildString {
                            if (model.sourceLabel.isNotBlank()) {
                                append("المصدر: ${model.sourceLabel}")
                            }
                            if (model.trustLabel.isNotBlank()) {
                                if (isNotEmpty()) append(" • ")
                                append("درجة الثقة: ${model.trustLabel}")
                            }
                        }
                        Text(
                            text = BidiDisplayFormatter.isolateTokensInRtlText(sourceInfo),
                            style = MaterialTheme.typography.labelLarge.copy(
                                textDirection = TextDirection.Rtl
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("${cardTestTag}_source")
                        )
                    }

                    if (model.serverMessage.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "رسالة الخادم:",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        textDirection = TextDirection.Rtl
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = contentTint
                                )
                                Text(
                                    text = BidiDisplayFormatter.isolateTokensInRtlText(model.serverMessage),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = TextDirection.ContentOrRtl
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("${cardTestTag}_message")
                                )
                            }
                        }
                    }

                    if (model.extractedText.isNotBlank() && model.extractedText.trim() != model.serverMessage.trim()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = BidiDisplayFormatter.isolateTokensInRtlText("النص المستخرج للـ AI:"),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        textDirection = TextDirection.Rtl
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = BidiDisplayFormatter.isolateTokensInRtlText(model.extractedText),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = TextDirection.ContentOrRtl
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("${cardTestTag}_extracted")
                                )
                            }
                        }
                    }

                    if (model.reasonSummary.isNotBlank() || model.guidance.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (model.reasonSummary.isNotBlank()) {
                                    Text(
                                        text = BidiDisplayFormatter.isolateTokensInRtlText("السبب: ${model.reasonSummary}"),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDirection = TextDirection.Rtl
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (model.guidance.isNotBlank()) {
                                    Text(
                                        text = BidiDisplayFormatter.isolateTokensInRtlText("الإجراء المقترح: ${model.guidance}"),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDirection = TextDirection.Rtl
                                        ),
                                        fontWeight = FontWeight.Medium,
                                        color = contentTint,
                                        modifier = Modifier.testTag("${cardTestTag}_guidance")
                                    )
                                }
                            }
                        }
                    }

                    if (model.truncated) {
                        Text(
                            text = "[تم اقتطاع الاستجابة للحد الأقصى المسموح]",
                            style = MaterialTheme.typography.labelSmall.copy(
                                textDirection = TextDirection.Rtl
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Separate LTR Monospace Horizontally-Scrollable Raw Snippet / JSON Box
            if (model.rawSnippet.isNotBlank()) {
                val formattedRaw = remember(model.rawSnippet) {
                    BidiDisplayFormatter.formatCodeExcerptForLtr(model.rawSnippet)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = "مقتطف الاستجابة الخام:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                textDirection = TextDirection.Rtl
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = formattedRaw,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection = TextDirection.Ltr
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    softWrap = false,
                                    modifier = Modifier.testTag("${cardTestTag}_raw_excerpt")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
