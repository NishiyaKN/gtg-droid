---
status: active
type: feat
created: 2026-05-20
depth: deep
origin: docs/brainstorms/2026-05-20-001-post-testing-batch-requirements.md
---

# feat: Lote pós-teste — meta opcional, modalidades de alerta, onboarding, intervalo estrito

## Summary

Quatro melhorias batched em phased delivery: (A) `dailySetTarget` vira opcional via toggle em Settings; (B) onboarding 3-step no primeiro launch com skip global; (C) modalidades de alerta refeitas como três toggles independentes (Som / Visual / Vibração); (D) `DynamicSchedulerUseCase` ganha branch `intervalMode = STRICT` que ignora rules 3 e 4 mantendo rule 5. Ordem A → B → C → D crescente em risco e blast radius.

**Correção a R23 e AE9 do brainstorm:** o brainstorm afirmou que Snooze atualiza `lastCheck = now`. Isso é incorreto contra o estado real do código (commit 693575a) e contra o learning `cadence-anchor-vs-reschedule-anchor-2026-05-19.md`. Este plano implementa a semântica correta — Snooze NÃO atualiza `lastCheck` — e ajusta a expectativa de AE9 conforme (próximo Check real é o que re-âncora a cadência em STRICT). Ver KD do Snooze + U16 test scenario para o trace corrigido.

## Problem Frame

Durante uso continuado do app surgiram quatro atritos independentes — meta diária ocupa espaço sem agregar valor; alarme sonoro nem sempre é apropriado e não há modalidade silenciosa; primeiro launch despeja o usuário em Home vazia sem pista do que configurar; e a cadência dinâmica desvia o intervalo configurado quando colide com `InactivityBlock` ou `MINIMUM_REST_MINUTES`.

Cada item toca um subsistema distinto (Home/Settings; navegação inicial; alarm pipeline; scheduler core), o que permite delivery faseada e atomicidade por commit. As mudanças se sobrepõem em poucos pontos — todas adicionam chaves a `SessionPreferences` e todas tocam `SettingsScreen` — mas não há dependência funcional entre fases.

(see origin: `docs/brainstorms/2026-05-20-001-post-testing-batch-requirements.md`)

---

## Requirements Traceability

| Req (origin) | Implementation Units |
|---|---|
| R1, R2, R3, R4 (daily target opcional) | U1, U2, U3 |
| R12, R13, R14, R15, R16 (onboarding) | U4, U5, U6, U7 |
| R5, R6, R7, R8, R9, R10, R11 (modalidades) | U8, U9, U10, U11, U12 |
| R17, R18, R19, R20, R21, R22, R23 (intervalo estrito) | U13, U14, U15, U16 |

AEs cobertas — AE1: U3 testes; AE2: U9 testes; AE3, AE4: U11+U12 testes; AE5, AE6: U4+U7 testes; AE7, AE8: U15 testes; AE9: U16 testes.

---

## High-Level Technical Design

Os 4 itens compartilham um padrão estrutural: nova chave em `SessionPreferences` → toggle em `SettingsScreen` → ramificação no consumer (Home, MainActivity, AlarmReceiver/Activity, DynamicSchedulerUseCase). A maior parte é extensão de patterns existentes; só dois pontos têm forma nova.

**Modo estrito — onde injetar (Item 4):**

```text
DynamicSchedulerUseCase.evaluateWithDependencies(intervalMode, ...):
  candidate = checkTime + baseInterval                   // Rule 2 — sempre
  if intervalMode == DYNAMIC:
    if candidate < now + MINIMUM_REST_MINUTES:          // Rule 3 — só em DYNAMIC
      candidate = now + MINIMUM_REST_MINUTES
  if candidate < windowStartToday:                       // ajuste de início de janela — sempre
    candidate = windowStartToday
  if candidateDate.dayOfWeek not in activeDays:          // active days — sempre
    return scheduleForNextActiveDay(...)
  if candidate >= windowEndToday:                        // Rule 5 — sempre
    return scheduleForNextActiveDay(...)
  if intervalMode == DYNAMIC:
    resolve_block_collisions(...)                        // Rule 4 — só em DYNAMIC
    if candidate >= windowEndToday: return scheduleForNextActiveDay(...)
  return Scheduled(candidate)
```

Diretamente: STRICT remove o clamp de rest mínimo e o loop de colisão; mantém active days, windowStart e windowEnd. Esta sketch é guidance direcional para revisão, não código a copiar — o implementador faz o branching idiomaticamente.

**Onboarding — onde gatekeep (Item 3):**

```text
MainActivity.onCreate after PermissionGate:
  if !sessionPrefs.hasSeenOnboarding:
    OnboardingHost(onFinish = { sessionPrefs.setHasSeenOnboarding(true); recompose to GtgNavHost })
  else:
    GtgNavHost()

OnboardingHost holds step state {0,1,2}, navega entre Welcome/Window/Exercise
internamente sem usar NavController. SkipAll em qualquer step encerra.
```

**Modalidades — onde ramificar (Item 2):**

- Settings: três toggles `Switch` (padrão já usado em `SettingsScreen.kt:731,855,1020,1128`); validação at-least-one-ON em ViewModel.
- `AlarmReceiver.onReceive`: lê flags, condiciona `AlarmSoundPlayer.play` e `VibrationPlayer.start`.
- `AlarmActivity`: lê flag `visualEnabled` (via `AlarmViewModel`), aplica `infiniteRepeatable` no alpha do `Box` de fundo (mesmo pattern já usado em outras animações da própria Activity, ver `AlarmActivity.kt:9-13`). `LaunchedEffect(actionCompleted)` para o `VibrationPlayer.stop()` junto com `AlarmSoundPlayer.stop()`.

---

## Output Structure

Arquivos novos (sob `app/src/main/java/com/gtg/app/`):

```text
presentation/
  onboarding/
    OnboardingHost.kt           // step state + skip-all + finish
    WelcomeStep.kt              // Step 1
    ActivityWindowStep.kt       // Step 2
    ExerciseStep.kt             // Step 3
presentation/alarm/
  VibrationPlayer.kt            // singleton análogo a AlarmSoundPlayer
```

**Sem `OnboardingViewModel`.** Step state vive em `OnboardingHost` via `remember { mutableStateOf(...) }`. Steps 2 e 3 injetam repositories diretamente via Hilt entry points dentro dos Composables (ou recebem por parâmetro do Host). Decisão consciente — adicionar VM para 3 steps sequenciais sem state cross-step seria over-engineering.

Resto do trabalho modifica arquivos existentes.

---

## Phased Delivery

Cada fase é um PR independente. Ordem A → B → C → D em risco crescente. Fases não bloqueiam umas às outras tecnicamente (só compartilham `SessionPreferences`), mas a ordem facilita revisão incremental.

---

### Phase A — Daily target opcional (Item 1)

#### U1. SessionPreferences: chave `showDailyTarget`

- **Goal:** Adicionar a chave booleana persistente que controla a visibilidade do daily target. Default `false` para usuários novos e existentes (R4).
- **Requirements:** R1, R4
- **Dependencies:** —
- **Files:**
  - `app/src/main/java/com/gtg/app/data/local/SessionPreferences.kt` — nova KEY + getter + setter + DEFAULT
  - `app/src/test/java/com/gtg/app/data/local/SessionPreferencesTest.kt` — se já existir; senão criar
- **Approach:** Seguir o pattern existente (ver `KEY_BYPASS_DND`/`bypassDnd`/`setBypassDnd`/`DEFAULT_BYPASS_DND` em `SessionPreferences.kt:39,95-96,53`). Constante `KEY_SHOW_DAILY_TARGET = "show_daily_target"`, default `false`.
- **Patterns to follow:** `bypassDnd` / `setBypassDnd` é o padrão simétrico mais próximo.
- **Test scenarios:**
  - Quando a chave nunca foi escrita, getter retorna `false` (default).
  - Após `setShowDailyTarget(true)`, getter retorna `true`.
  - Após `setShowDailyTarget(false)`, getter retorna `false`.
  - `Test expectation: none -- nova chave de pref; cobertura via U2/U3.` se já não houver suíte de testes para SessionPreferences. Verificar.
- **Verification:** Build passa; chave aparece em `adb shell run-as` dump das prefs após toggle no app.

#### U2. Settings: toggle "Mostrar meta diária" + ocultação do campo dailySetTarget

- **Goal:** Expor o toggle em Settings e esconder o `WheelNumberPicker` de `dailySetTarget` quando OFF.
- **Requirements:** R1, R3
- **Dependencies:** U1
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsViewModel.kt` — novo campo em state + flow + setter
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsScreen.kt` — novo `Switch` + conditional render do bloco de `dailySetTarget`
  - `app/src/main/res/values/strings.xml`, `app/src/main/res/values-pt-rBR/strings.xml` — labels
- **Approach:** Switch posicionado próximo do atual campo de daily target. Quando OFF, esconder o `WheelNumberPicker` via `AnimatedVisibility` (evita visual jump em scroll denso de Settings) mantendo o valor persistido intocado (não chamar setter). Quando ON, mostra picker com valor atual.
- **Patterns to follow:** Switches existentes em `SettingsScreen.kt:731,855,1020,1128` (padrão `Row` com label à esquerda + `Switch` à direita).
- **Test scenarios:**
  - Toggle OFF (default): UI não renderiza o WheelNumberPicker; o valor persistido de `dailySetTarget` permanece (inspecionável após toggle ON).
  - Toggle ON: WheelNumberPicker aparece com o valor atual de `dailySetTarget`.
  - Mudar o picker enquanto ON persiste o novo valor.
  - Persistência sobrevive a recomposição (collectAsStateWithLifecycle).
  - `Test expectation: none -- UI condicional; cobertura manual + AE1 via U3.`
- **Verification:** Manual — abrir Settings, observar default OFF, ligar/desligar e verificar render condicional.

#### U3. HomeScreen: ocultar card "Daily Summary" quando `showDailyTarget` está OFF

- **Goal:** Não renderizar o card de Daily Summary quando o toggle está OFF.
- **Requirements:** R2
- **Dependencies:** U1, U2
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt` — propagar `showDailyTarget` ao state
  - `app/src/main/java/com/gtg/app/presentation/home/HomeScreen.kt` — condicional no `item(key = "daily_summary")` em torno da linha 125
- **Approach:** Adicionar `showDailyTarget: Boolean` ao state observado pelo VM. No `LazyColumn`, envolver o `item(key = "daily_summary")` com `if (state.showDailyTarget) { ... }`. Quando OFF, o item nem entra no layout (não há reserva de espaço).
- **Patterns to follow:** Outros itens condicionais na Home (verificar `HomeScreen.kt` para precedente; se não houver, este vira o padrão).
- **Test scenarios:**
  - **Covers AE1.** Toggle OFF (default): card "Daily Summary" não aparece na Home; só os demais cards (timer, current exercise, etc.).
  - Toggle ON: card aparece com progress bar e contador.
  - Alternar toggle em runtime atualiza a Home reativamente (sem reabrir).
  - `Test expectation: none -- UI condicional; verificação manual + AE1.`
- **Verification:** Manual — abrir app default (sem dados anteriores), confirmar Home sem o card; ligar toggle em Settings e voltar — card aparece.

---

### Phase B — Onboarding (Item 3)

#### U4. Onboarding scaffold: flag `hasSeenOnboarding` + gate em MainActivity + `OnboardingHost`

- **Goal:** Detectar primeiro launch e mostrar o fluxo de onboarding antes do `GtgNavHost`. Criar o esqueleto da navegação interna de 3 steps com Skip global.
- **Requirements:** R12, R13, R15
- **Dependencies:** —
- **Files:**
  - `app/src/main/java/com/gtg/app/data/local/SessionPreferences.kt` — `KEY_HAS_SEEN_ONBOARDING` + `hasSeenOnboarding` + `setHasSeenOnboarding`
  - `app/src/main/java/com/gtg/app/MainActivity.kt` — gate condicional entre `OnboardingHost` e `GtgNavHost` após o `PermissionGate`
  - `app/src/main/java/com/gtg/app/presentation/onboarding/OnboardingHost.kt` — novo arquivo, step state interno `{WELCOME, WINDOW, EXERCISE}`, `onFinish` callback
- **Approach:** `OnboardingHost` é Composable com `var step by remember { mutableStateOf(WELCOME) }`. Cada step é um Composable separado (U5-U7), passa `onContinue` e `onSkipAll` por parâmetro. Skip-all em qualquer step chama `sessionPrefs.setHasSeenOnboarding(true)` + `onFinish()`. **Gate como Composable wrapper**, espelhando o shape do `LanguageGate` existente em `MainActivity.kt:102+`: novo Composable `OnboardingGate(sessionPrefs) { content() }` aninhado entre `PermissionGate { OnboardingGate { GtgNavHost() } }`. Use `var seen by remember { mutableStateOf(sessionPrefs.hasSeenOnboarding) }` (NÃO Flow — pattern simétrico ao `var tag` do LanguageGate); ao chamar `sessionPrefs.setHasSeenOnboarding(true)`, o `OnboardingHost.onFinish` também faz `seen = true` para disparar recomposição imediata.
- **Patterns to follow:** `LanguageGate` em `MainActivity.kt:102` é o padrão direto (state local + recomposição manual). NÃO inventar `hasSeenOnboardingFlow` — `SessionPreferences` expõe somente `observeChanges(): Flow<Long>` (linha 267) que emite contador monotônico; consumidores que precisam de reatividade usam `sessionPrefs.observeChanges().conflate().collect { ... sessionPrefs.<field> ... }` como em `SettingsViewModel.kt:134`. Para o gate aqui, state local com `remember` basta (nenhum outro componente muda `hasSeenOnboarding` durante o lifecycle da `MainActivity`).
- **Test scenarios:**
  - Primeiro launch (flag default false): MainActivity renderiza `OnboardingHost` em step WELCOME.
  - SkipAll a partir de WELCOME marca `hasSeenOnboarding=true` e troca para `GtgNavHost` na próxima recomposição.
  - Próximo launch após skip ou conclusão: vai direto pra `GtgNavHost`.
  - `Test expectation: none -- gating de UI; cobertura manual + AE5.`
- **Verification:** Manual — limpar dados do app, abrir, ver Welcome; pular tudo; reabrir → vai direto pra Home.

#### U5. Step 1: Welcome screen + strings

- **Goal:** Composable do Step 1 com texto explicativo de GtG + botão "Continuar" + botão "Pular tudo".
- **Requirements:** R14 (Step 1), R15, R16
- **Dependencies:** U4
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/onboarding/WelcomeStep.kt` — novo
  - `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — `onboarding_welcome_title`, `onboarding_welcome_body`, `onboarding_button_continue`, `onboarding_button_skip_all`
- **Approach:** Layout simples — título grande, body paragraph descrevendo GtG (séries submáximas distribuídas ao longo do dia, baixa fadiga, app alerta nos horários respeitando janela e descansos). Dois botões Material3: `Button` primário "Continuar" + `TextButton` discreto "Pular tudo". Texto resolvido via `stringResource()`.
- **Patterns to follow:** Tipografia da `AlarmActivity.kt` (já tem patterns para títulos grandes e texto explicativo) e botões Material3 com tema do app (`GtgPrimary`, `GtgSurface`).
- **Test scenarios:**
  - **Covers AE5.** Welcome aparece em primeiro launch com texto em pt-BR ou en conforme locale do app.
  - "Continuar" avança step para Step 2.
  - "Pular tudo" encerra onboarding (flag=true, navega pra Home).
  - Locale en mostra texto em inglês; locale pt-BR em português.
  - `Test expectation: none -- screen estática; cobertura via AE5 + AE6 manual.`
- **Verification:** Manual — abrir em locale en e pt-BR, observar tradução.

#### U6. Step 2: ActivityWindow form + strings

- **Goal:** Composable do Step 2 que permite criar a primeira `ActivityWindow` (horário início, fim, dias da semana) e persistir via `ActivityWindowRepository`.
- **Requirements:** R14 (Step 2), R15, R16
- **Dependencies:** U4
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/onboarding/ActivityWindowStep.kt` — novo
  - `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — labels do step
- **Approach:** Form simplificado com `WheelNumberPicker` para hora inicial / hora final (mesmo widget já usado em `ScheduleScreen`) + seletor de dias da semana ativos. Botão "Continuar" persiste via injection de `ActivityWindowRepository` (Hilt) e avança. "Pular" encerra sem persistir. **Decisão de planning:** reusar `WheelNumberPicker` é seguro (componente já genérico); evitar reusar todo o `ScheduleScreen` (acoplamento alto a estado de calendário). Widget dedicado neste arquivo é mais simples e atende ao step.
- **Patterns to follow:** `WheelNumberPicker` em `presentation/common/`, ViewModel injection via `@HiltViewModel` se necessário (mas state local com remember pode bastar se a persistência for one-shot no Continue).
- **Test scenarios:**
  - **Covers AE6 (parcial).** Selecionar 08:00–18:00, dias úteis, Continuar: `ActivityWindow` persistida no Room com esses valores; avança pra Step 3.
  - "Pular" não persiste nenhuma Window; avança pra Step 3.
  - Validar que horário final > inicial (mesma regra usada em `ScheduleScreen`).
  - `Test expectation: none -- UI form; verificação manual e teste integrado via AE6.`
- **Verification:** Manual — completar Step 2 com janela 08:00–18:00 seg-sex; após concluir todo o onboarding, abrir Schedule e ver a Window persistida.

#### U7. Step 3: Exercise form + strings + onFinish

- **Goal:** Composable do Step 3 que permite criar o primeiro `Exercise` (nome + targetReps) e finalizar o onboarding.
- **Requirements:** R14 (Step 3), R15, R16
- **Dependencies:** U4 (U6 é ordenação lógica do flow AE6, não dependência de compilação)
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/onboarding/ExerciseStep.kt` — novo
  - `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — labels
- **Approach:** Form com `OutlinedTextField` para nome do exercício + `WheelNumberPicker` para targetReps. Botão "Concluir" persiste via `ExerciseRepository`, marca `setHasSeenOnboarding(true)`, dispara `onFinish` do `OnboardingHost`. "Pular" só marca flag e dispara finish (sem persistir).
- **Patterns to follow:** Forms de exercício existentes em `ExercisesScreen.kt`. Mesmo pattern de injection do U6.
- **Test scenarios:**
  - **Covers AE6.** Inserir "Push-up", 10 reps, Concluir: `Exercise` persistido no Room; `hasSeenOnboarding=true`; navega pra Home; Home renderiza com o Exercise visível.
  - "Pular" não persiste o Exercise; marca flag; navega pra Home (vazia se U6 também foi pulado).
  - Validar nome não-vazio antes de habilitar "Concluir".
  - `Test expectation: none -- UI form; cobertura manual via AE6.`
- **Verification:** Manual — onboarding completo end-to-end com Push-up/10, voltar pra Home e verificar Exercise visível.

---

### Phase C — Modalidades de alerta (Item 2)

#### U8. SessionPreferences: três chaves de modalidade

- **Goal:** Adicionar `soundEnabled`, `visualEnabled`, `vibrationEnabled` em `SessionPreferences`. Defaults: Som=true, Visual=false, Vibração=false (preserva comportamento atual byte-for-byte).
- **Requirements:** R5
- **Dependencies:** —
- **Files:**
  - `app/src/main/java/com/gtg/app/data/local/SessionPreferences.kt` — 3 KEYS + getters + setters + DEFAULTS
- **Approach:** Mesmo pattern do `bypassDnd`. Constantes: `KEY_SOUND_ENABLED`, `KEY_VISUAL_ENABLED`, `KEY_VIBRATION_ENABLED`. Defaults: `true`, `false`, `false`. Documentar comentário inline que defaults preservam comportamento atual (Som puro).
- **Patterns to follow:** `bypassDnd` / `setBypassDnd` / `DEFAULT_BYPASS_DND`.
- **Test scenarios:**
  - Após install limpo, soundEnabled=true, visualEnabled=false, vibrationEnabled=false.
  - Setter atualiza valor persistido.
  - `Test expectation: none -- chaves de pref; cobertura via U9.`
- **Verification:** Build passa; chaves visíveis em `adb shell run-as cat shared_prefs/gtg_session.xml` após toggle.

#### U9. Settings: três toggles de modalidade + validação at-least-one-ON

- **Goal:** Expor os 3 toggles em Settings, com validação que pelo menos um esteja ON.
- **Requirements:** R5, R6
- **Dependencies:** U8
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsViewModel.kt` — novos campos no state; método `toggleModality(modality)` com validação
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsScreen.kt` — novo grupo "Modalidades de alerta" com 3 Switches
  - `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — labels
- **Approach:** ViewModel: ao receber toggle OFF de uma modalidade, verifica se é a última ON; se for, no-op (não chama setter). UI: o `Switch.onCheckedChange` chama `toggleModality(...)`; o estado mostrado vem do flow do ViewModel, então quando o toggle é rejeitado, o Switch visualmente "volta" para ON imediatamente. Considerar Snackbar/Toast curto explicando "Pelo menos uma modalidade precisa estar ativa" no momento da rejeição.
- **Patterns to follow:** Switches existentes em `SettingsScreen.kt:731,855,1020,1128`. Snackbar host se já existir no Settings; se não, optar por visual feedback mínimo (toggle "resistente").
- **Test scenarios:**
  - **Covers AE2.** Estado inicial Som=ON, Visual=OFF, Vib=OFF. Tentar desligar Som: toggle resiste, estado persistido permanece Som=ON.
  - Ativar Visual; agora pode desligar Som (Visual continua ON).
  - Desligar todas exceto a última: cada tentativa de desligar a última falha.
  - Persistência: alterar combinações, fechar app, reabrir, valores preservados.
  - `Test expectation: ViewModel test cobrindo validação at-least-one-ON (lógica testável sem Compose).`
- **Verification:** Manual — tentar desligar o último toggle e observar que volta para ON.

#### U10. VibrationPlayer + permission VIBRATE no Manifest

- **Goal:** Criar utilitário singleton `VibrationPlayer` (análogo a `AlarmSoundPlayer`) com start/stop + path dual de API (Vibrator/VibratorManager). Declarar permission no Manifest.
- **Requirements:** R9, R10
- **Dependencies:** —
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/alarm/VibrationPlayer.kt` — novo
  - `app/src/main/java/com/gtg/app/presentation/alarm/AlarmActivity.kt` — adicionar `VibrationPlayer.stop()` no `onDestroy()` defensivo (linha próxima da `AlarmSoundPlayer.stop()` existente em ~121-124), garantindo que vibração para mesmo em destruction sem passar pelo `LaunchedEffect`
- **Manifest:** `VIBRATE` permission **JÁ está declarada** em `AndroidManifest.xml:25` (de iteração anterior). U10 NÃO precisa adicionar — só verificar.
- **Approach:** Object/singleton com método `start(context)` e `stop()`. Lê o sistema vibrator: API 31+ via `context.getSystemService(VibratorManager::class.java).defaultVibrator`; 26-30 via `context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator`. Usa `VibrationEffect.createWaveform(longArrayOf(0, 500, 250), repeat = 0)` para pattern repetido. `stop()` chama `vibrator.cancel()`. Guard `if (!vibrator.hasVibrator()) return` em devices sem hardware.
- **Patterns to follow:** `AlarmSoundPlayer.kt` é o gêmeo direto (singleton, start/stop, lida com lifecycle do Activity sem injeção). Replicar estilo.
- **Test scenarios:**
  - `vibrator.hasVibrator() == false` → `start()` é no-op, não quebra.
  - `stop()` antes de `start()` é no-op, não quebra (idempotente).
  - Path API 31+ usa `VibratorManager.defaultVibrator`.
  - Path API 26-30 usa `Context.VIBRATOR_SERVICE`.
  - `Test expectation: unit tests com mock Vibrator/Context não trivial; planning sugere teste manual em emulador API 26 e device físico Android 12+.`
- **Verification:** Manual em duas versões de Android (26-30 e 31+); verificar vibração com pattern repetido + cancel correto.

#### U11. AlarmReceiver: ramificar disparo conforme modalidades

- **Goal:** Em `AlarmReceiver.onReceive`, condicionar `AlarmSoundPlayer.play` em `soundEnabled` e disparar `VibrationPlayer.start` em `vibrationEnabled`. Visual é responsabilidade da `AlarmActivity` (U12), Receiver não toca.
- **Requirements:** R7, R9, R11
- **Dependencies:** U8, U10
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/alarm/AlarmReceiver.kt` — branch nas chamadas finais (linhas ~159-164)
- **Approach:** Substituir a chamada incondicional a `AlarmSoundPlayer.play` por `if (sessionPrefs.soundEnabled) { AlarmSoundPlayer.play(...) }`. Adicionar `if (sessionPrefs.vibrationEnabled) { VibrationPlayer.start(context) }`. Manter a ordem atual onde o overshoot é agendado antes do play (race-condition fix do learning `alarm-receiver-overshoot-schedule-race-2026-05-19.md`).
- **Patterns to follow:** Estrutura atual do `onReceive` em `AlarmReceiver.kt`. Não duplicar logic; ler `sessionPrefs` uma vez como já é feito.
- **Test scenarios:**
  - Som=true, Vib=false → AlarmSoundPlayer.play chamado, VibrationPlayer não.
  - Som=false, Vib=true → AlarmSoundPlayer não, VibrationPlayer.start chamado.
  - Som=true, Vib=true → ambos chamados.
  - Cobre integração com overshoot — re-alerta também respeita as flags (R11): o segundo disparo passa pelo mesmo branch.
  - **Covers AE4 (parcial).** Os 3 estímulos reaparecem juntos no overshoot.
  - `Test expectation: unit tests do branching com SessionPreferences fake; integration manual.`
- **Verification:** Manual — combinar diferentes flags, simular disparo, observar comportamento.

#### U12. AlarmActivity: pulse visual + stop de vibração no actionCompleted

- **Goal:** Aplicar pulse de alpha no fundo da `AlarmActivity` quando `visualEnabled`. Parar `VibrationPlayer` junto com `AlarmSoundPlayer` no `actionCompleted`.
- **Requirements:** R8, R9
- **Dependencies:** U8, U10, U11
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/alarm/AlarmActivity.kt` — `Box` de fundo com alpha animado + atualizar `LaunchedEffect`
  - `app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt` — expor `visualEnabled` ao Composable
- **Approach:** `AlarmViewModel` injeta `SessionPreferences` (já injetado provavelmente — verificar) e expõe `visualEnabled: Boolean` (lido one-shot no init). No `setContent`, wrap o `AlarmScreen` com `Box` que tem `Modifier.background(GtgPrimary.copy(alpha = pulseAlpha))` quando `visualEnabled` for true. O `pulseAlpha` vem de `rememberInfiniteTransition().animateFloat(0.3f, 1.0f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse))` — ~1Hz (500ms ida + 500ms volta). `LaunchedEffect(actionCompleted)` chama `VibrationPlayer.stop()` junto com `AlarmSoundPlayer.stop()`.
- **Patterns to follow:** `infiniteRepeatable` já importado em `AlarmActivity.kt:12`. Cor `GtgPrimary` (acento azul `#2196F3`) — definida no `theme/Color.kt`. Padrão de `LaunchedEffect(actionCompleted)` em `AlarmActivity.kt:96-103`.
- **Test scenarios:**
  - **Covers AE3.** Som=OFF, Visual=ON, Vib=OFF: alarme abre AlarmActivity com fundo pulsando ~1Hz na cor azul; nenhum som; nenhuma vibração. Check para o pulse imediatamente.
  - **Covers AE4.** Som=ON, Visual=ON, Vib=ON: os três estímulos rodam simultaneamente; Snooze para todos.
  - Visual=OFF: fundo segue tema dark padrão; sem animação.
  - `actionCompleted` para vibração junto com som (mesma LaunchedEffect).
  - `Test expectation: visual verificável só manualmente; lógica de stop coverable via ViewModel test ou screenshot test se houver infra.`
- **Verification:** Manual com cada combinação de flags; observar pulse, vibração, som e o stop coordenado em Check/Skip/Snooze.

---

### Phase D — Intervalo estrito (Item 4)

#### U13. SessionPreferences: enum `intervalMode`

- **Goal:** Adicionar a chave `intervalMode` (`DYNAMIC` | `STRICT`) em `SessionPreferences`. Default `DYNAMIC` (preserva comportamento atual byte-for-byte).
- **Requirements:** R17, R20
- **Dependencies:** —
- **Files:**
  - `app/src/main/java/com/gtg/app/data/local/SessionPreferences.kt` — KEY + enum + getter retornando enum + setter aceitando enum + DEFAULT
- **Approach:** Persiste como String (`name` do enum). Enum `IntervalMode { DYNAMIC, STRICT }` declarado em `data/local/IntervalMode.kt` ou inline em `SessionPreferences.kt` — **não** em `domain/model/`. Razão: `IntervalMode` não cruza repository boundary (só é lido por SessionPreferences e passado como parâmetro ao `DynamicSchedulerUseCase`); promover para domain é over-modeling. Consistente com a KD do `dailySetTarget` (não promover para Room sem benefício). Getter retorna `IntervalMode` parseando a String com fallback para `DYNAMIC` em valor inválido.
- **Patterns to follow:** `activeDaysOfWeek` em `SessionPreferences.kt` (também persiste enum/set serializado como String).
- **Test scenarios:**
  - Default sem ter sido setado: `DYNAMIC`.
  - Após `setIntervalMode(STRICT)`, getter retorna `STRICT`.
  - Valor inválido na pref (corrupção): fallback `DYNAMIC`, não crasha.
  - `Test expectation: unit test SessionPreferences se já existir suíte; senão criar arquivo mínimo cobrindo este enum.`
- **Verification:** Build passa; chave aparece em `gtg_session.xml` com value String.

#### U14. Settings: radio "Dinâmico / Fixo" + texto auxiliar

- **Goal:** Expor o seletor de modo em Settings com label e texto auxiliar explicativo.
- **Requirements:** R18
- **Dependencies:** U13
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsViewModel.kt` — campo + setter
  - `app/src/main/java/com/gtg/app/presentation/settings/SettingsScreen.kt` — novo bloco "Modo de intervalo" com radio ou segmented control
  - `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — labels e descrição auxiliar
- **Approach:** Layout: header "Modo de intervalo", dois `RadioButton` ou `SegmentedButton` lado a lado: "Dinâmico (padrão)" e "Fixo (estrito dentro da janela)". Texto auxiliar abaixo do seletor (`Text` discreto): "Modo Fixo desliga descanso mínimo e desvio automático em blocos de inatividade — alarme pode tocar dentro de bloco."
- **Patterns to follow:** Para radios/segmented controls, verificar se já há precedente em `SettingsScreen.kt`; se não, usar `SegmentedButton` do Material3 (clean visualmente). Texto auxiliar tipo "helper text" abaixo de Switches já é padrão no app.
- **Test scenarios:**
  - Selecionar "Fixo" persiste `STRICT`; voltar a "Dinâmico" persiste `DYNAMIC`.
  - Default visual: "Dinâmico" selecionado.
  - Texto auxiliar visível em pt-BR e en.
  - `Test expectation: none -- UI; cobertura manual.`
- **Verification:** Manual — alternar, fechar app, reabrir, valor persiste.

#### U15. DynamicSchedulerUseCase: branch STRICT em `evaluateWithDependencies`

- **Goal:** Adicionar parâmetro `intervalMode` à API do use case e ramificar rules 3 e 4 conforme o modo. Manter rule 5, active days, windowStart.
- **Requirements:** R19, R20
- **Dependencies:** U13
- **Files:**
  - `app/src/main/java/com/gtg/app/domain/usecase/DynamicSchedulerUseCase.kt` — adicionar parâmetro `intervalMode: IntervalMode` em `calculateNextAlarm` e `evaluateWithDependencies`; branch as duas regras
  - `app/src/test/java/com/gtg/app/domain/usecase/DynamicSchedulerUseCaseTest.kt` — se existir, adicionar casos; senão criar
  - `app/src/main/java/com/gtg/app/domain/usecase/PreviewTodayRoutineUseCase.kt` — atualizar caller para passar `intervalMode`
- **Approach:** Conforme a sketch da HLD. Parâmetro com default `DYNAMIC` para não quebrar testes existentes. No corpo de `evaluateWithDependencies`:
  - **STRICT envolve em `if (intervalMode == DYNAMIC) { ... }`:**
    - Rule 3 — clamp de descanso mínimo (linhas `:165-168`)
    - Rule 4 — collision loop com `InactivityBlock` (linhas `:222-263`)
    - Rule 5 pós-colisão (linhas `:270-272`) — redundante em STRICT pois sem rule 4 o candidato não se move
  - **STRICT mantém INTOCADO (sempre aplica):**
    - WindowStart adjustment (linhas `:179-181`) — candidato antes da janela rola para o início
    - Active-days roll-forward (linhas `:189-191`) — candidato em dia inativo vai para próximo dia ativo
    - Rule 5 inicial (linhas `:198-200`) — candidato após fim da janela vai para próximo dia ativo
    - Cross-midnight return (linhas `:277-281`) — `Scheduled` vs `ScheduledTomorrow` baseado em data
- **Execution note:** Test-first para esta unit — escrever os casos STRICT antes de mexer no algoritmo. Cobertura forte aqui é a defesa contra regressão da algorítmica core.
- **Patterns to follow:** Estrutura atual do método, comentários inline detalhados explicando cada regra (manter esse estilo). Adicionar comentário de seção para "intervalMode branching" explicando o trade-off.
- **Test scenarios:**
  - **Covers AE7.** `STRICT`, baseInterval=45, checkTime=10:00, InactivityBlock 10:30–11:30 → retorna `Scheduled(10:45)` (dentro do bloco).
  - **Covers AE7.** Mesmo cenário com `DYNAMIC` → retorna candidato ajustado (10:25 antecipado ou 11:35 adiado conforme proximidade).
  - **Covers AE8.** `STRICT`, baseInterval=45, checkTime=17:55, window 08-18, dias úteis → retorna `ScheduledTomorrow(08:00 do próximo dia útil)`.
  - **Covers AE8.** `STRICT`, checkTime=11:00, sequência simulada de 12 Checks consecutivos: cada próximo cai em `lastCheck + 45` sem nenhum ajuste, até cruzar o fim da janela.
  - `STRICT` + Check atrasado (lastCheck=10:00, now=12:00, baseInterval=45): candidato = 10:45; já passou. Resultado depende se windowEnd cobre — se sim, retorna `Scheduled(10:45)` (no passado, o `AlarmManager` dispara imediatamente). Se passou o windowEnd, vai pro próximo dia. Documentar o comportamento explícito.
  - `STRICT` + check em dia inativo: rola pro próximo dia ativo (active days é orthogonal).
  - `DYNAMIC` baseline: todos os testes existentes do `DynamicSchedulerUseCaseTest` continuam passando.
  - **Covers AE7.** Verificar logging/print de candidato em STRICT vs DYNAMIC para o mesmo cenário com bloco — assertion na diferença.
- **Verification:** Suite de testes do scheduler passa; teste manual com `intervalMode=STRICT`, baseInterval=2min (para iterar rápido), confirmar cadência exata mesmo com `InactivityBlock` configurado e atravessado.

#### U16. HomeViewModel + callers: thread `intervalMode` em todos os caminhos de schedule

- **Goal:** Propagar `intervalMode` para todos os call sites que invocam `DynamicSchedulerUseCase.calculateNextAlarm` ou `evaluateWithDependencies`.
- **Requirements:** R19, R21, R22, R23
- **Dependencies:** U15
- **Files:**
  - `app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt` — `startSession`, `performManualCheck`, `rescheduleOnIntervalChange` (e quaisquer outros caminhos que cheguem ao scheduler)
  - `app/src/main/java/com/gtg/app/presentation/alarm/AlarmViewModel.kt` — `performCheck`, `performSkip` (Snooze NÃO toca o scheduler core — ver U16 KD abaixo)
  - `app/src/main/java/com/gtg/app/domain/usecase/PreviewTodayRoutineUseCase.kt` — passar `intervalMode` ao loop interno
  - Outros call sites encontráveis via `grep "calculateNextAlarm\|evaluateWithDependencies"` no momento da implementação
- **Approach:** Adicionar leitura de `sessionPrefs.intervalMode` em cada call site e passar como parâmetro. **Snooze não muda** — `AlarmViewModel.performSnooze` continua agendando `now + overshootRepeatMinutes` direto via `alarmScheduler.schedule`, sem passar pelo `DynamicSchedulerUseCase`. **Overshoot não muda** — re-alerta automático em `AlarmReceiver` continua agendando `now + overshootRepeatMinutes` direto. Ambos são independentes de `intervalMode` por design (KD).

  **Sub-unit U16a — Adicionar `setLastCheck` em `AlarmViewModel.performCheck`:** verificação contra o código atual revelou que `AlarmViewModel.performCheck` (`AlarmViewModel.kt:75-101`) NÃO grava `setLastCheck(now)`, apesar do learning `cadence-anchor-vs-reschedule-anchor-2026-05-19.md` indicar que "performCheck na full-screen também atualiza setLastCheck". O learning é aspiracional/desatualizado em relação ao código real. Para STRICT preservar a âncora corretamente após Check pela AlarmActivity, adicionar `sessionPrefs.setLastCheck(nowMillis)` logo após o `exerciseLogRepository.insert(...)` em `performCheck`. Sem isso, mid-session interval change após Check pela full-screen usaria o `lastCheck` antigo (startSession ou último `performManualCheck`), drift de cadência. **NÃO** adicionar em `performSkip` (skip ≠ check real) nem em `performSnooze` (já validado pelo learning).

  **NÃO precisa propagar `intervalMode` para:** `BootReceiver` (re-agenda direto a partir de `nextAlarmMillis` persistido), `HomeViewModel.rescheduleForNextDayKeepingExercise` (agenda direto a partir de `windowStart` do próximo dia), `AlarmReceiver` overshoot (`now + overshootRepeatMinutes` direto). Já estão insulados.
- **Patterns to follow:** Como o `activeDaysOfWeek` foi threadado (ver `HomeViewModel.kt`); padrão idêntico aqui.
- **Test scenarios:**
  - **Covers AE9.** STRICT, lastCheck=10:00, baseInterval=45, snooze às 10:45 com overshootRepeatMinutes=5: primary reagendado para 10:50, `lastCheck` PERMANECE em 10:00 (não é atualizado pelo snooze — ver KD do Snooze). Check em 10:50 atualiza `lastCheck=10:50`; próximo via scheduler STRICT = `10:50 + 45 = 11:35`.
  - `startSession` em STRICT: primeiro alarme = `now + baseInterval` exato.
  - `rescheduleOnIntervalChange` em STRICT: usa `lastCheck` como âncora, sem clamp e sem desvio de bloco.
  - Overshoot dispara conforme `overshootRepeatMinutes` em STRICT — independente do modo.
  - `Test expectation: unit tests dos ViewModels com fake DynamicSchedulerUseCase verificando que o intervalMode chega; tests E2E manuais.`
- **Verification:** Manual — sessão em STRICT, fazer Checks atrasados, snoozes e mudança de baseInterval mid-session; observar cadência preservada onde esperado.

---

## Key Technical Decisions

- **Phased delivery por item (A → B → C → D), ordem crescente de blast radius.** Cada fase é PR independente. Razão: 4 itens são funcionalmente independentes, e a ordem facilita revisão incremental — item 1 é trivial, item 4 toca o algoritmo central. Risco da última fase fica isolado dos demais. (Alternativa rejeitada: single PR — implementação simultânea em 4 subsistemas dificulta revisão e rollback parcial.)

- **Snooze NÃO atualiza `lastCheck` (corrige R23 do brainstorm).** O brainstorm afirmou que Snooze grava `lastCheck = now`, mas isso contradiz o aprendizado `docs/solutions/architecture-patterns/cadence-anchor-vs-reschedule-anchor-2026-05-19.md` que documenta o motivo de o Snooze ter sido revertido para NÃO gravar (commit 693575a). `lastCheck` é âncora de Check real, não de qualquer reagendamento. Em modo STRICT, snoozes consecutivos NÃO deslocam a âncora — o próximo Check real ainda re-âncora cadência em `lastCheck + N`. (Corrige interpretação do brainstorm; AE9 ainda passa, mas o passo intermediário "lastCheck=10:45" do brainstorm está errado.)

- **STRICT mantém active days, windowStart, e Rule 5 (windowEnd).** STRICT desliga apenas Rule 3 (clamp de descanso mínimo) e Rule 4 (desvio de colisão com `InactivityBlock`). Active days e fim de janela seguem aplicando. (Razão: alarme à noite, fora da janela, ou em dia inativo não é GtG — preserva integridade do produto; rule 5 foi explicitamente mantida no brainstorm.)

- **Snooze e overshoot automático são independentes de `intervalMode`.** Ambos agendam `now + overshootRepeatMinutes` direto via `alarmScheduler.schedule`, sem passar pelo `DynamicSchedulerUseCase`. Razão: são overrides conscientes do usuário (snooze) ou semântica de re-alerta (overshoot), conceitualmente ortogonais à cadência principal. (Alternativa rejeitada: snooze em STRICT = `lastCheck + N + overshoot` — viola intenção do botão "atrasa por X agora".)

- **At-least-one-ON é UI-side, não pref-side.** A validação acontece no `SettingsViewModel.toggleModality`. O storage de `SessionPreferences` aceitaria 3 OFFs (não há contraint físico) — é decisão de UX. Razão: se uma migração futura ou um bug bypassar a UI, a app não trava em estado quebrado — `AlarmReceiver` ainda recebe os flags como booleans e age conforme. Tradeoff: a invariante não é garantida pelo storage, só pela UI. (Alternativa rejeitada: clamping no SessionPreferences setter — adiciona acoplamento estranho e dificulta testes.)

- **`OnboardingHost` usa state interno (não NavController).** Step state vive como `mutableStateOf` dentro do Host. Razão: 3 steps lineares, sem deep-linking, sem back-stack relevante; NavController seria over-engineering. Skip global é trivial assim. (Alternativa rejeitada: integrar steps como rotas no `GtgNavHost` — requer expor rotas de onboarding mesmo após dismiss; complexidade desproporcional.)

- **Visual pulse usa `infiniteRepeatable` com `RepeatMode.Reverse`.** Já é o pattern usado em outras animações da própria `AlarmActivity` (`AlarmActivity.kt:9-13`). Fade 0.3 → 1.0 em 500ms + reverse de 500ms = ciclo de 1Hz. Razão: padrão idiomático do Compose; performance previsível em recompose. (Alternativa considerada: `Animatable` manual com loop suspend — equivalente mas mais código.)

- **VibrationPlayer é singleton (não @Inject).** Mesmo modelo do `AlarmSoundPlayer`. Razão: `AlarmReceiver` precisa chamá-lo do contexto do `BroadcastReceiver` (lifecycle curto, sem ViewModel); singleton com `start(context)/stop()` é o caminho idiomático em receivers Android. Hilt no Receiver continua útil para `SessionPreferences` (lifecycle aware) mas a player não precisa.

- **Daily target opcional preserva o valor — não migra para Room.** O `dailySetTarget` continua em `SessionPreferences`. Toggle OFF apenas oculta UI. Razão: zero migration, zero risk; o usuário religa e recupera o valor último. (Alternativa rejeitada: mover para Room ou remover a chave — não traz benefício e adiciona migration step.)

- **Onboarding strings em chaves dedicadas (`onboarding_*`).** Namespace separado em `strings.xml`. Razão: facilita manutenção/auditoria; futuras revisões do welcome copy ficam isoladas das demais strings do app. (Sem alternativa relevante.)

---

## Dependencies / Assumptions

- `AppCompatDelegate.setApplicationLocales` continua propagando per-app locale para os novos textos (já comprovado para os outros textos do app).
- `Vibrator.hasVibrator()` reflete fielmente a presença de hardware — devices sem vibrador silenciosamente ignoram U10/U11 (no-op).
- `VibratorManager.defaultVibrator` em API 31+ é compatível com `VibrationEffect.createWaveform(...)` (padrão Android documentado).
- `ActivityWindowRepository` e `ExerciseRepository` aceitam inserts simples sem coordenar com estado de sessão ativa (no first launch, sessão não está ativa).
- Recomposição do `MainActivity` ao mudar `hasSeenOnboarding` não causa flicker ou loss de estado de permissões — `PermissionGate` mantém estado após primeira execução.
- `SessionPreferences.bypassDnd` continua relevante apenas quando `soundEnabled = true`. Quando som está OFF, o canal de notificação ainda é escolhido por `bypassDnd`, mas como não há som, é silencioso de qualquer forma (sem efeito perceptível).
- Persistência de novos campos em `SessionPreferences` é compatível com backup/restore padrão de SharedPreferences (sem migration step necessário).
- `AlarmViewModel` já pode injetar `SessionPreferences` (verificar — provavelmente sim, dado que o brainstorm anterior já fazia coisas similares).

---

## Risk Analysis & Mitigation

- **Risco médio: `DynamicSchedulerUseCase` ganhar branching aumenta superfície de bugs no algoritmo central.** Mitigação: U15 marca `Execution note: Test-first` — escrever testes STRICT antes de tocar o método. Cobertura para os 5 cenários AE-mapped + cases-de-descontrole (Check atrasado, dia inativo).

- **Risco médio: race condition entre `vibrator.cancel()` e Activity destruction.** Se a `AlarmActivity` for destruída sem passar pelo `LaunchedEffect(actionCompleted)`, vibração pode continuar até o sistema cancelar pattern automaticamente. Mitigação: hook em `onDestroy()` da Activity chama `VibrationPlayer.stop()` defensivamente — mesmo pattern do `AlarmSoundPlayer.stop()` em U10/U12.

- **Risco baixo: usuário em modo STRICT com Checks atrasados pode receber alarme "no passado".** Se `lastCheck=10:00`, baseInterval=45 e usuário só abre o app às 12:00, próximo = 10:45 (passado). `AlarmManager.setAlarmClock` dispara imediatamente quando trigger time é passado. Comportamento aceitável (usuário queria estrito) mas pode surpreender. Mitigação: nenhuma — é exatamente o que "estrito" pede. Documentar explicitamente em comentário do U15 e no doc do `IntervalMode`.

- **Risco baixo: o pattern de vibração em devices sem amplitude control pode soar diferente.** API 26-30 não suporta amplitudes; o waveform será aplicado como ON/OFF binário. Aceitável.

- **Risco baixo: `PermissionGate` + `OnboardingHost` em sequência podem alongar percepção do startup.** Mitigação: Welcome step é leve (texto + 2 botões); user pode pular tudo em 1 tap.

- **Risco identificado e mitigado: divergência R23 (brainstorm) vs estado real do código.** Brainstorm afirma snooze grava `lastCheck`; learning documenta que NÃO grava. Plano corrige em U16 + KD do Snooze.

---

## System-Wide Impact

- **`SessionPreferences` cresce com 6 novas chaves** (`showDailyTarget`, `hasSeenOnboarding`, `soundEnabled`, `visualEnabled`, `vibrationEnabled`, `intervalMode`). Storage ainda é trivial; backup/restore continua funcionando.
- **`SettingsScreen` ganha 3 novos blocos** (toggle daily target, grupo modalidades de alerta, seletor de modo de intervalo). Layout precisa caber visualmente — verificar densidade após implementação.
- **`AlarmReceiver` e `AlarmActivity`** ganham branching por flags. Cuidar com a ordem para preservar o race fix do learning `alarm-receiver-overshoot-schedule-race-2026-05-19.md` (overshoot agendado antes do play).
- **`DynamicSchedulerUseCase`** ganha parâmetro novo; todos os call sites devem ser atualizados (U16). `grep` ao final da implementação para garantir cobertura — referência do learning `active-days-alarm-bypass-2026-05-16.md` (que documenta como 5 writers escaparam).
- **`MainActivity`** ganha uma camada extra de gate (`hasSeenOnboarding`) entre `PermissionGate` e `GtgNavHost`. Lifecycle precisa manter `collectAsStateWithLifecycle()` consistente.
- **Manifest** já tem `VIBRATE` permission declarada (`AndroidManifest.xml:25`); nenhuma mudança nesse arquivo.
- **`strings.xml` (values/ + values-pt-rBR/)** crescem com labels de onboarding (~8 chaves) + modalidades (3 chaves) + modo de intervalo (3 chaves) + daily target toggle (1 chave).

---

## Scope Boundaries

### Out of scope (carried from origin)

- Pattern de vibração configurável pelo usuário — fixo (planning escolhe valores).
- Presets ou perfis de combinação de alerta.
- Re-acessar onboarding pelo Settings.
- Pausa global de sessão.
- 4ª ação "Silenciar" na AlarmActivity.
- `MIN_BASE_INTERVAL` continua 20 mesmo em STRICT.
- `intervalMode` global, não por-Exercise.
- Welcome estático, sem tutorial interativo.
- Onboarding não cobre InactivityBlocks.
- Statistics / histórico permanecem como hoje.

### Deferred to Follow-Up Work

- Refactor de `SessionPreferences` para domain-layer abstraction (tem cheirinho de "preference repository"; cresceu bastante mas reescrever está fora de escopo).
- Migrar `dailySetTarget` para Room (não traz valor concreto; deferred indefinidamente).
- Adicionar telemetria de adoção de modo STRICT vs DYNAMIC (depende de infra de telemetria que não existe).
- Pattern de vibração configurável em Settings (se demanda futura aparecer).
- Re-acesso ao onboarding em Settings ("Ver tour novamente").

---

## Outstanding Questions

### Resolve Before Planning

Nenhuma — todas as decisões product foram fechadas no brainstorm.

### Deferred to Implementation

- **[Affects U6, U7][Technical]** Steps 2 e 3 do onboarding reusam `ScheduleScreen`/`ExercisesScreen` em modo embedded, ou widgets dedicados simplificados? Planning recomenda widgets dedicados (acoplamento menor); confirmar ao implementar.
- **[Affects U9][User decision]** Feedback visual quando "tudo OFF" é rejeitado: `SettingsScreen` hoje NÃO tem `Scaffold`/`SnackbarHost` — adicionar Snackbar requer wrap em Scaffold (refactor estrutural). Caminho recomendado pelo planning: **toggle resistente** (silenciosamente volta para ON sem snackbar), com helper text inline abaixo do header "Modalidades de alerta" explicando "Pelo menos uma modalidade precisa estar ativa". Evita refactor de layout.
- **[Affects U10][Technical]** Pattern exato de vibração: planning sugere `longArrayOf(0, 500, 250)` com `repeat = 0` (vibra 500, pausa 250, repete). Ajustável após teste em hardware real.
- **[Affects U12][Design]** **Stack do pulse vs gradient existente.** A `AlarmActivity` hoje renderiza `AlarmScreen` com `Box` de fundo usando `Brush.verticalGradient(GtgBackground, GtgSurface, GtgBackground)` (`AlarmActivity.kt:191-203`). Quando `Visual=ON`, o pulse de alpha em `GtgPrimary` precisa: opção (a) sobrepor o gradient existente como camada superior — escolha recomendada, simples, gradient permanece visível através do alpha pulsante; opção (b) substituir o gradient — perde visual atual. Planning recomenda (a): `Box` adicional como overlay TOPO antes do conteúdo da AlarmScreen, com `alpha = pulseAlpha` no `GtgPrimary.copy(alpha = 0.3f..1.0f * 0.4f)` (multiplicador 0.4 para não tornar conteúdo ilegível em alpha máximo). Texto/botões da AlarmScreen ficam por cima do overlay, então legibilidade preservada.
- **[Affects U4][Design]** **Back-press behavior no onboarding.** Sem `BackHandler` explícito, predictive back chama `finish()` da MainActivity — app fecha, `hasSeenOnboarding=false`, onboarding reaparece no próximo launch. Planning recomenda: **não adicionar BackHandler** — comportamento default é coerente (back sai sem persistir flag). Confirmar com primeiro teste manual; se necessário, adicionar `BackHandler` em Steps 2/3 que volta para step anterior (UX mais permissivo, mas mais código).
- **[Affects U6][Design]** Erro de validação no form Step 2 quando `horárioFim <= horárioInício`: planning recomenda desabilitar botão "Continuar" + texto auxiliar em cor de erro abaixo do `WheelNumberPicker`. Sem Toast/Snackbar (mesma razão que U9 — sem host).
- **[Affects U14][User decision/Design]** Componente + label do seletor de intervalMode. Planning recomenda `SegmentedButton` Material3 com labels **curtos** ("Dinâmico" / "Fixo") e descrição longa em `Text` auxiliar abaixo: "Modo Fixo desliga descanso mínimo e desvio de inatividade". Evita clipping em 360dp.
- **[Affects U5][Needs research]** Copy exato do Welcome — planning propõe rascunho baseado em `claude.md` seção 1; refinar com revisão linguística. **Review gate explícito**: revisar copy antes de mergear Phase B.

---

## Success Criteria

- Usuário consegue ligar/desligar daily target via Settings; valor persiste após restart; default OFF para todos.
- Em silencioso, Visual+Vibração funciona sem som; usuário pode voltar a Som puro sem perder configuração.
- Primeiro launch após install limpo mostra Welcome → Window → Exercise → Home; SkipAll em qualquer step funciona; segundo launch vai direto pra Home.
- Em STRICT, cadência `lastCheck + N` preservada com precisão; alarme toca dentro de `InactivityBlock` quando aplica; rule 5 (window end) continua ativando o roll-over para próximo dia ativo; DYNAMIC permanece sem regressão.
- Snooze em STRICT NÃO atualiza `lastCheck` — o próximo Check real é o que re-âncora a cadência.
- Todos os testes existentes do `DynamicSchedulerUseCaseTest` continuam passando.
- `ce-work` consegue executar cada fase sem precisar voltar para o plano ou o brainstorm — todas as decisões product/architectural estão capturadas.
