package com.gtg.app.presentation.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * Reproduz o som do alarme GtG.
 *
 * Por que tocar manualmente (e não via NotificationChannel)?
 * O som vinculado ao [android.app.NotificationChannel] é imutável após a
 * criação do canal (Android 8+). Para permitir que o usuário troque o som
 * em runtime via Configurações, os canais GtG são silenciosos e o som é
 * tocado aqui via [Ringtone] API.
 *
 * Uso do AudioAttributes.usage:
 * - `USAGE_ALARM`: passa por modo Não Perturbe e modo silencioso. Volume
 *   controlado pelo slider de alarme do sistema.
 * - `USAGE_NOTIFICATION_RINGTONE`: respeita DND e modo silencioso. Volume
 *   controlado pelo slider de notificações.
 *
 * Singleton intencional: precisamos parar a reprodução de outro componente
 * (AlarmActivity ao tocar "Check"). Mantém uma referência única ao Ringtone
 * em vigor.
 */
object AlarmSoundPlayer {

    private const val TAG = "AlarmSoundPlayer"

    @Volatile
    private var current: Ringtone? = null

    /**
     * `true` enquanto a Ringtone gerenciada está em reprodução. Ringtones
     * de notificação são tipicamente curtos (1-3s) e param sozinhos — sem
     * este getter o consumidor não tem como saber que o som já terminou.
     */
    val isPlaying: Boolean
        get() = current?.isPlaying == true

    /**
     * Inicia a reprodução do som do alarme.
     *
     * @param soundUri URI do som (null → som padrão de alarme do sistema).
     * @param bypassDnd Se true, usa `USAGE_ALARM` (passa por DND/silencioso).
     *                  Se false, usa `USAGE_NOTIFICATION_RINGTONE`.
     */
    fun play(context: Context, soundUri: Uri?, bypassDnd: Boolean) {
        stop() // garante que não há outra Ringtone tocando

        val effectiveUri = soundUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: run {
                Log.w(TAG, "Nenhum som padrão disponível — pulando playback")
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
            val ringtone = RingtoneManager.getRingtone(context, effectiveUri) ?: run {
                Log.w(TAG, "Ringtone não pôde ser construída para $effectiveUri")
                return
            }
            ringtone.audioAttributes = attrs
            ringtone.play()
            current = ringtone
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao tocar alarme", e)
        }
    }

    /** Para qualquer reprodução em curso. Idempotente. */
    fun stop() {
        try {
            current?.takeIf { it.isPlaying }?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao parar Ringtone", e)
        } finally {
            current = null
        }
    }
}
