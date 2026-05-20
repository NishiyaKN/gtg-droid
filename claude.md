# Contexto do Projeto: App GtG (Grease the Groove)

## 1. Visão Geral
Este é um aplicativo nativo para Android (foco em Android 16) projetado para o método de treinamento "Grease the Groove" (GtG). O objetivo é alertar o usuário dinamicamente ao longo do dia para realizar séries submáximas de exercícios específicos, respeitando janelas de atividade, bloqueios de inatividade e tempos mínimos de descanso obrigatórios.

**Postura Exigida para o LLM:** Você atua como um Engenheiro de Software Sênior especialista em Android. Seja cético, verifique a documentação moderna (API 34+), não adoce as respostas e foque na precisão técnica. Assuma que nem o desenvolvedor nem você estão sempre certos à primeira vista; priorize o rigor e a estabilidade.

## 2. Stack Tecnológica Obrigatória
*   **Linguagem:** Kotlin.
*   **Interface:** Jetpack Compose (Material 3), estritamente Dark Mode (Background `#121212`, Accent `#2196F3`).
*   **Arquitetura:** Clean Architecture (Domain, Data, Presentation) com UDF (Unidirectional Data Flow) nos ViewModels.
*   **Injeção de Dependência:** Dagger Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`).
*   **Banco de Dados:** Room Database.
*   **Navegação:** Jetpack Navigation Compose.

## 3. Estado Atual da Implementação (O que já foi feito)
O motor do aplicativo e a UI principal foram implementados, auditados e estão funcionais:
*   **Data & Domain:** Entidades Room (`Exercise`, `ActivityWindow`, `InactivityBlock`, `ExerciseLog`), DAOs, Repositórios mapeados e DI configurada (`AppModule`).
*   **Core Logic:** `DynamicSchedulerUseCase` implementado. Regras estritas: 1 alarme por vez, descanso mínimo de 20 min, colisão com `InactivityBlock` antecipa/adia em 5 min, transição para dia seguinte gerencia meia-noite corretamente. *Limitação conhecida na V1: Blocos de inatividade que cruzam a meia-noite (ex: 23h às 01h) são ignorados; aceitável pois a janela de atividade padrão é diurna.*
*   **Background Machinery:** `AlarmSchedulerImpl` usando `setAlarmClock` (com fallback para `setExactAndAllowWhileIdle` para evitar OEMs quirks). `BootReceiver` configurado para reagendar após reinicializações.
*   **Full-Screen Intent Pipeline:** `AlarmReceiver` dispara uma notificação de `IMPORTANCE_HIGH` através de um `NotificationChannel` dedicado (configurado na `GtgApplication`), utilizando `.setFullScreenIntent()`. A `AlarmActivity` aplica as flags para manter a tela ligada independentemente do lockscreen.
*   **Presentation:** `MainActivity` gerencia as permissões de notificação e alarmes exatos do Android 14+. Telas criadas: `HomeScreen`, `ExercisesScreen`, `ScheduleScreen`, `StatisticsScreen`. Coleta de estado utiliza `collectAsStateWithLifecycle()`.

## 4. Regras de Ouro para Futuras Modificações
Sempre que for solicitado a alterar ou criar novo código, obedeça estritamente a estas diretrizes:

1.  **Sobrevivência a Background & Doze Mode:** O Android 16 é agressivo. Nunca sugira usar `WorkManager` para alarmes exatos de GtG. Mantenha o pipeline de Notificação High Priority + Full Screen Intent intocado.
2.  **Ciclo de Vida no Compose:** Nunca use `collectAsState()` simples; use sempre `collectAsStateWithLifecycle()`.
3.  **Permissões Modernas:** Qualquer nova feature que envolva background deve tratar silenciosamente e adequadamente as restrições introduzidas no Android 14 e 15 (ex: `USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM`).
4.  **Simplicidade Visual:** Não importe bibliotecas externas de gráficos ou calendários. Use o Canvas nativo do Compose e LazyLists para manter o pacote leve e performático.
5.  **Nenhum Código Obsoleto:** Não utilize APIs deprecadas. Se uma API foi deprecada, pesquise a alternativa moderna recomendada pelo Google para a API 35 antes de gerar o código.

## 5. Conhecimento Documentado

`docs/solutions/` contém soluções a problemas passados (bugs, patterns, convenções), organizadas por categoria (logic-errors, architecture-patterns, performance-issues, etc.) com YAML frontmatter (`module`, `tags`, `problem_type`). Relevante ao implementar ou debugar em áreas documentadas — especialmente alarm pipeline, scheduler e session state.
