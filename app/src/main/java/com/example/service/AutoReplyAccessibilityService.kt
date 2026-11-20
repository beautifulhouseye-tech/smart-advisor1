package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.local.entity.DedupKeyEntity
import com.example.engine.ConversationMemoryManager
import com.example.engine.IdempotencyGuard
import com.example.engine.ImageDeliveryState
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
import com.example.media.ProductImageCacheManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoReplyAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AccessibilityEntryPoint {
        fun pendingReplyCoordinator(): PendingReplyCoordinator
        fun idempotencyGuard(): IdempotencyGuard
        fun conversationMemoryManager(): ConversationMemoryManager
        fun productImageCacheManager(): ProductImageCacheManager
    }

    internal enum class PostSendStatus {
        CONFIRMED,
        FAILED_PRE_SEND,
        AMBIGUOUS
    }

    private val entryPoint: AccessibilityEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            AccessibilityEntryPoint::class.java
        )
    }

    private val coordinator: PendingReplyCoordinator by lazy {
        entryPoint.pendingReplyCoordinator()
    }

    private val idempotencyGuard: IdempotencyGuard by lazy {
        entryPoint.idempotencyGuard()
    }

    private val conversationMemoryManager: ConversationMemoryManager? by lazy {
        runCatching { entryPoint.conversationMemoryManager() }.getOrNull()
    }

    private val productImageCacheManager: ProductImageCacheManager? by lazy {
        runCatching { entryPoint.productImageCacheManager() }.getOrNull()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        coordinator.setAccessibilityConnected(true)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        coordinator.setAccessibilityConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.trim() ?: return
        if (coordinator.peekValidForPackage(pkg) == null) return
        val rootNode = rootInActiveWindow ?: return
        val eventTitle = event.text?.firstOrNull()

        serviceScope.launch {
            handleEventInternal(
                eventPackage = pkg,
                rootNode = rootNode,
                eventTitleFallback = eventTitle
            )
        }
    }

    internal suspend fun handleEventInternal(
        eventPackage: String?,
        rootNode: AccessibilityNodeInfo?,
        coordinatorOverride: PendingReplyCoordinator = coordinator,
        idempotencyGuardOverride: IdempotencyGuard = idempotencyGuard,
        rootPackageOverride: String? = null,
        eventTitleFallback: CharSequence? = null,
        windowTitleOverride: CharSequence? = null,
        clockMs: () -> Long = { System.currentTimeMillis() },
        sendButtonDelayMs: Long = 220L,
        memoryManagerOverride: ConversationMemoryManager? = null,
        cacheManagerOverride: ProductImageCacheManager? = null,
        stepDelayMs: Long = 450L,
        postSendVerifyDelayMs: Long = 250L,
        rootProvider: (() -> AccessibilityNodeInfo?)? = null,
        postSendVerifier: ((AccessibilityNodeInfo?) -> PostSendStatus)? = null
    ): Boolean {
        val pkg = eventPackage?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        val root = rootNode ?: return false

        val rootPkg = (rootPackageOverride ?: root.packageName?.toString())?.trim().orEmpty()
        if (rootPkg.isNotEmpty() && rootPkg != pkg) return false

        val pending = coordinatorOverride.peekValidForPackage(pkg) ?: return false
        if (pending.targetPackage != pkg) return false

        val resolvedMemoryManager = memoryManagerOverride ?: conversationMemoryManager
        val resolvedCacheManager = cacheManagerOverride ?: productImageCacheManager

        // I4: TTL > 30s -> reject & drop
        if (clockMs() - pending.createdAtMs > PendingReplyCoordinator.REPLY_EXPIRY_MS) {
            coordinatorOverride.consumeAtomic(pending.id)
            resolvedMemoryManager?.discardPendingHandoffTurn(pending.msgKey)
            resolvedCacheManager?.cleanupStagedMedia(pending.mediaStoreUri)
            return false
        }

        // I4: dedup_keys SEND_SENT / DELIVERY_UNKNOWN / COMPLETED -> reject & drop
        if (pending.msgKey.isNotBlank()) {
            val dedupEntry = idempotencyGuardOverride.getEntry(pending.msgKey)
            if (dedupEntry != null && isBlockedDedupState(dedupEntry)) {
                coordinatorOverride.consumeAtomic(pending.id)
                resolvedCacheManager?.cleanupStagedMedia(pending.mediaStoreUri)
                return false
            }
        }

        // I4: Open conversation title must match senderName (best-effort; unverified -> do not write)
        if (!matchesOpenConversation(root, pending.senderName, eventTitleFallback, windowTitleOverride)) {
            return false
        }

        val pendingUri = pending.imageUri
        if (!pendingUri.isNullOrBlank()) {
            // I5: Atomic single-use removal BEFORE acting
            if (!coordinatorOverride.consumeAtomic(pending.id)) return false
            return performProductImageFlow(
                pending = pending,
                initialRoot = root,
                coordinatorOverride = coordinatorOverride,
                idempotencyGuardOverride = idempotencyGuardOverride,
                memoryManagerOverride = resolvedMemoryManager,
                cacheManagerOverride = resolvedCacheManager,
                stepDelayMs = stepDelayMs,
                postSendVerifyDelayMs = postSendVerifyDelayMs,
                rootProvider = rootProvider ?: { runCatching { rootInActiveWindow }.getOrNull() },
                postSendVerifier = postSendVerifier
            )
        }

        val editNode = findEditableInputNode(root) ?: return false

        // I5: Atomic removal BEFORE writing; if taken concurrently, abort with zero writes
        if (!coordinatorOverride.consumeAtomic(pending.id)) {
            return false
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                pending.replyText
            )
        }
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val textApplied = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textApplied) return false

        if (sendButtonDelayMs > 0L) {
            delay(sendButtonDelayMs)
        }

        val refreshedRoot = rootProvider?.invoke()
            ?: runCatching { rootInActiveWindow }.getOrNull()
            ?: root
        val sendNode = findSendButtonNode(refreshedRoot)
        if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            coordinatorOverride.markServiceActivity()
            // I6: Update sendState = SEND_SENT & STATE_COMPLETED ONLY upon successful Accessibility send
            if (pending.msgKey.isNotBlank()) {
                idempotencyGuardOverride.updateSendState(pending.msgKey, DedupKeyEntity.SEND_SENT)
                idempotencyGuardOverride.markCompleted(pending.msgKey)
                resolvedMemoryManager?.confirmHandoffTurnSent(pending.msgKey)
            }
            return true
        }
        return false
    }

    fun sendReply(
        packageName: String,
        message: String,
        senderName: String = "",
        msgKey: String = ""
    ) {
        val reply = PendingAccessibilityReply(
            msgKey = msgKey,
            targetPackage = packageName,
            senderName = senderName,
            replyText = message
        )
        coordinator.enqueue(reply)
        serviceScope.launch {
            val currentRoot = rootInActiveWindow
            if (currentRoot?.packageName?.toString() == packageName &&
                matchesOpenConversation(currentRoot, senderName)
            ) {
                val handled = handleEventInternal(
                    eventPackage = packageName,
                    rootNode = currentRoot
                )
                if (handled) return@launch
            }
            launchPackage(packageName)
        }
    }

    fun sendImage(
        packageName: String,
        imageUri: Uri,
        caption: String = "",
        senderName: String = "",
        msgKey: String = ""
    ) {
        val pending = PendingAccessibilityReply(
            msgKey = msgKey,
            targetPackage = packageName,
            senderName = senderName,
            replyText = caption,
            imageUri = imageUri.toString(),
            requireCaptionBeforeSend = caption.isNotBlank()
        )
        coordinator.enqueue(pending)
        serviceScope.launch {
            launchPackage(packageName)
            delay(700L)
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@launch
            if (senderName.isNotBlank()) {
                handleEventInternal(
                    eventPackage = packageName,
                    rootNode = root
                )
            } else if (coordinator.consumeAtomic(pending.id)) {
                performProductImageFlow(
                    pending = pending,
                    initialRoot = root,
                    coordinatorOverride = coordinator,
                    idempotencyGuardOverride = idempotencyGuard,
                    memoryManagerOverride = conversationMemoryManager,
                    cacheManagerOverride = productImageCacheManager,
                    stepDelayMs = 450L,
                    postSendVerifyDelayMs = 250L,
                    rootProvider = { runCatching { rootInActiveWindow }.getOrNull() },
                    postSendVerifier = null
                )
            }
        }
    }

    private fun launchPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launchIntent) }
    }

    private suspend fun performProductImageFlow(
        pending: PendingAccessibilityReply,
        initialRoot: AccessibilityNodeInfo,
        coordinatorOverride: PendingReplyCoordinator,
        idempotencyGuardOverride: IdempotencyGuard,
        memoryManagerOverride: ConversationMemoryManager?,
        cacheManagerOverride: ProductImageCacheManager?,
        stepDelayMs: Long,
        postSendVerifyDelayMs: Long,
        rootProvider: () -> AccessibilityNodeInfo?,
        postSendVerifier: ((AccessibilityNodeInfo?) -> PostSendStatus)?
    ): Boolean {
        fun recordState(state: ImageDeliveryState) {
            if (pending.msgKey.isNotBlank()) {
                coordinatorOverride.recordDeliveryState(pending.msgKey, state)
            }
        }

        suspend fun failPreSend(reason: String): Boolean {
            Log.w(TAG, "Product image pre-send failure: $reason")
            recordState(ImageDeliveryState.FAILED)
            if (pending.msgKey.isNotBlank()) {
                idempotencyGuardOverride.updateSendState(
                    pending.msgKey,
                    DedupKeyEntity.SEND_FAILED_CONFIRMED
                )
            }
            memoryManagerOverride?.discardPendingHandoffTurn(pending.msgKey)
            runCatching { cacheManagerOverride?.cleanupStagedMedia(pending.mediaStoreUri) }
            runCatching { pending.onPreSendImageFailure?.invoke() }
                .onFailure { err -> Log.e(TAG, "Pre-send text fallback invocation failed", err) }
            return false
        }

        // Step 1: Click attachment icon -> OPENING_ATTACHMENTS
        recordState(ImageDeliveryState.OPENING_ATTACHMENTS)
        val attachNode = findByViewId(
            initialRoot,
            listOf("attach", "clip", "input_attach", "attachment", "btn_attach")
        ) ?: findByText(initialRoot, listOf("attach", "مرفق", "إرفاق"))

        val attachClicked = runCatching {
            attachNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }.getOrDefault(false)
        if (!attachClicked) {
            return failPreSend("تعذر النقر على زر المرفقات")
        }

        if (stepDelayMs > 0L) delay(stepDelayMs)

        // Step 2: Select Gallery option -> IMAGE_SELECTED (picker opened)
        val galleryRoot = rootProvider() ?: initialRoot
        val galleryNode = findByText(
            galleryRoot,
            listOf("gallery", "photos", "المعرض", "معرض", "صور")
        ) ?: findByViewId(galleryRoot, listOf("gallery", "pick_from_gallery", "photos"))
        val galleryClicked = runCatching {
            galleryNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }.getOrDefault(false)
        if (!galleryClicked) {
            return failPreSend("تعذر فتح المعرض لاختيار صورة المنتج")
        }

        if (stepDelayMs > 0L) delay(stepDelayMs)

        // Step 3: Select image thumbnail -> IMAGE_SELECTED
        val pickerRoot = rootProvider() ?: galleryRoot
        val mediaNode = findByViewId(
            pickerRoot,
            listOf("thumbnail", "media_item", "grid", "image", "photo", "thumb")
        ) ?: findByText(pickerRoot, listOf("photo", "image", "صورة", "thumbnail"))
        val mediaClicked = runCatching {
            mediaNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }.getOrDefault(false)
        if (!mediaClicked) {
            return failPreSend("تعذر اختيار صورة المنتج من المعرض")
        }
        recordState(ImageDeliveryState.IMAGE_SELECTED)

        if (stepDelayMs > 0L) delay(stepDelayMs)

        // Step 4: CRITICAL SAFETY GATE — Enter caption BEFORE clicking Send
        val previewRoot = rootProvider() ?: pickerRoot
        val needsCaption = pending.requireCaptionBeforeSend || pending.replyText.isNotBlank()
        if (needsCaption) {
            val captionNode = findCaptionInputNode(previewRoot)
                ?: return failPreSend("لم يتم العثور على حقل التعليق المصاحب للصورة؛ تم إلغاء إرسال الصورة")
            val focused = runCatching {
                captionNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }.getOrDefault(false)
            if (!focused && captionNode.isFocusable) {
                Log.d(TAG, "Caption focus returned false, attempting SET_TEXT directly")
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    pending.replyText
                )
            }
            val captionApplied = runCatching {
                captionNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
            if (!captionApplied) {
                return failPreSend("فشل إدخال التعليق المصاحب للصورة؛ لن يتم إرسال صورة بلا نص")
            }
            recordState(ImageDeliveryState.CAPTION_ENTERED)
            if (stepDelayMs > 0L) delay(stepDelayMs)
        }

        // Step 5: Find and click Send button -> SENDING -> SENT / FAILED / DELIVERY_UNKNOWN
        val sendRoot = rootProvider() ?: previewRoot
        val sendButton = findSendButtonNode(sendRoot)
            ?: findByText(sendRoot, listOf("إرسال", "ارسال", "send"))
            ?: return failPreSend("لم يتم العثور على زر الإرسال بعد إدخال التعليق")

        recordState(ImageDeliveryState.SENDING)
        val clickAttempt = runCatching {
            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (clickAttempt.isFailure) {
            return handleAmbiguousImageSend(
                pending = pending,
                coordinatorOverride = coordinatorOverride,
                idempotencyGuardOverride = idempotencyGuardOverride,
                memoryManagerOverride = memoryManagerOverride,
                cacheManagerOverride = cacheManagerOverride,
                reason = clickAttempt.exceptionOrNull()?.message ?: "استثناء بعد الضغط على زر الإرسال"
            )
        }
        if (clickAttempt.getOrDefault(false) != true) {
            return failPreSend("تعذر تفعيل زر إرسال الصورة")
        }

        if (postSendVerifyDelayMs > 0L) {
            delay(postSendVerifyDelayMs)
        }

        if (postSendVerifier != null) {
            val verifyRoot = runCatching { rootProvider() }.getOrNull() ?: sendRoot
            when (postSendVerifier.invoke(verifyRoot)) {
                PostSendStatus.CONFIRMED -> Unit
                PostSendStatus.FAILED_PRE_SEND -> {
                    return failPreSend("فشل مؤكد في شاشة الإرسال قبل التسليم")
                }
                PostSendStatus.AMBIGUOUS -> {
                    return handleAmbiguousImageSend(
                        pending = pending,
                        coordinatorOverride = coordinatorOverride,
                        idempotencyGuardOverride = idempotencyGuardOverride,
                        memoryManagerOverride = memoryManagerOverride,
                        cacheManagerOverride = cacheManagerOverride,
                        reason = "نتيجة إرسال الصورة غير مؤكدة بعد النقر على الإرسال"
                    )
                }
            }
        }

        recordState(ImageDeliveryState.SENT)
        coordinatorOverride.markServiceActivity()
        if (pending.msgKey.isNotBlank()) {
            idempotencyGuardOverride.updateSendState(pending.msgKey, DedupKeyEntity.SEND_SENT)
            idempotencyGuardOverride.markCompleted(pending.msgKey)
            memoryManagerOverride?.confirmHandoffTurnSent(pending.msgKey)
        }
        runCatching { cacheManagerOverride?.cleanupStagedMedia(pending.mediaStoreUri) }
        return true
    }

    private suspend fun handleAmbiguousImageSend(
        pending: PendingAccessibilityReply,
        coordinatorOverride: PendingReplyCoordinator,
        idempotencyGuardOverride: IdempotencyGuard,
        memoryManagerOverride: ConversationMemoryManager?,
        cacheManagerOverride: ProductImageCacheManager?,
        reason: String
    ): Boolean {
        Log.e(TAG, "Product image send ambiguous -> DELIVERY_UNKNOWN (blocking retries & fallback): $reason")
        if (pending.msgKey.isNotBlank()) {
            coordinatorOverride.recordDeliveryState(pending.msgKey, ImageDeliveryState.DELIVERY_UNKNOWN)
            idempotencyGuardOverride.markDeliveryUnknown(pending.msgKey)
        }
        memoryManagerOverride?.discardPendingHandoffTurn(pending.msgKey)
        runCatching { cacheManagerOverride?.cleanupStagedMedia(pending.mediaStoreUri) }
        return false
    }

    internal fun matchesOpenConversation(
        rootNode: AccessibilityNodeInfo,
        senderName: String,
        eventTitleFallback: CharSequence? = null,
        windowTitleOverride: CharSequence? = null
    ): Boolean {
        val expected = normalizeTitle(senderName)
        if (expected.isEmpty()) return false

        val explicitTitleTexts = mutableListOf<String>()
        collectHeaderTitleTexts(rootNode, explicitTitleTexts)

        val candidates = if (explicitTitleTexts.isNotEmpty()) {
            explicitTitleTexts
        } else {
            val windowTitle = windowTitleOverride?.toString()
                ?: runCatching { rootNode.window?.title?.toString() }.getOrNull()
            listOfNotNull(windowTitle, eventTitleFallback?.toString())
        }

        val normalizedCandidates = candidates
            .map { normalizeTitle(it) }
            .filter { it.isNotEmpty() }

        if (normalizedCandidates.isEmpty()) {
            return false
        }

        return normalizedCandidates.any { candidate ->
            candidate == expected ||
                candidate.contains(expected) ||
                expected.contains(candidate)
        }
    }

    private fun collectHeaderTitleTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isVisibleToUser && !node.isEditable && isTitleViewId(viewId)) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) out += text
            if (desc.isNotEmpty() && desc != text) out += desc
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHeaderTitleTexts(child, out)
        }
    }

    private fun isTitleViewId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return TITLE_ID_FRAGMENTS.any { viewId.contains(it) } ||
            viewId.endsWith(":id/title") ||
            viewId.endsWith(":id/name")
    }

    internal fun isBlockedDedupState(entry: DedupKeyEntity): Boolean {
        if (entry.state == DedupKeyEntity.STATE_COMPLETED ||
            entry.state == DedupKeyEntity.STATE_DELIVERY_UNKNOWN ||
            entry.sendState == DedupKeyEntity.SEND_SENT ||
            entry.sendState == DedupKeyEntity.SEND_DELIVERY_UNKNOWN
        ) {
            return true
        }
        // SEND_IN_FLIGHT, SEND_NONE, and SEND_HANDOFF remain eligible for Accessibility consumption
        return false
    }

    private fun normalizeTitle(raw: String): String {
        return raw.replace(BIDI_CONTROL_REGEX, "")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase()
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) {
            current = current.parent
        }
        return current
    }

    private fun findByViewId(
        node: AccessibilityNodeInfo,
        idFragments: List<String>
    ): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isVisibleToUser && idFragments.any { viewId.contains(it) }) {
            clickableSelfOrParent(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByViewId(child, idFragments)
            if (found != null) return found
        }
        return null
    }

    private fun findByText(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val matches = keywords.any { kw -> text.contains(kw) || desc.contains(kw) }
        if (matches && node.isVisibleToUser) {
            clickableSelfOrParent(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByText(child, keywords)
            if (found != null) return found
        }
        return null
    }

    private fun findCaptionInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findEditableByIdOrHint(root, CAPTION_ID_OR_HINT_FRAGMENTS)
            ?: findEditableInputNode(root)
    }

    private fun findEditableByIdOrHint(
        node: AccessibilityNodeInfo,
        fragments: List<String>
    ): AccessibilityNodeInfo? {
        val isEditableField = node.isEditable || node.className?.toString()?.contains("EditText") == true
        if (isEditableField && node.isVisibleToUser) {
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            if (fragments.any { viewId.contains(it) || desc.contains(it) || text.contains(it) }) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableByIdOrHint(child, fragments)
            if (found != null) return found
        }
        return null
    }

    private fun findEditableInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            if (node.isVisibleToUser) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableInputNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButtonNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()

        val matchesKnownSendId = viewId.endsWith(":id/send") ||
            viewId.endsWith(":id/chat_send_button") ||
            viewId.contains("send_button") ||
            viewId.contains("btn_send")

        val matchesSendLabel = desc.contains("send") ||
            desc.contains("إرسال") ||
            desc.contains("ارسال") ||
            text == "send" ||
            text == "إرسال"

        if (node.isClickable && node.isVisibleToUser && (matchesKnownSendId || matchesSendLabel)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButtonNode(child)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "AutoReplyAccessibility"

        @Volatile
        var activeInstance: AutoReplyAccessibilityService? = null
            private set

        private val TITLE_ID_FRAGMENTS = listOf(
            "conversation_contact_name",
            "contact_name",
            "chat_title",
            "toolbar_title",
            "action_bar_title",
            "header_title",
            "recipient_name",
            "user_name",
            "conversation_title",
            "chat_name"
        )
        private val CAPTION_ID_OR_HINT_FRAGMENTS = listOf(
            "caption",
            "add_caption",
            "media_caption",
            "comment",
            "entry",
            "إضافة تعليق",
            "تعليق"
        )
        private val BIDI_CONTROL_REGEX = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

