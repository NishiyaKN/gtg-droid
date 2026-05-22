---
title: "BroadcastReceiver goAsync + withTimeout + SupervisorJob for suspend Room reads"
date: 2026-05-22
category: docs/solutions/architecture-patterns/
module: alarm
problem_type: architecture_pattern
component: background_job
severity: high
applies_when:
  - "BroadcastReceiver needs to perform suspend Room reads inside onReceive"
  - "AlarmManager delivers a broadcast that must query the database before acting"
  - "onReceive must stay within PendingResult system deadline (~10s on Android 8+)"
  - "WakeLock must be held across async work then released deterministically"
tags:
  - broadcastreceiver
  - goasync
  - suspend
  - coroutine
  - wakelock
  - pendingresult
  - room
  - supervisorjob
---

# BroadcastReceiver goAsync + withTimeout + SupervisorJob for suspend Room reads

## Context

`AlarmReceiver.onReceive` precisava chamar `ActivityWindowRepository.getActiveWindow()` — uma suspend query Room — para decidir, no momento do disparo, se devia tocar (dentro da `ActivityWindow`) ou rolar a cadeia para o próximo dia. A implementação síncrona pré-existente (try/finally + wakelock 30s) não conseguia `await` suspend functions. `runBlocking` numa thread main de receiver é receita para ANR sob Doze. A migração para `goAsync()` + corrotina é o que permite suspend work dentro do ciclo de vida de um `BroadcastReceiver`.

## Guidance

**O pattern: `goAsync()` + `withTimeout` + `SupervisorJob` + `Dispatchers.Default` + cleanup explícito em `try/finally`.**

```kotlin
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionPrefs: SessionPreferences
    @Inject lateinit var activityWindowRepository: ActivityWindowRepository
    // ... outras deps

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val SUSPEND_BUDGET_MILLIS = 9_000L   // withTimeout
        private const val WAKELOCK_TIMEOUT_MILLIS = 15_000L // wakelock ceiling
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 1. goAsync IMEDIATAMENTE — sem isso o sistema mata o trabalho assim
        //    que onReceive retorna.
        val pendingResult = goAsync()

        // 2. WakeLock dimensionado pra cobrir withTimeout + folga de teardown.
        //    NÃO maior — wakelock vivo depois do PendingResult ter expirado só
        //    drena bateria.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "myapp:receiver_wake_lock",
        ).apply { acquire(WAKELOCK_TIMEOUT_MILLIS) }

        // 3. Parse de extras ANTES da coroutine — early-return guards síncronos
        //    precisam dos extras pra decidir, e precisam fazer cleanup explícito.
        val exerciseId = intent.getLongExtra(EXTRA_EXERCISE_ID, -1L)
        val isOvershoot = intent.getBooleanExtra(EXTRA_IS_OVERSHOOT, false)

        // 4. Early-return guards síncronos — release wakelock + finish() ANTES
        //    do return, sem entrar na coroutine.
        if (isOvershoot && LocalDateTime.now().dayOfWeek !in sessionPrefs.activeDaysOfWeek) {
            if (wakeLock.isHeld) wakeLock.release()
            pendingResult.finish()
            return
        }

        // 5. Coroutine fire-and-forget com SupervisorJob.
        //    Dispatchers.Default (NÃO Main) — work é Room/AlarmManager IPC,
        //    não UI.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(SUSPEND_BUDGET_MILLIS) {
                    handleDispatch(context, exerciseId, /* ... */)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "work exceeded ${SUSPEND_BUDGET_MILLIS}ms budget", e)
            } catch (e: Exception) {
                Log.e(TAG, "work failed", e)
            } finally {
                // 6. INVARIANTE: finish() em TODOS os caminhos, dentro do finally.
                //    Sem isso, o sistema considera o receiver vivo até o timeout
                //    (~10s) e pode disparar ANR ou kill do processo.
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDispatch(context: Context, exerciseId: Long /*...*/) {
        val window = activityWindowRepository.getActiveWindow() // suspend Room read
        // ... lógica de negócio
    }
}
```

### Hierarquia de budgets (sizing não-arbitrário)

| Budget | Valor | Por que |
|---|---|---|
| **`PendingResult` system deadline** | ~10s | Imposto pelo Android 8+. Doze pode encurtar. Acima disso o sistema pode matar o processo. |
| **`withTimeout`** | 9000ms | 1s de margem abaixo do PendingResult — garante que `finish()` roda dentro do budget mesmo em caso de Room lock ou IPC lento. |
| **WakeLock** | 15000ms | Cobre o `withTimeout` + folga de teardown (release + finish + GC). Maior só drena bateria — wakelock vivo após o PendingResult ter expirado não tem efeito útil. |

**Planejamento original neste app sugeria 60s wakelock — o code review pegou o mismatch.** Documente os três budgets juntos como constantes nomeadas; manter implícito convida regressão.

### Companion helper precondition pattern

Helpers chamados de dentro do `goAsync` precisam fazer próprio cleanup em paths de falha — silent no-op vaza state:

```kotlin
fun rescheduleForNextDay(
    alarmScheduler: AlarmScheduler,
    sessionPrefs: SessionPreferences,
    window: ActivityWindow,
    /* ... */
) {
    if (!alarmScheduler.canScheduleExactAlarms()) {
        Log.w(TAG, "rescheduleForNextDay aborted — SCHEDULE_EXACT_ALARM revoked")
        // Permissão revogada: não consegue reagendar, mas encerra cadeia
        // limpamente em vez de silent return. UI não fica presa.
        alarmScheduler.cancelOvershoot()
        sessionPrefs.setFirstAlarmInChain(0L)
        return
    }
    // ... agenda normal
}
```

### Atomic state write após o gate visível

Quando o receiver escreve estado observável (ex: `isAlarmPending=true` em SharedPreferences), o write deve vir **depois** do `notify()` para evitar partial-state se `withTimeout` fire entre o write e o notify:

```kotlin
// Ordem dos efeitos no handleDispatch:
// 1. (suspend) query Room → decide if-window
// 2. alarmScheduler.scheduleOvershoot(...) — race-safe gate ANTES de notify
// 3. notificationManager.notify(NOTIFICATION_ID, notification) — gate visível
// 4. sessionPrefs.recordAlarmDispatchedNow(now) — atomic edit().apply(),
//    DEPOIS do gate visível para evitar UI mostrando pending sem alarme
// 5. AlarmSoundPlayer.play(...) + VibrationPlayer.start(...)
```

`recordAlarmDispatchedNow` faz batched write em single `.edit().apply()`:

```kotlin
fun recordAlarmDispatchedNow(nowMillis: Long) {
    val edit = prefs.edit().putBoolean(KEY_IS_ALARM_PENDING, true)
    if (prefs.getLong(KEY_FIRST_ALARM_IN_CHAIN_MILLIS, 0L) == 0L) {
        edit.putLong(KEY_FIRST_ALARM_IN_CHAIN_MILLIS, nowMillis)
    }
    edit.apply() // single tick no OnSharedPreferenceChangeListener
}
```

Isto evita janela transient em que o listener vê só um dos dois keys atualizados — Home renderia overdue legacy UI antes do anchor da cadeia ser atualizado.

## Why This Matters

**`pendingResult.finish()` é deadline duro, não sugestão.** O sistema mantém um wakelock em nome do `onReceive` apenas até `finish()` ser chamado (ou ~10s timeout). Sem `goAsync()`, `onReceive` retorna imediatamente e o sistema mata qualquer trabalho residual. Sem `finish()` em todos os branches (incluindo exceções e early returns), o sistema declara ANR.

**`SupervisorJob` isola child failures.** O scope é fire-and-forget — não está atado a ViewModelScope ou Activity lifecycle. `SupervisorJob` é o idiom para launch-and-manage scopes onde failures filhas não devem propagar pra parent. Bare `Job()` funcionaria mas `SupervisorJob` comunica explicitamente "esta é uma corrotina isolada".

**`Dispatchers.Default`, não `Dispatchers.Main`.** O trabalho é Room IPC (binder thread internamente) + AlarmManager IPC — nada UI. `Dispatchers.Main` bloquearia a main thread e desperdiçaria binder threads em frames sem necessidade.

**WakeLock dimensionado ao trabalho real.** Wakelock maior que o budget de trabalho não rende — o sistema já liberou sua referência ao processo via PendingResult timeout. Excesso de wakelock só significa CPU acesa sem contexto válido, drenando bateria.

**State writes depois do gate visível.** Em Compose/MVVM com observers em SharedPreferences, escrever flags de estado antes do notify cria janela transient onde a UI reflete "alarme pendente" sem nada visível ao usuário. Se o timeout fizer fire entre o write e o notify, fica permanente até o próximo dispatch.

## When to Apply

- BroadcastReceiver gatilhado por AlarmManager precisa fazer suspend Room reads, DataStore reads, ou network calls antes de emitir side-effects
- O receiver precisa fazer multi-step async I/O antes do gate visível (notificação, próximo schedule)
- WakeLock precisa ser garantidamente liberado após trabalho async
- Receiver precisa escrever estado atomicamente após uma gate visível

**Não use:** `GlobalScope.launch` (singleton scope sem timeout nem cleanup), `runBlocking` (bloqueia main, ANR garantido sob Doze), passar PendingResult para outro componente (acoplamento + risco de leak).

## Examples

### Caminho out-of-window com rollover

```kotlin
private suspend fun handleDispatch(/* ... */) {
    val now = LocalDateTime.now()
    val window = activityWindowRepository.getActiveWindow()

    // Out-of-window: empurra cadeia pro próximo dia ativo e NÃO toca
    if (window != null && now > now.toLocalDate().atTime(window.endTime)) {
        rescheduleForNextDay(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            window = window,
            activeDays = sessionPrefs.activeDaysOfWeek,
            // ...
        )
        return // sai do withTimeout normalmente; finally roda
    }

    // In-window: ordem importa
    if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
        val nextOvershoot = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())
        if (isInsideActiveWindow(nextOvershoot, window, sessionPrefs.activeDaysOfWeek)) {
            alarmScheduler.scheduleOvershoot(triggerAt = nextOvershoot, /* ... */)
            // ↑ ANTES de notify (race invariant)
        }
    }

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    // ↑ Gate visível

    sessionPrefs.recordAlarmDispatchedNow(System.currentTimeMillis())
    // ↑ State write DEPOIS do gate visível, atomic

    if (sessionPrefs.soundEnabled) AlarmSoundPlayer.play(/* ... */)
    if (sessionPrefs.vibrationEnabled) VibrationPlayer.start(context, bypassDnd = /* ... */)
}
```

### Efeitos do timeout firando em pontos diferentes

| Onde o timeout firou | O que sobrevive | UX consequence |
|---|---|---|
| Antes do `getActiveWindow` retornar | Nada | Sem ring, sem state — usuário não vê nada (próximo overshoot toca depois) |
| Entre `scheduleOvershoot` e `notify` | Overshoot agendado pro futuro | Próximo overshoot disparará no horário planejado |
| Entre `notify` e `recordAlarmDispatchedNow` | Notificação visível, mas SessionPreferences sem `isAlarmPending=true` | Usuário vê alarme, abre app, Home não exibe "pending" — recupera no próximo tick do listener quando alguma outra escrita acontecer |
| Depois de `recordAlarmDispatchedNow` (mid sound play) | Tudo persistido + visível | Som pode não tocar; vibração idem; usuário vê notificação |

Cada janela degrada graciosamente — nenhuma deixa o sistema em estado inconsistente que requer intervenção manual.

## Related

- `docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md` — race invariant: scheduleOvershoot ANTES de notify. Continua válido dentro deste pattern; o pattern goAsync herda essa restrição.
- `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md` — Bug C tinha deferred note pedindo "Requer goAsync() + injeção de DynamicSchedulerUseCase no receiver — refactor com escopo próprio". Este doc é a resolução desse deferred item.
- `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` — anchor-class discipline aplicada ao `firstAlarmInChainMillis` (o write em `recordAlarmDispatchedNow` é um exemplo de anchor-class write).

**Implementação de referência:** PR #3 (commit `e2fe223` em main), arquivos `app/src/main/java/com/gtg/app/presentation/alarm/AlarmReceiver.kt` + `app/src/main/java/com/gtg/app/domain/usecase/RotationHelpers.kt` + `app/src/main/java/com/gtg/app/data/local/SessionPreferences.kt` (helper `recordAlarmDispatchedNow`).
