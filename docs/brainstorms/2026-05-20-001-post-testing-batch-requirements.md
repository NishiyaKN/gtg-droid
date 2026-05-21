---
date: 2026-05-20
topic: post-testing-batch
---

# Lote pós-teste — meta opcional, modalidades de alerta, onboarding, intervalo estrito

## Summary

Quatro melhorias destiladas do uso real do app: o `dailySetTarget` deixa de ser exposto por default (toggle opcional em Settings, default OFF), modalidades de alerta passam a ser três toggles independentes (Som / Visual / Vibração) com combinatória livre, primeiro launch ganha onboarding em três steps com Skip global, e o `DynamicSchedulerUseCase` ganha um modo "Estrito dentro da janela" onde `next = lastCheck + N` ignora rule 3 e rule 4 mas mantém rule 5.

---

## Problem Frame

Durante uso continuado, surgiram quatro atritos independentes no app:

1. **Meta diária ocupa espaço sem agregar valor para o usuário atual.** O card "Daily Summary" da Home pressiona um número arbitrário ("X/10") que não corresponde ao modelo mental do método GtG ("treino quando der ao longo do dia"). A barra de progresso e a transição de cor para `GtgSuccess` não estão sendo usadas como sinal útil.

2. **Alarme sonoro nem sempre é apropriado.** Em reuniões, ambientes silenciosos, ou perto de outras pessoas, não há forma de receber o alerta sem som. Hoje existe som (com respeito a `bypassDnd`) e a tela ascende via `setTurnScreenOn`, mas sem chamar atenção visualmente nem vibrar. Sem essa flexibilidade, o usuário ou aceita o som ou silencia o aparelho e perde o alerta.

3. **Primeiro launch despeja o usuário na Home vazia.** Sem `ActivityWindow` configurada, o `DynamicSchedulerUseCase` retorna `NoWindowConfigured` e nada toca; sem `Exercise` cadastrado, idem. Um usuário novo não recebe nenhuma pista do que precisa fazer para o app começar a funcionar; sequer sabe o que é GtG.

4. **Cadência dinâmica não respeita o intervalo configurado em alguns cenários.** Quando `lastCheck + N` cai dentro de um `InactivityBlock`, o agendamento desvia em ±5min (rule 4); quando cai antes de `now + MINIMUM_REST_MINUTES`, clampa em now+20min (rule 3). Em alguns casos o usuário quer cadência exata — "se eu pedi 45 min, quero 45 min" — sem decisões automáticas embutidas pelo scheduler.

---

## Requirements

**Meta diária (Item 1)**

- R1. Settings ganha um toggle "Mostrar meta diária" persistido em `SessionPreferences` (nova chave booleana). Default `false`.
- R2. Quando o toggle está `false`, o card "Daily Summary" da `HomeScreen` não renderiza — nem barra de progresso, nem contador, nem texto de meta. Quando `true`, renderiza igual ao comportamento atual.
- R3. Quando o toggle está `false`, o campo numérico de `dailySetTarget` em Settings também é ocultado. Quando `true`, fica editável (como hoje). O valor persistido de `dailySetTarget` NÃO é apagado ao desligar o toggle — preserva-se para o caso de religar.
- R4. Usuários existentes (com `dailySetTarget` já configurado) recebem `false` como valor inicial do novo toggle — não preserva quem tinha o card visível. Coerente com o sinal "desnecessário".

**Modalidades de alerta (Item 2)**

- R5. Settings substitui o atual bloco de "Som do alarme" por três toggles independentes: "Som", "Visual" e "Vibração". Persistem em `SessionPreferences` (três novas chaves booleanas). Defaults: Som `true`, Visual `false`, Vibração `false` — preserva comportamento atual byte-for-byte para quem não mexer em Settings.
- R6. UI valida em runtime que pelo menos uma das três modalidades está ON. Tentar desligar a última modalidade ativa não tem efeito: o toggle volta visualmente para ON e o estado persistido não muda. UI sinaliza que pelo menos uma é obrigatória.
- R7. Quando "Som" está ON, o `AlarmActivity` dispara o `AlarmSoundPlayer` como hoje (URI de `alarmSoundUri`, respeito a `bypassDnd`). Quando OFF, o player não toca em nenhum dos disparos.
- R8. Quando "Visual" está ON, a `AlarmActivity` aplica um pulse de alpha no fundo entre 0.3 e 1.0 na cor de acento `#2196F3`, frequência ~1Hz, enquanto o alarme está pendente. Quando OFF, o fundo segue o tema dark padrão (`#121212`). O pulse para com Check, Skip ou Snooze e ao fechar a Activity.
- R9. Quando "Vibração" está ON, o dispositivo vibra em pattern repetido (definido em planning, ex: 500ms ON / 250ms OFF) enquanto o alarme está pendente. Para com as mesmas transições que param o pulse.
- R10. Permission `android.permission.VIBRATE` declarada no Manifest (install-time, sem runtime grant em Android 26+).
- R11. Os três toggles aplicam-se igualmente a alarme primary e a re-alerta automático (overshoot). Não há configuração separada por tipo de disparo.

**Onboarding de primeiro launch (Item 3)**

- R12. `SessionPreferences` ganha uma flag `hasSeenOnboarding` (default `false`). É setada para `true` ao final do onboarding, tanto pelo caminho "completar" quanto pelo caminho "pular tudo".
- R13. `MainActivity.onCreate`, depois do `PermissionGate` existente, verifica `hasSeenOnboarding`. Se `false`, navega para o fluxo de onboarding antes da Home; se `true`, vai direto para Home (comportamento atual).
- R14. Onboarding tem três steps sequenciais:
  - **Step 1 — Welcome:** título "Bem-vindo ao GtG" + parágrafo curto explicando GtG (Grease the Groove: séries submáximas distribuídas ao longo do dia para acumular volume com baixa fadiga; o app alerta nos horários certos respeitando sua janela e descansos). Botões "Continuar" (avança para Step 2) e "Pular tudo" (encerra o onboarding).
  - **Step 2 — ActivityWindow:** explicação curta + UI para criar a primeira `ActivityWindow` (horário início, fim, dias da semana ativos). Botões "Continuar" (persiste a Window via repositório e avança) e "Pular" (avança sem salvar). 
  - **Step 3 — Exercise:** explicação curta + UI para adicionar o primeiro `Exercise` (nome + target reps). Botões "Concluir" (persiste, marca `hasSeenOnboarding=true`, navega para Home) e "Pular" (encerra sem salvar).
- R15. O botão "Pular tudo" (Step 1) e cada "Pular" individual (Steps 2 e 3) marcam `hasSeenOnboarding=true` e navegam para a Home — o estado da Home pode ficar vazio se nada foi configurado (mesmo comportamento de hoje para quem não tem Window/Exercise).
- R16. Todos os textos do onboarding (título, explicação GtG, labels de step, labels de botão) entram em `strings.xml` (pt-BR) e `strings.xml` em `values-en/` (en), sem hardcode. Locale resolvido via `LocalConfiguration.current.locales[0]` em Composables.

**Modo de intervalo estrito (Item 4)**

- R17. `SessionPreferences` ganha uma chave `intervalMode` (enum persistido como String: `DYNAMIC` / `STRICT`, default `DYNAMIC`).
- R18. Settings ganha um seletor (radio ou segmented control) entre "Dinâmico (padrão)" e "Fixo (estrito dentro da janela)". Texto auxiliar curto explica que o modo "Fixo" sacrifica descanso mínimo e desvio automático em blocos de inatividade.
- R19. Quando `intervalMode = STRICT`, `DynamicSchedulerUseCase.calculateNextAlarm(checkTime, baseInterval)` retorna `lastCheck + baseInterval` exato — ignora `MINIMUM_REST_MINUTES` (rule 3) e a verificação de colisão com `InactivityBlock` (rule 4). Mantém rule 5: se o resultado cai depois do fim da `ActivityWindow`, agenda para o início da janela do próximo dia ativo (via lógica existente).
- R20. Quando `intervalMode = DYNAMIC` (default), o comportamento atual é preservado integralmente — as cinco regras aplicam como hoje.
- R21. O recálculo dinâmico mid-session (mudança de `baseInterval` com sessão ativa, via `HomeViewModel.rescheduleOnIntervalChange`) usa a mesma semântica do modo corrente. Em `STRICT`, recálculo também ignora rules 3 e 4.
- R22. O re-alerta automático (overshoot) é independente de `intervalMode` — continua disparando a cada `overshootRepeatMinutes` enquanto o alarme primary está em overdue, em qualquer modo.
- R23. Snooze (pelo botão da `AlarmActivity`) mantém sua semântica atual em ambos os modos — cancela overshoot, reagenda primary para `now + overshootRepeatMinutes`, atualiza `lastCheck` para now. Em modo `STRICT` isso é um override consciente que quebra a cadência `lastCheck + N` para o ciclo onde foi acionado; cadência estrita se restaura no próximo Check real, ancorada no novo `lastCheck`. Snoozes consecutivos deslocam progressivamente o âncora — efeito esperado.

---

## Acceptance Examples

- AE1. **Covers R2, R4.** Após upgrade do app, usuário existente com `dailySetTarget=10` configurado abre a Home: nenhum card "Daily Summary" aparece (toggle foi inicializado em `false`). Vai em Settings, ativa "Mostrar meta diária": card volta a aparecer mostrando "0 / 10".

- AE2. **Covers R6.** Estado inicial: Som=ON, Visual=OFF, Vibração=OFF. Usuário tenta desligar o toggle "Som". A UI não permite a transição; o toggle volta visualmente para ON; o estado persistido permanece Som=ON. Usuário liga "Vibração", depois consegue desligar "Som" (agora Vibração é a modalidade ativa restante).

- AE3. **Covers R8.** Som=OFF, Visual=ON, Vibração=OFF. Alarme dispara: `AlarmActivity` abre com o fundo pulsando alpha 0.3↔1.0 a ~1Hz na cor `#2196F3`. Nenhum áudio toca, nenhuma vibração. Usuário aperta Check: o pulse para imediatamente e a Activity fecha.

- AE4. **Covers R9, R11.** Som=ON, Visual=ON, Vibração=ON. Alarme primary dispara: som toca, fundo pulsa, dispositivo vibra simultaneamente. Usuário não faz Check; overshoot dispara em `overshootRepeatMinutes`: as três modalidades reaparecem juntas. Snooze interrompe todas as três no mesmo instante.

- AE5. **Covers R13, R15.** Primeiro launch após install: depois do `PermissionGate`, app abre direto no Step 1 do onboarding (não na Home). Usuário aperta "Pular tudo": `hasSeenOnboarding=true`, navega para Home (vazia). Próximo launch abre direto na Home — onboarding não reaparece.

- AE6. **Covers R14.** Usuário completa os três steps em sequência: cria `ActivityWindow` 08:00–18:00 em dias úteis, adiciona `Exercise` "Push-up" com target 10 reps, aperta "Concluir". `hasSeenOnboarding=true`, Home abre com a Window e o Exercise já persistidos no DB — pronto para iniciar sessão.

- AE7. **Covers R19, R20.** Cenário: `baseInterval=45`, `lastCheck=10:00`, `InactivityBlock` configurado 10:30–11:30. Com `intervalMode=STRICT`, próximo alarme agendado para 10:45 exato — dispara dentro do bloco. Mesmo cenário com `intervalMode=DYNAMIC` (default): rule 4 antecipa para 10:25 (`início do bloco - 5min`, está a 30min do bloco → antecipa) ou adia para 11:35 — comportamento atual preservado.

- AE8. **Covers R19.** Cenário: `intervalMode=STRICT`, `baseInterval=45`, `lastCheck=08:00`, `ActivityWindow=08:00–18:00` em dias úteis. Sequência de alarmes: 08:45, 09:30, 10:15, ..., 17:15. Próximo seria 18:00 = fim de janela: rule 5 agenda para 08:00 do próximo dia útil (não 18:00 do mesmo dia).

- AE9. **Covers R23.** Cenário: `intervalMode=STRICT`, `lastCheck=10:00`, próximo alarme 10:45 (com `baseInterval=45`). Alarme dispara 10:45. Usuário aperta Snooze com `overshootRepeatMinutes=5`. Resultado: primary reagendado para 10:50, `lastCheck=10:45`. Em 10:50 alarme toca de novo; usuário faz Check: `lastCheck=10:50`, próximo alarme 11:35 — cadência `lastCheck + 45` restaurada (ancorada no novo lastCheck).

---

## Success Criteria

- Usuário consegue desligar a meta diária e parar de ver o card; consegue religar e o último valor configurado de `dailySetTarget` reaparece intacto.
- Em ambiente silencioso, usuário usa Visual+Vibração (Som=OFF) e o alarme é perceptível sem áudio; consegue voltar para Som puro a qualquer momento sem perder estado de configuração.
- Usuário novo, na primeira abertura, lê uma explicação coerente de GtG, e ou conclui o setup mínimo (Window + Exercise) ou pula tendo lido o Welcome.
- Em modo estrito, alarmes ocorrem em `lastCheck + N` exato durante a janela, mesmo cruzando blocos de inatividade. Em modo dinâmico (default), comportamento atual é preservado byte-for-byte — sem regressão.
- `ce-plan` consegue propor implementação dos quatro itens sem precisar re-perguntar: defaults, semântica de cada toggle, sequência do onboarding, e quais regras o modo estrito desliga.

---

## Scope Boundaries

- **Pattern de vibração não é configurável pelo usuário** — fixo no app (planning escolhe valores). Customização de duração / intensidade fica fora.
- **Sem presets ou perfis de combinação de modalidades** (ex: "Trabalho", "Noturno"). Usuário configura os três toggles diretamente.
- **Sem re-acessar onboarding pelo Settings** — once dismissed, dismissed. Reaparece só apagando dados do app.
- **Sem pausa global da sessão** — alternativa "silenciar como pausa de sessão" foi rejeitada explicitamente; Snooze cobre pausas pontuais.
- **Sem 4ª ação "Silenciar" na `AlarmActivity`** — Snooze cobre o caso de "parar agora sem resolver".
- **`MIN_BASE_INTERVAL=20` permanece como lower bound do slider em ambos os modos** — modo estrito não destrava intervalos < 20min.
- **`intervalMode` é global por instalação** — não há override por exercício, por dia, ou por janela.
- **Welcome é estático** — sem tutorial interativo, sem link para vídeo, sem tour avançado dentro do app.
- **Onboarding não cobre `InactivityBlock` nem outras configs avançadas** — usuário descobre pelo Schedule.
- **Statistics / histórico não mudam** — daily target opcional não altera o cálculo de `StatisticsViewModel`.
- **Daily target permanece em `SessionPreferences`** — não migrar para Room só por causa do toggle.
- **Sem mudança no `overshootRepeatMinutes`** ou no slider 1–15min existente.

---

## Key Decisions

- **Daily target opcional em vez de remover.** Preserva código já testado, evita migração destrutiva. Default OFF universal respeita o sinal "desnecessário" sem fechar a porta para quem queira ligar. Alternativa rejeitada: remover por completo. Motivo: custo de migração > benefício; configuração reversível é mais barata.

- **Existing users também migram para OFF.** Coerente com "desnecessário"; o valor persistido continua no `SessionPreferences`. Alternativa rejeitada: preservar visibilidade para quem já tinha. Motivo: divide usuários sem ganho concreto.

- **Três toggles independentes em vez de três modos exclusivos.** Decisão revisada no Phase 2.5. Combinatória livre dá 7 combinações úteis e cada modalidade é uma capability separada no código (mais fácil de testar e iterar). Alternativa rejeitada: radio de três modos (Som / Visual / Som+Visual). Motivo: limita combinações úteis como Visual+Vibração sem Som.

- **Defaults preservam comportamento atual.** Som ON, Visual OFF, Vibração OFF — usuário existente não nota mudança alguma até abrir Settings.

- **UI bloqueia "tudo OFF" em vez de permitir alarme silencioso.** Alarme sem modalidade é alarme inútil — preserva sanidade. Alternativa rejeitada: permitir "tudo OFF" como pausar alarmes. Motivo: a pausa pontual já é Snooze, e a global é não iniciar sessão.

- **Pulse suave (alpha fade) em vez de strobe alto contraste.** Escolha estética do usuário. Aceita-se o trade-off de eficácia menor em Visual puro — usuário que precise de mais atenção combina com Vibração ou Som. Alternativa rejeitada: strobe preto↔branco a 2Hz. Motivo: agressivo demais visualmente.

- **Visual e Vibração aplicam também ao overshoot.** Cada disparo do alarme usa as três modalidades configuradas. Alternativa rejeitada: configurar overshoot separadamente. Motivo: complexidade extra sem demanda; modelo mental "modalidades são propriedades do alerta, não do tipo de disparo".

- **Onboarding em 3 steps com Skip global.** Escolha do usuário sobre 3 obrigatórios, 3 com skip por step, e 4 steps com `InactivityBlock`. Skip global = rede de segurança que não fragmenta a decisão por step.

- **`hasSeenOnboarding` é flag explícita, não derivada.** Inferir "novo usuário" a partir de contagem de Windows/Exercises no DB seria implícito e quebra se usuário apagar tudo manualmente. Flag dedicada em `SessionPreferences` é robusta.

- **Modo "Estrito dentro da janela" em vez de "Puro estrito" ou "Grid fixo".** Escolha do usuário. Preserva rule 5 (fim de janela → próximo dia ativo) por integridade do produto — alarme à noite ou de madrugada não é GtG. Alternativa rejeitada: puro estrito sem rule 5. Motivo: alarme fora da janela hostiliza o uso real.

- **Snooze mantém `now + overshootRepeatMinutes` em modo estrito.** Snooze é override consciente do usuário ("atrasa por X minutos agora"); submetê-lo ao estrito violaria a intenção do botão. Snoozes consecutivos deslocam progressivamente o âncora — efeito esperado e auditável via AE9. Alternativa rejeitada: snooze em estrito = `lastCheck + N + overshoot`. Motivo: comportamento contraintuitivo, snooze deixa de ser snooze.

- **`intervalMode` é global, não por-Exercise.** Configuração única em Settings. Alternativa rejeitada: override por exercício. Motivo: complexidade combinatória sem demanda concreta.

---

## Dependencies / Assumptions

- `AppCompatDelegate.setApplicationLocales` continua propagando o per-app locale para os textos do onboarding e dos novos toggles (mesmo mecanismo do lote 2026-05-19).
- `Vibrator` / `VibratorManager`: `minSdk=26` exige path duplo — API 31+ via `Context.getSystemService(VibratorManager::class.java).defaultVibrator`, e 26–30 via `Context.getSystemService(VIBRATOR_SERVICE) as Vibrator`. Planning decide encapsulamento (provavelmente um `VibrationPlayer` simétrico a `AlarmSoundPlayer`).
- O fundo da `AlarmActivity` hoje usa o tema Compose com `MaterialTheme.colorScheme.background` (`#121212`). O pulse de alpha precisa ser implementado sem conflitar com os botões Check/Skip/Snooze (sobrepor por `Box` com `alpha` animado, ou animar a cor de fundo do container). Assumido implementável sem refactor estrutural da Activity.
- `intervalMode=STRICT` não altera o range válido de `baseInterval` (`MIN=20`, `MAX=240`).
- Persistência das novas chaves em `SessionPreferences` é compatível com backup/restore atual (SharedPreferences padrão; sem migration step necessário).
- Componentes do `ScheduleScreen` (criação de Window) e do `ExercisesScreen` (criação de Exercise) podem ser reusados em modo embed dentro do onboarding, ou — se acoplamento atual não permitir — substituídos por widgets dedicados simplificados nos steps 2 e 3. Decisão concreta fica para planning.
- Todos os novos textos (Settings + onboarding + auxiliares dos modos) entram em `strings.xml` pt-BR + en, sem hardcode — segue infra existente.
- `AlarmReceiver` precisa ler as 3 flags de modalidade antes de chamar `AlarmSoundPlayer` ou disparar vibração. Assumido injetável via `@Inject SessionPreferences` no Receiver (Hilt) como já é feito para `AlarmScheduler`.

---

## Outstanding Questions

### Resolve Before Planning

Nenhuma — todas as decisões de produto foram fechadas no diálogo.

### Deferred to Planning

- **[Affects R14][Technical]** Steps 2 e 3 do onboarding reusam `ScheduleScreen`/`ExercisesScreen` em modo "single item" ou usam widgets dedicados simplificados? Decisão baseada em viabilidade de extração dos componentes existentes sem refactor profundo.
- **[Affects R8][Technical]** Pulse de alpha implementado via `Animatable` + `drawBehind` ou via `Box` sobreposto com `alpha` animado? Trade-off de performance de recompose vs simplicidade.
- **[Affects R9][Technical]** Pattern de vibração específico (durações em ms) — usuário não especificou. Planning escolhe padrão razoável (sugestão inicial: 500ms ON / 250ms OFF, repetido) e documenta. Considerar `VibrationEffect.createWaveform` com `repeat=0`.
- **[Affects R6][User decision]** UX exata de "este toggle não pode desligar": apenas resistir à interação, mostrar tooltip / snackback, ou desabilitar visualmente o toggle quando é o único ON? Decisão durante implementação com referência visual.
- **[Affects R18][User decision]** Componente da escolha de `intervalMode` — radio vertical, segmented control horizontal, ou switch + texto? Pequeno mas afeta hierarquia visual de Settings.
- **[Affects R14][Needs research]** Texto exato do Welcome explicando GtG — copy precisa ser claro em uma tela, em pt-BR e en. Planning pode propor primeiro draft a partir do conteúdo do `claude.md` (seção 1).
