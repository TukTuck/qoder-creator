package app.consolepocket.ui.browser

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.consolepocket.R

/**
 * Sofort sichtbare UI ohne ein Byte Netzwerk (PLAN L3, Ziel < 300 ms).
 *
 * In Compose gezeichnet statt als WebView-Seite: kein zusaetzlicher Navigationszyklus, kein
 * Flackern beim Uebergang zur echten Console. Verschwindet bei `onPageCommitVisible`.
 * Die HTML-Variante in `assets/shell/` dient zusaetzlich als Offline-/Fehlerseite.
 */
@Composable
fun SkeletonOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )

    @Composable
    fun Block(modifier: Modifier = Modifier, circle: Boolean = false) {
        Box(
            modifier = modifier
                .clip(if (circle) CircleShape else RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
        )
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        // Top-Bar-Platzhalter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Block(Modifier.size(32.dp), circle = true)
            Block(Modifier.height(20.dp).width(120.dp))
            Block(Modifier.height(32.dp).weight(1f).clip(RoundedCornerShape(16.dp)))
            Block(Modifier.size(28.dp), circle = true)
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.weight(1f)) {
            // Nav-Rail
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                repeat(6) { Block(Modifier.size(40.dp)) }
            }
            Spacer(Modifier.width(16.dp))

            // Inhalt
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Block(Modifier.height(14.dp).fillMaxWidth(0.5f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Block(Modifier.height(12.dp).fillMaxWidth(0.6f))
                            Block(Modifier.height(48.dp).fillMaxWidth())
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Block(Modifier.height(14.dp).fillMaxWidth(0.35f))
                    repeat(3) { Block(Modifier.height(34.dp).fillMaxWidth()) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hinweis bei fehlender Verbindung. Bietet die lokal gespeicherte Shell an
 * (`assets/shell` via WebViewAssetLoader) - alles in-app, kein externes Fenster.
 */
@Composable
fun OfflineBanner(
    onShowShell: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.state_offline_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.state_offline_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        TextButton(onClick = onShowShell) { Text("Shell") }
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}
