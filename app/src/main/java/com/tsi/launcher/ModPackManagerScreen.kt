package com.tsi.launcher

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tsi.launcher.instances.GameInstance
import com.tsi.launcher.instances.GameInstanceRepository
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles
import java.io.File
import java.io.IOException

private val PackPage = Color(0xFF16191A)
private val PackCard = Color(0xFF292E2F)
private val PackText = Color(0xFFF0F3F0)
private val PackMuted = Color(0xFFAAB5AF)
private val PackGreen = Color(0xFF9FC18E)

@Composable
fun ModPackManagerScreen(
    modifier: Modifier = Modifier,
    profileName: String,
    searchQuery: String,
    onInstancesClick: () -> Unit,
    onInstanceImported: (GameInstance) -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val api = remember {
        CommonApi(context.getString(net.kdt.pojavlaunch.R.string.curseforge_api_key))
    }
    val instanceRepository = remember { GameInstanceRepository(appContext) }

    var packs by remember { mutableStateOf<List<ModItem>>(emptyList()) }
    var pageResult by remember { mutableStateOf<SearchResult?>(null) }
    var totalResults by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<ModItem?>(null) }
    var selectedDetail by remember { mutableStateOf<ModDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var selectedVersion by remember { mutableIntStateOf(0) }

    fun makeFilters(query: String): SearchFilters {
        return SearchFilters().apply {
            isModpack = true
            name = query.trim()
        }
    }

    LaunchedEffect(searchQuery) {
        delay(350)
        loading = true
        loadingMore = false
        searchError = ""
        pageResult = null
        try {
            val result = withContext(Dispatchers.IO) {
                api.searchMod(makeFilters(searchQuery))
            }
            pageResult = result
            packs = result?.results?.toList().orEmpty()
            totalResults = result?.totalResultCount ?: 0
            if (result == null) {
                searchError = "Modrinth and CurseForge did not return a usable result. Check the connection and retry."
            }
        } catch (error: Exception) {
            packs = emptyList()
            totalResults = 0
            searchError = error.message ?: "Could not search modpacks."
        } finally {
            loading = false
        }
    }

    fun openPack(item: ModItem) {
        selectedItem = item
        selectedDetail = null
        selectedVersion = 0
        detailLoading = true
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { api.getModDetails(item) }
                selectedDetail = detail
                if (detail == null || detail.versionNames.isNullOrEmpty()) {
                    statusMessage = "No installable modpack versions were returned for " + item.title + "."
                }
            } catch (error: Exception) {
                statusMessage = error.message ?: "Could not load modpack versions."
            } finally {
                detailLoading = false
            }
        }
    }

    fun findNewAmethystProfile(previousKeys: Set<String>): Triple<String, String, String> {
        val profiles = LauncherProfiles.mainProfileJson.profiles
        val candidate = profiles.entries.firstOrNull { (key, profile) ->
            key !in previousKeys &&
                profile.gameDir?.replace('\\', '/')?.contains("custom_instances/") == true
        } ?: throw IOException(
            "The modpack installer did not create a new selectable Java profile. " +
                "This usually means the archive has no supported loader metadata."
        )
        val profile = candidate.value
        val name = profile.name?.takeIf { it.isNotBlank() } ?: candidate.key
        val version = profile.lastVersionId?.takeIf { it.isNotBlank() }
            ?: throw IOException("The imported modpack profile has no Minecraft version.")
        val gameDirectory = profile.gameDir?.takeIf { it.isNotBlank() }
            ?: throw IOException("The imported modpack profile has no game directory.")
        return Triple(name, version, gameDirectory)
    }

    fun importSelectedFile(uri: Uri) {
        scope.launch {
            importing = true
            statusMessage = "Importing modpack archive..." 
            try {
                val result = withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        } else null
                    }.orEmpty()

                    val extension = if (displayName.endsWith(".mrpack", true)) ".mrpack" else ".zip"
                    val archive = File(context.cacheDir, "tsi-modpack-" + System.currentTimeMillis() + extension)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Android could not open the selected archive.")
                        input.use { source ->
                            archive.outputStream().buffered().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                                destination.flush()
                            }
                        }
                        if (!archive.isFile || archive.length() == 0L) {
                            throw IOException("The selected modpack archive is empty.")
                        }

                        LauncherProfiles.load()
                        val previousKeys = LauncherProfiles.mainProfileJson.profiles.keys.toSet()
                        val loader = api.importModpack(archive)
                        if (loader != null) {
                            val task = loader.getDownloadTask(
                                NotificationDownloadListener(appContext, loader)
                            )
                            task?.run()
                        }
                        Pair(loader, previousKeys)
                    } finally {
                        archive.delete()
                    }
                }
                LauncherProfiles.load()
                val importedInstance = withContext(Dispatchers.IO) {
                    val (name, version, gameDirectory) = findNewAmethystProfile(result.second)
                    instanceRepository.importInstalledModpack(name, version, gameDirectory)
                }
                onInstanceImported(importedInstance)
                statusMessage = if (result.first == null) {
                    "Modpack imported as " + importedInstance.name + "."
                } else {
                    "Modpack imported as " + importedInstance.name + " (" + result.first.modLoaderTypeName() +
                        "). Forge/NeoForge may finish through the installer notification."
                }
            } catch (error: Exception) {
                statusMessage = error.message ?: "Modpack import failed."
            } finally {
                importing = false
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSelectedFile(uri)
    }

    Column(
        modifier = modifier
            .background(PackPage)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Java Edition Mod Manager", color = PackText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Search real Modrinth and CurseForge modpacks", color = PackMuted, fontSize = 11.sp)
            }
            Surface(
                color = Color(0xFF2A3030),
                shape = RoundedCornerShape(30.dp)
            ) {
                Text(
                    profileName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = PackText,
                    fontSize = 11.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onInstancesClick,
                modifier = Modifier.weight(1f)
            ) { Text("INSTANCES") }
            Button(
                onClick = { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                enabled = !importing,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PackGreen)
            ) {
                Text(if (importing) "IMPORTING…" else "IMPORT .ZIP / .MRPACK", color = Color(0xFF172117), fontSize = 10.sp)
            }
        }

        OutlinedButton(
            onClick = {
                val intent = Intent().setClassName(context, "net.kdt.pojavlaunch.LauncherActivity")
                intent.putExtra("tsi_open_modloader_manager", true)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OPEN FORGE / FABRIC / QUILT / NEOFORGE / OPTIFINE INSTALLERS", fontSize = 10.sp)
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search from the launcher search bar") },
            singleLine = true
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MODPACK CATALOGUE", color = PackText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (loading) "Searching…" else packs.size.toString() + " shown / " + totalResults + " results",
                    color = PackMuted,
                    fontSize = 10.sp
                )
            }
            OutlinedButton(
                onClick = { statusMessage = "Search refreshes automatically when the top search query changes." },
                enabled = !loading && !loadingMore
            ) { Text("INFO") }
        }

        if (searchError.isNotBlank()) {
            Text(searchError, color = Color(0xFFFFC28C), fontSize = 11.sp)
            OutlinedButton(onClick = {
                scope.launch {
                    loading = true
                    searchError = ""
                    try {
                        val result = withContext(Dispatchers.IO) { api.searchMod(makeFilters(searchQuery)) }
                        pageResult = result
                        packs = result?.results?.toList().orEmpty()
                        totalResults = result?.totalResultCount ?: 0
                        if (result == null) searchError = "The modpack service returned no results."
                    } catch (error: Exception) {
                        searchError = error.message ?: "Search failed."
                    } finally {
                        loading = false
                    }
                }
            }) { Text("RETRY SEARCH") }
        }

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, color = PackGreen, fontSize = 11.sp)
        }

        if (loading && packs.isEmpty()) {
            Text("Loading live modpack listings…", color = PackMuted, fontSize = 12.sp)
        } else if (!loading && packs.isEmpty() && searchError.isBlank()) {
            Text("No modpacks matched this search.", color = PackMuted, fontSize = 12.sp)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(packs, key = { it.apiSource.toString() + "-" + it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openPack(item) },
                    shape = RoundedCornerShape(15.dp),
                    colors = CardDefaults.cardColors(containerColor = PackCard)
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.title ?: "Untitled modpack",
                                modifier = Modifier.weight(1f),
                                color = PackText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (item.apiSource == 0) "MODRINTH" else "CURSEFORGE",
                                color = PackGreen,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(
                            item.description ?: "No description supplied by the modpack provider.",
                            color = PackMuted,
                            fontSize = 10.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("VIEW VERSIONS  ›", color = PackGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (packs.size < totalResults && pageResult != null) {
                item {
                    Button(
                        onClick = {
                            val previous = pageResult
                            if (previous != null) {
                                scope.launch {
                                    loadingMore = true
                                    try {
                                        val next = withContext(Dispatchers.IO) {
                                            api.searchMod(makeFilters(searchQuery), previous)
                                        }
                                        if (next != null) {
                                            val nextItems = next.results?.toList().orEmpty()
                                            packs = (packs + nextItems).distinctBy { it.apiSource.toString() + "-" + it.id }
                                            pageResult = next
                                            totalResults = next.totalResultCount
                                        }
                                    } catch (error: Exception) {
                                        searchError = error.message ?: "Could not load more modpacks."
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            }
                        },
                        enabled = !loadingMore && !loading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PackGreen)
                    ) {
                        Text(if (loadingMore) "LOADING…" else "LOAD MORE", color = Color(0xFF172117))
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        AlertDialog(
            onDismissRequest = {
                selectedItem = null
                selectedDetail = null
            },
            title = { Text(item.title ?: "Modpack details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(item.description ?: "No description supplied.", fontSize = 12.sp)
                    Text(
                        if (detailLoading) "Loading available versions…" else "Select a Minecraft/modpack version:",
                        color = PackMuted,
                        fontSize = 11.sp
                    )
                    val versions = selectedDetail?.versionNames.orEmpty()
                    if (!detailLoading && versions.isEmpty()) {
                        Text("No compatible versions were returned.", color = Color(0xFFB56C3D), fontSize = 11.sp)
                    }
                    LazyColumn(
                        modifier = Modifier.height(230.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(versions.size) { index ->
                            val selected = selectedVersion == index
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedVersion = index },
                                color = if (selected) Color(0xFFE4F0E0) else Color(0xFFF0F3F1),
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Text(
                                    versions[index],
                                    modifier = Modifier.padding(9.dp),
                                    color = Color(0xFF1A2820),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !detailLoading && selectedDetail?.versionNames?.indices?.contains(selectedVersion) == true,
                    onClick = {
                        val detail = selectedDetail
                        if (detail != null && selectedVersion in detail.versionNames.indices) {
                            scope.launch {
                                importing = true
                                statusMessage = "Downloading and installing modpack…"
                                try {
                                    val imported = withContext(Dispatchers.IO) {
                                        LauncherProfiles.load()
                                        val previousKeys = LauncherProfiles.mainProfileJson.profiles.keys.toSet()
                                        val loader = api.installMod(detail, selectedVersion)
                                        if (loader != null) {
                                            val task = loader.getDownloadTask(
                                                NotificationDownloadListener(appContext, loader)
                                            )
                                            task?.run()
                                        }
                                        LauncherProfiles.load()
                                        val (name, version, gameDirectory) = findNewAmethystProfile(previousKeys)
                                        val instance = instanceRepository.importInstalledModpack(
                                            name,
                                            version,
                                            gameDirectory
                                        )
                                        instance to loader
                                    }
                                    onInstanceImported(imported.first)
                                    statusMessage = if (imported.second == null) {
                                        "Modpack installed as " + imported.first.name + "."
                                    } else {
                                        "Modpack installed as " + imported.first.name + " (" +
                                            imported.second.modLoaderTypeName() +
                                            "). Forge/NeoForge may finish through the installer notification."
                                    }
                                    selectedItem = null
                                    selectedDetail = null
                                } catch (error: Exception) {
                                    statusMessage = error.message ?: "Could not install the modpack."
                                } finally {
                                    importing = false
                                }
                            }
                        }
                    }
                ) { Text("INSTALL") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedItem = null
                    selectedDetail = null
                }) { Text("CLOSE") }
            }
        )
    }
}

private fun net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader.modLoaderTypeName(): String {
    return when (modLoaderType) {
        net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader.MOD_LOADER_FORGE -> "Forge"
        net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader.MOD_LOADER_FABRIC -> "Fabric"
        net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader.MOD_LOADER_QUILT -> "Quilt"
        net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader.MOD_LOADER_NEOFORGE -> "NeoForge"
        else -> "modloader"
    }
}
