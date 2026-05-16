package com.gtg.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GtgApplication : Application() {

    companion object {
        /**
         * Canal com bypass do Do Not Disturb — visível mesmo em DND.
         * Silencioso por design: o som é tocado manualmente pelo
         * [com.gtg.app.presentation.alarm.AlarmSoundPlayer] para permitir
         * troca de som em runtime (canal tem som imutável após criação).
         */
        const val ALARM_CHANNEL_PRIORITY_ID = "gtg_alarm_v2_priority"

        /** Canal padrão — respeita DND. Também silencioso (ver acima). */
        const val ALARM_CHANNEL_DEFAULT_ID = "gtg_alarm_v2_default"

        private const val ALARM_CHANNEL_PRIORITY_NAME = "Alarmes GtG (Prioridade)"
        private const val ALARM_CHANNEL_DEFAULT_NAME = "Alarmes GtG"

        /** IDs legados deletados na inicialização para não poluir System Settings. */
        private val LEGACY_CHANNEL_IDS = listOf(
            "gtg_alarm_channel",
            "gtg_alarm_channel_default",
        )
    }

    override fun onCreate() {
        super.onCreate()
        createAlarmNotificationChannels()
    }

    /**
     * Cria os dois NotificationChannels dedicados a alarmes GtG.
     *
     * Por que dois canais?
     * `NotificationChannel.setBypassDnd()` é imutável após a criação do canal
     * (Android 8+) — o app não pode alterá-lo em runtime. Para deixar o bypass
     * do DND opcional para o usuário, criamos dois canais idênticos exceto pelo
     * flag de bypass, e [com.gtg.app.presentation.alarm.AlarmReceiver] escolhe
     * qual usar baseado em [com.gtg.app.data.local.SessionPreferences.bypassDnd].
     *
     * CRÍTICO: IMPORTANCE_HIGH é o mínimo para que o sistema eleve a notificação
     * para Full-Screen Intent. Com IMPORTANCE_DEFAULT ou inferior, o Android 14+
     * silencia o intent e exibe apenas um heads-up notification passivo.
     *
     * Chamadas repetidas a createNotificationChannel são no-op para campos
     * imutáveis (importance, sound, bypassDnd) — só nome e descrição atualizam.
     */
    private fun createAlarmNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Limpa canais legados (versões anteriores que tinham som no canal).
        LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }

        // ── Canal prioritário (bypass DND) ─────────────────────────
        manager.createNotificationChannel(
            buildAlarmChannel(
                id = ALARM_CHANNEL_PRIORITY_ID,
                name = ALARM_CHANNEL_PRIORITY_NAME,
                description = "Visível mesmo em modo Não Perturbe.",
                bypassDnd = true,
            ),
        )

        // ── Canal padrão (respeita DND) ────────────────────────────
        manager.createNotificationChannel(
            buildAlarmChannel(
                id = ALARM_CHANNEL_DEFAULT_ID,
                name = ALARM_CHANNEL_DEFAULT_NAME,
                description = "Silenciado durante o modo Não Perturbe.",
                bypassDnd = false,
            ),
        )
    }

    /**
     * Constrói um canal de alarme **silencioso** (sem som vinculado ao canal).
     * O som é tocado em runtime por [com.gtg.app.presentation.alarm.AlarmSoundPlayer],
     * permitindo trocar o som via UI sem precisar recriar o canal.
     *
     * Vibração e luzes permanecem no canal porque são imutáveis pelo usuário
     * e fazem parte do "padrão de alerta" do app.
     */
    private fun buildAlarmChannel(
        id: String,
        name: String,
        description: String,
        bypassDnd: Boolean,
    ): NotificationChannel = NotificationChannel(
        id,
        name,
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        this.description = description
        setSound(null, null) // canal silencioso — som é manual
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 300, 200, 300)
        enableLights(true)
        lightColor = 0xFF2196F3.toInt() // Azul accent do app
        setBypassDnd(bypassDnd)
        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
    }
}
