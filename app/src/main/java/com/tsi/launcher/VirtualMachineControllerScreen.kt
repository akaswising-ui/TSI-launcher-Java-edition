package com.tsi.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsi.launcher.runtime.AmethystEngineBridge
import com.tsi.launcher.runtime.AndroidJreInstaller
import com.tsi.launcher.runtime.JavaRuntimeManager
import com.tsi.launcher.runtime.VersionVmInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VmInk = Color(0xFF183B4A)
private val VmMuted = Color(0xFF71838B)
private val VmGreen = Color(0xFF25845B)
private val VmAmber = Color(0xFFAE7729)

@Composable
fun VirtualMachineControllerScreen(
    modifier: Modifier = Modifier,
    versionId: String,
    instanceId: String,
    rendererId: String? = null,
    javaArgs: String? = null,
    controlLayout: String? = null,
    argsMode: Int = 0,
    sharedData: Boolean = false,
    ramMb: Int,
    runtimePreference: String,
    playerName: String,
    storageAccessRefreshKey: Int,
    onBackToDashboard: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { JavaRuntimeManager(context.applicationContext) }
    val jreInstaller = remember { AndroidJreInstaller(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var inspection by remember { mutableStateOf<VersionVmInspection?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var installingRuntime by remember { mutableStateOf(false) }
    var launchingGame by remember { mutableStateOf(false) }
    var runtimeInstallMessage by remember { mutableStateOf("") }
    var controllerMessage by remember {
        mutableStateOf("VM is stopped. Inspecting selected Minecraft installation…")
    }

    fun refreshInspection() {
        if (versionId.isBlank()) {
            inspection = null
            controllerMessage = "Select a Java version from Dashboard first."
            return
        }
        scope.launch {
            scanning = true
            inspection = null
            controllerMessage = "Checking version files, JREs, libraries and assets…"
            try {
                val result = withContext(Dispatchers.IO) {
                    manager.inspect(versionId, runtimePreference, instanceId)
                }
                inspection = result
                controllerMessage = if (result.readyToLaunch) {
                    "All launch checks passed. VM engine is ready."
                } else {
                    "VM is stopped. Some launch requirements are missing."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                inspection = null
                controllerMessage = "VM inspection failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(versionId, runtimePreference, storageAccessRefreshKey) {
        refreshInspection()
    }

    Column(
        modifier = modifier
            .background(Color(0xFFEAF4F7))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Virtual Machine Controller", color = VmInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Java Edition runtime and game process", color = VmMuted, fontSize = 12.sp)
            }
            Surface(color = Color(0xFFFFEBD7), shape = RoundedCornerShape(50)) {
                Text(
                    "STOPPED",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = VmAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            shape = RoundedCornerShape(21.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(3.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("SELECTED GAME", color = VmMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (versionId.isBlank()) "No version selected" else "Minecraft Java $versionId",
                    color = VmInk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        label = if (inspection?.clientJarExists == true) "CLIENT JAR OK" else "CLIENT JAR MISSING",
                        ok = inspection?.clientJarExists == true
                    )
                    StatusChip(
                        label = if (inspection?.metadataExists == true) "METADATA OK" else "METADATA MISSING",
                        ok = inspection?.metadataExists == true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, null, tint = VmGreen)
                    Spacer(Modifier.width(7.dp))
                    Text("Heap request: $ramMb MB", color = VmInk, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Required Java: " + (inspection?.requiredJavaMajor?.let { "Java $it" } ?: "Not resolved"),
                    color = VmInk,
                    fontSize = 13.sp
                )
                Text("Runtime preference: $runtimePreference", color = VmMuted, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { refreshInspection() },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (scanning) "CHECKING…" else "REFRESH VM STATUS")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(21.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("JAVA RUNTIME MANAGER", color = VmInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val runtimes = inspection?.detectedRuntimes.orEmpty()
                if (runtimes.isEmpty()) {
                    Text("No compatible Android JRE detected.", color = VmAmber, fontWeight = FontWeight.SemiBold)
                    Text(
                        "TSI installs Android OpenJDK runtimes in the same app-data runtime folder used by Amethyst. A desktop Java install is not an Android JVM.",
                        color = VmMuted,
                        fontSize = 11.sp
                    )
                } else {
                    runtimes.forEach { runtime ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFFF2F8F5), RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, tint = VmGreen)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Java " + runtime.majorVersion + " • " + runtime.version, color = VmInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(runtime.name + " • " + runtime.architecture, color = VmMuted, fontSize = 10.sp)
                            }
                            Text("DETECTED", color = VmGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                val neededJava = inspection?.requiredJavaMajor
                val isNeededRuntimeInstalled = neededJava != null &&
                    runtimes.any { it.majorVersion >= neededJava }
                val installableJava = neededJava?.let(jreInstaller::recommendedRuntimeMajor)
                if (neededJava != null && installableJava != null && !isNeededRuntimeInstalled) {
                    Button(
                        onClick = {
                            scope.launch {
                                installingRuntime = true
                                runtimeInstallMessage = "Preparing Java $installableJava download…"
                                try {
                                    withContext(Dispatchers.IO) {
                                        jreInstaller.downloadAndInstall(installableJava) { update ->
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                runtimeInstallMessage = update
                                            }
                                        }
                                    }
                                    inspection = withContext(Dispatchers.IO) {
                                        manager.inspect(versionId, runtimePreference, instanceId)
                                    }
                                    runtimeInstallMessage = "Java $installableJava installed. Rechecking the selected version."
                                } catch (error: Exception) {
                                    runtimeInstallMessage = "Runtime install failed: ${error.message ?: "unknown error"}"
                                } finally {
                                    installingRuntime = false
                                }
                            }
                        },
                        enabled = !installingRuntime,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = VmGreen)
                    ) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (installingRuntime) "INSTALLING JAVA $installableJava…" else if (installableJava == neededJava) "DOWNLOAD & INSTALL JAVA $installableJava" else "INSTALL JAVA $installableJava (FOR JAVA $neededJava)")
                    }
                }

                AnimatedVisibility(visible = runtimeInstallMessage.isNotBlank()) {
                    Text(runtimeInstallMessage, color = VmMuted, fontSize = 11.sp)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(21.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Terminal, null, tint = VmGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("LAUNCH PIPELINE", color = VmInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                val current = inspection
                RequirementRow("Client JAR", current?.clientJarExists == true)
                RequirementRow("Version metadata", current?.metadataExists == true)
                RequirementRow("Java runtime match", current?.compatibleRuntime != null)
                RequirementRow(
                    "Libraries (" + (current?.installedLibraryCount ?: 0) + "/" + (current?.libraryCount ?: 0) + ")",
                    current != null && current.libraryCount > 0 && current.installedLibraryCount >= current.libraryCount
                )
                RequirementRow("Asset index", current?.assetIndexExists == true)
                RequirementRow(
                    "Asset objects (" + (current?.assetObjectCount ?: 0) + "/" +
                        (current?.assetObjectTotal ?: 0) + ")",
                    current != null && current.assetObjectTotal > 0 &&
                        current.assetObjectCount >= current.assetObjectTotal
                )
                RequirementRow("Android JVM + LWJGL engine", current?.engineBridgeAvailable == true)
            }
        }

        AnimatedVisibility(
            visible = inspection?.blockers?.isNotEmpty() == true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E9)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("WHY THE VM CANNOT START", color = VmAmber, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    inspection?.blockers?.forEach { reason ->
                        Text("• $reason", color = Color(0xFF76552C), fontSize = 11.sp)
                    }
                }
            }
        }

        Button(
            onClick = {
                val ready = inspection
                val runtime = ready?.compatibleRuntime
                if (ready == null || !ready.readyToLaunch || runtime == null) {
                    controllerMessage = "Launch blocked by preflight. Resolve every missing requirement first."
                } else {
                    scope.launch {
                        launchingGame = true
                        try {
                            AmethystEngineBridge.prepare(context) { update ->
                                withContext(Dispatchers.Main.immediate) {
                                    controllerMessage = update
                                }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                controllerMessage = "Starting Minecraft $versionId in the Amethyst game surface…"
                                AmethystEngineBridge.launch(
                                    context = context,
                                    versionId = versionId,
                                    heapMb = ramMb.toFloat(),
                                    runtime = runtime,
                                    playerName = playerName,
                                    instanceId = instanceId,
                                    rendererId = rendererId,
                                    javaArgs = javaArgs,
                                    controlLayout = controlLayout,
                                    argsMode = argsMode,
                                    sharedData = sharedData
                                )
                            }
                        } catch (error: Exception) {
                            controllerMessage =
                                "Engine launch failed: ${error.cause?.message ?: error.message ?: "unknown error"}"
                        } finally {
                            launchingGame = false
                        }
                    }
                }
            },
            enabled = inspection?.readyToLaunch == true && !scanning && !launchingGame,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VmGreen,
                disabledContainerColor = Color(0xFFAABFC1)
            )
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (launchingGame) "PREPARING JAVA ENGINE…" else "START VIRTUAL MACHINE",
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = { controllerMessage = "Use the in-game Exit control to stop Minecraft and return to TSI." },
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.width(7.dp))
            Text("STOP VM")
        }

        Text(controllerMessage, color = VmMuted, fontSize = 11.sp)
        TextButton(onClick = onBackToDashboard, modifier = Modifier.fillMaxWidth()) {
            Text("BACK TO DASHBOARD")
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Surface(
        color = if (ok) Color(0xFFE8F5ED) else Color(0xFFFFF0E0),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = if (ok) VmGreen else VmAmber,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun RequirementRow(label: String, ready: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ready) Icons.Filled.CheckCircle else Icons.Filled.Stop,
            contentDescription = null,
            tint = if (ready) VmGreen else VmAmber
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = VmInk, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            if (ready) "READY" else "MISSING",
            color = if (ready) VmGreen else VmAmber,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}
