---
date: 2026-05-21
topic: alarm-snooze-rotation-followups
---

# Lote follow-up — snooze inteligente, vibração que vibra, overshoot dentro da janela, fim do Skip, picker de hora estável

## Summary

Cinco correções e refinamentos derivados de uso real do app. (1) Quando o alarme está em snooze/overshoot, a Home libera o Check imediatamente e mostra um contador crescente "+MM:SS" desde o primeiro alarme da cadeia em vez do timer regressivo — o usuário precisa enxergar quanto atraso já acumulou. (2) `VibrationPlayer` ganha `AudioAttributes` com `USAGE_ALARM`, eliminando a falha intermitente em DND e em OEMs (Samsung/OnePlus) que silenciam vibrações sem usage declarado. (3) `AlarmReceiver` para de tocar overshoot fora da `ActivityWindow` — o disparo vira rollover automático para o início da próxima janela ativa, preservando o exercício pending. (4) O botão "Pular" da `AlarmActivity` é removido — Snooze cobre "não agora", Check cobre "feito", Stop cobre "encerrar". (5) `WheelNumberPicker` corrige o cálculo de item central que erra com fling rápido, fazendo a hora pular indevidamente.

---

## Problem Frame

Cinco atritos independentes observados em uso continuado:

1. **Snooze esconde quanto atraso já acumulou.** Hoje, ao dar snooze, `AlarmActivity.performSnooze` agenda `nextAlarmMillis = now + overshootRepeatMinutes` e zera `isAlarmPending`. A Home volta a mostrar timer regressivo "5:00 → 0:00" para o próximo dispatch — o usuário perde a referência de "o alarme original tocou às 14:25, já se passaram 18 minutos". Pior: se o usuário aumentou snooze para 10 ou 15min, o Check fica bloqueado por 5–10min porque `canCheck = remaining ≤ CHECK_WINDOW_SECONDS` (5 min) e `remaining` é positivo durante todo o snooze.

2. **Vibração não dispara de forma confiável.** `VibrationPlayer.start()` chama `vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0))` sem `AudioAttributes`. Sem `USAGE_ALARM` declarado, o sistema decide silenciar a vibração quando: o usuário está em DND, certos OEMs (Samsung/OnePlus) interpretam usage `UNKNOWN` como notificação silenciável, e em telas apagadas com perfil de vibração reduzido. `AlarmSoundPlayer` já usa `AudioAttributes` corretamente — só o caminho de vibração esqueceu.

3. **Overshoot continua tocando fora da janela de atividade.** `AlarmReceiver.onReceive` (linhas 142–154) agenda o próximo overshoot em `now + overshootRepeatMinutes` validando apenas `activeDaysOfWeek`. Cenário real: alarme primary toca às 17:25, janela termina 17:30, ninguém atende; 17:30 dispara overshoot 1, agenda 17:35; 17:35 dispara overshoot 2 fora da janela; cadeia segue até alguém abrir o app (que roda `rescheduleForNextDayKeepingExercise`) ou dar Stop. Com o app fechado e tela apagada, o telefone soa indefinidamente após o expediente. O `clampSnoozeToBounds` em `AlarmViewModel.kt:191-204` já protege o snooze manual, mas o overshoot automático não passa por esse caminho.

4. **Botão "Pular" produz distribuição de sets que parece bug e não é.** Em uma sessão típica com 3 exercícios em rotação round-robin, dar Skip 2 vezes (porque "esse set eu não vou fazer agora") produz log desigual — ex1=3, ex2=2, ex3=1. O `performSkip` em `AlarmViewModel.kt:122-128` avança a rotação chamando `pickNextExerciseInRotation` sem inserir log; matematicamente correto, mas o usuário interpreta como bug do scheduler. Como `performSnooze` já cobre "não agora, daqui a N min" sem avançar rotação, o Skip não traz capacidade nova — só confunde e desequilibra a rotação.

5. **`WheelNumberPicker` pula de hora indevidamente em fling rápido.** Configurando `ActivityWindow` no onboarding ou em Settings (entre 8 e 9 horas, observado em uso), rolar minutos com velocidade alta faz a hora pular para o valor adjacente. Diagnóstico: o `derivedStateOf { items.minBy { ... }.index }` (linhas 64-72 do `WheelNumberPicker`) computa o item central por proximidade visual entre offset/size e o viewportCenter. Em fling rápido com `rememberSnapFlingBehavior`, o snap não cai pixel-perfect no item alvo — o `centeredIndex` reflete a posição intermediária, dispara `onValueChange` com o valor errado, e o `LaunchedEffect(value)` faz `scrollToItem(v)` reforçando o pulo. O range de minutos (0–59) é maior que o range de horas (0–23) e por isso o problema é mais visível na coluna de minutos, mas a causa raiz é o mesmo cálculo de centro.

---

## Requirements

### Item 1 — Snooze inteligente (Check sempre liberado + contador crescente)

- R1. `SessionPreferences` ganha um novo campo `firstAlarmInChainMillis: Long` (default `0L`). Representa o epoch millis do primeiro dispatch do alarme primary nesta cadeia atual (cadeia = sequência primary → overshoot → snooze → primary remarcado → ... até Check/Stop/rollover).
- R2. `AlarmReceiver.onReceive` escreve `firstAlarmInChainMillis = System.currentTimeMillis()` quando recebe um disparo **e** `firstAlarmInChainMillis == 0L` (ou seja: primeiro dispatch da cadeia). Dispatches subsequentes (overshoot, ou primary reagendado por snooze) **não sobrescrevem** o valor — preservam o timestamp do primeiro alarme.
- R3. `firstAlarmInChainMillis` é resetado para `0L` em:
  - `HomeViewModel.performManualCheck` e `AlarmViewModel.performCheck` (cadeia encerrou com sucesso).
  - `HomeViewModel.stopSession` (sessão encerrada).
  - Sempre que `RescheduleForNextDayUseCase` (R19) é invocado — seja por `HomeViewModel.rescheduleForNextDayKeepingExercise` (rollover de fim de janela em sessão ativa) ou por `AlarmReceiver.onReceive` ao detectar overshoot fora da janela (R20). O reset acontece no corpo do use case, então qualquer caller herda a semântica.
  - `SessionPreferences.clearSession`.
- R4. `HomeUiState` ganha o campo derivado `chainStartedAtMillis: Long?` (= `firstAlarmInChainMillis.takeIf { it > 0L }`).
- R5. Quando `chainStartedAtMillis != null` e `isSessionActive == true`, a `HomeScreen` exibe um **contador crescente** no lugar do timer regressivo, no formato `"+MM:SS"` (ou `"+HH:MM:SS"` se > 1h), calculado como `(now - chainStartedAtMillis) / 1000`. O contador atualiza a cada segundo via o mesmo `countdownJob` do `HomeViewModel`.
- R6. Quando `chainStartedAtMillis != null` e o instante atual está **dentro** da `ActivityWindow` (entre `startTime` e `endTime` inclusive, em dia ativo), o botão Check fica habilitado a qualquer momento — `canCheck = true` ignora `CHECK_WINDOW_SECONDS`. Fora da janela ou fora de dia ativo, Check fica desabilitado (o rollover automático trata o caso).
- R7. Quando `chainStartedAtMillis == null` (sessão ativa sem cadeia em andamento — countdown normal antes do primeiro disparo), `canCheck` mantém a regra atual: `remaining ≤ CHECK_WINDOW_SECONDS` (5 min antes) **ou** `isOverdue`.
- R8. O nome do exercício e reps alvo continuam visíveis durante o contador crescente. Acima do contador, label muda de "PRÓXIMO EXERCÍCIO" / "QUASE LÁ" / "HORA DO GTG" para "ADIADO" (ou string equivalente em `strings.xml`).
- R9. O botão "Silenciar" (que aparece quando `isAlarmPending == true` e não reagenda nada) é preservado — ele cobre o caso onde o som está tocando agora mas o usuário só quer parar o som sem fazer Check.

### Item 2 — Vibração com `AudioAttributes`

- R10. `VibrationPlayer.start(context)` passa a aceitar um parâmetro `bypassDnd: Boolean` e a chamar `vibrator.vibrate(VibrationEffect, AudioAttributes)`, construindo `AudioAttributes` com:
  - `setUsage(AudioAttributes.USAGE_ALARM)` quando `bypassDnd == true`.
  - `setUsage(AudioAttributes.USAGE_NOTIFICATION)` quando `bypassDnd == false`.
  - `setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)` em ambos os casos.
- R11. `AlarmReceiver.onReceive` passa `sessionPrefs.bypassDnd` para `VibrationPlayer.start`, espelhando o que já faz com `AlarmSoundPlayer.play`.
- R12. Não há nova configuração em Settings — sem seletor de pattern, sem seletor de intensidade. Mantém o mesmo `PATTERN = longArrayOf(0L, 500L, 250L)` repetindo.
- R13. O overload usado é o `vibrate(VibrationEffect, AudioAttributes)` (existente desde API 26, depreciado em API 33 mas funcional). `VibrationAttributes` (API 33+) **não** é adotado neste lote para evitar branching de API e bug de regressão em 26-32. Reavaliar quando `minSdk` subir.
- R14. Permission `android.permission.VIBRATE` já está declarada no Manifest (confirmado em `app/src/main/AndroidManifest.xml:25`).

### Item 3 — Remover botão "Pular" da `AlarmActivity`

- R15. `AlarmActivity.AlarmScreen` remove o `TextButton` "Pular" e o parâmetro `onSkip`. A tela do alarme passa a ter três ações: FAZER CHECK (botão primário), Adiar N min (Outlined), e o sistema fecha a Activity via gesto/notificação sem ação de skip explícita.
- R16. `AlarmViewModel.performSkip` é deletado. Nenhum outro caller usa skip (confirmado: o ramo "Pular" da Home já foi removido no lote 2026-05-15).
- R17. String `R.string.alarm_skip` removida de `strings.xml` (pt-BR e en).
- R18. **Não** é adicionada nenhuma migração para sessões com "skip parcial em curso" — Skip não persiste estado, é uma ação puntual; remover não deixa órfão.

### Item 4 — Overshoot não toca fora da `ActivityWindow`

- R19. Novo use case `RescheduleForNextDayUseCase` extraído da lógica atual de `HomeViewModel.rescheduleForNextDayKeepingExercise` (linhas 485–516). Recebe injetados `AlarmScheduler`, `SessionPreferences` e `ActivityWindowRepository`. Quando invocado, calcula o próximo dia ativo via `findNextActiveDate`, cancela alarme primary e overshoot, agenda primary para `nextDate.atTime(window.startTime)` preservando o exercise pending atual, persiste em `SessionPreferences.setNextAlarm` e zera `firstAlarmInChainMillis`. `HomeViewModel.rescheduleForNextDayKeepingExercise` passa a delegar a este use case.
- R20. `AlarmReceiver.onReceive` usa `goAsync()` para permitir chamadas suspend. Toda a lógica suspend roda dentro de `try { ... } finally { wakeLock.release(); pendingResult.finish() }` — incluindo branches happy/null-window/out-of-window/primary-out-of-window e qualquer caminho de exceção (Room read falhou, `AlarmScheduler.schedule` lançou `SecurityException`, etc.). Sem o `finally` envolvendo todos os branches, falhas silenciosas vazariam o `PendingResult` e o sistema marcaria o receiver como vivo até timeout (~10s), abrindo janela de ANR. O `wakeLock.acquire(...)` é estendido para 60s (era 30s) cobrindo a coroutine pós-`goAsync`; o release fica no MESMO `finally` que `finish()` para manter CPU ativa durante a query Room + reschedule.

  Lógica antes de exibir notificação, tocar som ou vibrar:
  - Lê a `ActivityWindow` ativa via `ActivityWindowRepository.getActiveWindow()` (já existe — método suspend usado pelo `AlarmViewModel`).
  - Se a window é `null` (sem window configurada), comportamento atual (toca normalmente).
  - Se a window existe e `LocalDateTime.now().toLocalTime() > window.endTime`: NÃO toca som/vibração/notificação, NÃO agenda próximo overshoot, e chama `RescheduleForNextDayUseCase` para empurrar primary para a próxima janela.
  - Se a window existe e estamos `≤ window.endTime` (inclusive): comportamento atual — toca, agenda próximo overshoot. O overshoot pode tocar exatamente em `endTime`.
- R21. A mesma validação se aplica a alarme primary (não-overshoot) — defensiva contra casos em que o primary cai fora da janela por mudança de configuração depois do agendamento. Hoje isso é raro porque o scheduler já valida no agendamento, mas o receiver deve segurar a invariante "fora da janela não toca".
- R22. O agendamento do **próximo overshoot** dentro do receiver também valida que `now + overshootRepeatMinutes ≤ window.endTime` (no mesmo dia ativo). Se passaria do limite, não agenda — o silêncio é deliberado. A próxima cadeia começa amanhã (já garantido por R19/R20).
- R23. O guard atual em `AlarmReceiver` para dia inativo (`isOvershoot && dayOfWeek !in activeDaysOfWeek` retorna early) permanece — defesa em profundidade. Mas agora é redundante com R20 nos casos típicos.
- R24. A injeção de `ActivityWindowRepository` no `AlarmReceiver` segue o padrão Hilt já usado em `AlarmViewModel` (`@AndroidEntryPoint` + `@Inject lateinit var`).

### Item 5 — `WheelNumberPicker` estável em fling rápido

- R25. O cálculo de `centeredIndex` em `WheelNumberPicker.kt:64-72` é substituído por uma fonte determinística:
  - Enquanto `state.isScrollInProgress == false`, `centeredIndex = state.firstVisibleItemIndex + (visibleItems / 2)`, **coerced** para `0..max`.
  - Durante scroll, o valor de `centeredIndex` é irrelevante para `onValueChange` (já filtrado pelo `if (!scrolling)` no `LaunchedEffect`), então a expressão "minBy proximidade visual" pode ser eliminada por completo.
- R26. Ao detectar fim do fling (`isScrollInProgress` transita de `true` para `false`), o composable chama `state.animateScrollToItem(centeredIndex)` para garantir snap pixel-perfect. Isso elimina drift entre o que o `rememberSnapFlingBehavior` produz e o item central real.
- R27. O `LaunchedEffect(value)` que reage a mudanças externas mantém o comportamento atual (não rola enquanto `isScrollInProgress`), mas usa `state.animateScrollToItem(v)` em vez de `state.scrollToItem(v)` — animação curta evita "puxões" abruptos quando o pai força um valor.
- R28. O contrato de `onValueChange` é preservado: emite **apenas** quando o scroll para e o índice central muda. Sem flicker durante fling.
- R29. Caso o item central calculado caia fora de `0..max` (ex: lista de 60 itens, índice 61 por algum motivo de over-scroll), o `coerceIn(0, max)` continua sendo aplicado defensivamente. Não há mudança no contrato de saída.

### Geral

- R30. Strings novas e atualizadas (label "ADIADO", possíveis novos labels do Item 1) entram em `strings.xml` pt-BR e en sem hardcode em Composables.
- R31. Nenhuma migração de schema Room é necessária. Mudança em `SessionPreferences` (novo campo `firstAlarmInChainMillis`) é additive em SharedPreferences — install antigo lê default `0L` (= "sem cadeia ativa"), o que é seguro.

---

## Acceptance Examples

- AE1. **Covers R2, R5, R6, R8.** Usuário tem `overshootRepeatMinutes = 10`. Alarme primary toca às 14:25 — fundo full-screen abre, usuário dá Snooze. AlarmActivity fecha. Usuário abre a Home às 14:28: vê o nome do exercício, reps alvo, label "ADIADO", e contador `+03:00` crescendo a cada segundo. O botão Check está **habilitado imediatamente** (não espera 5 minutos). Usuário toca Check: contador some, log é inserido com timestamp 14:28, próximo alarme é agendado em `now + baseInterval` com o **próximo** exercício da rotação.

- AE2. **Covers R3, R5.** Continuação de AE1 — após o Check, `firstAlarmInChainMillis` foi zerado. Home volta a mostrar timer regressivo normal "00:44:59" até o próximo alarme. Sem contador crescente.

- AE3. **Covers R7.** Usuário tem sessão ativa, próximo alarme em 30min, `chainStartedAtMillis == null` (nenhum disparo aconteceu nesta cadeia). Botão Check fica **desabilitado** com a dica "Check libera 5min antes". Quando faltam 4 minutos, Check habilita. Comportamento idêntico ao de hoje.

- AE4. **Covers R10, R11.** Usuário ativa Vibração + Som em Settings, deixa `bypassDnd = true`, ativa modo DND do sistema. Alarme dispara: `AlarmSoundPlayer` toca som (já funcionava), e `VibrationPlayer.start(context, bypassDnd=true)` invoca `vibrator.vibrate(VibrationEffect, AudioAttributes(USAGE_ALARM))` — dispositivo vibra mesmo em DND. Usuário toca Check; vibração para imediatamente.

- AE5. **Covers R10.** Usuário ativa apenas Vibração (Som=OFF, Visual=OFF), `bypassDnd = false`. Alarme dispara em modo silencioso normal (sem DND): `VibrationPlayer` chama `vibrate(VibrationEffect, AudioAttributes(USAGE_NOTIFICATION))` — dispositivo vibra. Em DND, o sistema pode silenciar (esperado: usage `NOTIFICATION` respeita DND).

- AE6. **Covers R15, R16.** Alarme dispara em full-screen. Usuário vê FAZER CHECK (botão primário) e "Adiar 5 min" (Outlined). **Nenhum** botão "Pular" aparece. Apertar o back do sistema ou puxar a notificação dispensa o som/vibração via `onDestroy`, mas o alarme permanece pendente (overdue até overshoot ou Check). 

- AE7. **Covers R20, R22.** Configuração: janela 08:00–17:30 ativa seg–sex, hoje sexta, `overshootRepeatMinutes = 5`. Alarme primary cai 17:25 e usuário não atende. 17:30 — overshoot dispara (dentro da janela, `now == endTime` permitido), toca normalmente. Antes de agendar o próximo overshoot, verifica se `17:30 + 5min = 17:35 ≤ window.endTime (17:30)` → falha. Não agenda próximo overshoot. Cadeia para. Em paralelo, dispara `RescheduleForNextDayUseCase` (R20): cancela alarmes, agenda primary para segunda-feira 08:00, preserva o exercício pending, zera `firstAlarmInChainMillis`. 17:31 — silêncio. Não há overshoot às 17:35, 17:40, etc.

- AE8. **Covers R20, R23.** Configuração: hoje é domingo, dia desabilitado em `activeDaysOfWeek`. Por algum motivo (ex: app reiniciou e mexeu em `activeDaysOfWeek`), um overshoot ficou armado para domingo 10:00. 10:00 — receiver é invocado. R20 detecta que a window não existe ou hoje está fora dela; faz rollover. R23 (guard de dia inativo) também rejeitaria — defesa em profundidade.

- AE9. **Covers R25, R26.** Onboarding, step ActivityWindow, valor inicial hora=8 minuto=0. Usuário faz um fling rápido para baixo no picker de minutos. Sem o fix, o picker de minuto pode parar em 55 ou 5 (drift do snap) e o `onValueChange` propaga o valor errado; pior, em alguns fluxos o `LaunchedEffect(value)` faz scrollToItem para o valor pretendido criando flicker. Com o fix: o snap fling termina, `centeredIndex` é calculado como `firstVisibleItemIndex + 1`, `animateScrollToItem` corrige o offset, e `onValueChange` emite o valor final correto **uma única vez**.

- AE10. **Covers R27.** Usuário tem `startHour = 9`. Outro processo (ex: defaults restaurados) altera `startHour` para 8 enquanto a UI está visível. O `LaunchedEffect(value)` detecta a mudança, espera o usuário soltar o dedo se estiver scrollando, e chama `animateScrollToItem(8)` — a roleta de hora rola suavemente de 9 para 8, sem pulo brusco.

---

## Dependencies and Assumptions

- D1. `ActivityWindowRepository.getActiveWindow()` (`suspend`) já existe e é acessível de `AlarmReceiver` via Hilt — confirmado em uso pelo `AlarmViewModel.performSnooze`.
- D2. `Vibrator.vibrate(VibrationEffect, AudioAttributes)` está disponível desde API 26 (`minSdk` atual). Depreciado em API 33 mas funcional.
- D3. `BroadcastReceiver.goAsync()` + `PendingResult.finish()` é o padrão para suspend functions em receivers; tempo limite de ~10s, suficiente para query Room single-row + agendamento de alarme.
- D4. O comportamento "overshoot pode tocar exatamente em `endTime`" foi explicitamente confirmado pelo usuário — `now == endTime` permite o disparo; `now > endTime` bloqueia.
- D5. O cenário "first launch / sessão pré-migração com `firstAlarmInChainMillis` ausente" lê default `0L`, que significa "sem cadeia ativa" — comportamento defensivo correto.

## Non-goals (explícito)

- N1. **Não** adicionar Pattern ou Intensidade configurável para vibração neste lote (R12).
- N2. **Não** substituir `AudioAttributes` por `VibrationAttributes` (R13) — adiar até `minSdk ≥ 33`.
- N3. **Não** introduzir "log de skip" / "missed sets" — opção rejeitada na decisão sobre Item 3. Skip simplesmente sai do produto.
- N4. **Não** mudar a semântica de Snooze quanto à rotação — Snooze continua mantendo o **mesmo** exercício na próxima dispatch (não avança rotação). Comportamento atual estava correto.
- N5. **Não** rebuild completo do `WheelNumberPicker` (ex: substituir por `Picker` do Material 3 ou implementação Canvas) — escopo deste lote é estabilizar o cálculo de centro e o snap, não trocar o widget.

## Open Questions for Planning

- OQ1. Em R19, o `RescheduleForNextDayUseCase` deve ficar em `domain/usecase/` (mesmo pacote dos demais) ou em `domain/scheduler/` (junto ao `AlarmScheduler`)? Trade-off: pacote scheduler agrupa coisas que dialogam com `AlarmManager`, mas o use case é puro orquestrador. Decisão fica para `/ce-plan`.
- OQ2. Em R26, `animateScrollToItem` ou `scrollToItem` no fim do fling? `animateScrollToItem` é mais suave mas adiciona delay de animação curto que pode atrasar `onValueChange`. Provavelmente `scrollToItem` (instantâneo, já estamos no snap point) — confirmar em testes manuais durante `/ce-plan`.
- OQ3. Em R8, o label "ADIADO" é o melhor termo, ou prefere "EM ESPERA", "AGUARDANDO CHECK", outro? Estética/copy fica para `/ce-plan`.

---

## Deferred / Open Questions

### From 2026-05-21 review

- **R6's out-of-window Check clause is unreachable** — R6 / R20 / R21 (P1, coherence, confidence 75)

  R6 prescreve Check habilitado quando dentro da `ActivityWindow`, mas R20/R21 impedem alarmes de tocar fora da janela (dispara rollover). A cláusula "fora da janela, Check fica desabilitado" do R6 vira inalcançável porque `chainStartedAtMillis` nunca fica `!= null` fora da janela. Resolver: ou R6 é defesa em profundidade contra estado transiente durante rollover, ou a cláusula "fora da janela" deve ser excluída de R6 como redundante. /ce-plan decide ao implementar.

  <!-- dedup-key: section="r6 r20 r21" title="r6s outofwindow check clause is unreachable" evidence="r6 prescribes check enabled when inside the activitywindow but r20 r21 prevent alarms from firing outside the window at all" -->

- **Single-consumer abstraction may be premature** — R19 (P1, scope-guardian, confidence 75)

  R19 extrai `HomeViewModel.rescheduleForNextDayKeepingExercise` em um novo `RescheduleForNextDayUseCase` que produz exatamente um caller novo (`AlarmReceiver` via R20) além do delegate existente. Use case com um único consumidor não-delegate é generalidade especulativa — OQ1 admite que o pacote está indecidido, sintoma do boundary não conquistado. Alternativa: `AlarmReceiver` chama uma `private suspend fun` compartilhada ou inline a lógica, espelhando o padrão já usado para `AlarmScheduler`. /ce-plan decide entre extrair use case vs shared fn vs inline.

  <!-- dedup-key: section="r19" title="singleconsumer abstraction may be premature" evidence="r19 extrai homeviewmodelreschedulefornextdaykeepingexercise em um novo reschedulefornextdayusecase que produz exatamente um caller" -->

- **Counter visual conflicts with existing overdue state** — R5 / R6 / R8 (P1, design-lens, confidence 100)

  `HomeScreen.kt` já renderiza um estado overdue com card vermelho pulsante (`GtgPrimary.copy(alpha=0.12f)`), `accentColor = GtgPrimary`, secondary "atrasado" text e pulse 1.04f — tudo gated apenas em `isOverdue`. R8 só especifica troca do label "ADIADO" — silencia sobre se o pulse/card-color/accent/secondary-text sobrevivem quando `chainStartedAtMillis != null`. Implementador chuta. Variantes demotadas para FYI: largura do "+MM:SS" no AutoShrinkText (counter mode sizing não especificado); hierarquia visual Check+counter+Silenciar (Check sempre habilitado durante cadeia muda o balanço com Silenciar). /ce-plan deve definir tratamento visual completo da cadeia.

  <!-- dedup-key: section="r5 r6 r8" title="counter visual conflicts with existing overdue state" evidence="homescreen kt already renders an overdue state card background switches to gtgprimary copy alpha 012f accentcolor flips to gtgprimary" -->

- **R3 reset paths missing rescheduleFromAnchor and performSnooze decision** — R3 (P1, adversarial, confidence 75)

  `rescheduleFromAnchor` (chamado em mudança mid-sessão de `baseInterval` ou `activeDaysOfWeek`) cancela alarme atual e agenda novo — mas não zera `firstAlarmInChainMillis`. Próximo dispatch entra em R2 com `!= 0L` → preserva valor stale → contador exibe `+50:00` desde T0 que já não tem relação com a nova cadeia, e Check habilitado falsamente. Mesma ambiguidade para `performSnooze`: cadeia continua (Summary implica) ou reseta? R2 ("== 0L é primeiro dispatch") conflita com snooze→primary-remarcado preservando o campo. /ce-plan deve enumerar `rescheduleFromAnchor` em R3 e decidir explicitamente a semântica do snooze.

  <!-- dedup-key: section="r3" title="r3 reset paths missing reschedulefromanchor and performsnooze decision" evidence="reschedulefromanchor e chamado em duas situacoes midsessao intervalchangedduringsession e activedayschangedduringsession e faz alarmscheduler" -->

- **firstAlarmInChainMillis ghosted after force-stop / boot / update** — R2 / D5 (P1, adversarial, confidence 75)

  Após force-stop pelo Settings Android, reboot, kill por OEM por bateria, ou atualização via Play Store, `firstAlarmInChainMillis` sobrevive da cadeia anterior. `BootReceiver` reagenda alarme sem tocar o campo. Próximo dispatch entra em R2 com `!= 0L` → preserva valor obsoleto (possivelmente de dias atrás). Home exibe `+02:43:18` desde T0 obsoleto e Check habilitado falsamente via R6. Sem path de auto-cura — campo só zera em Check/Stop/rollover/clearSession, todos exigindo ação do usuário que já viu estado errado. /ce-plan: BootReceiver poderia limpar o campo no reagendamento, ou HomeViewModel detectar `firstAlarmInChainMillis > 0 AND nextAlarmMillis > now + threshold AND !isAlarmPending` como ghosted e auto-resetar.

  <!-- dedup-key: section="r2 d5" title="firstalarminchainmillis ghosted after forcestop boot update" evidence="usuario esta em cadeia ativa firstalarminchainmillis t0 force stop pelo android settings ou reboot ou oem kill por bateria ou play" -->

- **R26 prescription contradicts its own OQ2** — R26 / OQ2 (P2, coherence, confidence 75)

  R26 prescreve `animateScrollToItem(centeredIndex)` no fim do fling para garantir snap pixel-perfect, mas OQ2 questiona se `animateScrollToItem` ou `scrollToItem` é correto — `animateScrollToItem` é mais suave mas adiciona delay que pode atrasar `onValueChange`. Especificação contradiz pergunta aberta sobre si própria. /ce-plan deve resolver com teste manual antes de implementar.

  <!-- dedup-key: section="r26 oq2" title="r26 prescription contradicts its own oq2" evidence="r26 ao detectar fim do fling isscrollinprogress transita de true para false o composable chama state animatescrolltoitem centeredindex" -->

- **LocalTime comparison breaks DST / cross-midnight / timezone change** — R20 / R22 (P2, adversarial, confidence 75)

  `LocalDateTime.now().toLocalTime() > window.endTime` falha em três cenários: (a) cross-midnight — janela 22:00–23:55, overshoot 5min disparado às 23:58 calcula `now+5 = 00:03`, compara `00:03 ≤ 23:55` → PASSA, agenda overshoot em dia possivelmente inativo; (b) DST spring-forward — alarme 02:15 fire 03:15 wall-clock; janela 01:00-02:30 → R20 bloqueia o que deveria tocar (usuário perde set); (c) mudança de timezone com sessão ativa — alarmes UTC não migram, decisão window-bound vira arbitrária. /ce-plan: comparar instante absoluto vs `windowEndDateTime` chrono-aware na data correta, não `LocalTime` cega.

  <!-- dedup-key: section="r20 r22" title="localtime comparison breaks dst crossmidnight timezone change" evidence="se a window existe e localdatetime now tolocaltime window endtime nao toca somvibracao notificacao nao agenda proximo overshoot" -->

- **AlarmActivity layout post-Skip removal undefined** — R15 (P2, design-lens, confidence 75)

  Após remoção do "Pular", `AlarmScreen` tem 2 botões full-width (Check + Adiar) sem decisão sobre spacing (era 12dp Check↔Snooze + 16dp Snooze↔Skip) e sem reframing do label do Snooze que agora também serve como única via de "não agora". Usuários que usavam Skip como "deixa pra próxima vez" não têm sinal de que dismissal (back gesture, swipe da notificação) não pula mais — só silencia. /ce-plan: definir spacing pós-remoção e considerar atualizar label/hint do Snooze.

  <!-- dedup-key: section="r15" title="alarmactivity layout postskip removal undefined" evidence="alarmactivity kt currently has three action zones fazer check button 64dp min fullwidth adiar n min outlinedbutton 56dp min fullwidth" -->

- **RoutinePreviewCard first-item rendering during chain undefined** — RoutinePreviewCard (P2, design-lens, confidence 75)

  `RoutinePreviewCard` renderiza o primeiro item como "alarme REAL agendado" com filled blue dot e bold blue time — diferenciado das projeções. Durante uma cadeia, o "agendado" é o próximo overshoot, semanticamente diferente do "próximo set planejado". Requirements não dizem se a card preserva, esconde ou diferencia visualmente o primeiro item durante a cadeia. Mesmo filled dot agora significa duas coisas distintas. /ce-plan: decidir comportamento do card durante cadeia (esconder vs marcar visualmente vs manter).

  <!-- dedup-key: section="routinepreviewcard" title="routinepreviewcard firstitem rendering during chain undefined" evidence="homescreen kt 199 204 document that the first routinepreviewcard item quando ha sessao ativa e o alarme real agendado" -->

- **Skip removal eliminates "advance rotation without log" capability** — R15-R18 (P2, product-lens, confidence 75)

  Item 3 remove Skip argumentando que Snooze cobre "não agora", Check cobre "feito", Stop cobre "encerrar" — mas nenhum desses avança rotação sem inserir log. A justificativa "Skip desequilibra rotação" descreve Skip funcionando como projetado. Após esta mudança, a única forma de "esse exercício não cai bem agora, próximo da rotação" é Check com reps=0 (se permitido) ou esperar overshoot inteiro. Produto solo, autor pode validar — mas o doc deveria declarar "tenho N semanas de dados mostrando que nunca precisei" em vez de dispensar a capacidade como confusão. /ce-plan: confirmar com observação real ou aceitar trade-off explícito.

  <!-- dedup-key: section="r15 r18" title="skip removal eliminates advance rotation without log capability" evidence="item 3 removes skip on the grounds that snooze cobre nao agora check cobre feito stop cobre encerrar but none of those advance rotation" -->
