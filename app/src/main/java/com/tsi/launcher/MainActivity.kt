package com.tsi.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.tsi.launcher.data.JavaGameInstaller
import com.tsi.launcher.runtime.AmethystEngineBridge
import com.tsi.launcher.runtime.DevicePerformanceProfile
import com.tsi.launcher.data.MinecraftVersion
import com.tsi.launcher.data.VersionFilter
import com.tsi.launcher.data.VersionManifestRepository
import com.tsi.launcher.storage.VersionDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TsiInk = Color(0xFF183B4A)
private val TsiTeal = Color(0xFF087F9C)
private val TsiTealDark = Color(0xFF075E75)
private val TsiSky = Color(0xFFEAF5F8)
private val TsiPage = Color(0xFFF3F7F8)
private val TsiMuted = Color(0xFF71838B)
private val TsiLine = Color(0xFFDCE7EA)

class MainActivity : ComponentActivity() {
    var onLauncherResumed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TsiLauncherApp() }
    }

    override fun onResume() {
        super.onResume()
        onLauncherResumed?.invoke()
    }
}

private enum class MainSection { DASHBOARD, VM_CONTROLLER, MOD_PACKS, NEWS, SETTINGS }

@Composable
private fun TsiLauncherApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("tsi_launcher_preferences", 0)
    }
    val manifestRepository = remember { VersionManifestRepository() }
    val directoryManager = remember { VersionDirectoryManager(context.applicationContext) }
    val gameInstaller = remember { JavaGameInstaller(context.applicationContext, directoryManager) }
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf(MainSection.DASHBOARD) }
    var versions by remember { mutableStateOf<List<MinecraftVersion>>(emptyList()) }
    var selectedFilter by rememberSaveable { mutableStateOf(VersionFilter.ALL) }
    var selectedVersionId by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var activeDownload by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Connecting to the official version catalogue…") }
    var profileName by rememberSaveable {
        mutableStateOf(preferences.getString("profile_name", "Steve001") ?: "Steve001")
    }
    var accountMode by rememberSaveable {
        mutableStateOf(AmethystEngineBridge.selectedAccountMode(context))
    }
    var ramMb by rememberSaveable {
        val recommended = DevicePerformanceProfile.recommendedHeapMb(context)
        val saved = preferences.getInt("ram_mb", recommended)
        mutableFloatStateOf(DevicePerformanceProfile.clampHeap(context, saved).toFloat())
    }
    var javaRuntime by rememberSaveable {
        mutableStateOf(preferences.getString("java_runtime", "Auto") ?: "Auto")
    }
    var storageAccessRefreshKey by remember { mutableIntStateOf(0) }
    var legacyStoragePermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                )
        )
    }
    var overflowOpen by remember { mutableStateOf(false) }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        legacyStoragePermissionGranted =
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
                grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        statusMessage = if (legacyStoragePermissionGranted) {
            "Shared storage permission granted. Minecraft files can be installed."
        } else {
            "Read and write storage permissions are both required on Android 9 and older."
        }
        storageAccessRefreshKey++
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !legacyStoragePermissionGranted) {
            legacyStoragePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    val hostActivity = context as? MainActivity
    androidx.compose.runtime.DisposableEffect(hostActivity) {
        hostActivity?.onLauncherResumed = {
            storageAccessRefreshKey++
            legacyStoragePermissionGranted =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                    )
            AmethystEngineBridge.selectedAccountUsername(context)?.let { selectedUsername ->
                profileName = selectedUsername
                preferences.edit().putString("profile_name", selectedUsername).apply()
            }
            accountMode = AmethystEngineBridge.selectedAccountMode(context)
        }
        onDispose {
            hostActivity?.onLauncherResumed = null
        }
    }

    suspend fun refreshVersions() {
        isLoading = true
        statusMessage = "Contacting Mojang version manifest…"
        try {
            versions = manifestRepository.fetchVersions()
            if (selectedVersionId.isBlank()) {
                selectedVersionId = versions.firstOrNull { it.type == "release" }?.id.orEmpty()
            }
            statusMessage = "Loaded ${versions.size} Java version entries from Mojang."
        } catch (error: Exception) {
            statusMessage = "Could not load versions: ${error.message ?: "network error"}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        AmethystEngineBridge.selectedAccountUsername(context)?.let { selectedUsername ->
            profileName = selectedUsername
            preferences.edit().putString("profile_name", selectedUsername).apply()
        }
        accountMode = AmethystEngineBridge.selectedAccountMode(context)
        refreshVersions()
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = TsiTeal,
        onPrimary = Color.White,
        secondary = Color(0xFF56B5C7),
        background = TsiPage,
        surface = Color.White,
        onSurface = TsiInk,
        onBackground = TsiInk
    )) {
        Scaffold(
            containerColor = TsiPage,
            topBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFF0F5F6),
                            shape = RoundedCornerShape(28.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TsiLine)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = "Search", tint = TsiMuted)
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp),
                                    placeholder = { Text("Search versions, profiles…", color = TsiMuted) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TsiInk)
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh Java versions") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                    onClick = {
                                        overflowOpen = false
                                        scope.launch { refreshVersions() }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = {
                                        overflowOpen = false
                                        section = MainSection.SETTINGS
                                    }
                                )
                            }
                        }
                    }

                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = section == MainSection.DASHBOARD,
                        onClick = { section = MainSection.DASHBOARD },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Dashboard") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.VM_CONTROLLER,
                        onClick = { section = MainSection.VM_CONTROLLER },
                        icon = { Icon(Icons.Filled.Memory, contentDescription = null) },
                        label = { Text("VM") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.MOD_PACKS,
                        onClick = { section = MainSection.MOD_PACKS },
                        icon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                        label = { Text("Mod Packs") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.NEWS,
                        onClick = { section = MainSection.NEWS },
                        icon = { Icon(Icons.Filled.Article, contentDescription = null) },
                        label = { Text("News") }
                    )
                    NavigationBarItem(
                        selected = section == MainSection.SETTINGS,
                        onClick = { section = MainSection.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { innerPadding ->
            AnimatedContent(
                targetState = section,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 22 }
                    )) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically(
                            animationSpec = tween(180),
                            targetOffsetY = { -it / 30 }
                        ))
                },
                label = "main-section-transition"
            ) { target ->
                when (target) {
                    MainSection.DASHBOARD -> {
                            JavaDashboard(
                                versions = versions,
                                selectedFilter = selectedFilter,
                                onFilterChange = { selectedFilter = it },
                                selectedVersionId = selectedVersionId,
                                onVersionSelect = { selectedVersionId = it },
                                searchQuery = searchQuery,
                                loading = isLoading,
                                statusMessage = statusMessage,
                                onRefresh = { scope.launch { refreshVersions() } },
                                profileName = profileName,
                                accountMode = accountMode,
                                onAddProfile = {
                                    val intent = Intent()
                                        .setClassName(context, "net.kdt.pojavlaunch.LauncherActivity")
                                        .putExtra("tsi_open_account_manager", true)
                                    context.startActivity(intent)
                                },
                                activeDownload = activeDownload,
                                onOpenVmController = { section = MainSection.VM_CONTROLLER },
                                onDownload = { version ->
                                    scope.launch {
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                            !legacyStoragePermissionGranted
                                        ) {
                                            statusMessage = "Storage permission is required before installing Minecraft files."
                                            legacyStoragePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
                                            return@launch
                                        }
                                        activeDownload = version.id
                                        statusMessage = "Preparing complete Java installation for ${version.id}…"
                                        try {
                                            val result = gameInstaller.install(version) { progress ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    statusMessage = progress
                                                }
                                            }
                                            val javaText = result.requiredJavaMajor?.toString() ?: "not resolved"
                                            statusMessage =
                                                "${result.versionId} installed: ${result.libraryCount} libraries and ${result.assetCount} assets. Required Java: $javaText. Opening VM Controller."
                                            section = MainSection.VM_CONTROLLER
                                        } catch (error: Exception) {
                                            statusMessage =
                                                "Install failed for ${version.id}: ${error.message ?: "unknown error"}"
                                        } finally {
                                            activeDownload = null
                                        }
                                    }
                                }
                            )
                    }
                    MainSection.VM_CONTROLLER -> VirtualMachineControllerScreen(
                        modifier = Modifier.fillMaxSize(),
                        versionId = selectedVersionId,
                        ramMb = ramMb.toInt(),
                        runtimePreference = javaRuntime,
                        playerName = profileName,
                        storageAccessRefreshKey = storageAccessRefreshKey,
                        onBackToDashboard = { section = MainSection.DASHBOARD }
                    )
                    MainSection.MOD_PACKS -> ModPackManagerScreen(
                        modifier = Modifier.fillMaxSize(),
                        profileName = profileName,
                        searchQuery = searchQuery,
                        onInstancesClick = { section = MainSection.DASHBOARD }
                    )
                    MainSection.NEWS -> NewsScreen(Modifier.fillMaxSize())
                    MainSection.SETTINGS -> SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        ramMb = ramMb,
                        onRamChange = {
                            val safeHeap = DevicePerformanceProfile.clampHeap(context, it.toInt())
                            ramMb = safeHeap.toFloat()
                            preferences.edit().putInt("ram_mb", safeHeap).apply()
                        },
                        javaRuntime = javaRuntime,
                        onJavaRuntimeChange = {
                            javaRuntime = it
                            preferences.edit().putString("java_runtime", it).apply()
                        },
                        storageAccessRefreshKey = storageAccessRefreshKey,
                        legacyStoragePermissionGranted = legacyStoragePermissionGranted,
                        onRequestLegacyStoragePermission = {
                            legacyStoragePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
                        }
                    )
                }
            }
        }
    }

}

@Composable
private fun JavaDashboard(
    versions: List<MinecraftVersion>,
    selectedFilter: VersionFilter,
    onFilterChange: (VersionFilter) -> Unit,
    selectedVersionId: String,
    onVersionSelect: (String) -> Unit,
    searchQuery: String,
    loading: Boolean,
    statusMessage: String,
    onRefresh: () -> Unit,
    profileName: String,
    accountMode: String,
    onAddProfile: () -> Unit,
    activeDownload: String?,
    onOpenVmController: () -> Unit,
    onDownload: (MinecraftVersion) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEAF4F7), Color(0xFFF6F9FA), Color(0xFFF0F5F6))
                )
            )
    ) {
        val wideLayout = maxWidth >= 760.dp
        val filteredVersions = remember(versions, selectedFilter, searchQuery) {
            versions.filter { selectedFilter.accepts(it.type) }
                .filter { searchQuery.isBlank() || it.id.contains(searchQuery.trim(), true) }
        }
        val selectedVersion = filteredVersions.firstOrNull { it.id == selectedVersionId }
            ?: filteredVersions.firstOrNull()
        val latestRelease = versions.firstOrNull { it.type == "release" }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wideLayout) 24.dp else 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProfileStrip(
                profileName = profileName,
                accountMode = accountMode,
                onAddProfile = onAddProfile
            )

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1.35f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        FeaturedVersionCard(
                            modifier = Modifier.fillMaxWidth(),
                            selectedVersion = selectedVersion,
                            latestRelease = latestRelease,
                            activeDownload = activeDownload,
                            onOpenVmController = onOpenVmController,
                            onDownload = { selectedVersion?.let(onDownload) }
                        )
                        VersionCategoryCards(
                            onFilterChange = onFilterChange,
                            selectedFilter = selectedFilter
                        )
                    }
                    VersionPickerPanel(
                        modifier = Modifier.width(290.dp),
                        versions = filteredVersions,
                        selectedVersion = selectedVersion,
                        selectedFilter = selectedFilter,
                        onFilterChange = onFilterChange,
                        onVersionSelect = onVersionSelect,
                        onRefresh = onRefresh,
                        loading = loading
                    )
                }
            } else {
                FeaturedVersionCard(
                    modifier = Modifier.fillMaxWidth(),
                    selectedVersion = selectedVersion,
                    latestRelease = latestRelease,
                    activeDownload = activeDownload,
                    onOpenVmController = onOpenVmController,
                    onDownload = { selectedVersion?.let(onDownload) }
                )
                VersionCategoryCards(
                    onFilterChange = onFilterChange,
                    selectedFilter = selectedFilter
                )
                VersionPickerPanel(
                    modifier = Modifier.fillMaxWidth(),
                    versions = filteredVersions,
                    selectedVersion = selectedVersion,
                    selectedFilter = selectedFilter,
                    onFilterChange = onFilterChange,
                    onVersionSelect = onVersionSelect,
                    onRefresh = onRefresh,
                    loading = loading
                )
            }

            AnimatedVisibility(
                visible = statusMessage.isNotBlank(),
                enter = fadeIn(tween(240)) + slideInVertically { it / 3 },
                exit = fadeOut(tween(160)) + slideOutVertically { it / 4 }
            ) {
                StatusBanner(
                    message = statusMessage,
                    isLoading = loading || activeDownload != null
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileStrip(profileName: String, accountMode: String, onAddProfile: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePill(
            eyebrow = if (accountMode == "Microsoft") "MICROSOFT ACCOUNT" else "LOCAL PROFILE",
            name = profileName,
            icon = Icons.Filled.Person,
            active = true
        )
        Spacer(Modifier.width(8.dp))
        ProfilePill(
            eyebrow = "MODE",
            name = accountMode,
            icon = Icons.Filled.Person,
            active = accountMode != "No account"
        )
        Spacer(Modifier.width(4.dp))
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onAddProfile),
            shape = CircleShape,
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, TsiLine),
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Manage accounts", tint = TsiTeal)
            }
        }
    }
}

@Composable
private fun ProfilePill(
    eyebrow: String,
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean
) {
    Surface(
        color = if (active) Color(0xFFE2F4F7) else Color(0xFFF5F7F8),
        shape = RoundedCornerShape(30.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) TsiTeal else TsiLine
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                color = if (active) Color(0xFFBCE6ED) else Color(0xFFE2E7E9),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = if (active) TsiTealDark else TsiMuted)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    eyebrow,
                    color = if (active) TsiTealDark else TsiMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    name,
                    color = TsiInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FeaturedVersionCard(
    modifier: Modifier,
    selectedVersion: MinecraftVersion?,
    latestRelease: MinecraftVersion?,
    activeDownload: String?,
    onOpenVmController: () -> Unit,
    onDownload: () -> Unit
) {
    val versionName = selectedVersion?.id ?: latestRelease?.id ?: "Loading…"
    val isDownloading = selectedVersion != null && activeDownload == selectedVersion.id

    Card(
        modifier = modifier
            .shadow(7.dp, RoundedCornerShape(26.dp))
            .animateContentSize(tween(300, easing = FastOutSlowInEasing)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (modifier == Modifier) 220.dp else 220.dp)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            ) {
                VoxelLandscape()
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, bottom = 15.dp)
                ) {
                    Surface(
                        color = Color(0xD9073345),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "JAVA EDITION",
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        "MINECRAFT",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                val pulse by rememberInfiniteTransition(label = "hero-glow").animateFloat(
                    initialValue = 0.16f,
                    targetValue = 0.34f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "hero-glow-alpha"
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(11.dp)
                        .alpha(pulse)
                        .background(Color.White, CircleShape)
                )
            }
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (selectedVersion?.type == "release") "LATEST RELEASE: $versionName"
                    else "SELECTED VERSION: $versionName",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF102F3C)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDownload,
                        enabled = selectedVersion != null && activeDownload == null,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TsiTeal,
                            disabledContainerColor = Color(0xFF9EBBC2)
                        )
                    ) {
                        Icon(
                            if (isDownloading) Icons.Filled.Refresh else Icons.Filled.Download,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (isDownloading) "INSTALLING…" else "INSTALL & PREPARE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Surface(
                        color = Color(0xFFF0F6F7),
                        shape = RoundedCornerShape(15.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TsiLine)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("JAVA", color = TsiTealDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(
                                selectedVersion?.id ?: "—",
                                color = TsiInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onOpenVmController,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Memory, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("OPEN VIRTUAL MACHINE CONTROLLER", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                Text(
                    "Downloads open the VM Controller automatically. It checks the selected Android JRE, Minecraft libraries, assets and the embedded Amethyst game engine before launch.",
                    color = TsiMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun VoxelLandscape() {
    val transition = rememberInfiniteTransition(label = "sky-motion")
    val drift by transition.animateFloat(
        initialValue = -0.025f,
        targetValue = 0.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(6500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloud-drift"
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF70C8ED), Color(0xFFB7E8F5), Color(0xFFE5F7F7))
                )
            )
            drawCircle(
                color = Color(0xFFFFE9A5),
                radius = h * 0.105f,
                center = Offset(w * 0.82f, h * 0.22f)
            )

            fun cloud(x: Float, y: Float, scale: Float) {
                val cw = h * 0.25f * scale
                val ch = h * 0.075f * scale
                val xx = x + w * drift
                drawRoundRect(Color(0xFFEAF8FC), Offset(xx, y), Size(cw, ch), CornerRadius(ch / 2))
                drawRoundRect(Color(0xFFEAF8FC), Offset(xx + cw * 0.18f, y - ch * 0.42f), Size(cw * 0.55f, ch), CornerRadius(ch / 2))
            }
            cloud(w * 0.08f, h * 0.21f, 1f)
            cloud(w * 0.50f, h * 0.13f, 0.75f)
            cloud(w * 0.72f, h * 0.43f, 0.62f)

            val farHill = Path().apply {
                moveTo(0f, h * 0.67f)
                lineTo(w * 0.20f, h * 0.43f)
                lineTo(w * 0.38f, h * 0.65f)
                lineTo(w * 0.59f, h * 0.40f)
                lineTo(w * 0.83f, h * 0.67f)
                lineTo(w, h * 0.50f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(farHill, Color(0xFF8EC9A4))

            drawRect(
                color = Color(0xFF64A96E),
                topLeft = Offset(0f, h * 0.70f),
                size = Size(w, h * 0.30f)
            )
            drawRect(
                color = Color(0xFF8CCB71),
                topLeft = Offset(0f, h * 0.70f),
                size = Size(w, h * 0.065f)
            )

            // Low-poly voxel trees and blocky foliage create an original game-inspired scene.
            for (i in 0..8) {
                val x = w * (0.04f + i * 0.12f)
                val base = h * (0.77f + ((i % 3) * 0.025f))
                val trunkW = h * 0.045f
                val trunkH = h * (0.19f + (i % 2) * 0.04f)
                drawRect(Color(0xFF81553F), Offset(x, base - trunkH), Size(trunkW, trunkH))
                drawRoundRect(
                    color = if (i % 2 == 0) Color(0xFF498D58) else Color(0xFF68A85D),
                    topLeft = Offset(x - trunkW * 1.25f, base - trunkH - h * 0.11f),
                    size = Size(trunkW * 3.5f, h * 0.18f),
                    cornerRadius = CornerRadius(5f)
                )
                drawRect(
                    color = Color(0xFF8BCB78),
                    topLeft = Offset(x - trunkW * 0.7f, base - trunkH - h * 0.18f),
                    size = Size(trunkW * 2.6f, h * 0.08f)
                )
            }

            // Cherry-pink canopy blocks in the far background.
            for (i in 0..11) {
                val x = w * (0.02f + i * 0.087f)
                val y = h * (0.43f + (i % 3) * 0.045f)
                drawRoundRect(
                    color = if (i % 2 == 0) Color(0xFFECA5C8) else Color(0xFFF4C0D6),
                    topLeft = Offset(x, y),
                    size = Size(h * 0.08f, h * 0.065f),
                    cornerRadius = CornerRadius(3f)
                )
            }

            // Small square flowers along the foreground.
            for (i in 0..24) {
                val x = w * (0.025f + i * 0.039f)
                val y = h * (0.89f + (i % 4) * 0.018f)
                drawRect(
                    color = if (i % 3 == 0) Color(0xFFFFD7E8) else Color(0xFFFFE99A),
                    topLeft = Offset(x, y),
                    size = Size(h * 0.018f, h * 0.018f)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            repeat(3) { index ->
                Surface(
                    color = Color(0xAFFFFFFF),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        listOf("EXPLORE", "BUILD", "SURVIVE")[index],
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = TsiTealDark,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionCategoryCards(
    onFilterChange: (VersionFilter) -> Unit,
    selectedFilter: VersionFilter
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CategoryTile(
            title = "Releases",
            subtitle = "Stable builds",
            icon = Icons.Filled.CheckCircle,
            color = Color(0xFFE5F3EA),
            selected = selectedFilter == VersionFilter.RELEASE,
            modifier = Modifier.weight(1f),
            onClick = { onFilterChange(VersionFilter.RELEASE) }
        )
        CategoryTile(
            title = "Snapshots",
            subtitle = "Preview builds",
            icon = Icons.Filled.Memory,
            color = Color(0xFFE4F2F7),
            selected = selectedFilter == VersionFilter.SNAPSHOT,
            modifier = Modifier.weight(1f),
            onClick = { onFilterChange(VersionFilter.SNAPSHOT) }
        )
        CategoryTile(
            title = "Old versions",
            subtitle = "Alpha / Beta",
            icon = Icons.Filled.Computer,
            color = Color(0xFFF4EAF5),
            selected = selectedFilter == VersionFilter.OLD_ALPHA || selectedFilter == VersionFilter.OLD_BETA,
            modifier = Modifier.weight(1f),
            onClick = { onFilterChange(VersionFilter.OLD_ALPHA) }
        )
    }
}

@Composable
private fun CategoryTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (selected) TsiTeal else Color.Transparent,
        tween(220),
        label = "category-border"
    )
    Card(
        modifier = modifier
            .height(96.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = TsiTealDark, modifier = Modifier.size(20.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = TsiInk, fontSize = 12.sp, maxLines = 1)
                Text(subtitle, color = TsiMuted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VersionPickerPanel(
    modifier: Modifier,
    versions: List<MinecraftVersion>,
    selectedVersion: MinecraftVersion?,
    selectedFilter: VersionFilter,
    onFilterChange: (VersionFilter) -> Unit,
    onVersionSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean
) {
    var filterExpanded by remember { mutableStateOf(false) }
    var versionExpanded by remember { mutableStateOf(false) }
    // Keep the complete Mojang catalogue available; do not impose a UI entry cap.
    val menuVersions = versions

    Card(
        modifier = modifier.animateContentSize(tween(260)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("VERSION MANAGER", color = TsiTealDark, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(9.dp))
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filterExpanded = true },
                    color = Color(0xFFF4F8F9),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TsiLine)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedFilter.label, modifier = Modifier.weight(1f), color = TsiInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter", tint = TsiTeal)
                    }
                }
                DropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false }
                ) {
                    VersionFilter.entries.forEach { filter ->
                        DropdownMenuItem(
                            text = { Text(filter.label) },
                            onClick = {
                                onFilterChange(filter)
                                filterExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(9.dp))
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { versionExpanded = true },
                    color = Color(0xFFE7F5F8),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9BCBD5))
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("SELECTED VERSION", color = TsiTealDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(selectedVersion?.id ?: "No version found", color = TsiInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text("⌄", color = TsiTealDark, fontSize = 20.sp)
                    }
                }
                DropdownMenu(
                    expanded = versionExpanded,
                    onDismissRequest = { versionExpanded = false },
                    modifier = Modifier.height(320.dp)
                ) {
                    if (menuVersions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No matching versions") },
                            onClick = { versionExpanded = false }
                        )
                    } else {
                        menuVersions.forEach { version ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(version.id, fontWeight = FontWeight.SemiBold)
                                        Text(version.type.uppercase(), color = TsiMuted, fontSize = 9.sp)
                                    }
                                },
                                onClick = {
                                    onVersionSelect(version.id)
                                    versionExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Available versions", color = TsiInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(versions.size.toString(), color = TsiMuted, fontSize = 11.sp)
                IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh versions", tint = TsiTeal, modifier = Modifier.size(18.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                versions.take(6).forEach { version ->
                    val selected = version.id == selectedVersion?.id
                    val bg by animateColorAsState(
                        if (selected) Color(0xFFEAF5F7) else Color.Transparent,
                        tween(180),
                        label = "version-row"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .clickable { onVersionSelect(version.id) }
                            .padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            version.id,
                            modifier = Modifier.weight(1f),
                            color = TsiInk,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                        Text(
                            version.type.replace("_", " ").uppercase(),
                            color = TsiMuted,
                            fontSize = 8.sp
                        )
                    }
                }
            }
            Text(
                "Use the search bar above to jump to older versions.",
                color = TsiMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun StatusBanner(message: String, isLoading: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TsiLine)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = TsiTeal)
            } else {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF398B63))
            }
            Spacer(Modifier.width(9.dp))
            Text(message, color = TsiMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun NewsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(TsiPage)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Launcher updates", color = TsiInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Text("A clean place for release notes and Minecraft update links.", color = TsiMuted)
        listOf(
            "Java version catalogue" to "Version data is loaded directly from Mojang's official manifest.",
            "Bedrock integration" to "TSI checks the installed Android package and opens the official app.",
            "Android runtime" to "The Amethyst Android JVM, LWJGL and renderer bridge are included in engine builds; VM Controller checks device and version compatibility before launch."
        ).forEachIndexed { index, item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(250)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("0${index + 1}", color = TsiTeal, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Text(item.first, color = TsiInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(item.second, color = TsiMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    ramMb: Float,
    onRamChange: (Float) -> Unit,
    javaRuntime: String,
    onJavaRuntimeChange: (String) -> Unit,
    storageAccessRefreshKey: Int,
    legacyStoragePermissionGranted: Boolean,
    onRequestLegacyStoragePermission: () -> Unit
) {
    val context = LocalContext.current
    val hasAllFilesAccess = remember(storageAccessRefreshKey) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
    var javaMenuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .background(TsiPage)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", color = TsiInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, null, tint = TsiTeal)
                    Spacer(Modifier.width(9.dp))
                    Text("Java memory target", color = TsiInk, fontWeight = FontWeight.Bold)
                }
                Text("${ramMb.toInt()} MB", color = TsiTealDark, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                val maximumHeapMb = DevicePerformanceProfile.maxHeapMb(context)
                Slider(
                    value = ramMb.coerceIn(512f, maximumHeapMb.toFloat()),
                    onValueChange = onRamChange,
                    valueRange = 512f..maximumHeapMb.toFloat(),
                    steps = ((maximumHeapMb - 512) / 256 - 1).coerceAtLeast(0)
                )
                Text(
                    "Recommended: ${DevicePerformanceProfile.recommendedHeapMb(context)} MB. Device-safe limit: $maximumHeapMb MB. This is a JVM heap ceiling, not total game memory.",
                    color = TsiMuted,
                    fontSize = 11.sp
                )
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Computer, null, tint = TsiTeal)
                    Spacer(Modifier.width(9.dp))
                    Text("Preferred Java runtime", color = TsiInk, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { javaMenuExpanded = true }) {
                        Text(javaRuntime)
                    }
                    DropdownMenu(
                        expanded = javaMenuExpanded,
                        onDismissRequest = { javaMenuExpanded = false }
                    ) {
                        listOf("Auto", "Java 8", "Java 17", "Java 21", "Java 25").forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    onJavaRuntimeChange(value)
                                    javaMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "The selected Java runtime is checked against the Minecraft version before launch. TSI automatically chooses an installed compatible runtime when the preference is too old.",
                    color = TsiMuted,
                    fontSize = 11.sp
                )
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("File Manager access", color = TsiInk, fontWeight = FontWeight.Bold)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Text(
                        if (legacyStoragePermissionGranted) {
                            "Legacy shared-storage write permission is enabled."
                        } else {
                            "Android 9 and older require storage permission for the Amethyst game folder."
                        },
                        color = if (legacyStoragePermissionGranted) TsiTealDark else TsiMuted,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = onRequestLegacyStoragePermission,
                        enabled = !legacyStoragePermissionGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (legacyStoragePermissionGranted) "STORAGE PERMISSION ENABLED" else "REQUEST STORAGE PERMISSION")
                    }
                }
                Text(
                    if (hasAllFilesAccess) "Manage all files access is enabled."
                    else "Manage all files access is not enabled.",
                    color = if (hasAllFilesAccess) TsiTealDark else TsiMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "This optional Android permission allows access to shared-storage files. Android still blocks other apps' Android/data folders.",
                    color = TsiMuted,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val appSettings = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            try {
                                context.startActivity(appSettings)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                } catch (_: Exception) {
                                    // This Android build does not expose the special-access settings page.
                                }
                            }
                        }
                    },
                    enabled = !hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TsiTeal)
                ) {
                    Text(
                        when {
                            hasAllFilesAccess -> "FILE ACCESS ENABLED"
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "OPEN FILE ACCESS SETTINGS"
                            else -> "NOT REQUIRED ON THIS ANDROID VERSION"
                        }
                    )
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Advanced engine settings", color = TsiInk, fontWeight = FontWeight.Bold)
                Text(
                    "Open the existing Amethyst settings for renderer, video, controls, gamepad, mouse and JVM options.",
                    color = TsiMuted,
                    fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent().setClassName(context, "net.kdt.pojavlaunch.LauncherActivity")
                        intent.putExtra("tsi_open_engine_settings", true)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("OPEN ADVANCED ENGINE SETTINGS")
                }
            }
        }
    }
}
