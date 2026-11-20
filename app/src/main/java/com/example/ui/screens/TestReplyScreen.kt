package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.ReplySourceCategory
import com.example.engine.ResponseType
import com.example.ui.viewmodel.TestChatMessage
import com.example.ui.viewmodel.TestReplyUiState
import com.example.ui.viewmodel.TestReplyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TestReplyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TestReplyViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshCapabilitiesSummary()
    }

    TestReplyContent(
        uiState = uiState,
        onBack = onBack,
        onSendMessage = viewModel::sendTestMessage,
        onToggleGroupMode = viewModel::toggleGroupMode,
        onReset = viewModel::resetConversation,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestReplyContent(
    uiState: TestReplyUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleGroupMode: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val submitCurrentInput = {
        val textToSend = inputText.trim()
        if (textToSend.isNotEmpty() && !uiState.isEvaluating) {
            inputText = ""
            onSendMessage(textToSend)
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.isEvaluating) {
        if (uiState.messages.isNotEmpty()) {
            val targetIndex = uiState.messages.size - 1 + if (uiState.isEvaluating) 1 else 0
            listState.animateScrollToItem(targetIndex.coerceAtLeast(0))
        }
    }

    val canvasTopColor = MaterialTheme.colorScheme.background
    val canvasBottomColor = MaterialTheme.colorScheme.surface
    val accentGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = canvasTopColor,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("test_reply_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.test_reply_back_desc)
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = stringResource(R.string.test_reply_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("test_reply_title")
                            )
                            Text(
                                text = if (uiState.isGroupMode) {
                                    "وضع المجموعة • ${uiState.activePipelineSummary}"
                                } else {
                                    uiState.activePipelineSummary
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onToggleGroupMode(!uiState.isGroupMode) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("test_reply_group_toggle")
                    ) {
                        Icon(
                            imageVector = if (uiState.isGroupMode) Icons.Filled.Groups else Icons.Filled.Person,
                            contentDescription = if (uiState.isGroupMode) {
                                stringResource(R.string.test_reply_mode_group)
                            } else {
                                stringResource(R.string.test_reply_mode_private)
                            },
                            tint = if (uiState.isGroupMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("test_reply_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.test_reply_reset_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatComposerBar(
                inputText = inputText,
                isEvaluating = uiState.isEvaluating,
                onInputChange = { inputText = it },
                onSend = submitCurrentInput
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(canvasTopColor, canvasBottomColor)
                    )
                )
                .drawBehind {
                    drawCircle(
                        color = accentGlow,
                        radius = size.minDimension * 0.45f,
                        center = Offset(size.width * 0.2f, size.height * 0.25f)
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .testTag("test_reply_message_list"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "day_header") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.testTag("test_reply_day_chip")
                        ) {
                            Text(
                                text = stringResource(R.string.test_reply_today),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                items(uiState.messages, key = { it.id }) { msg ->
                    ChatMessageBubble(message = msg)
                }

                item(key = "evaluating_indicator") {
                    AnimatedVisibility(visible = uiState.isEvaluating) {
                        EvaluatingReplyBubble()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(message: TestChatMessage) {
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (message.isFromUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }
    val bubbleColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val timeText = remember(message.timestampMs) {
        formatArabicTime(message.timestampMs)
    }
    var showTraceDetails by remember(message.id) { mutableStateOf(false) }
    val hasCollaborationTrace = !message.isFromUser && !message.isWelcome && (
        message.usedNewApi ||
            message.switchedToQwen ||
            message.calledSmartAdvisor ||
            message.calledWebhook ||
            message.verifiedStoreDataUsed ||
            message.memoryLoaded ||
            message.memoryUpdated ||
            message.needsHuman ||
            message.matchedRuleKeyword.isNotBlank() ||
            message.stageLatenciesMs.isNotEmpty() ||
            !message.redactedError.isNullOrBlank()
        )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (message.isFromUser) "chat_bubble_user" else "chat_bubble_bot"),
        contentAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            tonalElevation = if (message.isFromUser) 2.dp else 1.dp,
            border = if (message.isFromUser) null else BorderStroke(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            ),
            modifier = Modifier.widthIn(min = 120.dp, max = 340.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (!message.isFromUser && !message.isWelcome && !message.sourceLabel.isNullOrBlank()) {
                    SourceBadgePill(
                        label = message.sourceLabel,
                        category = message.sourceCategory ?: ReplySourceCategory.NO_MATCH
                    )
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )

                if (!message.imageUri.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = "صورة مرفقة",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = message.productMessage?.productName?.takeIf { it.isNotBlank() }
                                    ?.let { "صورة المنتج: $it" }
                                    ?: message.imageUri,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (hasCollaborationTrace || message.responseType != ResponseType.TEXT_MESSAGE) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (message.responseType) {
                            ResponseType.PRODUCT_IMAGE -> TraceMiniChip("رسالة صورة + وصف", icon = Icons.Filled.Image)
                            ResponseType.CATEGORY_LINK -> TraceMiniChip("رابط تصنيف")
                            ResponseType.PRODUCT_LIST -> TraceMiniChip("قائمة منتجات")
                            ResponseType.PRODUCT_COMPARISON -> TraceMiniChip("مقارنة منتجات")
                            ResponseType.TEXT_MESSAGE -> Unit
                        }
                        if (message.usedNewApi) TraceMiniChip("New API")
                        if (message.switchedToQwen) TraceMiniChip("Fallback: Qwen")
                        if (message.calledSmartAdvisor) TraceMiniChip("أداة: Smart Advisor")
                        if (message.calledWebhook) TraceMiniChip("أداة: Webhook")
                        if (message.toolRoundsUsed > 0) TraceMiniChip("جولات: ${message.toolRoundsUsed}")
                        if (message.verifiedStoreDataUsed) TraceMiniChip("بيانات متجر مؤكدة")
                        if (message.memoryLoaded) TraceMiniChip("ذاكرة محملة")
                        if (message.memoryUpdated) TraceMiniChip("ذاكرة محدثة")
                        if (message.needsHuman) {
                            TraceMiniChip("تحويل لبشري", icon = Icons.Filled.SupportAgent)
                        }
                    }

                    TextButton(
                        onClick = { showTraceDetails = !showTraceDetails },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("test_reply_trace_toggle")
                    ) {
                        Text(
                            text = if (showTraceDetails) "إخفاء تفاصيل التتبع" else "عرض تفاصيل التتبع",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    AnimatedVisibility(visible = showTraceDetails) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (message.conversationKeyUsed.isNotBlank()) {
                                    Text(
                                        text = "مفتاح المحادثة: ${message.conversationKeyUsed}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.matchedRuleKeyword.isNotBlank()) {
                                    Text(
                                        text = "القاعدة المطابقة: ${message.matchedRuleKeyword}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.detectedIntent.isNotBlank()) {
                                    Text(
                                        text = "النية المكتشفة: ${message.detectedIntent}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.activeEngineLabel.isNotBlank()) {
                                    Text(
                                        text = "المحرك الفعلي: ${message.activeEngineLabel}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.invokedTools.isNotEmpty()) {
                                    Text(
                                        text = "الأدوات المستدعاة: ${message.invokedTools.joinToString(" + ")}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.saToolReason.isNotBlank()) {
                                    Text(
                                        text = "سبب Smart Advisor: ${message.saToolReason}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.verifiedDataSnippet.isNotBlank()) {
                                    Text(
                                        text = "بيانات المتجر: ${message.verifiedDataSnippet.take(180)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.calledWebhook) {
                                    val statusText = message.webhookStatusCode?.let { "HTTP $it" } ?: "غير متصل"
                                    val reasonPart = message.webhookToolReason.ifBlank { "استعلام خارجي" }
                                    Text(
                                        text = "أداة Webhook ($statusText | مصدر خارجي): $reasonPart",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.webhookDataSnippet.isNotBlank()) {
                                    Text(
                                        text = "نتيجة Webhook: ${message.webhookDataSnippet.take(180)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (message.stageLatenciesMs.isNotEmpty()) {
                                    val timingStr = message.stageLatenciesMs.entries
                                        .joinToString(" | ") { "${it.key}:${it.value}ms" }
                                    Text(
                                        text = "الزمن حسب المرحلة: $timingStr",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (!message.redactedError.isNullOrBlank()) {
                                    Text(
                                        text = "ملاحظة/خطأ منقّح: ${message.redactedError}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                if (!message.isFromUser && !message.isWelcome && message.evaluatedPath.isNotEmpty()) {
                    Text(
                        text = "المسار: ${message.evaluatedPath.joinToString(" ← ")} • ${message.latencyMs} مللي ثانية",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isFromUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (message.isFromUser) {
                        Icon(
                            imageVector = Icons.Filled.DoneAll,
                            contentDescription = "تم الإرسال للاختبار",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceMiniChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        shape = RoundedCornerShape(50.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceBadgePill(
    label: String,
    category: ReplySourceCategory
) {
    val (bgColor, fgColor) = when (category) {
        ReplySourceCategory.LOCAL_RULE,
        ReplySourceCategory.WILDCARD_RULE,
        ReplySourceCategory.MENU_REPLY ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) to MaterialTheme.colorScheme.primary
        ReplySourceCategory.AI_ENGINE,
        ReplySourceCategory.WEBHOOK,
        ReplySourceCategory.SMART_ADVISOR ->
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.secondary
        ReplySourceCategory.DRIVING_MODE,
        ReplySourceCategory.POLICY_BLOCKED ->
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.tertiary
        ReplySourceCategory.NO_MATCH,
        ReplySourceCategory.ERROR ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.16f) to MaterialTheme.colorScheme.error
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fgColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EvaluatingReplyBubble() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("evaluating_reply_bubble"),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "جاري فحص القواعد والرد الموحد...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatComposerBar(
    inputText: String,
    isEvaluating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.test_reply_input_hint),
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("test_reply_input")
            )

            FilledIconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isEvaluating,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .size(50.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("test_reply_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.test_reply_send_desc)
                )
            }
        }
    }
}

private fun formatArabicTime(timestampMs: Long): String {
    val raw = SimpleDateFormat("hh:mm a", Locale.US).format(Date(timestampMs))
    return raw.replace("AM", "ص").replace("PM", "م")
}
