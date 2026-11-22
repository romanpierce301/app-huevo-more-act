package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Error
import com.example.ui.theme.ErrorContainer
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.Primary
import com.example.ui.theme.PrimaryGlow
import com.example.ui.theme.SurfaceContainer
import com.example.ui.viewmodel.OverlayState

/**
 * Contenedor modular de los 3 overlays del sistema (Cargando, Éxito, Error).
 * Diseñado para reemplazarse fácilmente cuando se reciban las plantillas exactas en ZIP.
 */
@Composable
fun OverlayHost(
    state: OverlayState,
    modifier: Modifier = Modifier,
) {
    if (state is OverlayState.None) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC050F19))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is OverlayState.Loading -> LoadingOverlayTemplate(state.title, state.message)
            is OverlayState.Success -> SuccessOverlayTemplate(state.title, state.message)
            is OverlayState.Error -> ErrorOverlayTemplate(state.title, state.message)
            OverlayState.None -> Unit
        }
    }
}

@Composable
fun LoadingOverlayTemplate(title: String, message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceContainer,
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .testTag("overlay_loading"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                color = Primary,
                trackColor = Primary.copy(alpha = 0.18f),
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SuccessOverlayTemplate(title: String, message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceContainer,
        border = BorderStroke(1.dp, PrimaryGlow.copy(alpha = 0.55f)),
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .testTag("overlay_success"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(PrimaryGlow.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Operación exitosa",
                    tint = PrimaryGlow,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ErrorOverlayTemplate(title: String, message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceContainer,
        border = BorderStroke(1.dp, Error.copy(alpha = 0.55f)),
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .testTag("overlay_error"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(ErrorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Cancelación procesada",
                    tint = Error,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
