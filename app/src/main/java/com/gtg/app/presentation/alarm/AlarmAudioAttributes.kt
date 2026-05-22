package com.gtg.app.presentation.alarm

import android.media.AudioAttributes

/**
 * Constrói o [AudioAttributes] usado por som E vibração do alarme GtG.
 *
 * Centraliza o pattern para evitar drift entre [AlarmSoundPlayer] e
 * [VibrationPlayer] — ambos precisam da mesma decisão de `usage` (alarme
 * que ignora DND vs notificação que respeita) para que o sistema trate
 * as duas modalidades coerentemente.
 *
 * - `bypassDnd=true` → `USAGE_ALARM`: passa por modo Não Perturbe, modo
 *   silencioso, e é tratado como alarme legítimo por OEMs (Samsung,
 *   OnePlus) que costumam silenciar usage `UNKNOWN`.
 * - `bypassDnd=false` → `USAGE_NOTIFICATION_RINGTONE`: respeita DND e
 *   modo silencioso; volume controlado pelo slider de notificações.
 */
internal fun alarmAudioAttributes(bypassDnd: Boolean): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(
            if (bypassDnd) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )
        .build()
