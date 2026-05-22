package com.gtg.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * Picker numérico estilo roleta — substitui os antigos `OutlinedTextField`
 * de 2 dígitos que sofriam com bug de digitação (valor "ficava 00").
 *
 * Comportamento:
 * - Range `0..max`, com snap automático ao soltar o gesto.
 * - O item central do viewport é o valor selecionado, destacado em [GtgPrimary].
 * - Gradiente fade nas bordas para reforçar a metáfora de roleta.
 * - Emite `onValueChange` quando o scroll para (não a cada pixel) para evitar
 *   thrashing de ViewModel.
 */
@Composable
fun WheelNumberPicker(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    itemHeight: Dp = 40.dp,
    visibleItems: Int = 3,
) {
    require(visibleItems % 2 == 1) { "visibleItems precisa ser ímpar para ter item central" }

    val coerced = value.coerceIn(0, max)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = coerced)
    val flingBehavior = rememberSnapFlingBehavior(state)

    // Índice central do viewport — calcula via proximidade do center
    // geométrico ao viewportCenter. O slot-based `items[size/2]` falha no
    // boundary (value=0 ou value=max): com `contentPadding(vertical=
    // itemHeight)`, a primeira composição em value=0 tem
    // visibleItemsInfo = [{index=0}, {index=1}] (size=2), então
    // `items[1]` retornaria index=1 mesmo com item 0 visualmente centrado.
    // Mesmo bug simétrico no top: em value=max, `items[size/2]` aponta um
    // índice ANTES do centro real.
    //
    // O minBy geométrico estava correto pré-refactor; o pixel-perfect snap
    // ao fim do fling (LaunchedEffect abaixo) é o que fecha o gap em fling
    // rápido. Combinado, garantem ambos: posição estável (geométrico após
    // snap) e correção determinística (scrollToItem instantâneo).
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

    // Propaga o valor selecionado APENAS quando o scroll para. Sem isto, cada
    // pixel de scroll dispararia uma escrita em SharedPreferences.
    //
    // Ao detectar a transição `isScrollInProgress: true → false`, força
    // `scrollToItem(centeredIndex)` (instantâneo, sem animação) para
    // garantir snap pixel-perfect — `rememberSnapFlingBehavior` por si
    // só pode deixar drift de fração de item após fling muito rápido,
    // resultando em `centeredIndex` ambíguo.
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to centeredIndex }
            .distinctUntilChanged()
            .collect { (scrolling, idx) ->
                if (!scrolling) {
                    // Snap explícito ao terminar fling — robust contra drift.
                    if (wasScrolling) {
                        state.scrollToItem(idx.coerceIn(0, max))
                    }
                    val v = idx.coerceIn(0, max)
                    if (v != value) onValueChange(v)
                }
                wasScrolling = scrolling
            }
    }

    // Reage a mudanças externas do valor (ex: ViewModel coercitivo). Não rola
    // enquanto o usuário está com o dedo na tela — evita "puxões" indesejados.
    // Usa `scrollToItem` (instantâneo) intencionalmente — `animateScrollToItem`
    // adicionaria delay antes de `onValueChange` sem ganho funcional.
    LaunchedEffect(value) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val v = value.coerceIn(0, max)
        if (state.firstVisibleItemIndex != v || state.firstVisibleItemScrollOffset != 0) {
            state.scrollToItem(v)
        }
    }

    val sidePadding = itemHeight * (visibleItems / 2)
    val totalHeight = itemHeight * visibleItems

    Box(
        modifier = modifier
            .width(width)
            .height(totalHeight),
        contentAlignment = Alignment.Center,
    ) {
        // Banda destacando o item central
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(GtgSurfaceVariant.copy(alpha = 0.5f)),
        )

        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = sidePadding),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(max + 1) { idx ->
                val distance = abs(idx - centeredIndex)
                val isCenter = distance == 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "%02d".format(idx),
                        color = when {
                            isCenter -> GtgPrimary
                            distance == 1 -> Color.White.copy(alpha = 0.55f)
                            else -> Color.White.copy(alpha = 0.25f)
                        },
                        fontSize = if (isCenter) 22.sp else 16.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // Gradiente fade nas bordas (top/bottom) para mascarar os extremos
        // — reforça a sensação de roleta cilíndrica.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF121212), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFF121212)),
                    ),
                ),
        )
    }
}
