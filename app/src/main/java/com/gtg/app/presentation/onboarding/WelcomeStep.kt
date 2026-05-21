package com.gtg.app.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtg.app.R
import com.gtg.app.presentation.theme.GtgPrimary

/**
 * Step 1 do onboarding — boas-vindas + explicação curta do GtG.
 *
 * Conteúdo em pt-BR e en via `strings.xml`. Locale é resolvido pelo
 * AppCompatDelegate.setApplicationLocales escolhido no LanguageGate.
 */
@Composable
internal fun WelcomeStep(
    onContinue: () -> Unit,
    onSkipAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Icon(
            imageVector = Icons.Default.FitnessCenter,
            contentDescription = null,
            tint = GtgPrimary,
            modifier = Modifier.size(64.dp),
        )

        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GtgPrimary,
                contentColor = Color.White,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_button_continue),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TextButton(onClick = onSkipAll) {
            Text(
                text = stringResource(R.string.onboarding_button_skip_all),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
        }
    }
}
