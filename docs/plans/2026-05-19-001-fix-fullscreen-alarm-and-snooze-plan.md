---
status: active
type: fix
created: 2026-05-19
depth: standard
origin: docs/brainstorms/2026-05-19-001-fullscreen-alarm-fixes-requirements.md
---

# fix: Cancelar overshoot na full-screen, novo botão Snooze e i18n dos dias da semana

## Summary

Cobre os 4 problemas reportados durante teste de campo: (1) "Pular" na tela full-screen não pula de fato porque o overshoot já agendado pelo `AlarmReceiver` nunca é cancelado; (2) o ciclo "preso em flexão" tem a mesma raiz — o overshoot fica re-agendando com extras antigas; (3) falta um botão Snooze explícito na full-screen com o intervalo configurado visível; (4) iniciais dos dias da semana ficam em português mesmo com idioma do app em inglês. Inclui setup mínimo de infra de testes unitários para travar a regressão do cancelOvershoot.

## Problem Frame

`AlarmReceiver.onReceive` (`app/src/main/java/com/gtg/app/presentation/alarm/AlarmReceiver.kt:145-156`) agenda um overshoot via `alarmScheduler.scheduleOvershoot(...)` com as **extras estáticas** do PendingIntent que disparou. Esse PendingIntent vive até alguém cancelar.

`AlarmViewModel.performCheck` e `performSkip` (`app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt:59,87`) reagendam apenas o **primary** alarm, mas **nunca chamam `alarmScheduler.cancelOvershoot()`**. A função que cancela é `HomeViewModel.dismissActiveAlarm()` — pertence à outra Activity e não é alcançada quando o usuário resolve pela full-screen.

Consequência observada:
- Skip pela full-screen → primary reagenda certo, mas overshoot continua tocando com exercício antigo.
- Check da full-screen do overshoot → loga o exercício antigo (extras estáticas do PendingIntent) e rotaciona a partir dele → ciclo infinito do mesmo exercício, mesmo que a Home mostre outro próximo.
- Não há botão Snooze hoje; o que o usuário chamou de "snooze" é o overshoot automático.

Adicionalmente, 4 pontos do código usam `Locale("pt", "BR")` hardcoded mesmo com o app já tendo seletor de idioma via `AppCompatDelegate.setApplicationLocales` em `SettingsViewModel.kt:340`.

(see origin: `docs/brainstorms/2026-05-19-001-fullscreen-alarm-fixes-requirements.md`)

## Requirements Traceability

| Req | Origin | Implementation Units |
|---|---|---|
| R1 — Cancelar overshoot ao resolver pela full-screen | Origin R1 | U2, U3, U6 (teste) |
| R2 — Botão Snooze na full-screen | Origin R2 | U3 (lógica), U4 (UI), U6 (teste) |
| R3 — Skip realmente pula | Origin R3 | U2 (depende de R1), U6 (teste) |
| R4 — Dias da semana respeitam idioma | Origin R4 | U5 (Composables), U6 não cobre (UI/i18n é verificação manual) |

---

## High-Level Technical Design

A correção central é **simétrica ao que já existe em `HomeViewModel.dismissActiveAlarm`**, replicada dentro de `AlarmViewModel`:

```text
fun performCheck() { dismissActiveAlarmSideEffects(); ...log + reschedule primary; finish }
fun performSkip()  { dismissActiveAlarmSideEffects(); ...rotate + reschedule primary; finish }
fun performSnooze(){ dismissActiveAlarmSideEffects(); ...reschedule primary @ now+overshootMin com MESMO exercise; finish }

private fun dismissActiveAlarmSideEffects() {
  AlarmSoundPlayer.stop()
  NotificationManagerCompat.from(appContext).cancel(AlarmReceiver.NOTIFICATION_ID)
  alarmScheduler.cancelOvershoot()
}
```

*Esse esboço é direcional, não especificação de implementação. O implementador resolve nomeação final e detalhes.*

Para o Snooze, a Activity expõe 3 botões agora:

```text
[ FAZER CHECK AGORA ]   (primário, massivo — mantém)
[   Snooze 5 min    ]   (secundário, novo — label lê overshootRepeatMinutes via stringResource %1$d)
        Pular              (terciário, TextButton — mantém)
```

Para o i18n, a regra é única:

```text
- Composable     -> LocalConfiguration.current.locales[0]
- ViewModel/POJO -> Locale.getDefault()  (setApplicationLocales propaga)
```

---

## Implementation Units

### U1. Setup mínimo de infra de testes unitários

**Goal:** Habilitar testes unitários puros (JVM, sem instrumentação) para o módulo `app/`, permitindo travar regressões de comportamento em `AlarmViewModel`.

**Requirements:** Pré-requisito para U6.

**Dependencies:** nenhuma.

**Files:**
- `app/build.gradle.kts` — adicionar dependências de teste.
- `gradle/libs.versions.toml` — declarar versões de JUnit, MockK, kotlinx-coroutines-test.
- `app/src/test/java/com/gtg/app/MainDispatcherRule.kt` — `TestWatcher` que troca `Dispatchers.Main` por `StandardTestDispatcher` em cada teste.
- `app/src/test/java/com/gtg/app/SanityCheckTest.kt` — um teste trivial (`assertTrue(true)`) para validar que o setup roda.

**Approach:**
- Adicionar deps em `testImplementation`:
  - `junit:junit:4.13.2`
  - `io.mockk:mockk:1.13.13` (suficiente para classes finais Kotlin, sem `mockk-android`).
  - `org.jetbrains.kotlinx:kotlinx-coroutines-test` (versão compatível com o coroutines runtime já em uso; default jvm).
- Não adicionar Turbine — `StateFlow.value` é suficiente para os testes do escopo.
- `MainDispatcherRule` segue padrão público do Google (oficial nas docs do Compose/Coroutines), usado em qualquer ViewModel que faça `viewModelScope.launch`.

**Patterns to follow:** convenções do `libs.versions.toml` existente (alias kebab-case por versão e por biblioteca).

**Test scenarios:**
- Covers infra setup. `SanityCheckTest` valida que `./gradlew :app:testDebugUnitTest` executa um teste e reporta sucesso.

**Verification:** `./gradlew :app:testDebugUnitTest` roda e o relatório `app/build/reports/tests/testDebugUnitTest/index.html` lista `SanityCheckTest` como pass.

---

### U2. Extrair `dismissActiveAlarmSideEffects` + cancelar overshoot em Check/Skip

**Goal:** Eliminar o desalinhamento que causa "skip não pula" e "stuck no exercício". Toda transição que dispensa o alarme atual via `AlarmActivity` cancela o overshoot pendente.

**Requirements:** R1, R3 (origin).

**Dependencies:** U1 (para testes em U6, mas o código pode rodar antes).

**Execution note:** Test-first. Escrever os testes de U6 que asseguram `cancelOvershoot()` em `performCheck` e `performSkip` antes de modificar o ViewModel; rodar e ver falhar; só então fazer o fix.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt` — adicionar `private fun dismissActiveAlarmSideEffects()` e chamar no início de `performCheck` e `performSkip`.

**Approach:**
- Helper privado dentro do próprio `AlarmViewModel`. Não extrair para classe compartilhada (KD1 do origin) — o conjunto é pequeno e há só dois chamadores potenciais futuros.
- Para acessar `Context` sem segurar a Activity, injetar `@ApplicationContext context: Context` no construtor via Hilt (mesmo pattern do `HomeViewModel`).
- O helper:
  1. `AlarmSoundPlayer.stop()`
  2. `NotificationManagerCompat.from(appContext).cancel(AlarmReceiver.NOTIFICATION_ID)`
  3. `alarmScheduler.cancelOvershoot()`
- Ordem dentro de `performCheck`/`performSkip`: cancelar efeitos colaterais **antes** do reagendamento, para evitar janela em que o overshoot ainda esteja agendado quando o novo primary é gravado em prefs.

**Patterns to follow:** `HomeViewModel.dismissActiveAlarm()` (`app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt:570-577`). Replicar a forma, não extrair.

**Test scenarios:** cobertos em U6 (Test 1, Test 2).

**Verification:** ver U6 + verificação manual:
- Sessão ativa com 2 exercícios (flexão, barra). Espera alarme tocar (full-screen) na flexão. Apertar "Pular". Observar que o próximo alarme só toca no horário do `baseInterval` (ex: 45min), nenhum re-alerta antes disso, e o exercício é barra.
- Mesma cena, mas apertar "FAZER CHECK". Observar que nenhum re-alerta toca antes do próximo set; rotação avança para barra.

---

### U3. `performSnooze` no AlarmViewModel

**Goal:** Adicionar a ação Snooze: cancela efeitos colaterais (incluindo overshoot), reagenda primary para `now + overshootRepeatMinutes` mantendo o mesmo exercício, sem log.

**Requirements:** R2 (lógica).

**Dependencies:** U2 (usa `dismissActiveAlarmSideEffects`).

**Execution note:** Test-first. Testes de U6 (Tests 3 e 4) antes da implementação.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt` — método `fun performSnooze()`.

**Approach:**
- Sequência interna:
  1. `dismissActiveAlarmSideEffects()`
  2. Calcular `nextDateTime = LocalDateTime.now().plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())`.
  3. `alarmScheduler.cancel()` (cancela primary anterior).
  4. `alarmScheduler.schedule(triggerAt = nextDateTime, exerciseId = this.exerciseId, exerciseName = this.exerciseName, targetReps = this.targetReps)` — **mesmo exercício**.
  5. `sessionPrefs.setNextAlarm(epochMillis, exerciseId, exerciseName, targetReps)` — grava em prefs para a Home atualizar countdown.
  6. `sessionPrefs.setLastCheck(nowMillis)` — âncora para recálculo dinâmico de intervalo (KD do origin).
  7. `_actionCompleted.value = true` — fecha a Activity via `LaunchedEffect` existente em `AlarmActivity.kt:94`.
- **Não** chamar `dynamicScheduler.calculateNextAlarm(...)` — snooze é deslocamento direto, não passa pelas 5 regras (regra 3 "descanso mínimo 20min" iria empurrar `now+5min` para `now+20min` e quebrar a semântica).
- **Não** rotacionar exercício (não chama `pickNextExerciseInRotation`).
- **Não** inserir `ExerciseLog`.
- `setAlarmPending` é zerado por `setNextAlarm` (já é o comportamento existente; verificar em `SessionPreferences.kt`).

**Patterns to follow:** `HomeViewModel.rescheduleForNextDayKeepingExercise` (`HomeViewModel.kt:480-511`) — mesma forma de "agendar primary preservando exercício e mexendo só no horário".

**Test scenarios:** cobertos em U6 (Tests 3, 4, 5).

**Verification:** ver U6 + verificação manual:
- Alarme toca na flexão. Apertar Snooze. Activity fecha. Home mostra contador positivo (~5min) ainda apontando para flexão. Após ~5min o alarme volta a tocar na full-screen com flexão. Sem log de set entre os dois disparos. Stats diárias não incrementam.

---

### U4. Botão Snooze na tela full-screen

**Goal:** UI: adicionar o terceiro botão Snooze com label dinâmico mostrando o intervalo configurado.

**Requirements:** R2 (UI).

**Dependencies:** U3.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmActivity.kt` — adicionar parâmetro `onSnooze: () -> Unit` ao `AlarmScreen`, ligar ao `viewModel::performSnooze`, e renderizar botão entre Check e Skip.
- `app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt` — expor `snoozeMinutes: Int = sessionPrefs.overshootRepeatMinutes` como property pública (lê uma vez no `init`/lazy, sem flow — a Activity não sobrevive a mudanças de Settings durante o disparo).
- `app/src/main/res/values/strings.xml` — adicionar `alarm_snooze` (`"Snooze %1$d min"`).
- `app/src/main/res/values-pt-rBR/strings.xml` — adicionar `alarm_snooze` (`"Adiar %1$d min"`).

**Approach:**
- Hierarquia visual:
  - **Check** (primário): `Button` com `containerColor = GtgPrimary`, `heightIn(min = 64.dp)` — mantém o que existe.
  - **Snooze** (secundário, novo): `OutlinedButton` ou `Button` com `containerColor = GtgSurface` / borda fina, `heightIn(min = 56.dp)`. Label = `stringResource(R.string.alarm_snooze, snoozeMinutes)`. Inserir entre o Check e o Spacer que precede o Skip.
  - **Skip** (terciário): `TextButton` — mantém.
- Adicionar `Spacer(modifier = Modifier.height(12.dp))` entre Check e Snooze (consistente com o spacing usado em `AlarmActivity.kt:302`).
- `AdaptiveText` no label do botão Snooze para acomodar locale com palavras longas (`"Adiar 15 min"` é seguro mas dá pra ter font-scale XL).

**Patterns to follow:** estrutura do botão Check em `AlarmActivity.kt:282-300` (Button + AdaptiveText + heightIn).

**Test scenarios:**
- Test expectation: none -- UI Compose, verificação por inspeção visual conforme a verificação manual abaixo.

**Verification:**
- Build instala. Ao disparar alarme com `overshootRepeatMinutes = 5`, a tela full-screen mostra 3 botões: "FAZER CHECK", "Adiar 5 min" (pt-BR) ou "Snooze 5 min" (en), "Pular".
- Mudar `overshootRepeatMinutes` para 10 em Settings, esperar alarme tocar de novo, conferir label mostra "10 min".
- Botão Snooze aciona `performSnooze` (cobrido por U6, Tests 3-5).
- Tela cabe em portrait 360dp sem clipar (usar `AdaptiveText` no label).

---

### U5. Locale dinâmico nos Composables (3 pontos)

**Goal:** Eliminar `Locale("pt", "BR")` hardcoded em Composables que renderizam dias da semana ou outros formatadores sensíveis a locale.

**Requirements:** R4 (Composables).

**Dependencies:** nenhuma.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/schedule/ScheduleScreen.kt` (linhas 293 e 997).
- `app/src/main/java/com/gtg/app/presentation/settings/SettingsScreen.kt` (linha 312).

**Approach:**
- Substituir cada `Locale("pt", "BR")` / `Locale.forLanguageTag("pt-BR")` por:
  ```text
  val locale = LocalConfiguration.current.locales[0]
  ```
- Coletar o `locale` no escopo do Composable que renderiza o conteúdo (não puxar do parâmetro). Em `ScheduleScreen.kt:997` (dialog), o composable já tem acesso a `LocalConfiguration` — mesmo padrão.
- Remover `import java.util.Locale` se ficar sem uso em cada arquivo.

**Patterns to follow:** `ScheduleScreen.kt:730` e `:1073` já usam `LocalConfiguration.current.locales[0]` — replicar.

**Test scenarios:**
- Test expectation: none -- Composable plumbing, verificação por idioma do app.

**Verification:**
- App em pt-BR: chips de "Dias ativos" em Settings mostram "S T Q Q S S D"; header do calendário em ScheduleScreen mostra "S T Q Q S S D"; dialog de bloco recorrente idem.
- Trocar idioma em Settings para English. Activity recria. Chips agora mostram "M T W T F S S"; header e dialog também.

---

### U6. Locale dinâmico no StatisticsViewModel + testes do AlarmViewModel

**Goal:** Fechar o i18n no ViewModel das estatísticas e adicionar a cobertura de teste unitário que trava as regressões de U2/U3.

**Requirements:** R4 (ViewModel); testes de R1, R2, R3.

**Dependencies:** U1 (infra), U2, U3.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/statistics/StatisticsViewModel.kt` (linha 86) — `val locale = Locale.getDefault()`.
- `app/src/test/java/com/gtg/app/presentation/alarm/AlarmViewModelTest.kt` (novo) — testes JUnit + MockK.

**Approach (parte StatisticsViewModel):**
- Trocar `val locale = Locale("pt", "BR")` por `val locale = Locale.getDefault()`.
- `AppCompatDelegate.setApplicationLocales` propaga para `Locale.getDefault()`; nada mais a configurar.

**Approach (parte testes do AlarmViewModel):**
- `AlarmViewModelTest` instancia `AlarmViewModel` com:
  - `SavedStateHandle(mapOf(EXTRA_EXERCISE_ID to 1L, EXTRA_EXERCISE_NAME to "Flexão", EXTRA_TARGET_REPS to 10))`
  - Mocks de `ExerciseRepository`, `ExerciseLogRepository`, `DynamicSchedulerUseCase`, `AlarmScheduler`, `SessionPreferences`, `@ApplicationContext context: Context`.
- Configurar `coEvery { exerciseRepository.getActiveExercises() } returns listOf(<Flexão>, <Barra>)`.
- Configurar `every { dynamicScheduler.calculateNextAlarm(any(), any(), any()) } returns ScheduleResult.Scheduled(<datetime>)`.
- Usar `MainDispatcherRule` (de U1) para que `viewModelScope.launch` rode em test dispatcher; `runTest { ... ; advanceUntilIdle() }`.

**Patterns to follow:** estrutura do `MainDispatcherRule` (U1). Para mocks de `AlarmSoundPlayer` (object singleton): MockK suporta `mockkObject(AlarmSoundPlayer)`.

**Test scenarios:**

1. **Test 1 — performCheck cancela overshoot** (Covers R1)
   - Input: alarm fires for exerciseId=1 (Flexão), targetReps=10.
   - Action: `viewModel.performCheck()` + `advanceUntilIdle()`.
   - Expected: `verify(exactly = 1) { alarmScheduler.cancelOvershoot() }`. Adicionalmente `verify { alarmScheduler.schedule(any(), eq(2L), eq("Barra"), any()) }` (rotacionou).

2. **Test 2 — performSkip cancela overshoot** (Covers R3)
   - Action: `viewModel.performSkip()` + `advanceUntilIdle()`.
   - Expected: `verify(exactly = 1) { alarmScheduler.cancelOvershoot() }`. `verify(exactly = 0) { exerciseLogRepository.insert(any()) }` (skip não loga). `verify { alarmScheduler.schedule(any(), eq(2L), eq("Barra"), any()) }`.

3. **Test 3 — performSnooze cancela overshoot, mantém exercício, sem log** (Covers R2)
   - Configurar `every { sessionPrefs.overshootRepeatMinutes } returns 5`.
   - Action: `viewModel.performSnooze()` + `advanceUntilIdle()`.
   - Expected:
     - `verify(exactly = 1) { alarmScheduler.cancelOvershoot() }`
     - `verify(exactly = 1) { alarmScheduler.schedule(any(), eq(1L), eq("Flexão"), eq(10)) }` (MESMO exercício)
     - `verify(exactly = 0) { exerciseLogRepository.insert(any()) }`
     - `verify(exactly = 0) { dynamicScheduler.calculateNextAlarm(any(), any(), any()) }` (snooze não passa pelo scheduler).
     - `verify { sessionPrefs.setNextAlarm(any(), eq(1L), eq("Flexão"), eq(10)) }`.

4. **Test 4 — performSnooze grava lastCheck**
   - Action: `viewModel.performSnooze()` + `advanceUntilIdle()`.
   - Expected: `verify { sessionPrefs.setLastCheck(any()) }`.

5. **Test 5 — performSnooze sinaliza actionCompleted=true**
   - Action: `viewModel.performSnooze()` + `advanceUntilIdle()`.
   - Expected: `viewModel.actionCompleted.value == true`.

**Verification:**
- `./gradlew :app:testDebugUnitTest` passa com 5 testes do `AlarmViewModelTest` + `SanityCheckTest`.
- Statistics: app em inglês, ir em Statistics e verificar que o eixo X do gráfico semanal mostra dias em inglês ("Mon Tue Wed Thu Fri Sat Sun").

---

## Key Technical Decisions

### KD-P1 — Helper duplicado entre Home e AlarmViewModel, não extraído

Mantém KD1 do origin. `dismissActiveAlarmSideEffects` é replicado em `AlarmViewModel` em vez de extraído. Justificativa: 3 linhas, dependências sutilmente diferentes (`@ApplicationContext` em ambos, mas o ciclo de vida é diferente), sem terceiro chamador. YAGNI.

### KD-P2 — Snooze não passa pelo `DynamicSchedulerUseCase`

Snooze é deslocamento direto `now + overshootRepeatMinutes`, **sem** aplicar as 5 regras do scheduler. Razão: a regra 3 ("descanso mínimo 20min") quebraria a semântica de snooze=5min — o usuário acabou de pedir 5min, não faz sentido empurrar para 20min. Justificativa adicional: snooze é repetível sob demanda do usuário, não é cálculo de cadência.

Trade-off: snooze pode cair fora da janela de atividade (`ActivityWindow.endTime`) se o usuário snoozear no fim do expediente. Comportamento aceito: o `HomeViewModel.restartCountdown` já tem lógica de roll-over que captura isso ao expirar a janela (`HomeViewModel.kt:438-446`), reagendando para o próximo dia ativo. Coberto sem mudanças.

### KD-P3 — `Locale.getDefault()` no ViewModel em vez de injetar provider

`AppCompatDelegate.setApplicationLocales` (`SettingsViewModel.kt:340`) propaga para `Locale.getDefault()` em runtime. Não precisa injetar um `Locale` via Hilt nem ler `sessionPrefs.languageTag` diretamente. Mais simples e segue o padrão oficial da Compose/AppCompat.

### KD-P4 — Testes unitários puros (JVM), sem instrumentação

Testes via `testImplementation` em `app/src/test/`, rodando em JVM. Não usar `androidTest/` (instrumentação) — desnecessário para o que está em escopo (lógica de ViewModel + verificação de invocações de `AlarmScheduler`).

Trade-off: testes não validam interação real com `AlarmManager`. Aceito — `AlarmScheduler` é interface mockável, e a verificação de comportamento real fica para verificação manual.

### KD-P5 — `snoozeMinutes` exposto como property "snapshot", não Flow

`AlarmViewModel` lê `sessionPrefs.overshootRepeatMinutes` uma vez (val imutável) em vez de coletar como `StateFlow`. Justificativa: a `AlarmActivity` é criada para um disparo; se o usuário mudar o valor em Settings durante o disparo, o próximo disparo já reflete o novo valor. Streaming reativo aqui seria over-engineering.

### KD-P6 — Snooze atualiza `lastCheckMillis`

Coerente com o que `performManualCheck`/`startSession` já fazem (`HomeViewModel.kt:525, 612`). Sem isso, se o usuário mudar `baseInterval` em Settings logo após snoozear, o `rescheduleFromAnchor` usaria uma âncora antiga (último Check real), deslocando o snooze de forma inesperada.

(KD7 do origin sobre `Locale.getDefault()` permanece — ver KD-P3.)

---

## Scope Boundaries

### In scope
- Cancelamento de overshoot nas três ações da `AlarmActivity` (Check, Skip, Snooze).
- Novo botão Snooze com label dinâmico, no padrão visual já existente.
- Fix de Locale nos 4 pontos listados.
- Setup mínimo de infra de testes unitários + 5 testes específicos.
- Adicionar `Locale.getDefault()` no `StatisticsViewModel`.

### Não cobertos neste plano (Deferred to Follow-Up Work)
- Outras hardcodes de `Locale("pt", "BR")` que existam fora dos 4 pontos reportados — fora do escopo deste plano; abrir auditoria separada se desejado.
- Ampliação da cobertura de testes para `HomeViewModel`, `DynamicSchedulerUseCase` e outros use-cases — escopo extra a planejar como track separado.
- Refatoração do `AlarmReceiver` para puxar exercise extras de `SessionPreferences` em vez do PendingIntent — não causa bug depois do cancel; mexer agora é over-engineering.
- Extração de `AlarmDismisser` ou utilitário compartilhado entre `HomeViewModel` e `AlarmViewModel`.

### Fora do escopo (origin Scope Boundaries)
- Mudar a semântica do overshoot automático (continua sem limite, mesmo intervalo).
- Adicionar preferência separada `snoozeMinutes`.
- Forçar a tela full-screen como canal único de Check.
- Esconder/desabilitar botões da Home durante alarme pendente.
- Re-design completo da tela full-screen.
- Adicionar limite máximo de snooze ou decaimento progressivo (5→10→15).

---

## System-Wide Impact

| Camada | Impacto |
|---|---|
| `presentation/alarm` | Mudança comportamental em `AlarmViewModel` (3 métodos), adição de método novo, mudança de UI em `AlarmActivity`. |
| `presentation/home` | Nenhum. Comportamento de overdue + Check manual continua. |
| `presentation/schedule`, `presentation/settings` | Locale dinâmico (3 pontos), sem mudança comportamental. |
| `presentation/statistics` | Locale dinâmico (1 ponto), sem mudança comportamental. |
| `data/local/SessionPreferences` | Nenhum. `setNextAlarm` / `setLastCheck` / `setAlarmPending` já existem. |
| `domain/scheduler` | Nenhum. `AlarmScheduler` API não muda. |
| `domain/usecase/DynamicSchedulerUseCase` | Nenhum. Snooze não passa por aqui (KD-P2). |
| `build.gradle.kts`, `libs.versions.toml` | Adiciona deps de teste. |
| Resources (`values/`, `values-pt-rBR/`) | Adiciona `alarm_snooze` em ambos. |

---

## Risks & Mitigations

| Risco | Impacto | Mitigação |
|---|---|---|
| Snooze cai fora da `ActivityWindow.endTime` | Alarme dispararia após fim do expediente | Roll-over do `HomeViewModel.restartCountdown` (`HomeViewModel.kt:438-446`) captura — sem código adicional. |
| Mock de `AlarmSoundPlayer` (object singleton) em testes | Possível leakage de estado entre testes | `mockkObject` + `unmockkObject` em `@Before`/`@After`. |
| Adição de `@ApplicationContext` no construtor do `AlarmViewModel` pode quebrar o `SavedStateHandle` viewModels() em `AlarmActivity` | App não compila ou crash em runtime | Hilt + `@HiltViewModel` aceita `@ApplicationContext` ao lado de `SavedStateHandle` (padrão já em `HomeViewModel`). |
| `MockK` falha em classes Kotlin com Hilt-generated wrappers | Testes não compilam | Mockar as **interfaces** (`AlarmScheduler`, `ExerciseRepository`, etc.) e não classes geradas. Todas já são interfaces no domain layer. |
| `Locale.getDefault()` retornar locale do sistema (não do app) em ambiente que não chamou `setApplicationLocales` | i18n quebrado em dispositivos sem o seletor in-app ainda usado | `AppCompatDelegate` no `Application.onCreate()` já restaura o locale persistido ao iniciar; comportamento garantido. |

---

## Verification Strategy

### Automated (U6)
- `./gradlew :app:testDebugUnitTest` — 5 testes do `AlarmViewModelTest` + `SanityCheckTest`. Todos passam.

### Manual

| Cenário | Procedimento | Resultado esperado |
|---|---|---|
| Skip pela full-screen não dispara overshoot | Sessão ativa, 2 exercícios, intervalo 45min, overshoot=5min. Aguardar primeiro alarme. Apertar "Pular" na full-screen. Cronometrar. | Em 5min nenhum re-alerta. Próximo alarme só no minuto ~45 com o segundo exercício. |
| Check pela full-screen não dispara overshoot | Mesma cena, apertar "FAZER CHECK". | Idem. Log do primeiro exercício gravado. |
| Snooze mantém exercício | Mesma cena, apertar "Adiar 5 min". | Activity fecha. Home conta ~5min para o MESMO exercício. Após 5min, full-screen volta a tocar com o mesmo exercício. |
| Label do snooze reflete settings | Mudar `overshootRepeatMinutes` para 10 em Settings. Forçar próximo disparo. | Botão exibe "Adiar 10 min" / "Snooze 10 min". |
| Skip não loga | Apertar Pular. Conferir Statistics. | `todaySetsCompleted` e `todayBreakdown` não incrementam. |
| Snooze não loga | Apertar Snooze. Conferir Statistics. | Idem. |
| i18n — chips de dias ativos | Settings → idioma → English. Voltar para Settings. | Chips mostram "M T W T F S S". |
| i18n — calendário | Schedule → mês corrente. | Header mostra "M T W T F S S". |
| i18n — dialog bloco recorrente | Schedule → tocar em data → criar bloco recorrente. | Chips de dias da semana em inglês. |
| i18n — gráfico semanal | Statistics → vista semanal. | Eixo X mostra "Mon Tue Wed Thu Fri Sat Sun". |
| "Stuck no exercício" não acontece após fix | Sessão ativa, deixar o overshoot disparar 2-3 vezes sem interagir. Depois apertar Check pela full-screen do último overshoot. | Loga o exercício mostrado na tela. Próximo alarme primary é para o exercício SEGUINTE da rotação. Nenhum overshoot intermediário continua tocando depois. |

---

## Open Questions (Deferred to Implementation)

Nenhuma. Todas as decisões de produto foram fechadas no brainstorm e as decisões técnicas estão registradas em Key Technical Decisions.
