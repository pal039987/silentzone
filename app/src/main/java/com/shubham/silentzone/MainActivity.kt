package com.shubham.silentzone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shubham.silentzone.data.*
import com.shubham.silentzone.engine.ZoneEvaluatorEngine
import com.shubham.silentzone.util.RadioFingerprintScanner
import com.shubham.silentzone.util.RadioSnapshot
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ZoneEvaluatorEngine.evaluateNow(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF34D399),
                    onPrimary = Color(0xFF022C22),
                    primaryContainer = Color(0xFF064E3B),
                    onPrimaryContainer = Color(0xFFD1FAE5),
                    secondary = Color(0xFF60A5FA),
                    surface = Color(0xFF0D1321),
                    background = Color(0xFF070B14),
                    surfaceVariant = Color(0xFF151E32),
                    outline = Color(0xFF28354F)
                )
            ) {
                SilentZoneApp()
            }
        }
    }
}

fun hasDndPermission(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
}

fun openDndSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

fun categoryIcon(category: String): ImageVector {
    return when (category.lowercase()) {
        "college" -> Icons.Default.School
        "office" -> Icons.Default.BusinessCenter
        "library" -> Icons.Default.LocalLibrary
        "mandir" -> Icons.Default.SelfImprovement
        "sleep" -> Icons.Default.Bedtime
        else -> Icons.Default.NotificationsOff
    }
}

fun categoryAccentColor(category: String): Color {
    return when (category.lowercase()) {
        "college" -> Color(0xFF60A5FA)
        "office" -> Color(0xFF34D399)
        "library" -> Color(0xFFA78BFA)
        "mandir" -> Color(0xFFFBBF24)
        "sleep" -> Color(0xFFF472B6)
        else -> Color(0xFF38BDF8)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilentZoneApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repo = remember { ZoneRepository(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf(repo.getRules()) }
    var status by remember { mutableStateOf(repo.getStatus()) }
    var vipConfig by remember { mutableStateOf(repo.getVipConfig()) }
    var dndGranted by remember { mutableStateOf(hasDndPermission(context)) }

    fun refreshAll() {
        dndGranted = hasDndPermission(context)
        ZoneEvaluatorEngine.evaluateNow(context)
        rules = repo.getRules()
        status = repo.getStatus()
        vipConfig = repo.getVipConfig()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshAll()
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF10B981), Color(0xFF059669))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "SilentZone",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp,
                                color = Color.White
                            )
                            Text(
                                "Zero-GPS Smart Audio Guard",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = {
                            refreshAll()
                            Toast.makeText(context, "Zones synced", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFF34D399)
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync Now", modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0D1321),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; refreshAll() },
                    icon = {
                        Icon(
                            if (selectedTab == 0) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                            contentDescription = null
                        )
                    },
                    label = { Text("Dashboard", fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            if (selectedTab == 1) Icons.Filled.AddCircle else Icons.Outlined.AddCircleOutline,
                            contentDescription = null
                        )
                    },
                    label = { Text("Add Zone", fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            if (selectedTab == 2) Icons.Filled.Shield else Icons.Outlined.Shield,
                            contentDescription = null
                        )
                    },
                    label = { Text("VIP Guard", fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    status = status,
                    rules = rules,
                    vipConfig = vipConfig,
                    dndGranted = dndGranted,
                    onToggleRule = { id, enabled ->
                        val turnedOffOther = repo.toggleRule(id, enabled)
                        refreshAll()
                        if (turnedOffOther != null) {
                            Toast.makeText(
                                context,
                                "Switched: $turnedOffOther turned OFF",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onEditRule = { updatedRule ->
                        repo.addOrUpdateRule(updatedRule)
                        refreshAll()
                        Toast.makeText(context, "Updated: ${updatedRule.name}", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteRule = { id ->
                        repo.deleteRule(id)
                        refreshAll()
                    },
                    onNavigateAdd = { selectedTab = 1 },
                    onRequestPerms = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CALL_LOG
                            )
                        )
                    },
                    onQuickRestoreRing = {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (nm.isNotificationPolicyAccessGranted) {
                            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        }
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        Toast.makeText(context, "Normal Ring Restored", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> AddZoneScreen(
                    dndGranted = dndGranted,
                    onSaveRule = { newRule ->
                        repo.addOrUpdateRule(newRule)
                        refreshAll()
                        selectedTab = 0
                        if (newRule.profile == SilenceProfile.TOTAL_SILENT && !hasDndPermission(context)) {
                            Toast.makeText(
                                context,
                                "Allow SilentZone in Do Not Disturb settings now",
                                Toast.LENGTH_LONG
                            ).show()
                            openDndSettings(context)
                        } else {
                            Toast.makeText(context, "${newRule.name} saved & active!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRequestLocationPerm = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    }
                )
                2 -> VipGuardScreen(
                    vipConfig = vipConfig,
                    onUpdateVip = { updated ->
                        repo.saveVipConfig(updated)
                        vipConfig = updated
                    }
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    status: ActiveZoneStatus,
    rules: List<SilentZoneRule>,
    vipConfig: VipGuardConfig,
    dndGranted: Boolean,
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (SilentZoneRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onNavigateAdd: () -> Unit,
    onRequestPerms: () -> Unit,
    onQuickRestoreRing: () -> Unit
) {
    val context = LocalContext.current
    var editingRule by remember { mutableStateOf<SilentZoneRule?>(null) }
    val hasPhone = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val cardGradient = when {
        status.isMutedByApp -> Brush.linearGradient(
            listOf(Color(0xFF064E3B), Color(0xFF022C22))
        )
        !dndGranted -> Brush.linearGradient(
            listOf(Color(0xFF451A03), Color(0xFF271002))
        )
        else -> Brush.linearGradient(
            listOf(Color(0xFF1E293B), Color(0xFF0F172A))
        )
    }

    val borderStrokeColor by animateColorAsState(
        targetValue = when {
            status.isMutedByApp -> Color(0xFF10B981).copy(alpha = 0.55f)
            !dndGranted -> Color(0xFFF59E0B).copy(alpha = 0.55f)
            else -> Color(0xFF334155)
        },
        label = "borderColor"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Status Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, borderStrokeColor),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardGradient, RoundedCornerShape(24.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .scale(if (status.isMutedByApp) pulseScale else 1f)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        status.isMutedByApp -> Color(0xFF10B981)
                                        !dndGranted -> Color(0xFFD97706)
                                        else -> Color(0xFF334155)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (status.isMutedByApp)
                                    Icons.Default.VolumeOff else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (status.isMutedByApp)
                                    Color(0xFF10B981).copy(alpha = 0.2f)
                                else Color.White.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = if (status.isMutedByApp) "ACTIVE SILENT ZONE" else "STANDBY",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (status.isMutedByApp) Color(0xFF6EE7B7) else Color(0xFF94A3B8)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when {
                                    status.isMutedByApp -> status.activeZoneName ?: "Muted"
                                    !dndGranted -> "DND Access Needed"
                                    else -> "Normal Ring Mode"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    status.isMutedByApp -> status.matchedReason
                                    !dndGranted -> "Grant DND access to enable auto-silent"
                                    else -> "Monitoring Wi-Fi & Schedules silently"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(12.dp))

                    // Telemetry Pills Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChipPill(
                            icon = Icons.Default.BatteryChargingFull,
                            label = "0% GPS",
                            active = true,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChipPill(
                            icon = Icons.Default.DoNotDisturbOn,
                            label = if (dndGranted) "DND Ready" else "DND Off",
                            active = dndGranted,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChipPill(
                            icon = Icons.Default.Shield,
                            label = if (vipConfig.enabled) "VIP (${vipConfig.contacts.size})" else "VIP Off",
                            active = vipConfig.enabled,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (status.isMutedByApp) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onQuickRestoreRing,
                            border = BorderStroke(1.dp, Color(0xFF6EE7B7).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF6EE7B7))
                            Spacer(Modifier.width(6.dp))
                            Text("Quick Unmute Now", color = Color(0xFF6EE7B7), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Permissions Banner (if needed)
        if (!dndGranted || !hasPhone || !ignoringBattery) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF271705)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Complete One-Time Setup",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A)
                            )
                        }
                        if (!dndGranted) {
                            Button(
                                onClick = { openDndSettings(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B),
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DoNotDisturbOn, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Allow Do Not Disturb (DND)", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (!hasPhone) {
                            OutlinedButton(
                                onClick = onRequestPerms,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable VIP Call Detection", color = Color(0xFFFDE68A))
                            }
                        }
                        if (!ignoringBattery) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Allow Background Auto-Switch", color = Color(0xFFFDE68A))
                            }
                        }
                    }
                }
            }
        }

        // Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saved Zones",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "${rules.size}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onNavigateAdd,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Zone", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Color(0xFF28354F)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateAdd() }
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AddLocationAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Create Your First Silent Zone", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Automate silent mode via Wi-Fi or Schedule with Location OFF.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                ZoneRuleCard(
                    rule = rule,
                    onToggle = { onToggleRule(rule.id, it) },
                    onEdit = { editingRule = rule },
                    onDelete = { onDeleteRule(rule.id) }
                )
            }
        }
    }

    editingRule?.let { target ->
        EditZoneDialog(
            rule = target,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                onEditRule(updated)
                editingRule = null
            }
        )
    }
}

@Composable
fun StatusChipPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) Color.White.copy(alpha = 0.08f) else Color(0xFFEF4444).copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color(0xFF6EE7B7) else Color(0xFFFCA5A5),
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) Color.White else Color(0xFFFCA5A5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ZoneRuleCard(
    rule: SilentZoneRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = categoryAccentColor(rule.category)
    val timeText = String.format(
        Locale.US,
        "%02d:%02d – %02d:%02d",
        rule.startHour, rule.startMinute, rule.endHour, rule.endMinute
    )
    val hasCustomTime = !(rule.startHour == 0 && rule.startMinute == 0 && rule.endHour == 23 && rule.endMinute == 59 && rule.activeDays.size == 7)
    val triggerSummary = when (rule.triggerType) {
        TriggerType.WIFI_ANY -> {
            val label = rule.wifiSsid?.takeIf { it.isNotBlank() } ?: "Locked Wi-Fi Router"
            if (hasCustomTime) "$label • $timeText • Location OFF" else "$label • Location OFF"
        }
        TriggerType.SCHEDULE -> "$timeText • Location OFF"
        TriggerType.TOWER_WIFI -> "${rule.cellSignatures.size} Towers • Location ON"
    }
    val triggerIcon = when (rule.triggerType) {
        TriggerType.WIFI_ANY -> Icons.Default.Wifi
        TriggerType.SCHEDULE -> Icons.Default.Schedule
        TriggerType.TOWER_WIFI -> Icons.Default.CellTower
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            if (rule.isEnabled) accent.copy(alpha = 0.45f) else Color(0xFF28354F)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isEnabled) Color(0xFF151E32) else Color(0xFF0F1624)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = if (rule.isEnabled) 0.18f else 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    categoryIcon(rule.category),
                    contentDescription = null,
                    tint = if (rule.isEnabled) accent else Color(0xFF64748B),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (rule.isEnabled) Color.White else Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (rule.profile == SilenceProfile.TOTAL_SILENT)
                            Color(0xFF10B981).copy(alpha = 0.16f)
                        else Color(0xFF60A5FA).copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (rule.profile == SilenceProfile.VIBRATE_ONLY) "Vibrate" else "Total Silent",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (rule.profile == SilenceProfile.TOTAL_SILENT)
                                Color(0xFF6EE7B7) else Color(0xFF93C5FD)
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        triggerIcon,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        triggerSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
            Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit Place Name",
                    tint = Color(0xFF93C5FD),
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFF87171).copy(alpha = 0.85f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
fun AddZoneScreen(
    dndGranted: Boolean,
    onSaveRule: (SilentZoneRule) -> Unit,
    onRequestLocationPerm: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("College") }
    var triggerType by remember { mutableStateOf(TriggerType.WIFI_ANY) }
    var profile by remember { mutableStateOf(SilenceProfile.TOTAL_SILENT) }
    var snapshot by remember { mutableStateOf(RadioSnapshot(false, emptyList(), null, null)) }

    var startHour by remember { mutableIntStateOf(9) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(18) }
    var endMinute by remember { mutableIntStateOf(0) }
    var selectedDays by remember { mutableStateOf(setOf(2, 3, 4, 5, 6, 7)) }
    var limitWifiByTime by remember { mutableStateOf(false) }

    val presetCategories = listOf("College", "Office", "Library", "Mandir", "Sleep")
    var customCategory by remember { mutableStateOf("") }
    var isCustomCategory by remember { mutableStateOf(false) }
    val dayPairs = listOf(
        2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat", 1 to "Sun"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Create Silent Zone",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                "Choose a place preset, trigger method, and silence profile.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. Place Category & Custom Name", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetCategories) { cat ->
                            FilterChip(
                                selected = !isCustomCategory && selectedCategory == cat,
                                onClick = {
                                    isCustomCategory = false
                                    selectedCategory = cat
                                    if (name.isBlank() || presetCategories.contains(name)) name = cat
                                },
                                leadingIcon = {
                                    Icon(categoryIcon(cat), contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(cat) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = isCustomCategory,
                                onClick = { isCustomCategory = true },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("Custom") }
                            )
                        }
                    }
                    if (isCustomCategory) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customCategory,
                            onValueChange = {
                                customCategory = it
                                selectedCategory = it.ifBlank { "Custom" }
                            },
                            label = { Text("Custom Place Category (e.g., Gym, Studio)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Zone Display Name (Editable)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("2. Trigger Method", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = triggerType == TriggerType.WIFI_ANY,
                                onClick = { triggerType = TriggerType.WIFI_ANY },
                                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Wi-Fi (Location OFF)") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = triggerType == TriggerType.SCHEDULE,
                                onClick = { triggerType = TriggerType.SCHEDULE },
                                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Time (No Location)") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = triggerType == TriggerType.TOWER_WIFI,
                                onClick = { triggerType = TriggerType.TOWER_WIFI },
                                leadingIcon = { Icon(Icons.Default.CellTower, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Cell Tower (Location ON)") }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    when (triggerType) {
                        TriggerType.WIFI_ANY -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        if (!RadioFingerprintScanner.isConnectedToWifi(context)) {
                                            Toast.makeText(context, "Connect to your Office/Target Wi-Fi first!", Toast.LENGTH_LONG).show()
                                        } else {
                                            val netSig = RadioFingerprintScanner.getWifiNetworkSignature(context)
                                            val (_, optionalSsid) = RadioFingerprintScanner.getConnectedWifiInfo(context)
                                            if (!netSig.isNullOrBlank()) {
                                                val displayLabel = optionalSsid ?: name.ifBlank { selectedCategory }
                                                snapshot = snapshot.copy(
                                                    isWifiConnected = true,
                                                    wifiBssid = netSig,
                                                    wifiSsid = displayLabel,
                                                    wifiNetworkSignature = netSig
                                                )
                                                Toast.makeText(context, "Locked '$displayLabel' router (Location OFF)!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Could not read Wi-Fi router signature. Try again.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.WifiLock, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (!snapshot.wifiBssid.isNullOrBlank())
                                            "Locked Router: ${snapshot.wifiSsid ?: "Office Wi-Fi"}"
                                        else
                                            "Lock Current Wi-Fi (Location OFF)",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (!snapshot.wifiBssid.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Router Fingerprint Locked! Home, Cafe & other Wi-Fi networks will stay in Normal Ring mode — with Location 100% OFF.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF6EE7B7),
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = {
                                                snapshot = snapshot.copy(wifiBssid = null, wifiSsid = null, wifiNetworkSignature = null)
                                            }) {
                                                Text("Clear", color = Color(0xFFF87171))
                                            }
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.1f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Zero-Location Tech: Connect to your Office Wi-Fi and tap above. Captures router Gateway/Subnet/DNS fingerprint with phone Location 100% OFF.",
                                            modifier = Modifier.padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFA7F3D0)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Also Restrict by Working Hours", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                        Text("Optional extra safety window", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                    }
                                    Switch(checked = limitWifiByTime, onCheckedChange = { limitWifiByTime = it })
                                }

                                if (limitWifiByTime) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                android.app.TimePickerDialog(
                                                    context,
                                                    { _, h, m -> startHour = h; startMinute = m },
                                                    startHour, startMinute, false
                                                ).show()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(String.format(Locale.US, "Start %02d:%02d", startHour, startMinute))
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                android.app.TimePickerDialog(
                                                    context,
                                                    { _, h, m -> endHour = h; endMinute = m },
                                                    endHour, endMinute, false
                                                ).show()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(String.format(Locale.US, "End %02d:%02d", endHour, endMinute))
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(dayPairs) { (dayInt, label) ->
                                            val active = selectedDays.contains(dayInt)
                                            FilterChip(
                                                selected = active,
                                                onClick = {
                                                    selectedDays = if (active) selectedDays - dayInt else selectedDays + dayInt
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TriggerType.SCHEDULE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, h, m -> startHour = h; startMinute = m },
                                            startHour,
                                            startMinute,
                                            false
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(String.format(Locale.US, "Start %02d:%02d", startHour, startMinute))
                                }
                                OutlinedButton(
                                    onClick = {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, h, m -> endHour = h; endMinute = m },
                                            endHour,
                                            endMinute,
                                            false
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(String.format(Locale.US, "End %02d:%02d", endHour, endMinute))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(dayPairs) { (dayInt, label) ->
                                    val active = selectedDays.contains(dayInt)
                                    FilterChip(
                                        selected = active,
                                        onClick = {
                                            selectedDays = if (active) selectedDays - dayInt else selectedDays + dayInt
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        TriggerType.TOWER_WIFI -> {
                            Button(
                                onClick = {
                                    onRequestLocationPerm()
                                    if (!RadioFingerprintScanner.isLocationEnabled(context)) {
                                        Toast.makeText(context, "Turn ON Phone Location toggle first", Toast.LENGTH_LONG).show()
                                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    } else {
                                        RadioFingerprintScanner.captureFreshEnvironmentAsync(context) { fresh ->
                                            snapshot = fresh
                                            Toast.makeText(context, "Captured ${fresh.cellSignatures.size} towers", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CellTower, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Lock Current Cell Towers")
                            }
                            if (snapshot.cellSignatures.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Locked ${snapshot.cellSignatures.size} towers at current spot",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("3. Silence Mode", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = profile == SilenceProfile.VIBRATE_ONLY,
                            onClick = { profile = SilenceProfile.VIBRATE_ONLY },
                            leadingIcon = { Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Vibrate Only") }
                        )
                        FilterChip(
                            selected = profile == SilenceProfile.TOTAL_SILENT,
                            onClick = { profile = SilenceProfile.TOTAL_SILENT },
                            leadingIcon = { Icon(Icons.Default.DoNotDisturbOn, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Total Silent (DND)") }
                        )
                    }
                    if (profile == SilenceProfile.TOTAL_SILENT && !dndGranted) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { openDndSettings(context) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DoNotDisturbOn, contentDescription = null, tint = Color(0xFFFCD34D))
                            Spacer(Modifier.width(8.dp))
                            Text("Enable DND Access First", color = Color(0xFFFCD34D))
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val finalName = name.ifBlank { selectedCategory }
                    if (triggerType == TriggerType.WIFI_ANY &&
                        snapshot.wifiBssid.isNullOrBlank() &&
                        snapshot.wifiSsid.isNullOrBlank()
                    ) {
                        Toast.makeText(
                            context,
                            "Tap 'Lock Current Wi-Fi (Location OFF)' first so only that Wi-Fi mutes",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }
                    if (triggerType == TriggerType.TOWER_WIFI &&
                        snapshot.cellSignatures.isEmpty() &&
                        snapshot.wifiBssid == null
                    ) {
                        Toast.makeText(
                            context,
                            "Tap 'Lock Current Cell Towers' first or pick Wi-Fi / Time",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }
                    val useAllDayWifi = triggerType == TriggerType.WIFI_ANY && !limitWifiByTime
                    onSaveRule(
                        SilentZoneRule(
                            name = finalName,
                            category = selectedCategory,
                            triggerType = triggerType,
                            cellSignatures = snapshot.cellSignatures,
                            wifiBssid = snapshot.wifiBssid,
                            wifiSsid = snapshot.wifiSsid ?: finalName,
                            startHour = if (useAllDayWifi) 0 else startHour,
                            startMinute = if (useAllDayWifi) 0 else startMinute,
                            endHour = if (useAllDayWifi) 23 else endHour,
                            endMinute = if (useAllDayWifi) 59 else endMinute,
                            activeDays = if (useAllDayWifi) listOf(1, 2, 3, 4, 5, 6, 7) else selectedDays.toList(),
                            profile = profile
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Silent Zone", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

data class DevicePhoneEntry(
    val key: String,
    val name: String,
    val phoneNumber: String,
    val sourceLabel: String
)

fun loadDeviceContactsAndRecents(context: Context): List<DevicePhoneEntry> {
    val seenTenDigits = mutableSetOf<String>()
    val entries = mutableListOf<DevicePhoneEntry>()

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val rawName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val digits = rawNum.filter { it.isDigit() }
                    if (digits.length >= 7) {
                        val key = digits.takeLast(10)
                        if (seenTenDigits.add(key)) {
                            entries.add(
                                DevicePhoneEntry(
                                    key = key,
                                    name = rawName.ifBlank { rawNum },
                                    phoneNumber = rawNum,
                                    sourceLabel = "Contact"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
        runCatching {
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                var scanned = 0
                while (cursor.moveToNext() && scanned < 80) {
                    scanned++
                    val cachedName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val digits = rawNum.filter { it.isDigit() }
                    if (digits.length >= 7) {
                        val key = digits.takeLast(10)
                        if (seenTenDigits.add(key)) {
                            entries.add(
                                DevicePhoneEntry(
                                    key = key,
                                    name = cachedName.ifBlank { "Recent Caller" },
                                    phoneNumber = rawNum,
                                    sourceLabel = "Recent Call"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    return entries
}

@Composable
fun EditZoneDialog(
    rule: SilentZoneRule,
    onDismiss: () -> Unit,
    onSave: (SilentZoneRule) -> Unit
) {
    val presetCategories = listOf("College", "Office", "Library", "Mandir", "Sleep")
    var editedName by remember { mutableStateOf(rule.name) }
    var editedCategory by remember { mutableStateOf(rule.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151E32),
        title = {
            Text("Edit Place & Zone Name", fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Customize your zone name or place category:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Zone Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editedCategory,
                    onValueChange = { editedCategory = it },
                    label = { Text("Place Category (e.g., Office, Gym)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presetCategories) { preset ->
                        FilterChip(
                            selected = editedCategory.equals(preset, ignoreCase = true),
                            onClick = {
                                editedCategory = preset
                                if (editedName.isBlank()) editedName = preset
                            },
                            label = { Text(preset, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = editedName.trim().ifBlank { rule.name }
                    val finalCat = editedCategory.trim().ifBlank { rule.category }
                    onSave(rule.copy(name = finalName, category = finalCat))
                }
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
fun MultiContactPickerDialog(
    existingContacts: List<VipContact>,
    onDismiss: () -> Unit,
    onConfirmSelection: (List<VipContact>) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val allEntries = remember { loadDeviceContactsAndRecents(context) }
    val existingKeys = remember(existingContacts) {
        existingContacts.map { it.phoneNumber.filter { c -> c.isDigit() }.takeLast(10) }.toSet()
    }
    val selectedKeys = remember { mutableStateMapOf<String, Boolean>() }

    val filtered = remember(searchQuery, allEntries) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) allEntries
        else allEntries.filter {
            it.name.lowercase().contains(q) || it.phoneNumber.contains(q)
        }
    }

    val selectedCount = selectedKeys.count { it.value }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151E32),
        title = {
            Column {
                Text("Select VIP Contacts", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Tick multiple contacts or recent callers to add at once",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search name or number...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No contacts found. Ensure Contacts permission is allowed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(filtered, key = { it.key }) { entry ->
                            val alreadySaved = existingKeys.contains(entry.key)
                            val isChecked = alreadySaved || (selectedKeys[entry.key] == true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isChecked) Color(0xFF10B981).copy(alpha = 0.14f)
                                        else Color(0xFF0D1321)
                                    )
                                    .clickable(enabled = !alreadySaved) {
                                        selectedKeys[entry.key] = !(selectedKeys[entry.key] ?: false)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    enabled = !alreadySaved,
                                    onCheckedChange = { checked ->
                                        if (!alreadySaved) selectedKeys[entry.key] = checked
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (alreadySaved) Color(0xFF94A3B8) else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${entry.phoneNumber} • ${if (alreadySaved) "Already VIP" else entry.sourceLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (alreadySaved) Color(0xFF34D399) else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedCount > 0,
                onClick = {
                    val toAdd = allEntries
                        .filter { selectedKeys[it.key] == true && !existingKeys.contains(it.key) }
                        .map { VipContact(name = it.name, phoneNumber = it.phoneNumber) }
                    onConfirmSelection(toAdd)
                }
            ) {
                Text(
                    if (selectedCount > 0) "Add Selected ($selectedCount)" else "Select Contacts",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
fun VipGuardScreen(
    vipConfig: VipGuardConfig,
    onUpdateVip: (VipGuardConfig) -> Unit
) {
    val context = LocalContext.current
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var showMultiPicker by remember { mutableStateOf(false) }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        showMultiPicker = true
    }

    if (showMultiPicker) {
        MultiContactPickerDialog(
            existingContacts = vipConfig.contacts,
            onDismiss = { showMultiPicker = false },
            onConfirmSelection = { newlySelected ->
                if (newlySelected.isNotEmpty()) {
                    onUpdateVip(vipConfig.copy(contacts = vipConfig.contacts + newlySelected))
                    Toast.makeText(
                        context,
                        "Added ${newlySelected.size} VIP contact(s)!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showMultiPicker = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "VIP Emergency Guard",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                "Bypass silent mode for trusted family or urgent repeated calls.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("VIP Whitelist Bypass", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Ring loudly on 1st call from saved VIPs", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = vipConfig.enabled,
                            onCheckedChange = { onUpdateVip(vipConfig.copy(enabled = it)) }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.08f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val threshold = vipConfig.repeatCallThreshold.coerceAtLeast(5)
                            Text("Repeat Caller Breakthrough", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Ring only after >4 calls (${threshold}th call in 3 mins)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = vipConfig.repeatCallerBypass,
                            onCheckedChange = { onUpdateVip(vipConfig.copy(repeatCallerBypass = it)) }
                        )
                    }
                    if (vipConfig.repeatCallerBypass) {
                        Spacer(Modifier.height(10.dp))
                        val currentThreshold = vipConfig.repeatCallThreshold.coerceAtLeast(5)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 6, 7).forEach { countOption ->
                                FilterChip(
                                    selected = currentThreshold == countOption,
                                    onClick = { onUpdateVip(vipConfig.copy(repeatCallThreshold = countOption)) },
                                    label = { Text("${countOption}th Call (>${countOption - 1})") }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Add Trusted VIP Contacts", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val hasContacts = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasContacts) {
                                showMultiPicker = true
                            } else {
                                contactsPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.READ_CALL_LOG
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select Multiple Contacts / Calls", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.08f))
                        Text(
                            "  OR ENTER MANUALLY  ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.08f))
                    }
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Name (e.g., Mom, Dad)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (contactPhone.filter { it.isDigit() }.length >= 7) {
                                val newContact = VipContact(
                                    name = contactName.ifBlank { "VIP Contact" },
                                    phoneNumber = contactPhone.trim()
                                )
                                onUpdateVip(vipConfig.copy(contacts = vipConfig.contacts + newContact))
                                Toast.makeText(context, "Added ${newContact.name} to VIP Guard!", Toast.LENGTH_SHORT).show()
                                contactName = ""
                                contactPhone = ""
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Manual Number", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        items(vipConfig.contacts, key = { it.id }) { contact ->
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF28354F)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.name.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6EE7B7)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(contact.name, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    IconButton(onClick = {
                        onUpdateVip(vipConfig.copy(contacts = vipConfig.contacts.filterNot { it.id == contact.id }))
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFF87171))
                    }
                }
            }
        }
    }
}
