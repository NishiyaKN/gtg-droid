---
title: "Calendar/manual block filter bypassed by all AlarmManager rollover write paths"
date: 2026-06-12
category: logic-errors
module: scheduler
problem_type: logic_error
component: background_job
symptoms:
  - "Alarme toca dentro de um evento de calendário no início da janela (janela 09:30, evento 09:10–09:40, toque às 09:30)"
  - "Qualquer rollover (fim de janela, dia inativo, snooze cross-day, reboot) arma o início bare da janela ignorando blocos do dia alvo"
  - "Evento de calendário criado ou movido APÓS o arme nunca suprime o toque — não existia validação no momento do disparo"
root_cause: missing_validation
resolution_type: code_fix
severity: high
related_components:
  - DynamicSchedulerUseCase
  - RotationHelpers
  - AlarmReceiver
  - AlarmViewModel
  - BootReceiver
  - CalendarEventRepositoryImpl
tags:
  - alarm-manager
  - calendar-blocks
  - scheduler
  - rollover
  - fire-time-guard
  - defense-in-depth
  - fail-open
  - writer-enumeration
---

# Calendar/manual block filter bypassed by all AlarmManager rollover write paths

> Mesma família estrutural de
> `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md`
> (filtro vivendo em um lugar enquanto o AlarmManager tem múltiplos writers
> independentes) — leia aquele doc primeiro para o padrão fundacional. Este
> doc é a instância do filtro de BLOCOS, que não estava no checklist
> apply-or-document daquele doc.

## Problem

Todos os caminhos de rollover armavam o `AlarmManager` com `window.startTime`
bare do próximo dia ativo — sem buscar os blocos de inatividade (manuais ou
Calendar) daquele dia e sem executar a Regra 4 de colisão. Adicionalmente,
nenhuma re-validação existia no momento do disparo, então eventos de
calendário criados *após* o arme nunca eram honrados.

## Symptoms

- Janela configurada para 09:30; evento de calendário 09:10–09:40; alarme
  full-screen dispara às 09:30 dentro da reunião (caso reportado).
- Em geral, qualquer bloco cobrindo o início da `ActivityWindow` é ignorado
  quando o agendamento vem de rollover: fim de janela
  (`scheduleForNextActiveDay`, 3 early-returns), countdown da Home /
  out-of-window do receiver (`rescheduleForNextDay`), snooze cross-day
  (`clampSnoozeToBounds`) e replay pós-reboot (`BootReceiver`).
- Evento criado ou movido depois do arme nunca move o disparo — a integração
  com calendário é pull-only (sem `ContentObserver`, sem cache, prefs de
  calendário excluídas do snapshot de re-avaliação).

## What Didn't Work

**1. Guard fire-time decidindo supressão por cluster MESCLADO.**
O primeiro design de `decideFireTimeDispatch` decidia supressão pela
contenção em clusters mesclados (blocos com gap ≤ buffer unidos). Review
adversarial construiu: blocos 13:00–13:58 + 14:01–15:00; a Regra 4 (que
checa blocos CRUS) arma legitimamente 13:59 no gap de 3min; o cluster
mesclado `[13:00, 15:00)` suprimiria esse disparo e moveria o alarme 66
minutos em silêncio. Correção: supressão decidida por bloco **cru**;
clusters mesclados só calculam o `rearmAt` (rearmar num gap sub-buffer
recriaria o ping-pong que a mescla evita).

**2. Parâmetros `floor`/`intervalMode` no resolver puro.**
Eram provadamente mortos: com a mescla, o rearme natural
(`clusterEnd + buffer`) é sempre `> now + buffer`, excedendo qualquer piso
de 1min; e STRICT já era pré-gateado em todos os pontos de entrada antes de
qualquer I/O. Removidos; o piso sobrevive como um `maxOf` de uma linha na
decisão fire-time, documentando o contrato contra drift futuro.

**3. Acreditar que `withTimeoutOrNull` cobria cancelamento de escopo.**
`withTimeoutOrNull` nulifica apenas o *próprio* timeout; cancelamento do
`viewModelScope` (Activity terminando durante os ≤3s de resolução, DEPOIS
de `dismissActiveAlarmSideEffects` já ter cancelado som/notificação/
overshoot) re-lança `CancellationException` e deixava a sessão sem nada
armado. Correção: `withContext(NonCancellable)` envolve as seções
comprometidas de `performSnooze` e `performCheck`.

**4. Abort TOCTOU suprimindo sem rearmar.**
Se `SCHEDULE_EXACT_ALARM` é revogado entre a decisão do guard (até 2s) e a
aplicação, `schedule()` engole `SecurityException` e `setNextAlarm`
persistiria ponteiro para alarme fantasma. A primeira versão abortava
suprimindo sem rearme — exatamente o "alarme perdido em silêncio" que a
camada de decisão proíbe. Correção: `suppressPrimaryInsideBlock` devolve
`false` sem executar nada; o guard deixa o disparo cair para **Ring**
(tocar não exige exact alarm e o fluxo normal mantém a UI consistente).

**5. Queries por dia no lookahead.**
A primeira versão buscava blocos dia a dia ao longo do loop — até 7
round-trips cross-process sequenciais ao `CalendarContract.Instances`
dentro do budget de 3s (relevante pós-doze). As datas candidatas são
precomputáveis (dependem só de `activeDaysOfWeek`): o overflow decide *se*
continua, nunca *para onde* vai. Substituído por UM `getBlocksInRange`
batch cobrindo o range inteiro; blocos manuais (Room, local) continuam por
dia em paralelo.

## Solution

Três camadas complementares.

### Camada 1 — Resolução de blocos em todo schedule-site de rollover

`resolveFirstAlarmForDay` (DynamicSchedulerUseCase.kt) é o primitivo puro —
DYNAMIC-only, postpone-only, sem descanso mínimo, mescla de clusters em
passada única:

```kotlin
fun resolveFirstAlarmForDay(
    date: LocalDate,
    window: ActivityWindow,
    blocks: List<InactivityBlock>,
    candidate: LocalDateTime = date.atTime(window.startTime),
): FirstAlarmResolution {
    val clusters = mergeBlocksIntoClusters(blocks) // gap ≤ buffer OU sobreposição une blocos
    val containing = clusters.firstOrNull { cluster ->
        candidate >= date.atTime(cluster.start) && candidate < date.atTime(cluster.end)
    }
    val resolved = if (containing != null) {
        date.atTime(containing.end).plusMinutes(INACTIVITY_BUFFER_MINUTES)
    } else {
        candidate
    }
    return if (!resolved.isBefore(date.atTime(window.endTime))) {
        FirstAlarmResolution.OverflowsWindowEnd   // caller rola para o próximo dia
    } else {
        FirstAlarmResolution.Resolved(resolved)
    }
}
```

O wrapper suspend `resolveFirstAlarmStartingAt` normaliza o dia inicial,
precomputa as datas candidatas (≤ 7 dias ativos), busca os blocos em batch
(ver item 5 de "What Didn't Work"), itera o primitivo, e degrada fail-open:
exaustão → início bare do último dia examinado + warn; falha de fetch →
início bare; `CancellationException` re-lançada. STRICT curto-circuita
antes de qualquer I/O.

**Tabela de roteamento dos writers** — todo call site que arma alarme ou
passa pelo resolver ou documenta a compensação:

| Writer | Rota |
|--------|------|
| Engine `ScheduledTomorrow` (3 early-returns de `scheduleForNextActiveDay`) | Pós-processado em `calculateNextAlarm`, gateado por `dateTime.toLocalTime() == window.startTime` |
| `RotationHelpers.rescheduleForNextDay` (countdown Home, out-of-window do receiver) | `resolveFirstAlarmStartingAt` com budget 3s; fallback bare no estouro |
| Snooze cross-day (`clampSnoozeToBounds`, AlarmViewModel.kt) | `resolveFirstAlarmStartingAt` com budget 3s; fallback bare |
| Snooze same-day | NÃO valida blocos no arme (pedido explícito do usuário); coberto pelo guard fire-time |
| `BootReceiver` replay | NÃO roteado (replay verbatim do millis persistido); coberto pelo guard fire-time |
| Preview da Home (sessão parada) | Opta por NÃO resolver (`resolveRolloverAgainstBlocks = false`) — resultado é descartado |
| STRICT em qualquer writer | Início bare por design (contrato AE7: STRICT pode tocar dentro de bloco) |

O gate por horário existe porque `ScheduledTomorrow` tem um 4º produtor — o
fall-through cross-midnight — que carrega horário mid-window JÁ resolvido
pela Regra 4 com os blocos da data correta e não pode ser reescrito
(re-resolver do início da janela o anteciparia, violando o intervalo). O
acoplamento gate ↔ `scheduleForNextActiveDay` está comentado nos dois lados.

### Camada 2 — Guard fire-time no AlarmReceiver

`suppressedByBlockGuard` roda depois do guard de janela e ANTES de
`scheduleOvershoot`/`notify` (seguro: a race que o invariant
overshoot-antes-de-notify protege exige notificação visível, que ainda não
existe para primaries — ver amendment em
`alarm-receiver-overshoot-schedule-race-2026-05-19.md`). Sub-budget de 2s
com fail-open para Ring. A decisão pura:

```kotlin
fun decideFireTimeDispatch(
    now: LocalDateTime,
    window: ActivityWindow,
    blocks: List<InactivityBlock>,
    intervalMode: IntervalMode,
    canScheduleExactAlarms: Boolean,
): FireTimeDecision {
    if (intervalMode == IntervalMode.STRICT) return FireTimeDecision.Ring
    if (!canScheduleExactAlarms) return FireTimeDecision.Ring   // suprimir sem rearme = alarme perdido

    val date = now.toLocalDate()
    val insideRawBlock = blocks.any { block ->
        now >= date.atTime(block.startTime) && now < date.atTime(block.endTime)
    }
    if (!insideRawBlock) return FireTimeDecision.Ring   // contenção por bloco CRU

    val probe = resolveFirstAlarmForDay(date, window, blocks, candidate = now)
    return when (probe) {
        // Rearme pelo cluster MESCLADO. O rearme natural (fim do cluster +
        // buffer) já é > now por construção; o maxOf materializa o contrato
        // do piso fixo contra drift futuro (ex.: buffer reduzido a zero) —
        // não é um guard de runtime removível.
        is FirstAlarmResolution.Resolved ->
            FireTimeDecision.SuppressAndReschedule(
                maxOf(probe.dateTime, now.plusMinutes(FIRE_TIME_REARM_FLOOR_MINUTES)),
            )
        FirstAlarmResolution.OverflowsWindowEnd -> FireTimeDecision.SuppressAndRollToNextDay
    }
}
```

Side-effects da supressão de primary vivem em
`RotationHelpers.suppressPrimaryInsideBlock` (domain, testável): cancela
ambos os alarmes → `schedule` → `setNextAlarm`; NUNCA `setLastCheck`
(âncora de cadência); NÃO zera o T0 da cadeia (postponement same-day
preserva a cadeia em andamento, diferente do rollover cross-day);
re-valida `canScheduleExactAlarms` na aplicação e devolve `false` (→ Ring)
no abort TOCTOU. Re-arme de overshoot suprimido é gateado em
`isAlarmPending` (overshoot fantasma in-flight não rearma em dupla com o
primary).

### Camada 3 — Compromisso de conclusão nos caminhos de usuário

`performSnooze` e `performCheck` (AlarmViewModel.kt) rodam a seção
pós-dismissal sob `withContext(NonCancellable)`: depois que som/notificação/
overshoot foram cancelados, o rearme PRECISA completar mesmo com a Activity
terminando no meio da resolução.

## Why This Works

**Causa raiz estrutural.** A Regra 4 existia apenas em
`evaluateWithDependencies`, protegendo o candidato same-day calculado do
`checkTime`. Qualquer desvio do caminho feliz (rollover, snooze cross-day,
reboot) armava bare e a proteção evaporava — mesma anatomia do bypass de
`activeDaysOfWeek`.

**A integração com calendário é pull-only — e isso torna a Camada 2
obrigatória, não opcional.** `CalendarEventRepositoryImpl` consulta
`CalendarContract.Instances` live a cada chamada; não há cache, não há
`ContentObserver`, e as prefs de calendário estão fora do snapshot de
prefs-diff que dispara re-agendamento. Consequência: um evento criado
*depois* do arme JAMAIS move o alarme automaticamente. A correção no
schedule-site (Camada 1) só protege contra blocos que existiam no momento
do arme; o guard fire-time (Camada 2) é a única rede para mudanças
posteriores. Nenhuma camada sozinha basta:

- Camada 1 sozinha: cega para eventos pós-arme.
- Camada 2 sozinha: corrige o disparo, mas o ciclo seguinte voltaria a
  armar bare.
- Camada 3: sem ela, cancelamento de escopo durante resolução lenta deixa
  a sessão sem nada armado — classe de falha que as outras camadas não veem.

**Hierarquia fail-open explícita.** Tocar dentro de uma reunião invisível
ao app é melhor que um alarme que nunca toca (R5: nenhum caminho termina
sem nada armado). Falha/timeout de fetch no guard → Ring; falha de fetch no
resolver → início bare; revogação TOCTOU → Ring; estouro de budget no
rollover → início bare.

## Prevention

**1. Enumerar writers pelo grep da CAPACIDADE, não da função:**

```bash
grep -rn "alarmScheduler\.\|AlarmScheduler\." app/src/main/java/ --include="*.kt"
```

Cada call site de `schedule`/`scheduleOvershoot` precisa ou (a) rotear pelo
resolver/`calculateNextAlarm`, ou (b) documentar a compensação (guard
fire-time, STRICT por design). Omissão silenciosa — sem rota e sem
documentação — é a raiz de TODA esta família de bugs.

**2. Checklist apply-or-document para writers que bypassam o use case**
(estende o checklist do doc active-days, que listava só `activeDaysOfWeek`
e `endTime` — a ausência dos blocos nesse checklist é como este bug nasceu):

| Garantia | Rollover | Snooze same-day |
|----------|----------|-----------------|
| `activeDaysOfWeek` | aplicar (`findNextActiveDate`/resolver) | aplicar |
| `ActivityWindow.endTime` | aplicar | aplicar |
| Blocos (Regra 4) | aplicar via resolver | omitir + documentar (guard fire-time é a rede) |
| Descanso mínimo (Regra 3) | n/a (sem checkTime) | omitir (empurraria 5min → 20min) |
| `setLastCheck` | NUNCA | NUNCA |

**3. Invariante de composição de budgets pinada por teste** (em
`RotationHelpersTest`): `BLOCK_GUARD (2s) + FIRST_ALARM_RESOLUTION (3s) ≤
SUSPEND_BUDGET (9s) − DISPATCH_TAIL_SLACK (4s)`. A folga nomeada absorve o
que não tem budget próprio (leitura da window, notify, cauda de
agendamento) — crescer um sub-budget quebra o teste e força a conversa.
Constantes: `BLOCK_GUARD_BUDGET_MILLIS`, `SUSPEND_BUDGET_MILLIS` e
`DISPATCH_TAIL_SLACK_MILLIS` no companion de `AlarmReceiver.kt`;
`FIRST_ALARM_RESOLUTION_BUDGET_MILLIS` em `RotationHelpers.kt`.

**4. Toda fonte de dados pull-only sem observer exige re-validação no
consumo.** Se um dado externo pode mudar entre o cálculo e o uso (aqui:
calendário entre arme e disparo) e não há mecanismo de invalidação, o
ponto de USO precisa re-validar — com budget próprio e fail-open definido.

**5. Testes no nível de função pura** (`resolveFirstAlarmForDay`,
`decideFireTimeDispatch`): tempo injetado por parâmetro, nomes backtick em
PT, geometrias de fronteira pinadas (gap exatamente = buffer; bloco
terminando exatamente no início da janela; contenção pelo segundo bloco de
um cluster; gap sub-buffer → Ring). Wiring do receiver: verificação manual
por convenção (sem Robolectric).

## Related Issues

- `docs/solutions/logic-errors/active-days-alarm-bypass-2026-05-16.md` —
  o padrão fundacional desta família (writer-enumeration); este fix fecha
  parcialmente o item deferido daquele doc (revalidação de blocos em
  caminhos que bypassam o use case) via o guard fire-time.
- `docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md` —
  amendado por este fix: o block guard roda antes do par
  `scheduleOvershoot`+`notify`; claim de "nenhuma notificação existe"
  escopado a primaries, com gate `isAlarmPending` para overshoots.
- `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` —
  a matriz de side-effects de `suppressPrimaryInsideBlock` é a aplicação
  mais explícita daquela disciplina até agora.
- `docs/solutions/architecture-patterns/alarm-receiver-goasync-coroutine-room-2026-05-22.md` —
  os sub-budgets do guard seguem a hierarquia de budgets desse pattern.
- Plano: `docs/plans/2026-06-11-001-fix-window-start-calendar-block-plan.md`;
  PR: NishiyaKN/gtg-droid#4.
