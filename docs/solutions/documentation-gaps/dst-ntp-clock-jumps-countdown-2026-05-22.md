---
title: "DST and NTP clock jumps in epoch-millis countdown — known unhandled gap"
date: 2026-05-22
category: docs/solutions/documentation-gaps/
module: scheduler
problem_type: documentation_gap
component: background_job
severity: low
applies_when:
  - "App calcula tempo decorrido via (System.currentTimeMillis() - anchorMillis)"
  - "Counter rendering happens at 1Hz tick in a viewModelScope loop"
  - "Anchors são persistidos em wall-clock epoch millis (não monotonic)"
  - "App opera em regiões com DST OU usuários que ajustam manualmente o clock OU dispositivos com NTP auto-correction"
tags:
  - dst
  - ntp
  - clock-skew
  - countdown
  - elapsedrealtime
  - currenttimemillis
  - localdatetime
  - timezone
---

# DST and NTP clock jumps in epoch-millis countdown — known unhandled gap

## Context

`HomeViewModel.restartCountdown` calcula `chainElapsedSeconds = (System.currentTimeMillis() - chainStartedAtMillis) / 1000` a cada tick (1Hz) durante uma cadeia ativa de alerta. O resultado vai pra Home como contador crescente `+MM:SS`. O cálculo é correto em condições normais, mas o `System.currentTimeMillis()` é **wall-clock**, não monotonic — está sujeito a:

1. **NTP backward correction**: dispositivo descobre que o relógio está adiantado, ajusta para trás. Counter regrediria.
2. **NTP forward correction**: dispositivo descobre que o relógio está atrasado, ajusta para frente. Counter mente sobre tempo decorrido.
3. **Manual clock change**: usuário muda hora/data nas Settings.
4. **DST spring-forward**: relógio pula 1h.
5. **Timezone change**: viagem internacional — wall-clock muda independente de epoch (mas epoch millis é fuso-naive, então isso é seguro pra subtração — caveats de display abaixo).

O `coerceAtLeast(0L)` aplicado no resultado **protege apenas contra (1)** — quando `now < anchor`, clampa para 0 em vez de exibir negativo. Não protege contra (2-4), que aumentam o `now` e fazem o counter mostrar mais tempo decorrido do que realmente passou. (5) não afeta o cálculo de epoch millis em si.

Este doc registra a lacuna conhecida — não tem fix neste lote.

## Guidance

### O gap

```kotlin
// HomeViewModel.restartCountdown (current state)
chainElapsedSeconds = ((nowMillis - chainStart) / 1000).coerceAtLeast(0L)
// ↑ Protege contra now < chainStart (NTP backward, user-set clock back).
//   NÃO protege contra now jumping forward (NTP forward, DST spring-forward,
//   user-set clock forward).
```

**Cenário concreto de display lies (NTP forward correction):**
- T=14:00 — alarme primary toca. AlarmReceiver escreve `firstAlarmInChainMillis = 14:00` epoch.
- T=14:02 — user opens app. counter shows `+02:00`. ✓ correto.
- T=14:03 — NTP descobre clock estava 5min atrasado, ajusta o relógio para 14:08.
- T=14:08 wall-clock (real 14:03) — next tick: `nowMillis = 14:08`, `chainStart = 14:00`, elapsed = 8min. Counter shows `+08:00`. ✗ usuário sabe que só passaram ~3min de tempo real.

O bug é cosmético (display incorreto) mas pode confundir UX — usuário acha que adiou mais que adiou.

### Decision: aceitar como known limitation neste lote

**Razão:** Solo product, single user. NTP corrections de magnitude visível (>30s) são raras na prática. Adicionar handling defensivo (monotonic clock OR drift detection) é trade-off real:
- `SystemClock.elapsedRealtime()` (monotonic): não sofre wall-clock jumps. Mas reseta no boot, e o anchor `firstAlarmInChainMillis` é wall-clock epoch persistido — misturar os dois quebra a continuidade através de reboot.
- Drift detection: comparar `elapsedRealtime delta` vs `currentTimeMillis delta` por tick. Se divergem além de threshold (ex: >5s), o wall-clock pulou. Pode acionar reset do anchor ou notificar UI. Adiciona estado e complexidade.

Para o ciclo de uso do GtG (cadeias tipicamente <30min) e a base de usuário (solo, Android moderno com NTP auto), o display lie é aceitável trade-off vs complexidade adicional.

### Mitigation patterns (quando o trade-off mudar)

**1. Monotonic + persistent anchor reconstruction:**

```kotlin
// Use elapsedRealtime() para elapsed display, currentTimeMillis() apenas
// para persistir anchor e calcular agendamento de alarme.
// No init/boot: reconstruct elapsedRealtime offset:
private val realtimeOffsetAtBoot: Long =
    System.currentTimeMillis() - SystemClock.elapsedRealtime()

fun displayElapsedSeconds(chainStartEpochMillis: Long): Long {
    val chainStartRealtime = chainStartEpochMillis - realtimeOffsetAtBoot
    return ((SystemClock.elapsedRealtime() - chainStartRealtime) / 1000)
        .coerceAtLeast(0L)
}
```

Quebra: `realtimeOffsetAtBoot` muda a cada boot. Se a cadeia atravessou reboot, o anchor original (epoch millis pré-boot) não combina mais com o offset atual — display vai mostrar elapsed errado pós-reboot. Para o GtG isso é mitigado pelo `BootReceiver` resetar `firstAlarmInChainMillis = 0L` em `BOOT_COMPLETED`, então cadeias não atravessam reboot por design. Mas pra apps que precisam preservar cadeias pós-reboot, esse pattern não basta sozinho.

**2. Drift detection com fallback gracioso:**

```kotlin
private var lastTickRealtime: Long = 0L
private var lastTickWallclock: Long = 0L

fun tickAndDetectDrift() {
    val nowRealtime = SystemClock.elapsedRealtime()
    val nowWallclock = System.currentTimeMillis()
    if (lastTickRealtime > 0) {
        val realtimeDelta = nowRealtime - lastTickRealtime
        val wallclockDelta = nowWallclock - lastTickWallclock
        val drift = kotlin.math.abs(realtimeDelta - wallclockDelta)
        if (drift > DRIFT_THRESHOLD_MILLIS) { // ex: 5000ms
            Log.w(TAG, "wall-clock drift detected: ${drift}ms; resetting chain anchor")
            sessionPrefs.setFirstAlarmInChain(nowWallclock - realtimeDelta * elapsedInRealtime)
            // Reset anchor para "agora" - elapsedReal já contado
        }
    }
    lastTickRealtime = nowRealtime
    lastTickWallclock = nowWallclock
}
```

Quebra: adiciona estado mutável ao countdown loop, complexifica lógica do tick. Não vale para um display advisory; vale quando elapsed é load-bearing para decisão automatizada (ex: timeout de overshoot).

**3. ZonedDateTime + Instant para boundary checks (já correto):**

Em `AlarmReceiver.handleDispatch`, comparar window boundaries usa `LocalDateTime.now()` (wall-clock-based) vs `window.endTime` (`LocalTime`). DST spring-forward em janela cobrindo o horário fantasma (ex: janela 01:00-04:00, US Eastern spring-forward — 02:00 vira 03:00):
- Alarme primary agendado para 02:15 → AlarmManager dispara em 03:15 wall-clock (pulou 02:00-03:00).
- Receiver le `now = 03:15`, `window.endTime = 04:00`. `now > endTime`? Não (03:15 < 04:00). Toca normalmente. ✓
- Mas se janela fosse 01:00-02:30 e alarme 02:15: dispara em 03:15. `now > 02:30` → bloqueia, rollover. Usuário perde o set sem feedback "voce perdeu por causa do DST".

Comparar via `ZonedDateTime` no fuso explícito + `Instant` resolve corretamente apenas se a interpretação do user da janela é "intervalo de wall-clock no fuso atual" (DST-aware). Não há fix universal aqui — é decisão de produto: "janela 08:00-17:30 no horário local" significa diferentes Instants em manhãs de DST. O GtG atualmente aceita a semântica wall-clock simples e o usuário entende o trade-off.

## Why This Matters

**Counter advisory ≠ counter load-bearing.** Se o counter `+MM:SS` é só feedback visual ("você está atrasado X tempo"), display lies em casos raros são UX inconvenientes. Se fosse load-bearing (ex: app decide tocar overshoot mais agressivo após X min de delay), display lies viram decisão errada. O GtG mantém o counter advisory — o overshoot scheduling usa o nextAlarmMillis (wall-clock) preservado em SharedPreferences, e o AlarmManager respeita o instante absoluto independente de wall-clock subsequente.

**NTP corrections de magnitude visível são raras mas não impossíveis.** Pixel devices fazem NTP sync a cada few hours por default. Magnitude típica é sub-second. Mas: dispositivos volta de modo airplane, dispositivos recém boot, viagens internacionais sem auto-time. O usuário pode ver counter inconsistente em qualquer um desses cenários.

**Persistent anchors em epoch millis são intrinsecamente wall-clock-dependent.** Não há fix simples sem repensar como state cross-reboot é representado. Para apps que precisam de elapsed accuracy E persistência pós-reboot, considere armazenar AMBOS o epoch millis (para reconstrução pós-reboot) E periodicamente atualizar via tick-based incremento monotonic (para resilience entre ticks).

## When to Apply

Reabrir este gap quando QUALQUER um destes mudar:

- Usuário reporta confusão recorrente com counter (ex: "diz +08:00 mas só passou 3min")
- Usuário viaja internacionalmente com sessão ativa e perde clareza do estado
- Counter passa a drive lógica automática (não só display)
- Multi-user adoption — base ampliada aumenta probabilidade de hits dos cenários raros

Aceitar o gap quando:

- Cadeias permanecem curtas (<30min típico)
- Base de usuários single/solo
- NTP corrections sub-second são a norma
- Display lies em casos raros não corrompem decisões load-bearing

## Examples

### Detecção em uso real

Sintomas observáveis pelo usuário:
- Counter `+MM:SS` mostra valor inconsistente com timer mental do usuário
- Especialmente após boot (NTP pode rodar agressivamente nos primeiros minutos)
- Especialmente após retorno de modo airplane
- Especialmente em viagem internacional (mudança de fuso pode trigger NTP re-sync)

Log signal (se você adicionar drift detection):
```
W AlarmReceiver: wall-clock drift detected: 297000ms; resetting chain anchor
```

### Fix mais simples se decidir aceitar (current code)

```kotlin
chainElapsedSeconds = ((nowMillis - chainStart) / 1000).coerceAtLeast(0L)
//                                                    ^^^^^^^^^^^^^^^^
// Apenas protege contra clock REGRESSION. Display lies em forward jumps são aceitos.
```

### Fix robusto se precisar de accuracy

Combine monotonic clock (para tick-to-tick deltas confiáveis) + epoch millis (para anchor cross-reboot via `BootReceiver` reset + first-dispatch fresh write). Aceitar que cadeias não persistem através de reboot e a UX se baseia nessa simplicidade.

## Related

- `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` — Cadence anchor (`lastCheckMillis`) sofreria do mesmo gap se fosse usado para display. Atualmente é usado só para schedule next (epoch millis comparison é OK porque AlarmManager respeita instante absoluto).
- `docs/solutions/architecture-patterns/alarm-receiver-goasync-coroutine-room-2026-05-22.md` — `recordAlarmDispatchedNow` escreve `nowMillis = System.currentTimeMillis()` no anchor de chain. Esse anchor herda o gap descrito aqui.

**Codepoint de referência:** `app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt`, função `restartCountdown` — cálculo do `chainElapsedSeconds`. Surfaceado pelo ce-code-review (adversarial reviewer, anchor 75, finding adv-6) durante o lote alarm-snooze-rotation-followups (PR #3, commit `e2fe223`).
