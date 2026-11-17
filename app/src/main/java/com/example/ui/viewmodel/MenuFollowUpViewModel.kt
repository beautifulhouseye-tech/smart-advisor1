package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.FollowUpDao
import com.example.data.local.dao.MenuReplyDao
import com.example.data.local.dao.ReplyRuleDao
import com.example.data.local.entity.FollowUpEntity
import com.example.data.local.entity.MenuReplyEntity
import com.example.data.local.entity.ReplyRuleEntity
import com.example.engine.MenuOption
import com.example.engine.MenuReplyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MenuFollowUpViewModel @Inject constructor(
    private val menuReplyDao: MenuReplyDao,
    private val followUpDao: FollowUpDao,
    private val replyRuleDao: ReplyRuleDao,
    private val menuReplyManager: MenuReplyManager
) : ViewModel() {

    val menus: StateFlow<List<MenuReplyEntity>> = menuReplyDao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val followUps: StateFlow<List<FollowUpEntity>> = followUpDao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val rules: StateFlow<List<ReplyRuleEntity>> = replyRuleDao.observeAllRules()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun decodeOptions(optionsJson: String): List<MenuOption> =
        menuReplyManager.decodeOptions(optionsJson)

    fun saveMenu(
        trigger: String,
        title: String,
        options: List<MenuOption>,
        existing: MenuReplyEntity? = null
    ) {
        val cleanTrigger = trigger.trim()
        val cleanTitle = title.trim()
        val validOptions = options.filter { it.label.isNotBlank() && it.response.isNotBlank() }
        if (cleanTrigger.isEmpty() || validOptions.isEmpty()) return

        viewModelScope.launch {
            val encoded = menuReplyManager.encodeOptions(validOptions)
            if (existing == null) {
                menuReplyDao.insert(
                    MenuReplyEntity(
                        trigger = cleanTrigger,
                        title = cleanTitle,
                        optionsJson = encoded,
                        enabled = true
                    )
                )
            } else {
                menuReplyDao.update(
                    existing.copy(
                        trigger = cleanTrigger,
                        title = cleanTitle,
                        optionsJson = encoded
                    )
                )
            }
        }
    }

    fun toggleMenu(menu: MenuReplyEntity, enabled: Boolean) {
        viewModelScope.launch {
            menuReplyDao.update(menu.copy(enabled = enabled))
        }
    }

    fun deleteMenu(menu: MenuReplyEntity) {
        viewModelScope.launch {
            menuReplyDao.delete(menu)
        }
    }

    fun saveFollowUp(
        ruleId: Long,
        delayMinutes: Int,
        messageTemplate: String
    ) {
        val cleanMsg = messageTemplate.trim()
        if (ruleId <= 0L || cleanMsg.isEmpty()) return
        viewModelScope.launch {
            followUpDao.insert(
                FollowUpEntity(
                    ruleId = ruleId,
                    delayMinutes = delayMinutes.coerceAtLeast(1),
                    messageTemplate = cleanMsg,
                    enabled = true
                )
            )
        }
    }

    fun toggleFollowUp(followUp: FollowUpEntity, enabled: Boolean) {
        viewModelScope.launch {
            followUpDao.update(followUp.copy(enabled = enabled))
        }
    }

    fun deleteFollowUp(followUp: FollowUpEntity) {
        viewModelScope.launch {
            followUpDao.delete(followUp)
        }
    }
}
