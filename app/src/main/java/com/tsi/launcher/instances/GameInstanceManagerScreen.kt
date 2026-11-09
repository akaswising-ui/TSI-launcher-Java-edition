package com.tsi.launcher.instances

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val InstancePage = Color(0xFFEAF4F7)
private val InstanceInk = Color(0xFF183B4A)
private val InstanceMuted = Color(0xFF71838B)
private val InstanceAccent = Color(0xFF087F9C)

@Composable
fun GameInstanceManagerScreen(
    modifier: Modifier = Modifier,
    selectedVersionId: String,
    onInstanceSelected: (GameInstance) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { GameInstanceRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var instances by remember { mutableStateOf<List<GameInstance>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var nameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<GameInstance?>(null) }
    var deleteTarget by remember { mutableStateOf<GameInstance?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var cloneSelected by rememberSaveable { mutableStateOf(false) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (selectedVersionId.isNotBlank()) repository.ensureDefault(selectedVersionId)
                    repository.list()
                }
                instances = loaded
                selectedId = repository.selected()?.id.orEmpty()
            } catch (error: Exception) {
                message = error.message ?: "Could not load game instances."
            }
        }
    }

    LaunchedEffect(selectedVersionId) { refresh() }

    fun importArchive(uri: Uri) {
        scope.launch {
            busy = true
            message = "Reading MultiMC / Prism Launcher archive…"
            try {
                val imported = withContext(Dispatchers.IO) {
                    val temporary = File(context.cacheDir, "tsi-mmc-" + System.currentTimeMillis() + ".zip")
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Android could not open the selected ZIP.")
                        input.use { source ->
                            temporary.outputStream().buffered().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                                destination.flush()
                            }
                        }
                        repository.importMultiMc(temporary)
                    } finally {
                        temporary.delete()
                    }
                }
                instances = withContext(Dispatchers.IO) { repository.list() }
                selectedId = imported.id
                onInstanceSelected(imported)
                message = if (imported.loaderId == null) {
                    "Imported " + imported.name + " with Minecraft " + imported.versionId + "."
                } else {
                    "Imported " + imported.name + ". Loader metadata: " + imported.loaderId +
                        " " + (imported.loaderVersion ?: "") +
                        ". The compatible loader profile must exist or be installed before Play."
                }
            } catch (error: Exception) {
                message = error.message ?: "MultiMC / Prism import failed."
            } finally {
                busy = false
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importArchive(uri) }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val exported = File(context.cacheDir, "tsi-instance-export-" + System.currentTimeMillis() + ".zip")
                        try {
                            repository.exportMultiMc(id, exported)
                            val output = context.contentResolver.openOutputStream(uri)
                                ?: throw IOException("Android could not create the destination file.")
                            output.use { sink -> exported.inputStream().use { source -> source.copyTo(sink) } }
                        } finally {
                            exported.delete()
                        }
                    }
                    message = "Instance exported as a MultiMC-compatible ZIP."
                } catch (error: Exception) {
                    message = error.message ?: "Instance export failed."
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(InstancePage).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Game Instances", color = InstanceInk, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text("Separate saves, mods, configs and settings for each profile.", color = InstanceMuted, fontSize = 11.sp)
            }
            TextButton(onClick = onBack) { Text("DONE") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    nameInput = ""
                    cloneSelected = false
                    renameTarget = null
                    nameDialog = true
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("NEW INSTANCE") }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                androidx.compose.material3.Icon(Icons.Filled.FileUpload, null)
                Spacer(Modifier.width(5.dp))
                Text("IMPORT ZIP")
            }
        }

        Surface(color = Color.White, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Filled.Archive, null, tint = InstanceAccent)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Imports and exports MultiMC / Prism instance ZIP files.",
                    color = InstanceMuted,
                    fontSize = 11.sp
                )
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(10.dp))
                Text("Working with instance files…", color = InstanceMuted, fontSize = 12.sp)
            }
        }
        if (message.isNotBlank()) {
            Text(message, color = InstanceAccent, fontSize = 11.sp)
        }

        if (instances.isEmpty()) {
            Text("No game instances yet. Create one or import a MultiMC / Prism ZIP.", color = InstanceMuted)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(instances, key = { it.id }) { instance ->
                val selected = instance.id == selectedId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Color(0xFFD9EEF2) else Color.White
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (selected) InstanceAccent else InstanceMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(instance.name, color = InstanceInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "Minecraft " + instance.versionId +
                                        (if (instance.loaderId != null) " • " + instance.loaderId.substringAfterLast('.') else ""),
                                    color = InstanceMuted,
                                    fontSize = 10.sp
                                )
                            }
                            if (instance.pinned) {
                                androidx.compose.material3.Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = InstanceAccent)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { repository.select(instance.id) }
                                            selectedId = instance.id
                                            onInstanceSelected(instance)
                                            message = "Selected " + instance.name + "."
                                        } catch (error: Exception) {
                                            message = error.message ?: "Could not select instance."
                                        }
                                    }
                                },
                                enabled = !busy && !selected,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (selected) "SELECTED" else "SELECT") }
                            OutlinedButton(
                                onClick = {
                                    pendingExportId = instance.id
                                    exportPicker.launch(instance.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip")
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                androidx.compose.material3.Icon(Icons.Filled.FileDownload, null)
                                Spacer(Modifier.width(4.dp))
                                Text("EXPORT")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = {
                                renameTarget = instance
                                nameInput = instance.name
                                nameDialog = true
                            }, enabled = !busy) {
                                androidx.compose.material3.Icon(Icons.Filled.Edit, null)
                                Text("Rename")
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val updated = withContext(Dispatchers.IO) {
                                            repository.setPinned(instance.id, !instance.pinned)
                                        }
                                        instances = withContext(Dispatchers.IO) { repository.list() }
                                        message = if (updated.pinned) "Instance pinned." else "Instance unpinned."
                                    } catch (error: Exception) {
                                        message = error.message ?: "Could not update pin state."
                                    }
                                }
                            }, enabled = !busy) { Text(if (instance.pinned) "Unpin" else "Pin") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { deleteTarget = instance }, enabled = !busy) {
                                androidx.compose.material3.Icon(Icons.Filled.Delete, null, tint = Color(0xFFB13B3B))
                                Text("Delete", color = Color(0xFFB13B3B))
                            }
                        }
                    }
                }
            }
        }
    }

    if (nameDialog) {
        AlertDialog(
            onDismissRequest = { nameDialog = false },
            title = { Text(if (renameTarget == null) "Create instance" else "Rename instance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(48) },
                        label = { Text("Instance name") },
                        singleLine = true
                    )
                    if (renameTarget == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cloneSelected, onCheckedChange = { cloneSelected = it })
                            Text("Copy current saves and mods", color = InstanceMuted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            val updated = withContext(Dispatchers.IO) {
                                val target = renameTarget
                                if (target != null) {
                                    repository.rename(target.id, nameInput)
                                } else {
                                    repository.create(nameInput, selectedVersionId, cloneSelected)
                                }
                            }
                            instances = withContext(Dispatchers.IO) { repository.list() }
                            if (renameTarget == null) {
                                withContext(Dispatchers.IO) { repository.select(updated.id) }
                                selectedId = updated.id
                                onInstanceSelected(updated)
                            }
                            message = if (renameTarget == null) "Instance created." else "Instance renamed."
                            nameDialog = false
                            renameTarget = null
                        } catch (error: Exception) {
                            message = error.message ?: "Could not save instance."
                        }
                    }
                }, enabled = !busy && nameInput.isNotBlank()) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = false; renameTarget = null }) { Text("CANCEL") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete " + target.name + "?") },
            text = { Text("This removes that instance's saves, mods, configs and settings. Shared Minecraft versions and libraries are kept.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            val nextId = withContext(Dispatchers.IO) { repository.delete(target.id) }
                            instances = withContext(Dispatchers.IO) { repository.list() }
                            val next = instances.firstOrNull { it.id == nextId }
                            selectedId = next?.id.orEmpty()
                            if (next != null) onInstanceSelected(next)
                            message = "Instance deleted. Shared version files were kept."
                        } catch (error: Exception) {
                            message = error.message ?: "Could not delete instance."
                        } finally {
                            deleteTarget = null
                        }
                    }
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") } }
        )
    }
}
