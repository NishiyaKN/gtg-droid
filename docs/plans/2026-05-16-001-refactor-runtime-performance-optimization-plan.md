---
status: completed
type: refactor
created: 2026-05-16
completed: 2026-05-16
---

# refactor: Otimizar performance runtime para experiência fluida

## Problem Frame

Usuário reporta que a UI "parece travada e lenta" em vários pontos de uso normal. Duas otimizações pontuais já foram aplicadas nesta sessão (commits `ced262a` — fade transition no NavHost; `fd29bca` — coalescing dos 4 observers do HomeViewModel; `2cd2eb9` — encurta transição para sensação snappy). O sintoma reduziu mas há vetores residuais que comprometem fluidez em primeiro-paint e durante interações.

Duas auditorias paralelas via Explore mapearam ~12 vetores. Deste plano em diante:
- **Mantemos no escopo:** wall-time de primeiro-paint nas telas Home/Statistics, RingtoneManager I/O no main thread em Settings, redundância de queries em `observeDailyStats`/`PreviewTodayRoutineUseCase`, primeiro layout custoso da Home (Column + verticalScroll forçando medir tudo upfront), competição entre `AnimatedContent` interno e o novo fade do NavHost, e o consumer de `SessionPreferences.observeChanges` no SettingsViewModel sem `conflate`.
- **Fora do escopo:** cold start time, baseline profiles, otimização de APK size, rework dos algoritmos de scheduling (Regras 1–5 do `DynamicSchedulerUseCase`), refatoração arquitetural mais ampla.

**Alvo de qualidade:** zero stutter perceptível em portrait 360dp em devices médios; primeiro-paint de Statistics e Schedule cair de ~14×latência sequencial Room para paralelo (~max-latência).

## Approach

Aplicar os 7 findings remanescentes em unidades de implementação enxutas, **sem rework arquitetural**. Cada unidade preserva o comportamento atual — são micro-otimizações pontuais (parallel `async` em queries, `withContext(Dispatchers.IO)` em I/O do Ringtone, `conflate()` em SharedPreferences flow, `LazyColumn` em vez de `Column + verticalScroll`).

Não vamos instrumentar Macrobenchmark agora. Os findings já têm causa raiz identificada (sequencial vs paralelo, on-main vs IO, scroll medindo tudo upfront). Medição empírica fica para validação pós-fix — `adb shell dumpsys gfxinfo` no device durante smoke.

## Key Technical Decisions

1. **Parallelizar via `coroutineScope { async }`, não `Dispatchers.Default`.** Room já roda em executor de IO; o ganho vem de **paralelizar wall-time** das chamadas suspend, não de mudar o dispatcher. SQLite serializa internamente, então o ganho real é 2–4× (não 14×) — ainda materialmente perceptível.

2. **`PreviewTodayRoutineUseCase`: pré-fetch das dependências per-date, não async paralelo no loop.** O algoritmo é sequencial por design (cada iteração depende do `previousAlarm`). O custo está em `calculateNextAlarm` re-buscando `window + manualBlocks + calendarBlocks` em cada uma das 12 iterações. Como o `referenceDate` muda raramente dentro do loop, podemos buscar uma vez no início e passar como parâmetro para uma variante interna do scheduler. Reduz de ~36 queries (12 × 3) para 3 queries totais.

3. **`HomeScreen` Column → LazyColumn.** Refactor com algum risco (pulse animations, AnimatedContent interno, RoutinePreviewCard que tem sua própria List). Mitigação: preservar `Modifier.scale(pulseScale)` no Card de countdown (Card vira `item { }` dentro do LazyColumn — keys estáveis evitam re-medir).

4. **`AnimatedContent` interno do HomeScreen: encurta para 120ms `Crossfade`.** Hoje `fadeIn(tween(300)) togetherWith fadeOut(tween(300))` empilha 300ms quando o usuário inicia/para sessão e ao mesmo tempo entra na Home pela BottomNav (140ms). Não removo porque a transição entre estados visualmente vale; só reduzo para 120ms e troco para `Crossfade` (API mais simples; mesmo efeito).

5. **`SettingsViewModel.resolveSoundTitle`: mover para `Dispatchers.IO` + cache em StateFlow.** `RingtoneManager.getRingtone(...).getTitle(context)` é I/O síncrono. Hoje é chamado dentro do `observeChanges()` collector toda emissão. Solução: lazy compute uma vez por URI distinta, manter cache `Map<String?, String>` no ViewModel.

---

## Implementation Units

### U1. HomeScreen: migrar Column + verticalScroll para LazyColumn

**Goal:** Eliminar o custo de medir todos os children no primeiro frame da Home.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/home/HomeScreen.kt`

**Approach:**
- Substituir o `Column(.fillMaxSize().verticalScroll(rememberScrollState()))` na raiz da `HomeScreen` por um `LazyColumn(contentPadding = …)`.
- Cada seção atual vira `item { }` (ou `items(...)` quando aplicável):
  - `item("daily_summary")` envolvendo `DailySummaryCard` (passa também a lista de breakdown como `items(breakdown, key = { it.exerciseId })` se quiser sub-otimização — opcional).
  - `item("animated_content")` envolvendo o `AnimatedContent` (vide U6 para o ajuste de duração).
  - `item("routine_preview")` envolvendo `RoutinePreviewCard` se `state.routinePreview.isNotEmpty()`.
  - `item("spacer_bottom")` para o `Spacer(height(24.dp))`.
- Manter o `Scaffold` por fora (BottomNav padding via `padding(padding)`).
- Manter `RoutinePreviewCard` internamente Column (lista pequena, `forEachIndexed`) — não migrar essa lista interna agora.

**Patterns to follow:** o `ScheduleScreen.kt` já usa `LazyColumn` com `contentPadding = PaddingValues(bottom = 80.dp)`. Espelhar o padrão.

**Test scenarios:**
- Test expectation: none — pure layout refactor, behavior preserved. Validar manualmente: scroll fluido em sessão ativa com 12 PreviewRows + 5 BreakdownRows; pulse de overdue continua animando; AnimatedContent ainda fade entre estados.

**Verification:** abrir Home em sessão ativa, scrollar até o fim, voltar para o topo. `adb shell dumpsys gfxinfo com.gtg.app | head -40` deve mostrar 99th percentile melhor que pré-fix.

---

### U2. StatisticsViewModel: paralelizar `loadStats()` com `async`

**Goal:** Reduzir o wall-time de 14 queries Room sequenciais para o máximo paralelizável (limitado por SQLite serializar writes, mas reads de tabela única paralelizam).

**Files:**
- `app/src/main/java/com/gtg/app/presentation/statistics/StatisticsViewModel.kt`

**Approach:**
- Em `loadStats()`, envelopar todas as queries em `coroutineScope { ... }`.
- Cada chamada `totalSetsBetween` / `totalRepsBetween` vira `async { ... }`. Coletar via `.await()` no final.
- O loop "last 7 days" vira `(0..6).map { dayOffset -> async { totalRepsBetween(...) } }.awaitAll()`.
- `getExerciseBreakdown` permanece sequencial após — depende dos totais? Verificar. Se não, paraleliza também.

**Patterns to follow:** Kotlin `coroutineScope { async { } }` padrão. Sem necessidade de timeouts ou cancellation explícita (escopo herda do `viewModelScope`).

**Test scenarios:**
- Test expectation: none — pure parallelization, results identical. Validar manualmente: abrir Statistics, valores corretos para today/week/month/breakdown.

**Verification:** `loadStats` executa em ~max(latências individuais) ao invés de soma. Confirmar via log de timing temporário (`measureTimeMillis { … }`) durante validação, remover antes de commitar.

---

### U3. PreviewTodayRoutineUseCase: pré-fetch de window + blocks per-date

**Goal:** Eliminar o re-fetch redundante de `ActivityWindow`, `InactivityBlocks` e `CalendarBlocks` dentro do loop de 12 iterações.

**Files:**
- `app/src/main/java/com/gtg/app/domain/usecase/DynamicSchedulerUseCase.kt`
- `app/src/main/java/com/gtg/app/domain/usecase/PreviewTodayRoutineUseCase.kt`

**Approach:**
- Adicionar variante interna `internal suspend fun calculateNextAlarmWithCache(...)` em `DynamicSchedulerUseCase` que aceita `prefetchedWindow`, `prefetchedBlocksByDate: Map<LocalDate, List<InactivityBlock>>` como parâmetros opcionais. Quando passados, **não** consulta repositórios; usa diretamente.
- A `calculateNextAlarm` pública existente delega para a variante com cache vazio (preserva API).
- Em `PreviewTodayRoutineUseCase.invoke`, antes do loop:
  - Buscar `window` uma vez.
  - Buscar `manualBlocks` e `calendarBlocks` para o `referenceDate` em paralelo (`coroutineScope { async + async }`).
  - Compor `blocksByDate: Map<LocalDate, List<InactivityBlock>>` com a entrada do `referenceDate`.
- Passar para `calculateNextAlarmWithCache` em cada iteração.
- Se a iteração rolar para `currentDate.plusDays(N)` (dia diferente), a cache não cobre → fallback transparente para o caminho com repository (caso raro pois a preview pára em `referenceDate`).

**Patterns to follow:** já existe semântica de `_prefetch_` análoga no `HomeViewModel.observeActivityWindow` que cacheia em state. Esta unidade traz isso para o domain layer.

**Test scenarios:**
- Test expectation: behavior preservation. Idealmente um unit test simples comparando `PreviewTodayRoutineUseCase.invoke(...)` antes/depois retorna lista idêntica para configurações conhecidas (window 09:00-18:00, intervalo 45min, sem blocks). Como o projeto não tem testes unitários hoje, adicionar UMA suite mínima em `app/src/test/java/com/gtg/app/domain/usecase/PreviewTodayRoutineUseCaseTest.kt` cobrindo: (a) preview sem blocks gera lista esperada; (b) preview com 1 inactivity block (12:00-13:00) pula o slot corretamente.

**Verification:** com Calendar integration ligada + 5 events no dia, abrir Home. Antes: ~36 queries (ver no Logcat com debug logging). Depois: 3 queries. Tempo de preview cair de ~200-500ms para <50ms em devices médios.

---

### U4. SettingsViewModel: RingtoneManager em IO + cache de títulos

**Goal:** Remover `RingtoneManager.getRingtone(...).getTitle(context)` do main thread; evitar re-resolver em cada emissão de `observeChanges`.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/settings/SettingsViewModel.kt`

**Approach:**
- Converter `resolveSoundTitle(uriString: String?)` em `private suspend fun resolveSoundTitle(uriString: String?): String = withContext(Dispatchers.IO) { ... }`.
- Adicionar `private val titleCache = mutableMapOf<String?, String>()` (ou `LinkedHashMap` com bounded size se preocupar com leak — não é o caso, ~5 URIs por usuário).
- Antes de chamar `resolveSoundTitle`, checar cache. Se hit, usar direto sem launch.
- O collector de `observeChanges` (linhas 106-126) hoje é sync no update do state. Refatorar: ler a URI, ler do cache se possível, senão `launch { val title = resolveSoundTitle(uri); _state.update { it.copy(alarmSoundTitle = title) } }`.
- Validar que durante o gap (URI nova, ainda computando), o state mantém o título anterior — UX aceitável já que mudança de ringtone é interação consciente.

**Test scenarios:**
- Test expectation: none — pure IO offload + cache. Validar manualmente: trocar ringtone 5x via picker, retornar, ringtone correto exibido sem stutter no SettingsScreen.

**Verification:** abrir Settings em portrait estreita; tela renderiza imediatamente. Trocar som, sem perceber lag na atualização do title.

---

### U5. HomeViewModel.observeDailyStats: paralelizar 3 queries

**Goal:** Reduzir o wall-time do recálculo de stats diárias em cada Room emit.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/home/HomeViewModel.kt`

**Approach:**
- O `collectLatest` em `observeDailyStats()` (linha ~265-281) hoje:
  ```
  val sets = exerciseLogRepository.totalSetsBetween(today, today)
  val reps = exerciseLogRepository.totalRepsBetween(today, today)
  val breakdown = getExerciseBreakdown(today, today)
  ```
  Três suspend sequenciais.
- Refatorar para `coroutineScope { val setsDef = async {...}; val repsDef = async {...}; val breakdownDef = async {...}; Triple(setsDef.await(), repsDef.await(), breakdownDef.await()) }`.
- Manter o `.update` final exatamente igual.

**Patterns to follow:** mesmo padrão de U2 e U3.

**Test scenarios:**
- Test expectation: none — paralelização preserva resultado. Validar: ao fazer Check de exercício, dashboard da Home atualiza sets/reps/breakdown sem perceber lag.

**Verification:** comportamento idêntico, latência menor. Sem regressão visual.

---

### U6. HomeScreen.AnimatedContent: encurtar para 120ms

**Goal:** Evitar que o fade interno de 300ms entre estados (NO_EXERCISE/IDLE/COUNTDOWN) compite com o fade de 140ms do NavHost quando o usuário entra na Home pela BottomNav em sessão recém-iniciada.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/home/HomeScreen.kt`

**Approach:**
- Linha ~111-136 (`AnimatedContent(targetState = resolveScreenState(state), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, …)`).
- Substituir por `Crossfade(targetState = resolveScreenState(state), animationSpec = tween(120), label = "home_content_state") { screenState -> when(screenState) { ... } }`.
- `Crossfade` é equivalente semântico (fade in novo + fade out antigo) e tem API mais simples.
- 120ms está abaixo do limiar onde dois fades empilhados (NavHost 140ms + estado interno 120ms) parecem "lento" — sobrepostos somam ~200ms percebidos, dentro do snappy.

**Test scenarios:**
- Test expectation: none — animação cosmética. Validar: Start session na Home, transição IDLE → COUNTDOWN visualmente fluida e rápida.

**Verification:** Start/Stop session 5x sem trocar de tela. Cada transição interna < 150ms perceptual.

---

### U7. SettingsViewModel + ScheduleViewModel: `conflate()` no observeChanges

**Goal:** Aplicar o mesmo padrão de coalescing já adicionado ao `HomeViewModel.observeSessionPreferences` (commit `fd29bca`) nos outros dois consumers de `SessionPreferences.observeChanges`.

**Files:**
- `app/src/main/java/com/gtg/app/presentation/settings/SettingsViewModel.kt`
- `app/src/main/java/com/gtg/app/presentation/schedule/ScheduleViewModel.kt`

**Approach:**
- Em ambos os ViewModels, localizar o `sessionPrefs.observeChanges().collect { ... }`.
- Adicionar `.conflate()` antes do `.collect`.
- Import `kotlinx.coroutines.flow.conflate`.

**Patterns to follow:** `HomeViewModel.observeSessionPreferences` (commit `fd29bca`) — comentário inline já explica o motivo, copiar versão resumida.

**Test scenarios:**
- Test expectation: none — fan-in coalesce sem mudança semântica. Validar: trocar configs em rajada (toggle 5 switches rapidamente) — UI atualiza ao final sem flicker.

**Verification:** comportamento idêntico, menos ciclos de coleta durante bursts de escrita em SharedPreferences.

---

## Scope Boundaries

### In scope
- Os 7 implementation units acima.
- Validação manual de regressão visual + smoke `adb dumpsys gfxinfo`.

### Out of scope
- Cold start launch time (requer Macrobenchmark module + Baseline Profiles).
- APK size reduction.
- Rework arquitetural de Clean Architecture / MVVM.
- Reescrita do `DynamicSchedulerUseCase` (regras 1-5 são intocadas).
- Caching de longo prazo (memória / disk persistente) para preview ou stats — só pré-fetch dentro de uma invocação.

### Deferred to Follow-Up Work
- **Macrobenchmark module:** adicionar `:app:macrobenchmark` module com FrameMetrics + Baseline Profile gen. Permite mensurar cold start e tab switches consistentemente. Justifica plano próprio.
- **AlarmActivity full-screen animation:** se reclamação persistir sobre a transição app → AlarmActivity, override `Activity.overrideActivityTransition`.
- **HomeViewModel.observeDailyStats: debounce em rajada de logs:** se múltiplos Checks consecutivos disparam re-stats, adicionar `.debounce(100ms)` no flow source.

## System-Wide Impact

- **Domain layer:** `DynamicSchedulerUseCase` ganha uma variante interna com cache (U3). Public API preservada.
- **Data layer:** intocada (DAOs e repositories permanecem iguais).
- **Presentation:** mudanças em 5 ViewModels e 1 Screen.
- **Build/CI:** sem mudança em build files.
- **Test:** opcionalmente uma nova suite em `app/src/test/...` para U3 (sem isso, o projeto continua sem testes unitários).

## Risks

1. **U1 (LazyColumn migration):** risco de regressão visual no scroll/animations. Mitigação: testar pulse overdue + AnimatedContent em sessão ativa antes de commitar. Se quebrar, reverter unit isolado (commit atômico por unit).
2. **U3 (pré-fetch variant):** se a iteração rolar para outro dia (edge case raro: candidate cruzou meia-noite), o cache pode ficar desatualizado. Mitigação: fallback transparente para o caminho público de `calculateNextAlarm` quando `prefetchedBlocksByDate` não cobre a data atual.
3. **U4 (Ringtone IO cache):** se URI gravada em prefs for inválida (deleção do som do device), o cache pode persistir um título stale. Mitigação: invalidar cache no `setAlarmSound(uri)` (chamar `titleCache.clear()` ou re-resolver).
4. **U2/U5 (async paralelo SQLite):** SQLite serializa internamente em writes; reads paralelizam. Para queries puramente read (caso aqui), o ganho é real. Risco operacional: zero.

## Verification

Após cada unit (commits atômicos):

1. **Build:** `./gradlew :app:compileDebugKotlin` deve passar sem warnings novos.
2. **Smoke por unit:**
   - U1: Home scrolla, pulse anima, AnimatedContent transita.
   - U2: Statistics carrega valores corretos.
   - U3: Home com Calendar integration mostra preview consistente.
   - U4: Settings troca ringtone, title atualiza.
   - U5: Check de exercício atualiza dashboard.
   - U6: Start session na Home — transição rápida.
   - U7: trocar configs em rajada não regride.

3. **Após todos os fixes — métrica empírica:**
   ```
   adb shell dumpsys gfxinfo com.gtg.app reset
   # navegar Home → Statistics → Schedule → Settings → Home, 5×
   adb shell dumpsys gfxinfo com.gtg.app | head -80
   ```
   Esperado: "Janky frames" cai, 90/95/99 percentil melhora.

4. **Validação de UX:**
   - Tab switches: fade 140ms sem stutter.
   - Statistics: primeiro paint dos summary cards <100ms após arrival.
   - Home com sessão ativa: pulse + countdown não dropam frames.
   - Settings: ringtone title aparece sem perceptível delay ao abrir.

## Execution Order

Sequência sugerida (todos independentes — podem ser commits separados em ordem dependency-free):

1. U7 (`conflate`) — menor risco, ganho fácil em 2 arquivos.
2. U6 (Crossfade) — cosmético, isolado.
3. U5 (paralelizar dailyStats) — padrão simples.
4. U2 (paralelizar Statistics) — mesmo padrão, maior arquivo.
5. U4 (Ringtone IO) — toca lifecycle de prefs, vale isolar.
6. U3 (pré-fetch preview) — toca domain layer, maior risco.
7. U1 (LazyColumn) — maior risco de regressão visual; deixar por último para validar isolado.
