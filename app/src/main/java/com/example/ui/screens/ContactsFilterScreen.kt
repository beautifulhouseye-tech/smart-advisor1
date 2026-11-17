package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.ContactFilterEntity
import com.example.data.local.entity.ContactFilterListType
import com.example.data.preferences.ContactFilterMode
import com.example.ui.viewmodel.ContactsPickerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactsFilterScreen(
    selectedMode: ContactFilterMode,
    contacts: List<ContactFilterEntity>,
    onSelectMode: (ContactFilterMode) -> Unit,
    onAddContact: (String, ContactFilterListType) -> Unit,
    onDeleteContact: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddContactWithNotes: (String, ContactFilterListType, String) -> Unit = { name, type, _ ->
        onAddContact(name, type)
    },
    pickerViewModel: ContactsPickerViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    var newContactName by remember { mutableStateOf("") }
    var targetListType by remember(selectedMode) {
        mutableStateOf(
            if (selectedMode == ContactFilterMode.INCLUDE_ONLY) {
                ContactFilterListType.INCLUDE
            } else {
                ContactFilterListType.EXCLUDE
            }
        )
    }

    val hasPermission by pickerViewModel.hasPermission.collectAsStateWithLifecycle()
    val deviceContacts by pickerViewModel.contacts.collectAsStateWithLifecycle()
    val filteredDeviceContacts by pickerViewModel.filteredContacts.collectAsStateWithLifecycle()
    val searchQuery by pickerViewModel.searchQuery.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pickerViewModel.refreshContacts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        pickerViewModel.refreshContacts()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("فلترة جهات الاتصال") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("contacts_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "وضع الفلترة النشط",
                                style = MaterialTheme.typography.titleMedium
                            )
                            ContactFilterMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectMode(mode) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedMode == mode,
                                        onClick = { onSelectMode(mode) }
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(mode.labelAr, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            mode.descriptionAr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "اختيار من جهات اتصال الهاتف",
                                style = MaterialTheme.typography.titleMedium
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ContactFilterListType.entries.forEach { listType ->
                                    FilterChip(
                                        selected = targetListType == listType,
                                        onClick = { targetListType = listType },
                                        label = { Text(listType.labelAr) }
                                    )
                                }
                            }
                            if (!hasPermission) {
                                Text(
                                    text = "امنح صلاحية قراءة جهات الاتصال لاختيار الأسماء من هاتفك مباشرة مع حفظ بصمة مشفرة للرقم.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("grant_contacts_permission_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Contacts,
                                        contentDescription = "منح إذن جهات الاتصال"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("منح الإذن")
                                }
                            } else {
                                val selectedDeviceCount = remember(deviceContacts, contacts, targetListType) {
                                    deviceContacts.count { deviceContact ->
                                        val hashTag = "phoneHash:${deviceContact.phoneHash}"
                                        contacts.any { saved ->
                                            saved.listType == targetListType &&
                                                (saved.notes == hashTag ||
                                                    saved.contactName.equals(deviceContact.name, ignoreCase = true))
                                        }
                                    }
                                }
                                Text(
                                    text = "تم تحديد $selectedDeviceCount من ${deviceContacts.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = pickerViewModel::updateSearchQuery,
                                    label = { Text("ابحث بالاسم أو الرقم") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = "بحث"
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("contacts_search_input")
                                )
                                if (filteredDeviceContacts.isEmpty()) {
                                    Text(
                                        text = if (deviceContacts.isEmpty()) {
                                            "لا توجد جهات اتصال متاحة على الجهاز."
                                        } else {
                                            "لا توجد نتائج مطابقة للبحث."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 300.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(
                                            items = filteredDeviceContacts,
                                            key = { "${it.phoneHash}_${it.name}" }
                                        ) { deviceContact ->
                                            val hashNote = "phoneHash:${deviceContact.phoneHash}"
                                            val matchingSaved = contacts.firstOrNull { saved ->
                                                saved.listType == targetListType &&
                                                    (saved.notes == hashNote ||
                                                        saved.contactName.equals(deviceContact.name, ignoreCase = true))
                                            }
                                            val isChecked = matchingSaved != null
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (matchingSaved != null) {
                                                            onDeleteContact(matchingSaved.id)
                                                        } else {
                                                            onAddContactWithNotes(
                                                                deviceContact.name,
                                                                targetListType,
                                                                hashNote
                                                            )
                                                        }
                                                    }
                                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = deviceContact.name,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                    Text(
                                                        text = deviceContact.phone,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { checked ->
                                                        if (checked && matchingSaved == null) {
                                                            onAddContactWithNotes(
                                                                deviceContact.name,
                                                                targetListType,
                                                                hashNote
                                                            )
                                                        } else if (!checked && matchingSaved != null) {
                                                            onDeleteContact(matchingSaved.id)
                                                        }
                                                    }
                                                )
                                            }
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "إضافة جهة اتصال أو مجموعة بالاسم",
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedTextField(
                                value = newContactName,
                                onValueChange = { newContactName = it },
                                label = { Text("الاسم كما يظهر في إشعار WhatsApp") },
                                placeholder = { Text("مثال: أحمد علي") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("contact_name_input")
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ContactFilterListType.entries.forEach { listType ->
                                    FilterChip(
                                        selected = targetListType == listType,
                                        onClick = { targetListType = listType },
                                        label = { Text(listType.labelAr) }
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    onAddContact(newContactName, targetListType)
                                    newContactName = ""
                                },
                                enabled = newContactName.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("add_contact_filter_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PersonAdd,
                                    contentDescription = "إضافة جهة اتصال"
                                )
                                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                Text("إضافة للقائمة")
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "الأسماء المحفوظة (${contacts.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (contacts.isEmpty()) {
                    item {
                        Text(
                            text = "لا توجد أسماء مضافة في قوائم الفلترة بعد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(contacts, key = { it.id }) { contact ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = contact.contactName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Surface(
                                        color = if (contact.listType == ContactFilterListType.INCLUDE) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.errorContainer
                                        },
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = contact.listType.labelAr,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteContact(contact.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "حذف جهة الاتصال",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}
