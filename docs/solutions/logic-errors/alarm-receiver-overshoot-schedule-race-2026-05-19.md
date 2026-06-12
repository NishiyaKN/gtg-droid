---
title: "AlarmReceiver overshoot schedule races AlarmActivity cancel via heads-up notification"
date: 2026-05-19
category: logic-errors
module: alarm
problem_type: logic_error
component: background_job
symptoms:
  - "Após adicionar cancelOvershoot em performCheck/performSkip, o ciclo 'stuck no exercício' ainda reaparece em condição rara"
  - "Heads-up notification (tela ligada) permite ao usuário tocar Check/Skip antes do scheduleOvershoot ser armado"
  - "cancelOvershoot vira no-op porque o PendingIntent ainda não está no AlarmManager"
root_cause: async_timing
resolution_type: code_fix
severity: high
related_components:
  - AlarmReceiver
  - AlarmActivity
  - AlarmViewModel
  - AlarmSoundPlayer
  - AlarmSchedulerImpl
tags:
  - alarm-manager
  - overshoot
  - race-condition
  - broadcast-receiver
  - heads-up-notification
  - async-timing
  - operation-ordering
  - pendingintent
---

# AlarmReceiver overshoot schedule races AlarmActivity cancel via heads-up notification

## Problem

Após o fix do plano R1 (cancelar overshoot em `AlarmViewModel.performCheck/performSkip` quando o usuário resolve o alarme pela tela full-screen), o sintoma "preso no exercício antigo" continuava aparecendo em uma condição específica de timing: app aberto (tela ligada) quando o alarme dispara, sistema mostra a notificação como **heads-up** (não como Activity full-screen, porque `USE_FULL_SCREEN_INTENT` só lança Activity sobreposta com tela apagada), usuário toca rapidamente na heads-up, `AlarmActivity` inicia e `performCheck` chama `cancelOvershoot()` — que vira no-op porque o overshoot ainda não foi armado pelo `AlarmReceiver`. Em seguida o receiver completa `scheduleOvershoot()` com as extras antigas, e o ciclo retorna.

## Symptoms

- Condição precisa: app em foreground quando o alarme dispara. Sistema mostra heads-up (Android comportamento de `setFullScreenIntent` com tela ligada).
- Toque na heads-up dentro da janela de race (~100–500ms enquanto `AlarmSoundPlayer.play()` inicializa o `MediaPlayer`).
- `cancelOvershoot()` executado pela `AlarmActivity` retorna sem fazer nada — `PendingIntent` ainda não existe.
- Logo depois, `AlarmReceiver.onReceive` completa `scheduleOvershoot()` com as extras estáticas do disparo (exerciseId, exerciseName, targetReps).
- Resultado idêntico ao bug original que `cancelOvershoot` deveria corrigir: overshoot rebatendo a cada 5min com exercício antigo, "stuck no exercício".
- Reprodução probabilística: depende de dispositivo, tamanho do som customizado a decodificar, e reflexo do usuário. Mais comum em devices lentos ou quando o usuário fica de olho no alarme com app aberto.

## What Didn't Work

**Hipótese — apenas adicionar `cancelOvershoot` em `performCheck/performSkip` é suficiente.**

Insuficiente. A hipótese assume que `cancelOvershoot` sempre encontra um `PendingIntent` armado para cancelar. A ordem original em `AlarmReceiver.onReceive` era:

1. `sessionPrefs.setAlarmPending(true)`
2. `NotificationManagerCompat.notify(NOTIFICATION_ID, notification)` ← gate: heads-up pode lançar AlarmActivity aqui
3. `AlarmSoundPlayer.play(...)` ← I/O bloqueante (~100–500ms inicialização do MediaPlayer)
4. `alarmScheduler.scheduleOvershoot(...)` ← só agora o PendingIntent é armado

A janela de race existe entre 2 e 4. Se a Activity completar `cancelOvershoot` antes do passo 4 rodar, o cancel é no-op e o overshoot é armado em seguida.

## Solution

Reordenar `AlarmReceiver.onReceive` para armar o overshoot ANTES de qualquer I/O bloqueante.

**Before:**

```kotlin
// AlarmReceiver.onReceive — ordem original (vulnerável ao race)
sessionPrefs.setAlarmPending(true)
NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
// ^ heads-up pode lançar AlarmActivity aqui, toque imediato → cancelOvershoot no-op

val soundUri = sessionPrefs.alarmSoundUri?.let(Uri::parse)
AlarmSoundPlayer.play(context = context, soundUri = soundUri, bypassDnd = sessionPrefs.bypassDnd)
// ^ I/O bloqueante ~100–500ms

if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
    val now = LocalDateTime.now()
    if (now.dayOfWeek in sessionPrefs.activeDaysOfWeek) {
        alarmScheduler.scheduleOvershoot(
            triggerAt    = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong()),
            exerciseId   = exerciseId,
            exerciseName = exerciseName,
            targetReps   = targetReps,
        )
    }
}
```

**After:**

```kotlin
// AlarmReceiver.onReceive — ordem corrigida (overshoot armado ANTES do play)
sessionPrefs.setAlarmPending(true)
NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)

// Arma overshoot ANTES do play(). cancelOvershoot() da AlarmActivity
// sempre vê o PendingIntent já criado, independentemente de quando o
// usuário toca na heads-up.
if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
    val now = LocalDateTime.now()
    if (now.dayOfWeek in sessionPrefs.activeDaysOfWeek) {
        alarmScheduler.scheduleOvershoot(
            triggerAt    = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong()),
            exerciseId   = exerciseId,
            exerciseName = exerciseName,
            targetReps   = targetReps,
        )
    }
}

// I/O depois — não afeta a invariante de que o PendingIntent já existe
val soundUri = sessionPrefs.alarmSoundUri?.let(Uri::parse)
AlarmSoundPlayer.play(context = context, soundUri = soundUri, bypassDnd = sessionPrefs.bypassDnd)
```

## Why This Works

A race surge porque `cancelOvershoot()` e `scheduleOvershoot()` competem pela mesma operação no `AlarmManager`. Reordenar elimina a race por uma invariante de ordenação: ao mover `scheduleOvershoot` para antes do I/O bloqueante de `AlarmSoundPlayer.play()`, o `PendingIntent` do overshoot está **sempre presente** quando qualquer interação do usuário com a UI é possível. `cancelOvershoot()` passa a ser idempotente de forma robusta — sempre cancela algo real, nunca é no-op por timing.

O princípio geral: quando dois fluxos concorrentes (BroadcastReceiver e Activity via UI) precisam de visibilidade sobre o mesmo recurso (`PendingIntent` no `AlarmManager`), o recurso deve ser criado antes de qualquer gate que separe os fluxos. Em `AlarmReceiver`, `NotificationManagerCompat.notify()` é o gate — a partir do momento que a notificação é publicada, uma Activity pode ser lançada (via heads-up ou via toque) e interagir concorrentemente com o `AlarmManager`. Logo, `scheduleOvershoot` precisa preceder qualquer I/O que delay o caller após o gate.

(auto memory [claude]) Em `gtg-stack`, o app é alarm-style por design: "agarra o usuário no horário certo". Operações no `AlarmManager` são primárias; I/O auxiliar (som) é secundário. Reordenar respeita essa hierarquia.

## Prevention

**1. Recursos compartilhados com fluxos concorrentes devem ser armados antes do gate que separa os fluxos.**

Em qualquer `BroadcastReceiver` que dispara uma notificação capaz de lançar uma Activity:

```kotlin
// Regra: resources ANTES do gate (notify)
alarmScheduler.scheduleOvershoot(...)            // arma PendingIntent
NotificationManagerCompat.notify(...)            // gate: separa fluxos
AlarmSoundPlayer.play(...)                       // I/O — acontece depois
```

Identificar o gate: qualquer operação que possa transferir controle para outro componente (Activity, Service, outro processo). Para `AlarmReceiver`, é `notify()`. Para outros: `startActivity()`, `startService()`, `broadcast()`, etc.

**2. I/O bloqueante após o gate amplia a janela de race; minimize ou mova para depois das operações críticas.**

`AlarmSoundPlayer.play()` no Android faz `MediaPlayer.setDataSource` + `prepare` (assíncrono dependendo da fonte). Em URIs customizadas, o `prepare` pode tomar segundos. Qualquer operação após esse I/O que outro componente possa precisar ver precisa ser feita ANTES, ou tornada idempotente perante a janela de race.

**3. Adicione comentário inline explicando a ordem quando ela é não-óbvia.**

A ordem natural (notificar → tocar som → agendar próximo) parece óbvia. Mas quando o recurso agendado é cancelável por outro componente disparado pela notificação, a ordem importa. Custo zero documentar:

```kotlin
// scheduleOvershoot ANTES de play() para fechar a janela de race em que
// heads-up notification permite ao usuário tocar Check/Skip antes do
// scheduleOvershoot rodar. Se isso acontecesse, cancelOvershoot do
// AlarmViewModel viraria no-op (nada armado ainda).
```

**4. Em testes de unidade de ViewModel, considere ordering tests com `verifyOrder` quando há side-effects encadeados.**

Para confirmar que `cancelOvershoot` é chamado antes de `schedule` em `performSnooze`:

```kotlin
verifyOrder {
    alarmScheduler.cancelOvershoot()
    alarmScheduler.cancel()
    alarmScheduler.schedule(...)
}
```

## Amendment (2026-06-11): block guard fire-time roda ANTES do scheduleOvershoot

O fix window-start-calendar-block introduziu um guard de blocos no
`handleDispatch`: antes de construir a notificação, o receiver consulta os
blocos do dia (manual + Calendar, sub-budget de 2s com fail-open para Ring)
e pode **suprimir** o disparo — sem notify, sem overshoot, sem som — rearmando
o alarme para o fim do cluster + buffer.

À primeira leitura isso parece violar a regra "resources antes do gate",
porque introduz I/O suspenso (Room + CalendarProvider) antes do
`scheduleOvershoot`. Não viola — a invariante protege contra um race que
**exige a notificação visível**: o usuário só pode tocar Check/Snooze (e a
`AlarmActivity` só pode chamar `cancelOvershoot`) depois do `notify()`.

O argumento "nenhuma notificação existe ainda" vale integralmente para
**disparos primary** — DESTE dispatch nada foi publicado. Para **disparos de
overshoot**, a notificação do primary anterior pode já estar visível e a
`AlarmActivity` pode estar aberta — o guard alarga a janela entre o disparo
e o `scheduleOvershoot` deste dispatch em até 2s. A mitigação é o gate em
`isAlarmPending` no caminho de supressão do overshoot: qualquer Check/Snooze
/dismiss concorrente zera o flag (e cancela o overshoot) ANTES do re-arme, e
um overshoot fantasma já in-flight no momento do cancel não rearma. A regra
correta, refinada:

```text
[window guard / block guard — pode retornar sem tocar; nenhum gate aberto]
alarmScheduler.scheduleOvershoot(...)   // resource — imediatamente antes do gate
NotificationManagerCompat.notify(...)   // gate: separa fluxos
sessionPrefs.recordAlarmDispatchedNow() // estado visível pós-gate
AlarmSoundPlayer.play(...)              // I/O auxiliar por último
```

O que continua proibido: inserir I/O bloqueante **entre** `scheduleOvershoot`
e `notify`, ou mover o `scheduleOvershoot` para depois do `notify`. O guard
fica inteiro ANTES do par resource+gate, nunca no meio. Quem for mexer no
`handleDispatch` deve preservar esse sanduíche.

## Related Issues

- Doc relacionado: `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md` — família de bugs do pipeline AlarmManager. Bug E (5º writer) foi encontrado na mesma sessão de code review.
- Doc relacionado: `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` — pitfall do `setLastCheck` no snooze, também encontrado nessa sessão.
- Plano de origem: `docs/plans/2026-05-19-001-fix-fullscreen-alarm-and-snooze-plan.md` (R1: cancelar overshoot em Check/Skip).
- Code review: `ce-adversarial-reviewer` flagou a race como P0 single-reviewer (confidence 75 → anchor 75). Não foi detectada por correctness/testing — só apareceu na construção adversarial de cenários.
- Commit do fix: `50b39c3` — `fix(alarm): schedule overshoot before play() to close heads-up race`.
