---
title: "LazyListState snap fling: geometric centeredIndex + explicit scrollToItem at fling-end"
date: 2026-05-22
category: docs/solutions/design-patterns/
module: ui
problem_type: design_pattern
component: frontend_stimulus
severity: medium
applies_when:
  - "Compose LazyColumn/LazyRow used as a wheel/picker with snap fling"
  - "rememberSnapFlingBehavior produces drift at high fling velocity"
  - "centered item must drive an external state update (onValueChange)"
  - "Picker has non-zero contentPadding (typical for centered-slot widgets)"
tags:
  - compose
  - lazycolumn
  - snap
  - fling
  - rememberSnapFlingBehavior
  - scrollToItem
  - wheel-picker
---

# LazyListState snap fling: geometric centeredIndex + explicit scrollToItem at fling-end

## Context

Bug reportado em uso real: `WheelNumberPicker` no onboarding `ActivityWindowStep` pulava de hora em fling rápido. Usuário rolava minutos rápido para baixo, e o picker às vezes parava em valores estranhos OU emitia o valor adjacente do alvo do snap. O `rememberSnapFlingBehavior` por si só não garante snap pixel-perfect em fling de alta velocidade — pode deixar fração de item drift, e o cálculo de `centeredIndex` baseado nesse drift escolhe o item errado.

A primeira tentativa de fix mudou para slot-based (`items[items.size / 2].index`) — mas isso quebrou outro caminho: com `contentPadding(vertical = itemHeight)`, no boundary `value=0`, `visibleItemsInfo` é assimétrico (`[item_0, item_1]`, size=2) e `items[1]` retorna index=1 mesmo com item 0 visualmente centrado. Primeira composição em value=0 emitia `onValueChange(1)` silenciosamente.

## Guidance

**O pattern: cálculo geométrico do centro (`minBy` de distância ao viewport center) + correção pixel-perfect explícita via `scrollToItem` ao detectar fim de fling.**

```kotlin
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import kotlin.math.abs

@Composable
fun WheelNumberPicker(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    visibleItems: Int = 3, // odd: 3, 5, 7...
) {
    require(visibleItems % 2 == 1) { "visibleItems must be odd to have a center" }

    val coerced = value.coerceIn(0, max)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = coerced)
    val flingBehavior = rememberSnapFlingBehavior(state)

    // Índice central via PROXIMIDADE GEOMÉTRICA ao viewport center.
    // NÃO usar `items[items.size / 2].index` — slot-based quebra no boundary
    // value=0 (visibleItemsInfo é assimétrico com contentPadding) e no
    // boundary value=max (idem na outra direção).
    val centeredIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return@derivedStateOf coerced
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            items.minBy { abs((it.offset + it.size / 2) - viewportCenter) }.index
                .coerceIn(0, max) // defensive against over-scroll edge cases
        }
    }

    // Propaga valor APENAS quando scroll para. Pixel-perfect snap via
    // scrollToItem (instantâneo) na transição true→false.
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to centeredIndex }
            .distinctUntilChanged()
            .collect { (scrolling, idx) ->
                if (!scrolling) {
                    if (wasScrolling) {
                        // Snap explícito ao fim do fling — fecha o drift que
                        // rememberSnapFlingBehavior deixa em fling rápido.
                        state.scrollToItem(idx.coerceIn(0, max))
                    }
                    val v = idx.coerceIn(0, max)
                    if (v != value) onValueChange(v)
                }
                wasScrolling = scrolling
            }
    }

    // Reage a mudanças externas do valor (ViewModel coerce, restore).
    // scrollToItem instantâneo, NÃO animateScrollToItem — adiciona delay
    // antes do onValueChange sem ganho funcional já que estamos no snap point.
    LaunchedEffect(value) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val v = value.coerceIn(0, max)
        if (state.firstVisibleItemIndex != v || state.firstVisibleItemScrollOffset != 0) {
            state.scrollToItem(v)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = flingBehavior,
        // contentPadding cria espaço para o item central ficar realmente centrado
        // contentPadding = PaddingValues(vertical = itemHeight * (visibleItems / 2)),
        // ...
    ) {
        items(max + 1) { idx -> /* render item */ }
    }
}
```

### Os três componentes do pattern

1. **Geometric center identification**. `items.minBy { abs((it.offset + it.size / 2) - viewportCenter) }.index` percorre os items visíveis e escolhe o mais próximo ao centro do viewport. Funciona em qualquer estado de `visibleItemsInfo` (assimétrico ou simétrico, com ou sem contentPadding) porque pergunta "qual desses itens está mais próximo do meio?" diretamente.

2. **Defensive `coerceIn(0, max)`**. Em over-scroll ou estados transient (primeira composição antes do layout), `firstVisibleItemIndex` ou indices visíveis podem cair fora do range válido. Clamp garante que o valor emitido a `onValueChange` é sempre legal.

3. **Pixel-perfect snap via `scrollToItem` ao fim do fling**. `rememberSnapFlingBehavior` faz fling animation + snap, mas em velocidade alta deixa drift sub-pixel. Detectar `isScrollInProgress: true → false` e chamar `state.scrollToItem(centeredIndex)` instantaneamente alinha o item ao centro. O `wasScrolling` local var é o que diferencia "fim de fling real" de "estado inicial" (a primeira emissão também é `(false, idx)` mas sem fling precedente — sem o flag, snap dispararia ao composição inicial).

### Por que `scrollToItem` (não `animateScrollToItem`)

`animateScrollToItem` é mais suave visualmente, mas:
- Estamos JÁ no snap point geométrico — animar dali pra ele mesmo é redundante
- Animação adiciona delay (centenas de ms) antes do `onValueChange` propagar, atrasando feedback ao ViewModel
- Para snap correction, instant é semanticamente correto: "estou consertando o que o fling deveria ter feito"

`animateScrollToItem` faz sentido para mudanças EXTERNAS do `value` (ViewModel rewrites the value), mas mesmo lá o `scrollToItem` é defensável — o trade-off é "suavidade visual vs latência de update". O GtG escolheu instant para ambos os caminhos por consistência.

## Why This Matters

**`rememberSnapFlingBehavior` não é pixel-perfect.** Por design, ela faz fling animation com snap, mas em fling rápido o `predictFinalIndex` pode deixar a posição final com offset fracionário. O cálculo de `centeredIndex` baseado nesse estado intermediário emite o item errado a `onValueChange`. Bug é invisível em fling lento (snap converge naturalmente) e em emulador (gestos sintéticos não atingem velocidades altas).

**Slot-based `items[size / 2]` é tentação que quebra em boundaries.** Parece elegante — "o item do meio da lista visível é o centro". Mas `visibleItemsInfo` lista os items que intersectam o viewport — com `contentPadding(vertical = itemHeight)`:
- value=0: visibleItemsInfo = `[item_0 com offset=itemHeight, item_1 com offset=2*itemHeight]`, size=2. `items[1]` retorna index=1, mas visualmente item 0 está no centro.
- value=max: visibleItemsInfo = `[item_{max-1}, item_max]`, size=2. `items[1]` retorna max (acidentalmente correto), mas o pattern é frágil — qualquer mudança no contentPadding ou no número de slots visíveis muda a resposta.

Geométrico é robusto: pergunta o que importa (proximidade ao centro), não como a layoutInfo está organizada.

**Snap correction tem que ser explícita.** Confiar no `rememberSnapFlingBehavior` para "consertar sozinho" leva a drift acumulativo em sessões longas de uso. Cada fling deixa fração de pixel → próximo fling adiciona mais drift → eventualmente o `centeredIndex` está consistentemente off. Snap explícito ao fim de cada fling é resetting o accumulator.

**`wasScrolling` flag previne snap-on-composition.** A primeira emissão do `snapshotFlow` é `(scrolling=false, idx=coerced)` — sem flag, isso dispararia `scrollToItem` no composition inicial, criando potential thrashing entre `LaunchedEffect(value)` e o snap effect. O flag estabelece "só consertar se houve fling antes".

## When to Apply

- Compose `LazyColumn`/`LazyRow` usado como picker (wheel, carousel, year/month selector)
- `rememberSnapFlingBehavior` em uso e o item central drives external state
- Padded centered slots — contentPadding vertical/horizontal usado para centralizar
- Boundary values (`value=0`, `value=max`) podem ser visited regularmente — picker numérico, lista finita

Não se aplica para:
- LazyList sem snap (scroll livre, sem item central importante) — geometric center é desnecessário
- Carousel onde apenas presence em viewport importa — não há "item central"

## Examples

### Boundary bug que motivou o doc

```kotlin
// Tentativa de fix slot-based, simples mas QUEBRADA:
val centeredIndex by remember {
    derivedStateOf {
        val items = state.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return@derivedStateOf coerced
        items[items.size / 2].index.coerceIn(0, max) // ← BUG no boundary
    }
}
```

Cenário: ActivityWindowStep abre com hora=8, minuto=0. Composição inicial:
- LazyColumn minuto com value=0, contentPadding(vertical=itemHeight)
- visibleItemsInfo = `[{index=0, offset=itemHeight}, {index=1, offset=2*itemHeight}]`
- `items[1].index` = 1
- `centeredIndex` derivedState = 1
- `LaunchedEffect(state) snapshotFlow` emite `(false, 1)` na primeira composição
- `wasScrolling=false`, sem snap; mas `v=1 != value=0` → `onValueChange(1)` 🚨
- ViewModel registra `startMinute=1` silenciosamente — usuário vê "08:01" sem ter rolado

### Fix com geometric center

```kotlin
val centeredIndex by remember {
    derivedStateOf {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@derivedStateOf coerced
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        items.minBy { abs((it.offset + it.size / 2) - viewportCenter) }.index
            .coerceIn(0, max)
    }
}
```

Mesmo cenário inicial (value=0, visibleItemsInfo `[item_0 offset=itemHeight, item_1 offset=2*itemHeight]`, viewport center ~= 1.5*itemHeight):
- item_0 center: `itemHeight + itemHeight/2 = 1.5*itemHeight` → distance = 0
- item_1 center: `2*itemHeight + itemHeight/2 = 2.5*itemHeight` → distance = itemHeight
- `minBy` escolhe item_0 → index = 0 ✓
- snapshotFlow emite `(false, 0)`, `v == value` → no `onValueChange` call ✓

### Snap correction in action

```kotlin
LaunchedEffect(state) {
    var wasScrolling = false
    snapshotFlow { state.isScrollInProgress to centeredIndex }
        .distinctUntilChanged()
        .collect { (scrolling, idx) ->
            if (!scrolling) {
                if (wasScrolling) {
                    state.scrollToItem(idx.coerceIn(0, max))
                }
                val v = idx.coerceIn(0, max)
                if (v != value) onValueChange(v)
            }
            wasScrolling = scrolling
        }
}
```

Fling rápido:
1. User flings → `isScrollInProgress = true`, snapshotFlow emite `(true, ...)`. `wasScrolling=true` set ao fim do collect.
2. Fling termina, `rememberSnapFlingBehavior` snap'a aproximadamente. `isScrollInProgress = false`, `centeredIndex` calcula via minBy → escolhe item N. snapshotFlow emite `(false, N)`.
3. `wasScrolling` ainda é `true` (legado da emissão anterior) → `scrollToItem(N)` corrige drift sub-pixel. Item N agora ALINHADO pixel-perfect ao centro.
4. `onValueChange(N)` emitido.
5. `wasScrolling = false` set ao fim.

Composição inicial sem fling:
1. snapshotFlow primeira emissão: `(false, coerced)`. `wasScrolling = false` (default).
2. `if (wasScrolling)` é false → snap **NÃO** dispara (correto — nada pra consertar).
3. `if (v != value)` — `v = coerced`, `value = passed-in`. Iguais → no `onValueChange`. ✓

## Related

Nenhum doc anterior em `docs/solutions/` cobre Compose UI patterns. Este é o primeiro.

**Implementação de referência:** PR #3 (commit `e2fe223` em main), arquivo `app/src/main/java/com/gtg/app/presentation/common/WheelNumberPicker.kt`. A história completa do bug está em:
- Tentativa slot-based commit `d1a4a44` (introduzida) → simplify pass commit `dcdf55f` (consolidada) → boundary bug encontrado via ce-code-review `8c0399a` (revertida para geometric + snap correction).
