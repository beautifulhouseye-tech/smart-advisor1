package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.local.entity.DedupKeyEntity
import com.example.engine.ConversationMemoryManager
import com.example.engine.IdempotencyGuard
import com.example.engine.PendingAccessibilityReply
import com.example.engine.PendingReplyCoordinator
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

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
        memoryManagerOverride: ConversationMemoryManager? = null
    ): Boolean {
        val pkg = eventPackage?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        val root = rootNode ?: return false

        val rootPkg = (rootPackageOverride ?: root.packageName?.toString())?.trim().orEmpty()
        if (rootPkg.isNotEmpty() && rootPkg != pkg) return false

        val pending = coordinatorOverride.peekValidForPackage(pkg) ?: return false
        if (pending.targetPackage != pkg) return false

        // I4: TTL > 30s -> reject & drop
        if (clockMs() - pending.createdAtMs > PendingReplyCoordinator.REPLY_EXPIRY_MS) {
            coordinatorOverride.consumeAtomic(pending.id)
            (memoryManagerOverride ?: conversationMemoryManager)?.discardPendingHandoffTurn(pending.msgKey)
            return false
        }

        // I4: dedup_keys SEND_SENT / DELIVERY_UNKNOWN / COMPLETED -> reject & drop
        if (pending.msgKey.isNotBlank()) {
            val dedupEntry = idempotencyGuardOverride.getEntry(pending.msgKey)
            if (dedupEntry != null && isBlockedDedupState(dedupEntry)) {
                coordinatorOverride.consumeAtomic(pending.id)
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
            val imageSent = runCatching {
                performImageFlow(root, Uri.parse(pendingUri), pending.replyText, pending.msgKey)
            }.isSuccess
            return imageSent
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

        val refreshedRoot = runCatching { rootInActiveWindow }.getOrNull() ?: root
        val sendNode = findSendButtonNode(refreshedRoot)
        if (sendNode != null && sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            coordinatorOverride.markServiceActivity()
            // I6: Update sendState = SEND_SENT & STATE_COMPLETED ONLY upon successful Accessibility send
            if (pending.msgKey.isNotBlank()) {
                idempotencyGuardOverride.updateSendState(pending.msgKey, DedupKeyEntity.SEND_SENT)
                idempotencyGuardOverride.markCompleted(pending.msgKey)
                (memoryManagerOverride ?: conversationMemoryManager)?.confirmHandoffTurnSent(pending.msgKey)
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
        coordinator.enqueue(
            PendingAccessibilityReply(
                msgKey = msgKey,
                targetPackage = packageName,
                senderName = senderName,
                replyText = caption,
                imageUri = imageUri.toString()
            )
        )
        mainHandler.post {
            launchPackage(packageName)
            mainHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                if (senderName.isBlank() || matchesOpenConversation(root, senderName)) {
                    performImageFlow(root, imageUri, caption, msgKey)
                }
            }, 700L)
        }
    }

    private fun launchPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launchIntent) }
    }

    private fun performImageFlow(
        rootNode: AccessibilityNodeInfo,
        imageUri: Uri,
        caption: String,
        msgKey: String = ""
    ) {
        // 2. Click attachment icon (findByViewId / description)
        val attachNode = findByViewId(
            rootNode,
            listOf("attach", "clip", "input_attach", "attachment", "btn_attach")
        ) ?: findByText(rootNode, listOf("attach", "مرفق", "إرفاق"))
        attachNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 3. Select Gallery option (findByText)
        mainHandler.postDelayed({
            val galleryRoot = rootInActiveWindow ?: return@postDelayed
            val galleryNode = findByText(
                galleryRoot,
                listOf("gallery", "photos", "المعرض", "معرض", "صور")
            )
            galleryNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // 4. Select image thumbnail (findByViewId + CLICK)
            mainHandler.postDelayed({
                val pickerRoot = rootInActiveWindow ?: return@postDelayed
                val mediaNode = findByViewId(
                    pickerRoot,
                    listOf("thumbnail", "media_item", "grid", "image", "photo")
                )
                mediaNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 5. Optional caption & click Send ("إرسال" + CLICK)
                mainHandler.postDelayed({
                    val sendRoot = rootInActiveWindow ?: return@postDelayed
                    if (caption.isNotBlank()) {
                        findEditableInputNode(sendRoot)?.let { editNode ->
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    caption
                                )
                            }
                            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }
                    val sendButton = findByText(sendRoot, listOf("إرسال", "send"))
                        ?: findSendButtonNode(sendRoot)
                    if (sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        coordinator.markServiceActivity()
                        if (msgKey.isNotBlank()) {
                            serviceScope.launch {
                                idempotencyGuard.updateSendState(msgKey, DedupKeyEntity.SEND_SENT)
                                idempotencyGuard.markCompleted(msgKey)
                            }
                        }
                    }
                }, 600L)
            }, 600L)
        }, 450L)
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

    private fun findByViewId(
        node: AccessibilityNodeInfo,
        idFragments: List<String>
    ): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && node.isVisibleToUser && idFragments.any { viewId.contains(it) }) {
            return node
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
            var clickableCandidate: AccessibilityNodeInfo? = node
            while (clickableCandidate != null && !clickableCandidate.isClickable) {
                clickableCandidate = clickableCandidate.parent
            }
            if (clickableCandidate != null) return clickableCandidate
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByText(child, keywords)
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
        private val BIDI_CONTROL_REGEX = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
