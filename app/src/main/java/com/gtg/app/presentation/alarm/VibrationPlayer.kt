package com.gtg.app.presentation.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Vibra o dispositivo durante um alarme GtG quando o usuário ativa a
 * modalidade Vibração em Settings. Singleton paralelo a [AlarmSoundPlayer]:
 * mantém uma referência única ao [Vibrator] em uso para que outro componente
 * (`AlarmActivity` no Check/Snooze, `onDestroy` defensivo) possa parar
 * o pattern sem precisar do mesmo Context que iniciou.
 *
 * **AudioAttributes obrigatório** — sem `setUsage(USAGE_ALARM)`, OEMs
 * (Samsung/OnePlus) interpretam o usage padrão (`UNKNOWN`) como notificação
 * silenciável em DND ou em perfis de bateria. Espelha [AlarmSoundPlayer.play]:
 * `USAGE_ALARM` quando `bypassDnd=true`, `USAGE_NOTIFICATION_RINGTONE` caso
 * contrário (mesmas semânticas que o player de som).
 *
 * Path dual de API:
 * - **31+** (S+): [VibratorManager.defaultVibrator]
 * - **26-30** (`minSdk` do app é 26): [Context.getSystemService] retornando
 *   um [Vibrator] direto (deprecado em 31+ mas funcional).
 *
 * O overload [Vibrator.vibrate]`(VibrationEffect, AudioAttributes)` existe
 * desde API 26 e foi depreciado em 33 em favor de `VibrationAttributes`.
 * Mantido aqui (com `@Suppress("DEPRECATION")`) até `minSdk` subir para 33;
 * branching de API não vale o ganho com `minSdk=26`.
 *
 * Pattern repetido `0ms wait → 500ms ON → 250ms OFF → repeat`. `repeat=0`
 * faz `createWaveform` retomar o loop pelo índice 0 indefinidamente até
 * [stop] ou [Vibrator.cancel].
 */
object VibrationPlayer {

    private const val TAG = "VibrationPlayer"

    /** Pattern: wait, on, off — repete a partir do índice 0. */
    private val PATTERN = longArrayOf(0L, 500L, 250L)
    private const val REPEAT_FROM_INDEX = 0

    @Volatile
    private var current: Vibrator? = null

    /**
     * Inicia a vibração em loop. No-op se o device não tem hardware de
     * vibração ou se o serviço retorna null.
     *
     * @param bypassDnd Se `true`, usa `USAGE_ALARM` (passa por Não Perturbe).
     *   Se `false`, usa `USAGE_NOTIFICATION_RINGTONE` (respeita DND), espelhando
     *   o comportamento de [AlarmSoundPlayer.play].
     */
    @Suppress("DEPRECATION")
    fun start(context: Context, bypassDnd: Boolean) {
        stop() // garante que não há outro loop ativo

        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) {
            Log.d(TAG, "Device sem hardware de vibração — start ignorado")
            return
        }

        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(
                if (bypassDnd) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            )
            .build()

        try {
            vibrator.vibrate(
                VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX),
                attrs,
            )
            current = vibrator
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar vibração", e)
        }
    }

    /** Para qualquer loop em curso. Idempotente. */
    fun stop() {
        try {
            current?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao cancelar vibração", e)
        } finally {
            current = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
