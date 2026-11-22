package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.security.BiometricLockHelper
import com.example.ui.navigation.AISettingsRoute
import com.example.ui.navigation.ContactsFilterRoute
import com.example.ui.navigation.DiagnosticsRoute
import com.example.ui.navigation.FollowUpRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.LogsRoute
import com.example.ui.navigation.MenuReplyRoute
import com.example.ui.navigation.ReplyTemplatesRoute
import com.example.ui.navigation.RulesRoute
import com.example.ui.navigation.SaSettingsRoute
import com.example.ui.navigation.ServerRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.StatisticsRoute
import com.example.ui.navigation.TestReplyRoute
import com.example.ui.navigation.ToolsAndMemoryRoute
import com.example.ui.screens.AISettingsScreen
import com.example.ui.screens.BiometricLockScreen
import com.example.ui.screens.ContactsFilterScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.FollowUpScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.MenuReplyScreen
import com.example.ui.screens.ReplyTemplatesScreen
import com.example.ui.screens.RulesScreen
import com.example.ui.screens.SaSettingsScreen
import com.example.ui.screens.ServerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StatisticsScreen
import com.example.ui.screens.TestReplyScreen
import com.example.ui.screens.ToolsAndMemoryScreen
import com.example.ui.theme.AutoMateTheme
import com.example.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var biometricLockHelper: BiometricLockHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AutoMateTheme {
                    AutoMateRootApp(biometricLockHelper = biometricLockHelper)
                }
            }
        }
    }
}

private data class TopLevelNavItem<T : Any>(
    val route: T,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun AutoMateRootApp(
    biometricLockHelper: BiometricLockHelper,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isBiometricAvailable = remember(biometricLockHelper) {
        biometricLockHelper.isBiometricAvailable()
    }
    var isUnlocked by rememberSaveable { mutableStateOf(false) }

    if (!uiState.isSettingsLoaded) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize())
        }
        return
    }

    if (uiState.settings.isBiometricLockEnabled && isBiometricAvailable && !isUnlocked) {
        BiometricLockScreen(
            biometricHelper = biometricLockHelper,
            onUnlocked = { isUnlocked = true },
            onExit = { activity?.finish() }
        )
        return
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeStatusMessage()
        }
    }

    val navItems = remember {
        listOf(
            TopLevelNavItem(
                route = HomeRoute,
                labelRes = R.string.nav_home,
                selectedIcon = Icons.Filled.Home,
                unselectedIcon = Icons.Outlined.Home,
                testTag = "nav_home_item"
            ),
            TopLevelNavItem(
                route = RulesRoute,
                labelRes = R.string.nav_rules,
                selectedIcon = Icons.AutoMirrored.Filled.Rule,
                unselectedIcon = Icons.AutoMirrored.Outlined.Rule,
                testTag = "nav_rules_item"
            ),
            TopLevelNavItem(
                route = ToolsAndMemoryRoute,
                labelRes = R.string.nav_tools_memory,
                selectedIcon = Icons.Filled.Memory,
                unselectedIcon = Icons.Outlined.Memory,
                testTag = "nav_tools_memory_item"
            ),
            TopLevelNavItem(
                route = SettingsRoute,
                labelRes = R.string.nav_settings,
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "nav_settings_item"
            )
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isContactsSubScreen = currentDestination?.hasRoute(ContactsFilterRoute::class) == true ||
        currentDestination?.hasRoute(ServerRoute::class) == true ||
        currentDestination?.hasRoute(SaSettingsRoute::class) == true ||
        currentDestination?.hasRoute(LogsRoute::class) == true ||
        currentDestination?.hasRoute(AISettingsRoute::class) == true ||
        currentDestination?.hasRoute(StatisticsRoute::class) == true ||
        currentDestination?.hasRoute(MenuReplyRoute::class) == true ||
        currentDestination?.hasRoute(FollowUpRoute::class) == true ||
        currentDestination?.hasRoute(ReplyTemplatesRoute::class) == true ||
        currentDestination?.hasRoute(TestReplyRoute::class) == true ||
        currentDestination?.hasRoute(DiagnosticsRoute::class) == true

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail && !isContactsSubScreen) {
                    NavigationBar {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && !isContactsSubScreen) {
                    NavigationRail {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            uiState = uiState,
                            onToggleAutoReply = viewModel::toggleAutoReply,
                            onToggleDrivingMode = viewModel::toggleDrivingMode,
                            onRefreshPermissions = viewModel::refreshPermissions,
                            onClearLogs = viewModel::clearLogs,
                            onNavigateToRules = { navigateTopLevel(RulesRoute) },
                            onNavigateToToolsAndMemory = { navigateTopLevel(ToolsAndMemoryRoute) },
                            onNavigateToSettings = { navigateTopLevel(SettingsRoute) },
                            onNavigateToLogs = { navController.navigate(LogsRoute) },
                            onNavigateToStatistics = { navController.navigate(StatisticsRoute) },
                            onNavigateToTestReply = { navController.navigate(TestReplyRoute) }
                        )
                    }

                    composable<RulesRoute> {
                        RulesScreen(
                            rules = uiState.rules,
                            onSaveRule = viewModel::saveRule,
                            onToggleRule = viewModel::toggleRuleEnabled,
                            onDeleteRule = viewModel::deleteRule,
                            templates = uiState.templates,
                            onNavigateToTemplates = { navController.navigate(ReplyTemplatesRoute) },
                            onNavigateToMenus = { navController.navigate(MenuReplyRoute) },
                            onNavigateToFollowUps = { navController.navigate(FollowUpRoute) },
                            onSaveAsTemplate = viewModel::saveTemplate
                        )
                    }

                    composable<ToolsAndMemoryRoute> {
                        ToolsAndMemoryScreen(
                            onNavigateToAISettings = { navController.navigate(AISettingsRoute) },
                            onNavigateToSaSettings = { navController.navigate(SaSettingsRoute) },
                            onNavigateToServer = { navController.navigate(ServerRoute) },
                            onNavigateToTestReply = { navController.navigate(TestReplyRoute) },
                            onNavigateToLogs = { navController.navigate(LogsRoute) },
                            onNavigateToStatistics = { navController.navigate(StatisticsRoute) }
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = uiState.settings,
                            contactFilterCount = uiState.contactFilters.size,
                            onNavigateToContactsFilter = {
                                navController.navigate(ContactsFilterRoute)
                            },
                            onSetReplyInGroups = viewModel::setReplyInGroups,
                            onSetDelaySeconds = viewModel::setReplyDelaySeconds,
                            onSetCooldownMinutes = viewModel::setCooldownMinutes,
                            onSetScheduleEnabled = viewModel::setScheduleEnabled,
                            onSetScheduleWindow = viewModel::setScheduleWindow,
                            onSetAutoDetectDriving = viewModel::setAutoDetectDriving,
                            onUpdateDrivingReplyText = viewModel::updateDrivingReplyText,
                            onTogglePackage = viewModel::toggleSupportedPackage,
                            onSetAccessibilityFallback = viewModel::setUseAccessibilityFallback,
                            onExportJson = viewModel::exportBackup,
                            onImportJson = viewModel::importBackup,
                            isBiometricAvailable = isBiometricAvailable,
                            onSetBiometricLock = viewModel::setBiometricLockEnabled,
                            diagnosticEventsCount = uiState.diagnosticEventsCount,
                            diagnosticErrorsCount = uiState.diagnosticErrorsCount,
                            onSetDiagnosticLogging = viewModel::setDiagnosticLoggingEnabled,
                            onNavigateToDiagnostics = { navController.navigate(DiagnosticsRoute) },
                            onClearDiagnosticLogs = viewModel::clearDiagnosticLogs
                        )
                    }

                    composable<ContactsFilterRoute> {
                        ContactsFilterScreen(
                            selectedMode = uiState.settings.contactFilterMode,
                            contacts = uiState.contactFilters,
                            onSelectMode = viewModel::setContactFilterMode,
                            onAddContact = viewModel::addContactFilter,
                            onDeleteContact = viewModel::deleteContactFilter,
                            onBack = { navController.popBackStack() },
                            onAddContactWithNotes = viewModel::addContactFilter
                        )
                    }

                    composable<ServerRoute> {
                        ServerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<SaSettingsRoute> {
                        SaSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<LogsRoute> {
                        LogsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AISettingsRoute> {
                        AISettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<StatisticsRoute> {
                        StatisticsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<MenuReplyRoute> {
                        MenuReplyScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<FollowUpRoute> {
                        FollowUpScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<ReplyTemplatesRoute> {
                        val linkedCounts = remember(uiState.rules) {
                            uiState.rules.mapNotNull { it.templateId }.groupingBy { it }.eachCount()
                        }
                        ReplyTemplatesScreen(
                            templates = uiState.templates,
                            linkedCountsByTemplateId = linkedCounts,
                            onSaveTemplate = viewModel::saveTemplate,
                            onDuplicateTemplate = viewModel::duplicateTemplate,
                            onDeleteTemplate = viewModel::deleteTemplate,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<TestReplyRoute> {
                        TestReplyScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<DiagnosticsRoute> {
                        DiagnosticsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
