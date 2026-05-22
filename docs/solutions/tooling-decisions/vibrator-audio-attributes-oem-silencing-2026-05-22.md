---
title: "Vibrator requires AudioAttributes(USAGE_ALARM) to survive DND and OEM battery profiles"
date: 2026-05-22
category: docs/solutions/tooling-decisions/
module: alarm
problem_type: tooling_decision
component: background_job
severity: high
applies_when:
  - "App uses Vibrator API for alarm-style or notification-style feedback"
  - "Bug report: vibração intermitente; funciona em DND off mas falha em DND ou battery saver"
  - "Multi-modal alert system where sound + vibration must have coherent DND policy"
  - "Target devices include Samsung or OnePlus (OEMs known to silence usage=UNKNOWN)"
tags:
  - vibrator
  - audioattributes
  - dnd
  - oem
  - samsung
  - alarm
  - notification
  - vibrationeffect
---

# Vibrator requires AudioAttributes(USAGE_ALARM) to survive DND and OEM battery profiles

## Context

Bug reportado por uso real: vibração intermitente do alarme GtG. Funcionava com DND off em Pixel/AOSP, mas silenciava em Samsung (One UI) com DND ativo e às vezes em battery saver — mesmo com `Manifest.permission.VIBRATE` declarado e `VibrationEffect.createWaveform` chamado corretamente. O som do alarme NUNCA tinha esse problema. A diferença era no uso da API.

A causa raiz: `Vibrator.vibrate(VibrationEffect)` sem `AudioAttributes` declara o usage como `UNKNOWN` para o sistema. Pixel/AOSP toca, mas OEMs (Samsung, OnePlus) interpretam UNKNOWN como notificação silenciável conforme política do sistema (DND, modo silencioso, battery profile). O sistema do som, por outro lado, sempre passou `AudioAttributes` via `RingtoneManager` — daí a assimetria.

## Guidance

**Sempre passe `AudioAttributes` ao `Vibrator.vibrate` para feedback de alarme ou notificação.** Use o mesmo `usage` que você usa para o som equivalente — assim DND, modo silencioso e battery profile tratam os dois canais coerentemente.

Para alarmes que precisam tocar mesmo em DND:

```kotlin
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator

private val PATTERN = longArrayOf(0L, 500L, 250L) // wait, on, off
private const val REPEAT_FROM_INDEX = 0           // loop indefinido

private fun alarmAudioAttributes(bypassDnd: Boolean): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(
            if (bypassDnd) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )
        .build()

@Suppress("DEPRECATION")
fun startVibration(vibrator: Vibrator, bypassDnd: Boolean) {
    vibrator.vibrate(
        VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX),
        alarmAudioAttributes(bypassDnd),
    )
}
```

**Centralize o `AudioAttributes` builder em um único helper** se sua app tem mais de um canal (som + vibração). No GtG, o helper `AlarmAudioAttributes.kt` é compartilhado entre `AlarmSoundPlayer` e `VibrationPlayer` — sem isso o `usage` driftava (`USAGE_NOTIFICATION` vs `USAGE_NOTIFICATION_RINGTONE` em locais diferentes), provocando comportamentos divergentes entre som e vibração em DND.

### Choice of `usage`

| Cenário | `USAGE_*` | DND treatment |
|---|---|---|
| Alarme estilo "wake-me-up" que precisa passar por DND | `USAGE_ALARM` | Bypass DND; volume controlado pelo slider de alarme do sistema |
| Notificação opcional que respeita DND | `USAGE_NOTIFICATION_RINGTONE` | Respeita DND; volume controlado pelo slider de notificações |
| Sonificação sem destino claro | `USAGE_UNKNOWN` (default) | **Comportamento OEM-dependente — silenciado em Samsung/OnePlus** |

`USAGE_NOTIFICATION` (sem `_RINGTONE`) também respeita DND mas Samsung trata como mais silenciável que `_RINGTONE`. Para máxima paridade com som de notificação, use `USAGE_NOTIFICATION_RINGTONE`.

### API version note

O overload `Vibrator.vibrate(VibrationEffect, AudioAttributes)` existe desde **API 26**. Foi depreciado em **API 33** em favor de `VibrationAttributes`:

```kotlin
// API 33+, recomendado quando minSdk ≥ 33:
vibrator.vibrate(
    VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX),
    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
)
```

Para apps com `minSdk` entre 26 e 32, branching de API só pra evitar a deprecation warning não vale o ganho — use `@Suppress("DEPRECATION")` no call site com comentário justificando, e migre quando `minSdk` subir para 33.

## Why This Matters

**OEMs decidem comportamento de DND com base no `usage` declarado.** Sem AudioAttributes, o sistema vê `USAGE_UNKNOWN` e aplica políticas conservadoras — Samsung One UI e OnePlus OxygenOS silenciam vibração em DND para evitar interrupções de notificações de baixa prioridade. Pixel/AOSP é mais permissivo, então o bug é invisível em emulador e em dispositivos Google.

**Assimetria silenciosa entre som e vibração corrompe a UX de multi-modal alerts.** Se a vibração silencia em DND mas o som ainda toca (porque o som sempre teve AudioAttributes), o usuário recebe alerta incompleto sem feedback claro do que está acontecendo. Quando ambos são silenciados (DND completo), está OK. Quando os dois canais devem tocar (`bypassDnd=true`), os dois precisam tocar.

**Helper compartilhado evita drift.** Sem centralizar, o `usage` em `AlarmSoundPlayer` e `VibrationPlayer` divergiu durante o desenvolvimento — sound usava `USAGE_NOTIFICATION_RINGTONE`, vibração foi (acidentalmente) escrita com `USAGE_NOTIFICATION`. Comportamento de DND ficou inconsistente entre canais. Single helper força paridade.

**`Manifest.permission.VIBRATE` não é o problema.** A permission só controla se a app PODE vibrar — não diz NADA sobre quando o sistema HONRA o vibrate. AudioAttributes é a sinalização que o sistema usa para decidir honrar.

## When to Apply

- Toda invocação de `Vibrator.vibrate` em app Android com `minSdk ≥ 26` deve passar AudioAttributes (ou VibrationAttributes em 33+)
- Especialmente crítico quando há paridade som + vibração em alarmes ou notificações
- Especialmente crítico para apps que devem funcionar em DND (alarmes estilo wake-me-up)
- Especialmente crítico se a base de usuários inclui Samsung, OnePlus, ou outras OEMs com camadas customizadas

Não se aplica para `Vibrator.vibrate(milliseconds)` legacy (deprecado API 26+) — esse já não deveria ser usado.

## Examples

### Antes (bug reportado)

```kotlin
// VibrationPlayer.kt original
fun start(context: Context) {
    val vibrator = resolveVibrator(context) ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX))
    // ↑ Sem AudioAttributes — usage=UNKNOWN — silenciado em Samsung DND
    current = vibrator
}
```

Sintoma: usuário relata "vibração às vezes não funciona". Reprodução no Samsung com DND on → vibração não toca. No Pixel mesmo cenário → toca.

### Depois (fix)

```kotlin
// VibrationPlayer.kt corrigido
@Suppress("DEPRECATION") // vibrate(VibrationEffect, AudioAttributes) deprecada em API 33;
                        // adiar migração para VibrationAttributes até minSdk ≥ 33
fun start(context: Context, bypassDnd: Boolean) {
    val vibrator = resolveVibrator(context) ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(
        VibrationEffect.createWaveform(PATTERN, REPEAT_FROM_INDEX),
        alarmAudioAttributes(bypassDnd), // ← helper compartilhado com AlarmSoundPlayer
    )
    current = vibrator
}

// AlarmAudioAttributes.kt (novo, compartilhado)
internal fun alarmAudioAttributes(bypassDnd: Boolean): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(
            if (bypassDnd) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )
        .build()
```

### Caller passa o flag de `bypassDnd`

```kotlin
// AlarmReceiver.kt — passa o setting do user para ambos os canais
if (sessionPrefs.soundEnabled) {
    AlarmSoundPlayer.play(context, soundUri, bypassDnd = sessionPrefs.bypassDnd)
}
if (sessionPrefs.vibrationEnabled) {
    VibrationPlayer.start(context, bypassDnd = sessionPrefs.bypassDnd)
    // ↑ Mesmo bypassDnd → mesma usage → comportamento DND coerente
}
```

### Verificação manual

| Device | DND | bypassDnd | Som toca? | Vibração toca? |
|---|---|---|---|---|
| Pixel 8 (AOSP) | off | true | ✓ | ✓ (mesmo pré-fix; AOSP era permissivo) |
| Pixel 8 (AOSP) | on | true | ✓ | ✓ |
| Samsung S23 (One UI) | off | true | ✓ | ✓ |
| Samsung S23 (One UI) | on | true | ✓ | **✓ (pré-fix: ✗)** ← bug que motivou o doc |
| Samsung S23 (One UI) | on | false | depends on user notif policy | depends on user notif policy |

## Related

- `docs/solutions/architecture-patterns/alarm-receiver-goasync-coroutine-room-2026-05-22.md` — `AlarmAudioAttributes.kt` helper foi extraído como parte do mesmo lote que estabeleceu o pattern `goAsync + withTimeout`. Ambos lidam com como o sistema Android trata o trabalho de receiver.

**Implementação de referência:** PR #3 (commit `e2fe223` em main), arquivos:
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmAudioAttributes.kt` — helper compartilhado (novo)
- `app/src/main/java/com/gtg/app/presentation/alarm/VibrationPlayer.kt` — usa o helper, ganhou parâmetro `bypassDnd`
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmSoundPlayer.kt` — também migrado para usar o helper
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmReceiver.kt:213` — call site passa `sessionPrefs.bypassDnd` aos dois canais
