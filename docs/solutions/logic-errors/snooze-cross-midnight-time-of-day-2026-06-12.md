---
title: "Snooze cross-midnight validated by time-of-day only, arming alarm before next window start"
date: 2026-06-12
category: logic-errors
module: alarm
problem_type: logic_error
component: background_job
symptoms:
  - "Snooze às 23:48 + 15min agenda alarme para 00:03 do dia seguinte — de madrugada, antes do início da janela do próximo dia"
  - "Comportamento totalmente silencioso — sem erro, sem log; o usuário é acordado no horário errado"
  - "AlarmReceiver.handleDispatch só guardava o FIM da janela (now > endTime); disparo antes de startTime passava sem supressão"
root_cause: logic_error
resolution_type: code_fix
severity: high
related_components:
  - AlarmViewModel
  - AlarmReceiver
  - RotationHelpers
tags:
  - alarm-manager
  - snooze
  - cross-midnight
  - scheduler
  - time-of-day
  - rollover
  - off-by-one-day
  - defense-in-depth
---

# Snooze cross-midnight validated by time-of-day only, arming alarm before next window start

## Problem

`AlarmViewModel.clampSnoozeToBounds` validava se o candidato de snooze estava dentro da janela ativa comparando **apenas o horário** (`LocalTime`) com `window.endTime`, sem verificar a data. Um snooze gerado perto da meia-noite (alarme às 23:48, snooze de 15 min → candidato 00:03 do dia seguinte) passava na verificação porque `00:03.isAfter(23:50)` é `false`, e o alarme era armado para de madrugada, antes do início da janela do próximo dia.

Achados #8 (correctness + adversarial, promoção por concordância entre revisores) do code review whole-project de 2026-06-12 (run `20260612-195035-626bd1f2`). Fix nos commits `abd7c44` (código) e `1104b72` (teste).

## Symptoms

- O alarme dispara em plena madrugada, em horário anterior ao `startTime` da janela do dia seguinte.
- Nenhuma mensagem de erro; comportamento completamente silencioso — sem log, sem crash. O usuário simplesmente é acordado no horário errado.
- O guard fire-time existente no `AlarmReceiver.handleDispatch` só verificava o limite **final** da janela (`now > endTime`), deixando disparos antes de `startTime` sem defesa em profundidade.

## What Didn't Work

**Armadilha 1 — a correção sugerida pelos revisores carregava um segundo bug (salto de dia).**

Dois revisores independentes apontaram corretamente o problema do `LocalTime` e ambos sugeriram o mesmo fix de same-day — mas com o rollover ancorado em `candidateDate`. O contrato de `findNextActiveDate` (`RotationHelpers.kt`) é de busca **estritamente depois de** o argumento:

```
* Acha o próximo [LocalDate] ESTRITAMENTE depois de [after] cujo `dayOfWeek`
* pertence a [activeDaysOfWeek].
```

Para um candidato que já cruzou para D+1 (snooze 23:48+15min → `candidateDate` = amanhã), `findNextActiveDate(candidateDate, ...)` retornaria D+2 — pulando silenciosamente um dia inteiro:

```kotlin
// ERRADO — sugestão de AMBOS os revisores (salto de dia sutil):
val nextDate = findNextActiveDate(candidateDate, activeDays)

// CORRETO — âncora em today:
val nextDate = findNextActiveDate(today, activeDays)
```

O erro só foi detectado pela leitura do KDoc de `findNextActiveDate` *antes* de aplicar a mudança. Achado de revisor não dispensa verificação do contrato das funções que o fix sugerido invoca.

**Armadilha 2 — teste com `now` real era não determinístico.**

O bug só reproduzia se o teste rodasse perto da meia-noite; com `overshootRepeatMinutes = 5` durante o dia, o candidato não cruzava a data e o teste passava como falso negativo. Resolvido com `overshootRepeatMinutes = 1440` (24h), forçando o cruzamento de data em qualquer horário de execução.

## Solution

**1. `clampSnoozeToBounds` (`AlarmViewModel.kt`) — fast path exige mesmo dia + âncora do rollover em HOJE**

Antes:

```kotlin
val candidateDate = candidate.toLocalDate()
val dayOk = candidateDate.dayOfWeek in activeDays
val withinWindow = window == null || !candidate.toLocalTime().isAfter(window.endTime)
if (dayOk && withinWindow) return candidate

val nextDate = findNextActiveDate(candidateDate, activeDays)
```

Depois (commit `abd7c44`):

```kotlin
val today = java.time.LocalDate.now()
val candidateDate = candidate.toLocalDate()
val dayOk = candidateDate.dayOfWeek in activeDays
// Fast path exige MESMO dia: um snooze que cruza a meia-noite (ex.:
// 23:48 + 15min = 00:03) passaria no check de time-of-day (00:03 não
// é "depois de" 23:50) e armaria um alarme noturno antes do início da
// janela do dia seguinte — o guard fire-time só valida o FIM da
// janela. Cross-midnight cai no rollover via resolver abaixo.
val withinWindow = window == null ||
    (candidateDate == today && !candidate.toLocalTime().isAfter(window.endTime))
if (dayOk && withinWindow) return candidate

// Âncora do rollover é HOJE, não candidateDate: para um candidato que
// já cruzou para D+1, findNextActiveDate(candidateDate) pularia
// incorretamente para D+2 (a busca é estritamente "depois de").
val nextDate = findNextActiveDate(today, activeDays)
```

Cross-midnight agora cai no rollover existente via `resolveFirstAlarmStartingAt` (budget + fallback bare), idêntico ao caso de overflow same-day.

**2. Guard pré-início de janela no `AlarmReceiver.handleDispatch` (defesa em profundidade)**

Adicionado ao lado do guard de fim de janela, na mesma política do block guard (postpone-only, sub-budget, fail-open):

```kotlin
val windowStartToday = now.toLocalDate().atTime(window.startTime)
if (now.isBefore(windowStartToday)) {
    if (isOvershoot) return // drop silencioso, como no guard de dia inativo
    val rearmAt = withTimeoutOrNull(BLOCK_GUARD_BUDGET_MILLIS) {
        dynamicScheduler.resolveFirstAlarmStartingAt(
            startDate = now.toLocalDate(), /* ... */
        )
    } ?: windowStartToday
    val rearmed = suppressPrimaryInsideBlock(/* rearmAt = rearmAt, ... */)
    if (rearmed) return // TOCTOU de permissão → cai para Ring
}
```

Cobre também os outros dois vetores que armam disparo pré-janela: edição da janela mid-session (o `PendingIntent` armado não é cancelado quando o usuário move `startTime`) e mudança de fuso horário (o arme é epoch absoluto).

**3. Teste de regressão determinístico (`AlarmViewModelTest.kt`, commit `1104b72`)**

```kotlin
@Test
fun `performSnooze que cruza a meia-noite faz rollover em vez de armar de madrugada`() = runTest {
    // 1440 min (24h) força o candidato a cruzar a data de forma
    // determinística em qualquer horário de execução do teste.
    every { sessionPrefs.overshootRepeatMinutes } returns 1440
    coEvery { activityWindowRepository.getActiveWindow() } returns ActivityWindow(
        id = 1L, startTime = LocalTime.of(8, 0), endTime = LocalTime.of(23, 59),
    )
    // ...
    val tomorrow = LocalDateTime.now().toLocalDate().plusDays(1)
    // Pina AMANHÃ às 08:00 — não D+2, que seria o resultado de
    // ancorar o rollover em candidateDate.
    assertEquals(tomorrow.atTime(8, 0), captured.captured)
    verify(exactly = 1) { sessionPrefs.setFirstAlarmInChain(0L) }
}
```

## Why This Works

`LocalTime` representa apenas posição no ciclo de 24h — é uma grandeza **cíclica**, sem data. `00:03.isAfter(23:50)` retorna `false` porque 00:03 é numericamente menor, mas semanticamente 00:03 do dia seguinte está *depois* de 23:50 de hoje no eixo temporal real. Qualquer bounds check sobre um `LocalDateTime` construído pela adição de uma duração precisa comparar o `LocalDateTime` inteiro ou afirmar explicitamente que a data não mudou — nunca apenas `.toLocalTime()`.

O segundo vetor — a âncora errada do rollover — deriva do contrato **exclusivo** de `findNextActiveDate` (estritamente depois do argumento). Para um candidato já em D+1, ancorar em `candidateDate` produz D+2. A escolha da âncora é load-bearing e exige a leitura do contrato antes do uso.

## Prevention

1. **Bounds check sobre `LocalDateTime` derivado de adição de duração compara o objeto inteiro, nunca `.toLocalTime()` extraído.**

   ```kotlin
   // ERRADO:
   val ok = !candidate.toLocalTime().isAfter(window.endTime)
   // CERTO (same-day explícito):
   val ok = candidate.toLocalDate() == today && !candidate.toLocalTime().isAfter(window.endTime)
   // CERTO (comparação completa):
   val ok = !candidate.isAfter(today.atTime(window.endTime))
   ```

2. **Antes de usar um helper de caminhada de datas, releia o contrato inclusivo/exclusivo.** `findNextActiveDate` é estritamente-depois; a âncora muda o resultado em um dia inteiro. Pergunte: "o valor que vou passar já está no futuro em relação ao que quero encontrar?". Vale igualmente para fixes sugeridos por revisores/agentes — verifique o contrato das funções que o fix invoca antes de aplicar.

3. **Testes de cruzamento de meia-noite devem ser determinísticos via intervalo sobredimensionado** (≥ 1440 min), nunca dependentes do relógio rodar perto de 00:00. Pinar o dia EXATO do resultado (`tomorrow.atTime(...)`, não apenas `isAfter(today)`) também pina a ausência do salto D+2.

4. **Guards de fire-time validam AMBAS as bordas da janela.** Um guard só de `now > endTime` deixa a borda inferior aberta para snooze cross-midnight, edição de janela e mudança de fuso. Checklist de bordas: antes de `startTime` E depois de `endTime`, para primaries e overshoots separadamente.

## Related Issues

- `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md` — doc fundacional da família: o Bug E daquele lote criou `clampSnoozeToBounds`; este doc corrige um defeito latente da mesma função (o check existia mas descartava a data).
- `docs/solutions/logic-errors/window-start-block-bypass-2026-06-12.md` — irmão do mesmo branch: introduziu `resolveFirstAlarmStartingAt`, que o rollover deste fix reutiliza. Bug distinto na mesma função (lá: filtro de blocos ignorado nos schedule-sites; aqui: comparação time-of-day descartando a data).
- `docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md` — define a ordem de dispatch do receiver; o novo guard pré-janela se posiciona ao lado do block guard descrito no amendment de 2026-06-11.
- `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` — contrato de side-effects do snooze (`setLastCheck` nunca; `setFirstAlarmInChain(0L)` só em rollover cross-day), preservado por este fix.
- `docs/solutions/documentation-gaps/dst-ntp-clock-jumps-countdown-2026-05-22.md` — mesma classe de armadilha (comparações de relógio de parede); contexto adjacente.
- Sem issues do GitHub relacionadas (busca em 2026-06-12 não retornou resultados).
