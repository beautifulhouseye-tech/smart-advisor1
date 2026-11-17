package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.contacts.ContactsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsPickerViewModel @Inject constructor(
    private val contactsManager: ContactsManager
) : ViewModel() {

    private val _hasPermission = MutableStateFlow(contactsManager.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val contacts: StateFlow<List<ContactsManager.Contact>> = contactsManager.contacts

    val filteredContacts: StateFlow<List<ContactsManager.Contact>> = combine(
        contactsManager.contacts,
        _searchQuery
    ) { all, query ->
        val clean = query.trim()
        if (clean.isEmpty()) {
            all
        } else {
            val normalizedQuery = clean.replace(Regex("""[\s\-()]"""), "")
            all.filter {
                it.name.contains(clean, ignoreCase = true) ||
                    it.phone.contains(clean, ignoreCase = true) ||
                    (normalizedQuery.isNotEmpty() &&
                        it.phone.replace(Regex("""[\s\-()]"""), "").contains(normalizedQuery, ignoreCase = true))
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        refreshContacts()
    }

    fun refreshContacts() {
        val granted = contactsManager.hasPermission()
        _hasPermission.value = granted
        if (granted) {
            viewModelScope.launch(Dispatchers.IO) {
                contactsManager.getAllContacts()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
