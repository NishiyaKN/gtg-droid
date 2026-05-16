package com.gtg.app.presentation.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.gtg.app.presentation.theme.GtgBackground
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity Full-Screen Intent para alarme GtG.
 *
 * Exibida sobre o lockscreen quando o alarme dispara.
 * Flags de sistema (showWhenLocked, turnScreenOn) declaradas no Manifest
 * + reforço programático em [ensureScreenOn] para máxima compatibilidade.
 *
 * Fluxo:
 * 1. AlarmReceiver dispara → Full-Screen Intent → esta Activity.
 * 2. Tela acende + keyguard é dispensado.
 * 3. Usuário vê exercício + reps alvo.
 * 4. "FAZER CHECK" → [AlarmViewModel.performCheck] → log + reagendamento → finish().
 * 5. "Pular" → [AlarmViewModel.performSkip] → reagendamento sem log → finish().
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    private val viewModel: AlarmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureScreenOn()

        setContent {
            GtgTheme {
                val actionCompleted by viewModel.actionCompleted.collectAsStateWithLifecycle()

                // Quando a ação (check/skip) completa, para o som, limpa notificação e fecha.
                // Usa AlarmReceiver.NOTIFICATION_ID — fonte única da verdade do id da
                // notificação. Hardcode duplicado quebraria silenciosamente o cancel.
                LaunchedEffect(actionCompleted) {
                    if (actionCompleted) {
                        AlarmSoundPlayer.stop()
                        NotificationManagerCompat.from(this@AlarmActivity)
                            .cancel(AlarmReceiver.NOTIFICATION_ID)
                        finish()
                    }
                }

                AlarmScreen(
                    exerciseName = viewModel.exerciseName,
                    targetReps = viewModel.targetReps,
                    onCheck = viewModel::performCheck,
                    onSkip = viewModel::performSkip,
                )
            }
        }
    }

    /**
     * Para o som caso a Activity seja destruída sem passar por Check/Skip
     * (ex: swipe da notificação, back gesture, kill pelo sistema).
     */
    override fun onDestroy() {
        AlarmSoundPlayer.stop()
        super.onDestroy()
    }

    /**
     * Garante que a tela ligue e a Activity apareça SOBRE o lockscreen.
     *
     * Abordagem:
     * - `setShowWhenLocked(true)`: exibe a Activity sobre o keyguard sem dispensá-lo.
     *   O usuário interage com a Activity (tap no Check) sem precisar digitar PIN/senha.
     * - `setTurnScreenOn(true)`: liga a tela momentaneamente (one-shot).
     * - `FLAG_KEEP_SCREEN_ON`: mantém a tela acesa enquanto a Activity está visível,
     *   evitando que o timeout normal do sistema apague a tela antes da interação.
     *
     * NÃO usamos requestDismissKeyguard() — ele dispara o prompt de autenticação
     * (PIN/senha/biometria) quando o keyguard é seguro. Para GtG, o usuário precisa
     * apenas ver o exercício e tocar Check, sem desbloquear o dispositivo.
     *
     * NOTA SOBRE USE_FULL_SCREEN_INTENT (Android 14+):
     * A partir do Android 14, o Google Play revoga USE_FULL_SCREEN_INTENT para apps
     * que não se enquadram como "calling" ou "alarms". Um app GtG pode se qualificar
     * como alarm, mas é zona cinza. O ViewModel de configurações (ou a MainActivity)
     * deve verificar NotificationManager.canUseFullScreenIntent() e, se false,
     * redirecionar o usuário para Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT.
     */
    @Suppress("DEPRECATION")
    private fun ensureScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Sem requestDismissKeyguard — queremos exibir SOBRE o lockscreen,
            // não dispensá-lo (o que exigiria autenticação).
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // Mantém tela acesa independentemente da API — sem isso, setTurnScreenOn
        // apenas liga a tela momentaneamente e o timeout do sistema a desliga.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

// ──────────────────────────────────────────────────────────────────
// UI — Tela de Alerta de Exercício
// ──────────────────────────────────────────────────────────────────

@Composable
private fun AlarmScreen(
    exerciseName: String,
    targetReps: Int,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
) {
    // Animação pulsante no ícone para atrair atenção
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GtgBackground,
                        GtgSurface,
                        GtgBackground,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ── Ícone pulsante ──────────────────────────────────
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(pulseScale)
                    .background(
                        color = GtgPrimary.copy(alpha = 0.15f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = GtgPrimary,
                    modifier = Modifier.size(48.dp),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Título ──────────────────────────────────────────
            Text(
                text = "HORA DO GtG!",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Nome do exercício ───────────────────────────────
            Text(
                text = exerciseName,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Reps alvo — grande e destacado ──────────────────
            Text(
                text = "$targetReps",
                color = GtgPrimary,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            Text(
                text = "repetições",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── Botão FAZER CHECK — massivo ─────────────────────
            Button(
                onClick = onCheck,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GtgPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = "FAZER CHECK",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Botão Pular — discreto ──────────────────────────
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Pular esta série",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
        }
    }
}
