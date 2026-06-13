---
title: "Cadence anchor (lastCheckMillis) records only real user Checks, not reschedules"
date: 2026-05-19
last_updated: 2026-06-12
category: architecture-patterns
module: scheduler
problem_type: architecture_pattern
component: background_job
severity: medium
applies_when:
  - "Adicionar qualquer writer secundário (snooze, boot recovery, calendar sync) que persiste estado de agendamento"
  - "rescheduleFromAnchor usa lastCheckMillis como âncora de cadência ao mudar baseInterval mid-sessão"
  - "Side-effects no pipeline AlarmManager precisam preservar invariantes semânticos do SessionPreferences"
related_components:
  - AlarmViewModel
  - HomeViewModel
  - SessionPreferences
  - DynamicSchedulerUseCase
tags:
  - scheduler
  - snooze
  - last-check
  - anchor
  - cadence
  - reschedule
  - session-preferences
  - dynamic-interval
  - alarm-manager
---

# Cadence anchor (lastCheckMillis) records only real user Checks, not reschedules

## Context

No app Android GtG, `SessionPreferences.lastCheckMillis` é a âncora consumida por `HomeViewModel.rescheduleFromAnchor(newInterval)` para preservar cadência quando o usuário muda `baseInterval` durante uma sessão ativa. A semântica documentada de `lastCheck` é "o timestamp do último Check real do usuário (exercício confirmado ou início de sessão)" — gravada explicitamente em `HomeViewModel.startSession` e `HomeViewModel.performManualCheck`.

Quando o botão Snooze foi implementado (PR fix/fullscreen-alarm-and-snooze, plano `2026-05-19-001`), o autor da decisão de design (KD-P6 do plano) propôs gravar `sessionPrefs.setLastCheck(nowMillis)` dentro de `performSnooze`. A intenção era "servir de âncora caso o usuário mude `baseInterval` durante o intervalo de snooze, para que a cadência continue a partir do snooze e não do Check anterior".

A implementação fez exatamente o **oposto** do intuído. Snoozar às 14:00, mudar `baseInterval` 45→30 em Settings, e o `rescheduleFromAnchor(30)` recalcula `lastCheck (14:00) + 30min = 14:30`. O snooze de 5min foi clobberado para 30min sem feedback ao usuário. Pior: `lastCheck=14:00` continua contaminando recálculos futuros da sessão, como se o usuário tivesse feito Check às 14:00 quando na realidade só snoozou.

## Guidance

**`lastCheckMillis` é âncora de Check real, não de qualquer reagendamento.** A distinção semântica importa para preservar a integridade dos recálculos de cadência:

- `lastCheck` → "o usuário concluiu um set neste momento" — base para `rescheduleFromAnchor`, regra 3 (descanso mínimo 20min), eventuais analytics de adesão.
- `setNextAlarm` → "o sistema agendou um alarme para H:MM" — base para o countdown da Home, decisões de roll-over de fim de janela.

Snooze, BootReceiver e re-agendamentos automáticos atualizam `setNextAlarm` (o **plano** do scheduler), mas **não** `setLastCheck` (a **história** do usuário). Só `startSession`, `performManualCheck` e `AlarmViewModel.performCheck` (Check via full-screen — desde o fix U16a) atualizam `setLastCheck`.

**Verificado em 2026-05-20**: `performCheck` na full-screen (`AlarmViewModel.kt:75-101`) **NÃO** chama `setLastCheck` no código atual, contrariando o que o exemplo "CERTO" abaixo prescreve. Esse delta entre intenção documentada e código real foi descoberto durante o brainstorm/plan do lote `2026-05-20-001` quando o brainstorm assumiu (errado) que o Check via full-screen re-âncora a cadência. **Trade-off observado**: em modo de intervalo estrito (planejado em U16 daquele plano), Check via full-screen sem `setLastCheck` deixa âncora antiga; `rescheduleFromAnchor` mid-sessão usaria `lastCheck` do startSession (ou último `performManualCheck`), drift de cadência. Fix previsto na sub-unit U16a do plano `2026-05-20-001-feat-post-testing-batch-plan.md` — adicionar `sessionPrefs.setLastCheck(nowMillis)` em `AlarmViewModel.performCheck` logo após o `exerciseLogRepository.insert(...)`.

**Resolvido (verificado em 2026-06-12)**: o fix U16a FOI aplicado — `performCheck` chama `sessionPrefs.setLastCheck(nowMillis)` (o comentário no código cita "U16a do lote 2026-05-20") e em seguida `setFirstAlarmInChain(0L)`. Ambas as escritas estão pinadas por teste em `AlarmViewModelTest` (`performCheck cancela overshoot e rotaciona exercicio`). O exemplo "CERTO" abaixo reflete o estado real do código.

```kotlin
// CERTO — Check real move a âncora
fun performCheck() {
    dismissActiveAlarmSideEffects()
    val now = LocalDateTime.now()
    val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    if (exerciseId > 0L) {
        exerciseLogRepository.insert(ExerciseLog(...))
    }
    sessionPrefs.setLastCheck(nowMillis)     // OK — usuário confirmou o set
    sessionPrefs.setNextAlarm(...)
}

// CERTO — snooze NÃO move a âncora
fun performSnooze() {
    dismissActiveAlarmSideEffects()
    val nextDateTime = clampSnoozeToBounds(...)
    alarmScheduler.schedule(triggerAt = nextDateTime, ...)
    sessionPrefs.setNextAlarm(...)           // OK — countdown precisa do novo horário
    // sessionPrefs.setLastCheck(...) — NÃO fazer
}
```

## Why This Matters

A âncora `lastCheckMillis` tem semântica estrita: representa o timestamp do **último Check confirmado do usuário**. `rescheduleFromAnchor(newInterval)` usa essa âncora como `t₀` para recalcular cadência: `t₀ + newInterval`. Se `t₀` não é um Check real, o recálculo passa a usar um evento sem significado como base, desacoplando a cadência do comportamento real do usuário.

Gravar `lastCheck` no snooze transforma "não fiz agora, me lembra daqui a 5min" num "fiz às H:MM" do ponto de vista do scheduler. Consequências em cascata:

- `rescheduleFromAnchor` mid-snooze recalcula a partir do timestamp do snooze, clobberando o próprio snooze.
- A regra 3 (descanso mínimo 20min) passa a proteger um "descanso" que nunca foi necessário — o usuário não fez set algum.
- Quaisquer analytics futuras de adesão (sets por dia, tempo entre Checks reais) ficam falseadas.

(auto memory [claude]) As 5 regras do scheduler em `DynamicSchedulerUseCase` (`gtg-scheduler-rules`) operam sobre `lastCheckMillis` como ground truth do progresso do usuário. Contaminar essa âncora afeta tudo a jusante.

## When to Apply

Esta guidance aplica sempre que você considerar gravar `setLastCheck` (ou qualquer "anchor field" análogo) num caminho que não é um Check real. Em particular:

- **Snooze / adiar / "remind me later"**: nunca é Check; o usuário explicitamente postergou.
- **Boot recovery**: nunca é Check; o sistema está apenas replayando estado persistido.
- **Reschedule por mudança de config**: o trigger é externo (usuário mudou interval/dias), não é confirmação de trabalho.
- **Calendar sync / external trigger**: outro sistema mexeu no agendamento; não houve set.
- **Toggle de sessão re-ativada**: depende — se "iniciar sessão" significa "começo a contar daqui", então OK gravar. Se significa "retomar de onde estava", NÃO grave.

Princípio geral: **anchor fields ≠ scheduling state fields**. Ao adicionar um writer secundário a um `SessionPreferences`-like state, classifique cada campo:

| Campo | Categoria | Quem atualiza |
|---|---|---|
| `lastCheckMillis` | Anchor (história do usuário) | Apenas ações reais do usuário |
| `nextAlarmMillis` | Scheduling state (plano do sistema) | Qualquer writer que muda o agendamento |
| `pendingExerciseId` | Scheduling state | Idem |
| `isAlarmPending` | Volatile state | Lifecycle do alarme atual |

Writers secundários atualizam scheduling state e volatile state; **nunca** anchor.

## Examples

### Antes — snooze gravava lastCheck (errado)

```kotlin
fun performSnooze() {
    viewModelScope.launch {
        dismissActiveAlarmSideEffects()

        val now = LocalDateTime.now()
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nextDateTime = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())
        val nextMillis = nextDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        alarmScheduler.cancel()
        alarmScheduler.schedule(triggerAt = nextDateTime, ...)

        // Âncora para recálculo dinâmico se o usuário mudar baseInterval
        // logo após snoozar — sem isso, rescheduleFromAnchor usaria
        // o último Check real e deslocaria o snooze de forma inesperada.
        sessionPrefs.setLastCheck(nowMillis)   // ← contradição: o comentário descreve a intenção,
                                                //   o efeito real é gerar a "âncora falsa"
        sessionPrefs.setNextAlarm(epochMillis = nextMillis, ...)

        _actionCompleted.value = true
    }
}
```

Demonstração do bug em ação: usuário snooza às 14:00 (`overshootRepeatMinutes=5`).
- Antes do snooze: `lastCheck = 13:15` (último Check real), `nextAlarmMillis = 14:00`, `baseInterval = 45`.
- Snooze grava: `lastCheck = 14:00`, `nextAlarmMillis = 14:05`.
- Usuário muda `baseInterval` 45→30 em Settings.
- `HomeViewModel.observeSessionPreferences` detecta `intervalChangedDuringSession`, chama `rescheduleFromAnchor(30)`.
- `rescheduleFromAnchor` usa `lastCheck = 14:00` como âncora. Computa `14:00 + 30min = 14:30`.
- `nextAlarmMillis` atualizado para `14:30`. **Snooze de 5min virou 30min**.

### Depois — snooze não grava lastCheck

```kotlin
fun performSnooze() {
    viewModelScope.launch {
        dismissActiveAlarmSideEffects()

        val now           = LocalDateTime.now()
        val rawNext       = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())
        val activeWindow  = activityWindowRepository.getActiveWindow()
        val activeDays    = sessionPrefs.activeDaysOfWeek
        val nextDateTime  = clampSnoozeToBounds(rawNext, activeWindow, activeDays)

        alarmScheduler.cancel()
        alarmScheduler.schedule(triggerAt = nextDateTime, ...)
        sessionPrefs.setNextAlarm(epochMillis = ..., ...)
        // setLastCheck removido — snooze não é Check real

        _actionCompleted.value = true
    }
}
```

Mesma cena após o fix:
- Antes: `lastCheck = 13:15`, `nextAlarmMillis = 14:00`, `baseInterval = 45`.
- Snooze grava: `nextAlarmMillis = 14:05`. `lastCheck` permanece `13:15`.
- Mudança de `baseInterval` para 30 → `rescheduleFromAnchor(30)` usa `lastCheck = 13:15`. Computa `13:15 + 30min = 13:45` (passado) → regra 3 (descanso mínimo) clamp para `now + 20min ≈ 14:20`.
- Resultado: snooze ainda pode ser clobberado em mudança de interval mid-snooze, mas o erro fica contido naquele snooze específico. `lastCheck` continua válido para todos os recálculos futuros da sessão.

Trade-off documentado: a cadência não é perfeitamente preservada quando o usuário muda `baseInterval` mid-snooze. Esse é um caso raro (snooze + abrir Settings + mudar interval em < 5min) e o comportamento resultante é mais previsível ("o sistema usa o último Check real + novo intervalo, respeitando descanso mínimo") do que o alternativo ("o sistema usa um timestamp arbitrário como se fosse Check").

### Generalização — qualquer writer secundário

```kotlin
// Padrão recomendado: ao adicionar novo writer ao SessionPreferences,
// classifique cada campo antes de gravar.

fun performSomeReschedule() {
    // ... computa novo agendamento ...

    alarmScheduler.schedule(triggerAt = newDateTime, ...)

    // Scheduling state: OK gravar — é o que o sistema acabou de fazer
    sessionPrefs.setNextAlarm(epochMillis = ..., ...)

    // Anchor fields: NÃO gravar — não foi Check real
    // sessionPrefs.setLastCheck(...) — NÃO

    // Volatile state: gravar conforme o lifecycle do alarme atual
    // sessionPrefs.setAlarmPending(false) — se aplicável
}
```

## Related

- Doc relacionado: `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md` — família de bugs do pipeline AlarmManager. Bug E (5º writer = snooze) foi encontrado na mesma sessão.
- Doc relacionado: `docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md` — race entre `scheduleOvershoot` e `cancelOvershoot` via heads-up.
- Plano de origem: `docs/plans/2026-05-19-001-fix-fullscreen-alarm-and-snooze-plan.md` (KD-P6 propunha gravar `setLastCheck` no snooze; descartado após code review).
- Code review: `ce-correctness-reviewer` (P2, confidence 75) + `ce-adversarial-reviewer` (P1, confidence 90 → 75 anchor). Cross-reviewer corroboration promoveu para 100.
- Commit do fix: `693575a` — `fix(alarm): snooze respects activeDaysOfWeek and ActivityWindow, drop lastCheck write`.
