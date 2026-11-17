package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.MenuReplyEntity
import com.example.engine.MenuOption
import com.example.ui.viewmodel.MenuFollowUpViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuReplyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MenuFollowUpViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val menus by viewModel.menus.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("القوائم التفاعلية") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("menu_reply_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_menu_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "إضافة قائمة"
                )
            }
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
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "إضافة قائمة"
                        )
                        Text("إضافة قائمة تفاعلية", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                if (menus.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FormatListNumbered,
                                    contentDescription = "لا توجد قوائم",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "لا توجد قوائم تفاعلية بعد",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "أنشئ قائمة مرقمة (مثلاً عند إرسال 'القائمة') ليرد العميل بالرقم المطلوب.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(menus, key = { it.id }) { menu ->
                        MenuReplyCard(
                            menu = menu,
                            options = viewModel.decodeOptions(menu.optionsJson),
                            onToggle = { enabled -> viewModel.toggleMenu(menu, enabled) },
                            onDelete = { viewModel.deleteMenu(menu) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddMenuDialog(
            onDismiss = { showAddDialog = false },
            onSave = { trigger, title, options ->
                viewModel.saveMenu(trigger, title, options)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MenuReplyCard(
    menu: MenuReplyEntity,
    options: List<MenuOption>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "الكلمة المشغّلة: ${menu.trigger}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (menu.title.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = menu.title, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = menu.enabled, onCheckedChange = onToggle)
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف القائمة",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            options.sortedBy { it.number }.forEach { option ->
                Text(
                    text = "${option.number}. ${option.label} ← ${option.response}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddMenuDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, List<MenuOption>) -> Unit
) {
    var trigger by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("اختر الخدمة المطلوبة:") }
    val options = remember {
        mutableStateListOf(
            MenuOption(1, "خدمة العملاء", "مرحباً بك في خدمة العملاء، كيف نساعدك؟"),
            MenuOption(2, "المبيعات", "قسم المبيعات جاهز لتقديم أحدث العروض لك.")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة قائمة تفاعلية") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("الكلمة المشغّلة (Trigger)") },
                    placeholder = { Text("مثال: القائمة") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("menu_trigger_input")
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان القائمة") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("menu_title_input")
                )

                Text("الخيارات:", style = MaterialTheme.typography.labelLarge)
                options.forEachIndexed { idx, opt ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "خيار رقم ${opt.number}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (options.size > 1) {
                                    IconButton(onClick = { options.removeAt(idx) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "حذف الخيار"
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = opt.label,
                                onValueChange = { options[idx] = opt.copy(label = it) },
                                label = { Text("عنوان الخيار") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = opt.response,
                                onValueChange = { options[idx] = opt.copy(response = it) },
                                label = { Text("الرد التلقائي للخيار") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val nextNum = (options.maxOfOrNull { it.number } ?: 0) + 1
                        options.add(MenuOption(nextNum, "", ""))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "إضافة خيار")
                    Text("إضافة خيار جديد", modifier = Modifier.padding(start = 6.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(trigger, title, options.toList()) },
                enabled = trigger.isNotBlank() && options.any { it.label.isNotBlank() && it.response.isNotBlank() },
                modifier = Modifier.testTag("save_menu_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
