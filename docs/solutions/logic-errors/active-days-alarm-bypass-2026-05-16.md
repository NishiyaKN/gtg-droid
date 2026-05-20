---
title: "activeDaysOfWeek filter bypassed by multiple AlarmManager write paths"
date: 2026-05-16
last_updated: 2026-05-19
category: logic-errors
module: scheduler
problem_type: logic_error
component: background_job
symptoms:
  - "Alarmes disparam em dias que o usuário desativou em Settings → Dias da Semana"
  - "Toggle de dia tem efeito para sessões NOVAS mas o alarme já agendado continua tocando no dia desativado"
  - "Após reboot, alarmes voltam a disparar em todos os dias, ignorando o filtro persistido"
  - "Overshoot re-alert (alarme dispara de novo em N min) ignora o filtro quando N min cruza meia-noite para dia inativo"
  - "Snooze cruzando meia-noite para dia inativo dispara alarme mesmo com o dia desabilitado"
  - "Snooze cruzando o fim da ActivityWindow arma o alarme fora da janela e dispara cascata de overshoots"
root_cause: missing_validation
resolution_type: code_fix
severity: high
related_components:
  - HomeViewModel
  - BootReceiver
  - AlarmReceiver
  - AlarmViewModel
  - DynamicSchedulerUseCase
  - RotationHelpers
tags:
  - alarm-manager
  - active-days-filter
  - scheduler
  - boot-receiver
  - overshoot
  - snooze
  - activity-window
  - defense-in-depth
  - missing-validation
  - broadcast-receiver
---

# activeDaysOfWeek filter bypassed by multiple AlarmManager write paths

## Problem

No app Android GtG, o filtro `activeDaysOfWeek` (Settings → "Dias da Semana") era aplicado apenas dentro de `DynamicSchedulerUseCase.calculateNextAlarm`. **Quatro outros caminhos no código armavam o `AlarmManager` diretamente, sem passar pelo filtro**. Alarmes continuavam disparando em dias que o usuário desativou — Bug A (toggle mid-sessão), Bug B (roll-over de fim de janela), Bug C (reboot), Bug D (overshoot cruzando meia-noite).

**Update 2026-05-19:** um **5º writer** foi introduzido junto com o botão Snooze (`AlarmViewModel.performSnooze`). O método chamava `alarmScheduler.schedule(now + overshootRepeatMinutes)` sem validar contra `activeDaysOfWeek` nem contra `ActivityWindow.endTime`, replicando a mesma classe estrutural dos bugs A–D — Bug E. O fix está em "Solution → Bug E" abaixo.

## Symptoms

- Usuário desativa Sábado e Domingo em Settings com sessão ativa. Alarme já agendado **continua disparando** no sábado seguinte. Reprodução reportada: *"Mudei Sáb/Dom com sessão ativa"*.
- Após reboot do dispositivo, alarmes voltam a disparar em dias desativados — `nextAlarmMillis` persistido nunca era revalidado.
- Overshoot re-alert agendado para `now + 5min` cruzando meia-noite para sábado (desativado) ainda dispara.
- O fim-de-janela roll-over (`rescheduleForNextDayKeepingExercise`) joga o alarme para `today + 1` cego, sem checar se esse dia está ativo.
- Snooze sexta 23:50 com `overshootRepeatMinutes=15` dispara alarme sábado 00:05 mesmo com `activeDaysOfWeek=[MON..FRI]`. O guard de `AlarmReceiver:76` só cobre `isOvershoot=true`; snooze arma como PRIMARY (`isOvershoot=false`), sem guard.
- Snooze às 17:28 com janela 08:00–17:30 e delay=5min arma primary para 17:33 (fora da janela). `HomeViewModel.restartCountdown` só faz rollover quando `remaining < 0` E `now > windowEnd`; entre `windowEnd` e o fire do snooze o `remaining` ainda é positivo, sem rollover. `AlarmReceiver` então encadeia overshoots fora da janela.

## What Didn't Work

**Hipótese 1 — persistência de `activeDaysOfWeek` estava quebrada.**  
Verificamos `SessionPreferences.setActiveDaysOfWeek` e o getter correspondente. Ambos liam/gravavam corretamente no `SharedPreferences`. A preferência era salva; o problema era que ninguém a consultava no momento de **rearmar** o alarme.

**Hipótese 2 — o filtro do `DynamicSchedulerUseCase` seria suficiente.**  
O `calculateNextAlarm` aplicava `activeDaysOfWeek` corretamente — mas só era invocado pelo caminho principal de agendamento (start session, Check do usuário). Rastrear todos os call sites de `AlarmScheduler.schedule` e `AlarmScheduler.scheduleOvershoot` revelou **4 caminhos independentes** que bypassavam completamente esse método.

**O erro de descoberta original** *(session history)*. Quando o feature foi introduzido no commit `d0d3e39`, o autor da sessão fez:

```
grep "calculateNextAlarm|dynamicScheduler|DynamicSchedulerUseCase|previewTodayRoutine"
```

A busca pegou 3 call sites e o `activeDaysOfWeek` foi threadado neles. Mas a busca **não incluiu `AlarmScheduler.schedule()` direto** — e justamente `BootReceiver` e `AlarmReceiver` (no caminho de overshoot) chamam `AlarmScheduler.schedule` sem passar pelo UseCase. Esses dois writers escaparam da varredura.

## Solution

**Helper compartilhado** extraído em `app/src/main/java/com/gtg/app/domain/usecase/RotationHelpers.kt`:

```kotlin
// Avança para o próximo dia ativo a partir de `after` (nunca retorna `after`).
// Fallback defensivo para `after + 1` se todos os 7 dias estiverem inativos
// (config patológica que a UI deveria impedir).
fun findNextActiveDate(after: LocalDate, activeDaysOfWeek: Set<DayOfWeek>): LocalDate {
    repeat(7) { offset ->
        val candidate = after.plusDays(offset.toLong() + 1)
        if (candidate.dayOfWeek in activeDaysOfWeek) return candidate
    }
    return after.plusDays(1)
}
```

### Bug A — `HomeViewModel.observeSessionPreferences` (re-evaluation hook)

`PrefsSnapshot` ganhou o campo `activeDaysOfWeek`. Detectamos a mudança e reagendamos via `rescheduleFromAnchor`:

```kotlin
val activeDaysChangedDuringSession = previous != null &&
    previous.activeDaysOfWeek != snapshot.activeDaysOfWeek &&
    snapshot.isSessionActive
if (activeDaysChangedDuringSession && snapshot.nextAlarmMillis > 0L) {
    val nextAlarmDate = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(snapshot.nextAlarmMillis),
        ZoneId.systemDefault(),
    ).toLocalDate()
    if (nextAlarmDate.dayOfWeek !in snapshot.activeDaysOfWeek) {
        rescheduleFromAnchor(snapshot.baseIntervalMinutes)
    }
}
```

Conservador por design: só reagenda quando o dia ATUAL ficou inativo. Não "puxa para trás" quando o usuário re-habilita um dia — evita surpresas.

### Bug B — `HomeViewModel.rescheduleForNextDayKeepingExercise`

Substituição direta:

```kotlin
// Antes — ignora dias inativos
val nextDate = LocalDate.now().plusDays(1)

// Depois — pula para o próximo dia ativo
val nextDate = findNextActiveDate(LocalDate.now(), sessionPrefs.activeDaysOfWeek)
```

### Bug C — `BootReceiver`

Após reboot, valida o `dayOfWeek` do `nextAlarmMillis` persistido. Se ficou inativo, avança preservando o time-of-day:

```kotlin
val activeDays = sessionPrefs.activeDaysOfWeek
val needsShift = original.dayOfWeek !in activeDays
val triggerAt = if (!needsShift) original
else findNextActiveDate(original.toLocalDate(), activeDays)
    .atTime(original.toLocalTime())

// Ordem importa: schedule() PRIMEIRO, setNextAlarm() DEPOIS.
// AlarmSchedulerImpl engole SecurityException silenciosamente — se invertido,
// prefs apontariam para alarme inexistente.
alarmScheduler.schedule(triggerAt = triggerAt, ...)
if (needsShift) {
    sessionPrefs.setNextAlarm(epochMillis = triggerAt.toEpochMilli(), ...)
}
```

### Bug D — `AlarmReceiver` overshoot

Guard duplo: no agendamento e no fire (cobre overshoot que cruza meia-noite):

```kotlin
// Guard 1 — ao disparar alarme original, só agenda overshoot se hoje está ativo
if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
    val now = LocalDateTime.now()
    if (now.dayOfWeek in sessionPrefs.activeDaysOfWeek) {
        alarmScheduler.scheduleOvershoot(...)
    }
}

// Guard 2 — quando o overshoot em si dispara (pode ter cruzado meia-noite)
val isOvershoot = intent.getBooleanExtra(EXTRA_IS_OVERSHOOT, false)
if (isOvershoot && LocalDateTime.now().dayOfWeek !in sessionPrefs.activeDaysOfWeek) {
    return  // dia inativo — silencia sem reagendar
}
```

### Bug E — `AlarmViewModel.performSnooze` (5º writer, 2026-05-19)

O plano de implementação (KD-P2 do `docs/plans/2026-05-19-001-fix-fullscreen-alarm-and-snooze-plan.md`) decidiu corretamente que snooze não passaria pelo `DynamicSchedulerUseCase` (regra 3 empurraria snooze=5min para 20min). Essa decisão deixou snooze sem **nenhum** dos guards de session bounds que o use case aplicava internamente — `activeDaysOfWeek` E `ActivityWindow.endTime`.

Fix: helper `clampSnoozeToBounds` em `AlarmViewModel` + injeção de `ActivityWindowRepository`:

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
        sessionPrefs.setNextAlarm(...)
        _actionCompleted.value = true
    }
}

private fun clampSnoozeToBounds(
    candidate: LocalDateTime,
    window: ActivityWindow?,
    activeDays: Set<DayOfWeek>,
): LocalDateTime {
    val dayOk        = candidate.toLocalDate().dayOfWeek in activeDays
    val withinWindow = window == null || !candidate.toLocalTime().isAfter(window.endTime)
    if (dayOk && withinWindow) return candidate

    // Rollover paralelo a HomeViewModel.rescheduleForNextDayKeepingExercise
    val nextDate  = findNextActiveDate(candidate.toLocalDate(), activeDays)
    val startTime = window?.startTime ?: LocalTime.of(0, 0)
    return nextDate.atTime(startTime)
}
```

Por que o guard do `AlarmReceiver:76` não bastava: ele cobre `isOvershoot=true`. Snooze arma `PRIMARY` (`isOvershoot=false`), sem guard. Ampliar o guard só captaria o problema **no fire** — o usuário veria o alarme sumindo sem feedback. A correção pertence ao site de agendamento: calcular `nextDateTime` correto antes de chamar `schedule()`.

## Why This Works

A causa raiz é **estrutural**: o filtro existia em UM ponto (`calculateNextAlarm`), mas o `AlarmManager` tinha **múltiplos escritores independentes**. Centralizar o filtro no use case era necessário, mas não suficiente — cada escritor precisava (a) passar pelo gate único OU (b) aplicar o filtro inline.

O fix estabelece ambos:
- `findNextActiveDate` como helper inline reutilizável para escritores que não passam pelo use case.
- Re-evaluation hooks em observers e receivers para capturar mudanças de estado após o agendamento original.

**Insight crítico**: estado externo (AlarmManager) e estado interno (SharedPreferences) divergem sempre que há múltiplos escritores sem sincronização. O filtro no use case só protegia o caminho feliz — qualquer desvio (reboot, observer, receiver) contornava a proteção.

*(session history)* O autor original do feature `activeDaysOfWeek` (commit `d0d3e39`) tomou uma decisão de design consciente: **colocar o filtro como rollover gate dentro do scheduler, não como entry gate nos write sites**. A intenção era manter o conhecimento de "dias ativos" centralizado na lógica de agendamento. O efeito colateral não previsto foi que paths que não chamavam o scheduler — `BootReceiver` (replay de estado persistido) e `AlarmReceiver` (self-scheduling overshoot) — ficaram fora da proteção.

## Prevention

**1. Enumere todos os escritores de cada sistema externo com side-effects.**  
Para `AlarmManager`, `NotificationManager`, sockets, work managers — liste explicitamente todos os call sites de `.schedule()` / `.cancel()` / `.enqueue()`. Cada um precisa ou passar pelo gate central, ou aplicar o filtro inline. Comando recomendado para grep no projeto:

```bash
grep -rn "AlarmScheduler\." app/src/main/java/ --include="*.kt"
# inclui scheduleOvershoot, schedule, cancel, etc — captura TODOS os writers
```

**2. Observers que re-publicam side-effects devem snapshot o estado completo e re-avaliar.**

```kotlin
// Ruim: observer de sessão que ignora activeDaysOfWeek no snapshot
data class PrefsSnapshot(val baseIntervalMinutes: Int, val nextAlarmMillis: Long)

// Bom: snapshot inclui todos os campos que afetam decisões de agendamento
data class PrefsSnapshot(
    val baseIntervalMinutes: Int,
    val nextAlarmMillis: Long,
    val activeDaysOfWeek: Set<DayOfWeek>,  // novo
)
```

**3. BroadcastReceivers devem tratar o estado persistido como potencialmente stale.**  
Entre o agendamento e o disparo, o usuário pode ter alterado configs. Sempre re-valide filtros no `onReceive`:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (LocalDate.now().dayOfWeek !in sessionPrefs.activeDaysOfWeek) {
        // re-valida no fire, mesmo que o agendamento original tenha passado pelo filtro
        return
    }
    // ... lógica principal
}
```

**4. Ordem de operações: side-effect externo PRIMEIRO, persistência DEPOIS.**

```kotlin
alarmScheduler.schedule(targetMillis)        // AlarmManager primeiro
sessionPrefs.setNextAlarm(targetMillis)      // prefs depois — só persiste se schedule não lançou
```

Se invertido e `schedule()` falhar (ex: `SecurityException` se `SCHEDULE_EXACT_ALARM` foi revogado), as prefs apontariam para um alarme que não existe.

**5. Quando adicionar um filtro/validation novo, grep MAIS amplo do que o ponto óbvio.**  
A grep original buscou só `calculateNextAlarm|dynamicScheduler|DynamicSchedulerUseCase|previewTodayRoutine`. Deveria ter incluído `AlarmScheduler\.` para pegar TODOS os writers do recurso externo. Regra geral: ao adicionar filtro a um state-driven side-effect, busque pelo recurso externo (a *capacidade*), não só pela função que naturalmente o usaria.

**6. Writers que não passam pelo `DynamicSchedulerUseCase` precisam aplicar bounds inline (Bug E, 2026-05-19).**  
Ao adicionar qualquer novo caminho de agendamento que bypasse o use-case (por decisão intencional — ex: snooze que não deve passar pelas 5 regras), liste explicitamente cada garantia do use case e decida "aplicar" (replicar inline) ou "omitir" (documentar o motivo):

- `activeDaysOfWeek`: `candidate.dayOfWeek` está no conjunto ativo?
- `ActivityWindow.endTime`: `candidate.toLocalTime()` está antes do fim da janela?
- Regra 3 (descanso mínimo 20min): aplicar? (Snooze: NÃO — empurraria snooze=5min para 20min.)
- Atualização de `lastCheckMillis`: aplicar? (Snooze: NÃO — ver `docs/solutions/concurrency/alarm-pipeline-race-and-anchor-pitfalls.md`.)

Se `activeDaysOfWeek` ou `endTime` falham, aplique rollover via `findNextActiveDate` + `window.startTime`. Omissão silenciosa é a raiz de todos os bugs desta família.

## Related Issues

- Repo: `NishiyaKN/gtg-droid` no GitHub. Sem issues relacionadas registradas.
- Commits: `d0d3e39` (introdução de `activeDaysOfWeek`), `5fc4f0b` (fix dos 4 caminhos), `a6d305f` (refinamentos pós code-review), `693575a` (Bug E — snooze respeita activeDaysOfWeek e ActivityWindow).
- Doc relacionado: `docs/solutions/concurrency/alarm-pipeline-race-and-anchor-pitfalls.md` — race entre `AlarmReceiver.scheduleOvershoot` e `AlarmActivity.cancelOvershoot` + pitfall de `setLastCheck` no snooze. Bugs encontrados na mesma sessão de code review 2026-05-19.
- Plano deferido: revalidação de `ActivityWindow` + `InactivityBlocks` no `BootReceiver` quando o dia é shiftado. Requer `goAsync()` + injeção de `DynamicSchedulerUseCase` no receiver — refactor com escopo próprio.
