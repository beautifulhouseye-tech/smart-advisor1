package com.example.engine

import com.example.data.local.dao.MenuReplyDao
import com.example.data.local.entity.MenuReplyEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MenuOption(
    val number: Int,
    val label: String,
    val response: String
)

@Singleton
class MenuReplyManager @Inject constructor(
    private val menuReplyDao: MenuReplyDao,
    private val json: Json
) {
    private val conversationMenus = ConcurrentHashMap<String, MenuReplyEntity>()

    fun buildMenuText(menu: MenuReplyEntity): String {
        val options = decodeOptions(menu.optionsJson).sortedBy { it.number }
        val builder = StringBuilder()
        if (menu.title.isNotBlank()) {
            builder.append(menu.title.trim()).append('\n')
        }
        options.forEach { opt ->
            builder.append("${opt.number}. ${opt.label}\n")
        }
        builder.append("\nأرسل رقم الخيار:")
        return builder.toString().trim()
    }

    fun handleReply(
        incomingMessage: String,
        currentMenu: MenuReplyEntity?
    ): String? {
        if (currentMenu == null) return null
        val number = incomingMessage.trim().toIntOrNull() ?: return null
        val options = decodeOptions(currentMenu.optionsJson)
        return options.find { it.number == number }?.response?.takeIf { it.isNotBlank() }
    }

    suspend fun processConversationMessage(
        conversationKey: String,
        incomingMessage: String
    ): String? {
        val normalized = incomingMessage.trim()
        if (normalized.isEmpty()) return null

        val activeMenu = conversationMenus[conversationKey]
        if (activeMenu != null) {
            val selectedResponse = handleReply(normalized, activeMenu)
            if (selectedResponse != null) {
                conversationMenus.remove(conversationKey)
                return selectedResponse
            }
        }

        val triggeredMenu = menuReplyDao.findByTrigger(normalized)
        if (triggeredMenu != null && triggeredMenu.enabled) {
            conversationMenus[conversationKey] = triggeredMenu
            return buildMenuText(triggeredMenu)
        }

        return null
    }

    fun setActiveMenu(conversationKey: String, menu: MenuReplyEntity) {
        conversationMenus[conversationKey] = menu
    }

    fun getActiveMenu(conversationKey: String): MenuReplyEntity? = conversationMenus[conversationKey]

    fun clearActiveMenu(conversationKey: String) {
        conversationMenus.remove(conversationKey)
    }

    fun decodeOptions(optionsJson: String): List<MenuOption> {
        if (optionsJson.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MenuOption>>(optionsJson)
        }.getOrDefault(emptyList())
    }

    fun encodeOptions(options: List<MenuOption>): String {
        return json.encodeToString(options)
    }
}
