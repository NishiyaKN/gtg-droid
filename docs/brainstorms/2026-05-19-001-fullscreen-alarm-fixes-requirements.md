# Correções da Tela Full-Screen do Alarme + Snooze + i18n de Dias da Semana

**Data:** 2026-05-19
**Tipo:** Bugfix bundle + nova funcionalidade (Snooze)
**Status:** Requirements

## Summary

Corrigir o desalinhamento entre alarme primary e overshoot que faz a tela full-screen ficar presa em um exercício e o botão Skip não pular de fato; adicionar um botão Snooze explícito na full-screen que use o intervalo de re-alerta configurado; e corrigir 4 pontos onde o `Locale("pt", "BR")` hardcoded ignora o seletor de idioma in-app, deixando os dias da semana em português mesmo em inglês.

## Problem Frame

Durante testes, o usuário observou três sintomas inter-relacionados na tela full-screen do alarme:

1. **Skip não pula:** apertar "Pular" na full-screen reagenda o primary corretamente, mas o re-alerta automático (overshoot) continua disparando a cada 5 minutos com o exercício antigo.
2. **Preso em um exercício:** ao fazer Check pela notificação, o app conta o exercício errado (o antigo do PendingIntent do overshoot) e a rotação fica em loop, mesmo com a Home indicando outro próximo exercício.
3. **Não dá pra fazer Check pelo app:** quando o usuário entra no app durante um alarme pendente, a Home mostra o "próximo" (exercício B), enquanto a full-screen ainda toca o antigo (exercício A) — único caminho coerente é a notificação.

Adicionalmente, o usuário pediu um botão **Snooze** dedicado (separado do Skip) que use o intervalo de re-alerta configurado e mostre esse valor na tela.

Por último, mesmo com o idioma do app em inglês, os iniciais dos dias da semana aparecem em português ("s t q q s s d").

### Causa raiz dos sintomas 1–3 (todos a mesma)

`AlarmReceiver.onReceive` (`app/src/main/java/com/gtg/app/presentation/alarm/AlarmReceiver.kt:145-156`) agenda o overshoot via `alarmScheduler.scheduleOvershoot(...)` usando as **extras estáticas** do PendingIntent que acabou de disparar (`exerciseId`, `exerciseName`, `targetReps`). Esse PendingIntent vive até alguém cancelar.

`AlarmViewModel.performCheck` (`app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt:59`) e `performSkip` (`AlarmViewModel.kt:87`) reagendam apenas o **primary** alarm via `alarmScheduler.schedule(...)`, mas **nunca chamam `alarmScheduler.cancelOvershoot()`**.

A função que cancela o overshoot, `HomeViewModel.dismissActiveAlarm()` (`app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt:570-577`), pertence à Activity da Home e não é alcançada quando o usuário resolve o alarme pela full-screen.

Consequência: depois do Check/Skip pela full-screen, o overshoot do exercício antigo continua agendado e disparando, com extras antigas. O ciclo se auto-perpetua porque cada disparo do overshoot pelo `AlarmReceiver` re-agenda outro overshoot com as mesmas extras antigas.

### Causa raiz do sintoma de i18n

Quatro pontos no código usam `Locale("pt", "BR")` hardcoded em vez do locale atual do app:

- `app/src/main/java/com/gtg/app/presentation/schedule/ScheduleScreen.kt:293` (header do calendário)
- `app/src/main/java/com/gtg/app/presentation/schedule/ScheduleScreen.kt:997` (dialog de bloco recorrente)
- `app/src/main/java/com/gtg/app/presentation/settings/SettingsScreen.kt:312` (chips "Dias ativos" — é o "s t q q s s d" visto pelo usuário)
- `app/src/main/java/com/gtg/app/presentation/statistics/StatisticsViewModel.kt:86` (labels do gráfico semanal)

O app já tem seletor de idioma in-app via `AppCompatDelegate.setApplicationLocales` (`SettingsViewModel.kt:340`), mas esses pontos ignoram a propagação.

## Requirements

### R1 — Cancelar overshoot ao resolver alarme pela full-screen

`AlarmViewModel` deve garantir que **toda** ação que dispensa o alarme atual (Check, Skip e o novo Snooze) cancele o overshoot pendente antes de reagendar.

Comportamento esperado (paralelo ao já existente em `HomeViewModel.dismissActiveAlarm`):

- `AlarmSoundPlayer.stop()`
- `NotificationManagerCompat.from(context).cancel(AlarmReceiver.NOTIFICATION_ID)`
- `alarmScheduler.cancelOvershoot()`

Esse efeito colateral roda no **início** das ações Check/Skip/Snooze do `AlarmViewModel`, antes de qualquer reagendamento do primary.

**Critério de aceite:** depois de Check ou Skip pela full-screen, nenhum re-alerta automático dispara para o exercício que acabou de ser resolvido; só o próximo alarme primary calculado pelo `DynamicSchedulerUseCase` toca.

### R2 — Botão Snooze na tela full-screen

Adicionar um terceiro botão "Snooze" à `AlarmActivity`, entre o botão massivo "FAZER CHECK AGORA" e o discreto "Pular".

**Semântica:**

- Cancela o overshoot atual (R1).
- Cancela o alarme primary corrente (já está em overdue).
- Reagenda **primary** para `now + overshootRepeatMinutes`, mantendo **o mesmo exercício** (não rotaciona).
- **Não** registra `ExerciseLog` (snooze ≠ check).
- Grava `setLastCheck(nowMillis)` — define âncora para recálculo dinâmico de intervalo se o usuário mudar o `baseInterval` durante a sessão.
- Limpa `setAlarmPending(false)`.
- Fecha a `AlarmActivity` (mesmo fluxo do Check via `actionCompleted`).

**Visual (escopo de scope, detalhe na implementação):**

- Label do botão exibe o intervalo configurado dinamicamente — ex: `"Snooze 5 min"` lendo `sessionPrefs.overshootRepeatMinutes`.
- Hierarquia visual: Check (primário, massivo) > Snooze (secundário, médio) > Pular (terciário, discreto/TextButton).

**Comportamento após Snooze:**

- Home volta a mostrar timer positivo contando para o novo horário (não fica em overdue).
- Em `overshootRepeatMinutes` o alarme toca de novo na full-screen com o mesmo exercício.
- Snooze pode ser pressionado quantas vezes o usuário quiser, sem limite (coerente com a decisão atual de overshoot sem limite máximo de repetições).

**Critério de aceite:** ao apertar Snooze às 14:01 com `overshootRepeatMinutes=5` e exercício atual = flexão, o próximo alarme primary toca às 14:06 ainda com flexão; nenhum overshoot intermediário dispara antes disso.

### R3 — Skip realmente pula (correção semântica)

O comportamento atual de `performSkip` está quase certo — rotaciona o próximo exercício, agenda primary via `dynamicScheduler.calculateNextAlarm(checkTime = now)`, não registra log. Só falta cancelar o overshoot (R1).

Pós-fix de R1, o Skip se torna correto:

- Overshoot cancelado.
- Primary reagendado para `now + baseInterval` (sujeito às 5 regras do scheduler).
- Rotação avança para o próximo exercício do round-robin.
- Sem `ExerciseLog`.

**Critério de aceite:** depois de Skip pela full-screen, em 5 minutos nenhum alarme toca; o próximo alarme só dispara no `now + baseInterval` (~45min default) com o exercício seguinte da rotação.

### R4 — Dias da semana respeitam idioma do app

Trocar `Locale("pt", "BR")` por:

- `LocalConfiguration.current.locales[0]` em Composables (`ScheduleScreen.kt:293`, `ScheduleScreen.kt:997`, `SettingsScreen.kt:312`).
- `Locale.getDefault()` em ViewModels (`StatisticsViewModel.kt:86`) — `AppCompatDelegate.setApplicationLocales` já propaga o per-app locale para `Locale.getDefault()`.

**Critério de aceite:** com idioma do app em inglês, os chips de "Active days" em Settings mostram "M T W T F S S" (em vez de "S T Q Q S S D"); o calendário, o dialog de bloco recorrente e o gráfico semanal de Statistics também usam iniciais em inglês.

## Key Decisions

### KD1 — `dismissActiveAlarm` duplicado entre Home e AlarmViewModel, não extraído

A função `dismissActiveAlarm` já existe em `HomeViewModel` (linhas 570-577). Ela vai ser duplicada em `AlarmViewModel` em vez de extraída para uma classe compartilhada.

**Por quê:** o conjunto é pequeno (3 linhas), as dependências são levemente diferentes (`AlarmActivity` tem o `NotificationManagerCompat` via Activity context, `HomeViewModel` via injeção de `@ApplicationContext`), e extrair agora cria abstração antes de termos um terceiro chamador. YAGNI.

**Alternativa rejeitada:** extrair para `AlarmDismisser` injetável — adiciona indireção sem benefício imediato.

### KD2 — Snooze reagenda PRIMARY, não OVERSHOOT

Modelo mental: "snooze este set por X minutos". Por isso o snooze move o `nextAlarmMillis` para `now + overshootRepeatMinutes` e zera `isAlarmPending`, fazendo a Home voltar a contar positivo.

**Alternativa rejeitada:** re-agendar só o overshoot e manter primary em overdue. Mais simples no código, mas confuso na Home (usuário acabou de snoozar mas a Home segue "GtG TIME!").

### KD3 — Snooze e overshoot automático compartilham o mesmo intervalo (`overshootRepeatMinutes`)

Uma chave única, um modelo mental único. Sem nova preferência em Settings.

**Alternativa rejeitada:** preferência `snoozeMinutes` dedicada — duplica conceito (re-alerta) sem ganho concreto observado.

### KD4 — Home continua permitindo Check manual durante alarme pendente

A coerência se restaura sozinha depois de R1 (o que toca na full-screen = o que a Home mostra). Não é preciso esconder, desabilitar ou redesenhar a Home durante alarme pendente.

**Alternativa rejeitada:** forçar full-screen como canal único de Check. Hostil quando o app está em foreground (a Activity full-screen só sobrepõe com a tela apagada; com tela ligada é heads-up notification) e contraria o princípio "alarm-style" sem benefício.

### KD5 — Snooze sem limite de repetições

Coerente com a decisão atual do overshoot automático ("sem limite máximo de repetições por escolha do usuário; trade-off de bateria aceito"). Snooze é um overshoot manual; mesma política.

### KD6 — Skip não registra log; Snooze não registra log

- **Skip** = "vou pular esse set" → rotaciona para o próximo exercício; sem log (set não foi feito).
- **Snooze** = "não posso agora, me lembra de novo" → mantém o exercício; sem log (set não foi feito).
- **Check** (full-screen ou manual) = "fiz o set" → registra log + rotaciona.

### KD7 — Locale no ViewModel via `Locale.getDefault()`

`AppCompatDelegate.setApplicationLocales` propaga o locale escolhido pelo usuário tanto para `Configuration.locales` (acessível em Composables via `LocalConfiguration.current.locales[0]`) quanto para `Locale.getDefault()` (acessível em qualquer thread). Não precisa injetar um `Locale` provider via Hilt nem ler `sessionPrefs.languageTag` diretamente.

## Scope Boundaries

**Inclui:**

- Cancelamento de overshoot nas três ações da `AlarmActivity` (Check, Skip, Snooze).
- Novo botão Snooze na `AlarmActivity` com label dinâmico.
- Trocar 4 pontos de `Locale("pt", "BR")` por locale dinâmico.
- Critérios de aceite acima.

**Não inclui (deferred):**

- Mudar a semântica do overshoot automático (continua igual: sem limite, intervalo igual).
- Refatorar `AlarmReceiver` para puxar extras de prefs em vez do PendingIntent (não causa bug depois do cancel; mexer agora é over-engineering).
- Adicionar preferência separada `snoozeMinutes` em Settings.
- Forçar a tela full-screen como canal único de Check.
- Esconder/desabilitar botões da Home durante alarme pendente.
- Re-design completo da tela full-screen.
- Adicionar limite máximo de snooze (ex: "máximo 3 snoozes seguidos").
- Tratamento de Snooze repetido com decaimento (ex: 5min → 10min → 15min).
- Localização de outros pontos do app além dos 4 listados em R4 (escopo limitado ao que o usuário reportou).

## Dependencies / Assumptions

- `alarmScheduler.cancelOvershoot()` é idempotente (já documentado em `AlarmScheduler.kt:42`).
- `AppCompatDelegate.setApplicationLocales` propaga para `Locale.getDefault()` em runtime (comportamento padrão do AppCompat).
- O `LocalConfiguration.current.locales[0]` reflete o per-app locale corretamente em Composables — comportamento garantido pelo AppCompat na recriação da Activity.
- Recriação da `AlarmActivity` ao trocar idioma não é cenário comum (alarme não dispara enquanto o usuário está em Settings trocando idioma); não precisa de teste específico.
- O snooze pode ser pressionado em sequência sem limite — assume-se que o impacto em bateria já foi avaliado e aceito no contexto do overshoot atual.

## Open Questions

Nenhuma — todas as decisões de produto foram fechadas durante a brainstorm.
