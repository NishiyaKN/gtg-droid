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
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.presentation.navigation.GtgNavHost
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import com.gtg.app.presentation.theme.GtgTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point do app.
 *
 * Camadas (de fora para dentro):
 * 1. [LanguageGate] — força a escolha de idioma na primeira execução.
 * 2. [PermissionGate] — solicita as 3 permissões críticas.
 * 3. [GtgNavHost] — conteúdo principal (Home, Exercises, Schedule, Stats, Settings).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionPrefs: SessionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GtgTheme {
                LanguageGate(sessionPrefs) {
                    PermissionGate {
                        GtgNavHost()
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Language Gate (primeira execução)
// ──────────────────────────────────────────────────────────────────

@Composable
private fun LanguageGate(
    sessionPrefs: SessionPreferences,
    content: @Composable () -> Unit,
) {
    var tag by remember { mutableStateOf(sessionPrefs.languageTag) }
    if (tag == null) {
        LanguageSelectionScreen { picked ->
            sessionPrefs.setLanguageTag(picked)
            tag = picked
            // setApplicationLocales recria a Activity automaticamente — o estado
            // local `tag` é restaurado do prefs no novo lifecycle.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(picked))
        }
    } else {
        content()
    }
}

@Composable
private fun LanguageSelectionScreen(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = GtgPrimary,
            modifier = Modifier.size(56.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.language_select_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.language_select_subtitle),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(40.dp))

        LanguageOptionButton(
            label = stringResource(R.string.language_english),
            onClick = { onPick("en") },
        )

        Spacer(modifier = Modifier.height(12.dp))

        LanguageOptionButton(
            label = stringResource(R.string.language_portuguese),
            onClick = { onPick("pt-BR") },
        )
    }
}

@Composable
private fun LanguageOptionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GtgPrimary,
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ──────────────────────────────────────────────────────────────────
// Permission Gate
// ──────────────────────────────────────────────────────────────────

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var permissionState by remember { mutableStateOf(checkPermissions(context)) }
    var userDismissed by remember { mutableStateOf(false) }

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
        true
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

@Composable
private fun PermissionScreen(
    state: PermissionState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

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
            text = stringResource(R.string.permissions_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.permissions_subtitle),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.permission_notifications_title),
                description = stringResource(R.string.permission_notifications_description),
                granted = state.notifications,
                onRequest = {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionCard(
                icon = Icons.Default.Alarm,
                title = stringResource(R.string.permission_exact_alarm_title),
                description = stringResource(R.string.permission_exact_alarm_description),
                granted = state.exactAlarms,
                onRequest = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PermissionCard(
                icon = Icons.Default.Fullscreen,
                title = stringResource(R.string.permission_full_screen_title),
                description = stringResource(R.string.permission_full_screen_description),
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

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = stringResource(R.string.permissions_dismiss),
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
                    Text(
                        text = stringResource(R.string.permission_allow),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
