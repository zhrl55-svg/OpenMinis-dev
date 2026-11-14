package com.openminis.app.ui.media.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.media.music.MusicPreferences
import com.openminis.app.media.music.NeteaseMusicApi
import com.openminis.app.media.music.QrSession
import com.openminis.app.media.music.QrState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Music settings: the NetEase login and where the proxy lives.
 *
 * Without a login the proxy only serves the free catalogue, and most of the
 * well-known songs come back with an empty stream URL (and never show up in
 * the search at all). Scanning the QR code with the NetEase app logs the
 * phone in; the cookie is stored on the device and sent with every request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { NeteaseMusicApi { MusicPreferences.load(context) } }
    val settings = remember { MusicPreferences.load(context) }

    var nickname by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<QrSession?>(null) }
    var qrImage by remember { mutableStateOf<String?>(null) }
    var qrState by remember { mutableStateOf(QrState.WAITING_SCAN) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }

    // The cookie decides the whole thing: with one the proxy serves the
    // account's catalogue, without one most songs are unplayable.
    LaunchedEffect(Unit) {
        val cookie = MusicPreferences.load(context).cookie
        if (cookie.isNotBlank()) {
            val nick = api.loginNickname(cookie)
            loggedIn = nick != null
            nickname = nick
            if (nick == null) {
                // The cookie expired - drop it instead of pretending to be
                // logged in.
                MusicPreferences.saveCookie(context, "")
            }
        }
    }

    fun startLogin() {
        scope.launch {
            qrState = QrState.WAITING_SCAN
            qrImage = null
            val created = api.qrSession()
            if (created == null) {
                qrState = QrState.NETWORK_ERROR
                return@launch
            }
            session = created
            qrImage = api.qrImage(created)
        }
    }

    // Poll the proxy until the phone confirms the login.
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val (state, cookie) = api.qrCheck(current)
            qrState = state
            when (state) {
                QrState.SUCCESS -> {
                    if (!cookie.isNullOrBlank()) {
                        MusicPreferences.saveCookie(context, cookie)
                        nickname = api.loginNickname(cookie)
                        loggedIn = nickname != null
                    }
                    session = null
                    qrImage = null
                    break
                }
                QrState.EXPIRED -> {
                    session = null
                    break
                }
                QrState.ERROR, QrState.NETWORK_ERROR -> break
                else -> Unit
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.music_settings_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Login state ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.music_login_account),
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        loggedIn -> stringResource(
                            R.string.music_login_logged_in,
                            nickname ?: stringResource(R.string.music_login_logged_in_generic),
                        )
                        else -> stringResource(R.string.music_login_logged_out)
                    },
                    fontSize = 14.sp,
                    color = if (loggedIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (loggedIn) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (loggedIn) {
                OutlinedButton(
                    onClick = {
                        MusicPreferences.saveCookie(context, "")
                        nickname = null
                        loggedIn = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.music_login_logout))
                }
            } else {
                Button(
                    onClick = { startLogin() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_login_scan))
                }
            }

            // ── QR code ──
            if (session != null || qrImage != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                ),
                            ),
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = qrImage
                    val bitmap = remember(image) { image?.let { decodeQrBitmap(it) } }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.music_login_qr),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(190.dp),
                        )
                    } else if (image != null) {
                        Text(
                            text = stringResource(R.string.music_login_qr_failed),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            when (qrState) {
                                QrState.WAITING_SCAN -> R.string.music_login_waiting_scan
                                QrState.WAITING_CONFIRM -> R.string.music_login_waiting_confirm
                                QrState.EXPIRED -> R.string.music_login_expired
                                QrState.SUCCESS -> R.string.music_login_success
                                QrState.ERROR -> R.string.music_login_failed
                                QrState.NETWORK_ERROR -> R.string.music_login_network_error
                            },
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (qrState == QrState.EXPIRED || qrState == QrState.ERROR ||
                        qrState == QrState.NETWORK_ERROR
                    ) {
                        IconButton(onClick = { startLogin() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.music_login_refresh),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Proxy address ──
            Text(
                text = stringResource(R.string.music_api_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.music_api_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    MusicPreferences.saveBaseUrl(context, baseUrl)
                    baseUrl = MusicPreferences.load(context).baseUrl
                }) {
                    Text(stringResource(R.string.music_api_save))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Decode the QR PNG the proxy returns as a base64 data URI.
 *
 * The bytes are decoded here instead of handed to the image loader: the QR
 * is a couple of kilobytes of base64 that has to turn into a bitmap right
 * now, and a plain decode cannot fail for reasons the user cannot act on.
 */
private fun decodeQrBitmap(dataUri: String): androidx.compose.ui.graphics.ImageBitmap? {
    val payload = dataUri.substringAfter(',', "").takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap?.asImageBitmap()
    }.getOrNull()
}

private const val POLL_INTERVAL_MS = 2_000L
