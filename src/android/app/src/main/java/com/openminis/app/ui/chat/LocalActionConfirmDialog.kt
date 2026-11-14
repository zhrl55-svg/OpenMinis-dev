package com.openminis.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

/**
 * Confirmation card for local writes. When a calendar title is ambiguous the
 * user must pick the exact event before the destructive action is enabled.
 */
@Composable
fun LocalActionConfirmDialog(
    action: LocalActionConfirmation,
    voiceStatus: String? = null,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedChoiceId by remember(action.id) { mutableStateOf<String?>(null) }
    val requiresChoice = action.choices.isNotEmpty()
    val canConfirm = !requiresChoice || selectedChoiceId != null

    // [T-confirm-card-auto-dismiss] Second-by-second countdown, restarting
    // whenever a new card takes over. The card closes itself when it
    // reaches zero (the ViewModel's own timeout cancels the action); the
    // number is here so that is never a surprise.
    var remainingSeconds by remember(action.id) { mutableIntStateOf(action.timeoutSeconds) }
    LaunchedEffect(action.id) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1_000L)
            remainingSeconds -= 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.assistant_local_action_message,
                        action.summary,
                        action.detail,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                if (voiceStatus != null) {
                    // 一个呼吸的圆点：卡片打开时用户一眼能看出语音确认在跑，
                    // 而不是只看到一行静态说明。
                    val pulse = rememberInfiniteTransition(label = "voiceConfirmPulse")
                    val pulseAlpha by pulse.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "voiceConfirmPulseAlpha",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(pulseAlpha)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = voiceStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (requiresChoice) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    Text(
                        text = stringResource(R.string.assistant_local_action_choices),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(action.choices, key = { it.id }) { choice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedChoiceId = choice.id }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedChoiceId == choice.id,
                                    onClick = { selectedChoiceId = choice.id },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = choice.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (choice.subtitle.isNotBlank()) {
                                        Text(
                                            text = choice.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                if (remainingSeconds > 0) {
                    Text(
                        text = stringResource(
                            R.string.assistant_local_action_auto_cancel,
                            remainingSeconds,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    MinisTextButton(
                        onClick = { onConfirm(selectedChoiceId) },
                        enabled = canConfirm,
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_local_action_confirm),
                            color = if (!canConfirm) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}
