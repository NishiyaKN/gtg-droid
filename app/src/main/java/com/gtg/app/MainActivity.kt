package com.gtg.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gtg.app.presentation.navigation.GtgNavHost
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import com.gtg.app.presentation.theme.GtgTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point do app.
 *
 * Responsabilidades:
 * 1. Verificar e solicitar as 3 permissões críticas antes de exibir o conteúdo.
 * 2. Hospedar o [GtgNavHost] com BottomNavigation (Home, Exercises, Schedule, Statistics).
 *
 * Permissões:
 * - POST_NOTIFICATIONS (Android 13+): obrigatória para exibir notificações.
 * - SCHEDULE_EXACT_ALARM (Android 12+): verificada via canScheduleExactAlarms().
 *   setAlarmClock() é isento, mas garantimos que o fallback também funcione.
 * - USE_FULL_SCREEN_INTENT (Android 14+): verificada via canUseFullScreenIntent().
 *   Pode ser revogada pelo Play Store para apps não-alarm. Redirecionamos para Settings.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GtgTheme {
                PermissionGate {
                    GtgNavHost()
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Permission Gate
// ──────────────────────────────────────────────────────────────────

/**
 * Composable que verifica permissões e exibe o [content] apenas quando
 * todas as críticas estão concedidas (ou o usuário escolheu prosseguir).
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var permissionState by remember { mutableStateOf(checkPermissions(context)) }
    var userDismissed by remember { mutableStateOf(false) }

    // Re-check quando o app volta ao foreground (usuário pode ter ido a Settings e voltado)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionState = checkPermissions(context)
    }

    val allGranted = permissionState.notifications &&
        permissionState.exactAlarms &&
        permissionState.fullScreenIntent

    if (allGranted || userDismissed) {
        content()
    } else {
        PermissionScreen(
            state = permissionState,
            onRefresh = { permissionState = checkPermissions(context) },
            onDismiss = { userDismissed = true },
        )
    }
}

// ── Data ─────────────────────────────────────────────────────────

private data class PermissionState(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreenIntent: Boolean,
)

private fun checkPermissions(context: Context): PermissionState {
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true // Antes do Android 13, não precisa de runtime permission
    }

    val alarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    } else {
        true
    }

    val fullScreenGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.canUseFullScreenIntent()
    } else {
        true
    }

    return PermissionState(
        notifications = notifGranted,
        exactAlarms = alarmGranted,
        fullScreenIntent = fullScreenGranted,
    )
}

// ── UI ───────────────────────────────────────────────────────────

@Composable
private fun PermissionScreen(
    state: PermissionState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Launcher para POST_NOTIFICATIONS (runtime permission padrão)
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Permissões Necessárias",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "O GtG precisa destas permissões para funcionar corretamente.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── 1. POST_NOTIFICATIONS ────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notificações",
                description = "Exibir alertas quando for hora do exercício.",
                granted = state.notifications,
                onRequest = {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── 2. SCHEDULE_EXACT_ALARM ──────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionCard(
                icon = Icons.Default.Alarm,
                title = "Alarmes Exatos",
                description = "Agendar lembretes no horário preciso.",
                granted = state.exactAlarms,
                onRequest = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── 3. USE_FULL_SCREEN_INTENT ────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PermissionCard(
                icon = Icons.Default.Fullscreen,
                title = "Tela Cheia",
                description = "Exibir o exercício sobre a tela de bloqueio.",
                granted = state.fullScreenIntent,
                onRequest = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        },
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Botão de prosseguir mesmo sem todas (o app funciona parcialmente)
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = "Prosseguir mesmo assim",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) Color(0xFF4CAF50) else GtgPrimary,
                modifier = Modifier.size(32.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }

            if (!granted) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GtgSurfaceVariant,
                        contentColor = GtgPrimary,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Permitir", fontSize = 13.sp)
                }
            }
        }
    }
}
