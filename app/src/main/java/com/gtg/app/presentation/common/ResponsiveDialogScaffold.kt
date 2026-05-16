package com.gtg.app.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gtg.app.presentation.theme.GtgSurface

/**
 * Scaffold padrão para dialogs do GtG, garantindo:
 *
 * - **Scroll vertical** no conteúdo — protege contra dialogs com seleção
 *   recorrente + 7 chips DOW + texto dinâmico que não cabem em portrait curto
 *   ou landscape.
 * - **Tamanho responsivo** — `widthIn(max=560.dp)` evita que em tablets o
 *   dialog estique para largura inteira; `heightIn(max ≈ 90% da altura da
 *   tela)` reserva espaço para barras do sistema.
 * - **imePadding** — quando há `TextField` no conteúdo, o teclado empurra o
 *   dialog em vez de cobrir os botões.
 *
 * Botão primário ([confirmButton]) à direita, secundário opcional
 * ([dismissButton]) à esquerda — convenção M3.
 *
 * O conteúdo recebe um [ColumnScope] para que callers possam usar `weight`,
 * spacing, etc, dentro da área scrollável.
 */
@Composable
fun ResponsiveDialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val config = LocalConfiguration.current
    val maxHeight = (config.screenHeightDp * 0.9f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = 560.dp)
                .heightIn(max = maxHeight)
                .padding(horizontal = 16.dp)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = GtgSurface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                // Área scrollável — `weight(1f, fill=false)` permite que o
                // Column reserve espaço para os botões mesmo quando o
                // conteúdo é maior que `maxHeight`.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp),
                    content = content,
                )

                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissButton?.invoke()
                    // Spacer flexível empurra confirmButton para a direita
                    // quando dismissButton existe; se for null, confirmButton
                    // fica encostado à esquerda — caller pode usar
                    // `Modifier.fillMaxWidth()` no botão se quiser largura total.
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.weight(1f),
                    )
                    confirmButton()
                }
            }
        }
    }
}
