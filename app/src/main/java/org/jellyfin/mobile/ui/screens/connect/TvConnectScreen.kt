package org.jellyfin.mobile.ui.screens.connect

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.setup.AddressReceiver
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.jellyfin.mobile.ui.utils.QrImage
import org.koin.compose.koinInject

private val ACCENT = Color(0xFF00A4DC)
private val CARD = Color(0xFF151526)
private val CARD_FOCUSED = Color(0xFF1F2A44)
private val TEXT_SOFT = Color.White.copy(alpha = 0.7f)
private val COSMIC_GRADIENT = Brush.linearGradient(listOf(Color(0xFFA463D6), ACCENT))

/**
 * The connect screen of the TV, for the remote: the servers found in the network and the ones used before as big
 * cards, the address typed with the system keyboard, or sent from a phone through the QR code (AddressReceiver).
 */
@Suppress("LongMethod")
@Composable
fun TvConnectScreen(
    showExternalConnectionError: Boolean,
    onConnected: suspend (String) -> Unit,
    apiClientController: ApiClientController = koinInject(),
    connectionHelper: ConnectionHelper = koinInject(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    var hostname by remember { mutableStateOf("") }
    val suggestions = remember { mutableStateListOf<ServerSuggestion>() }
    var checkUrlState by remember<MutableState<CheckUrlState>> { mutableStateOf(CheckUrlState.Unchecked) }
    var externalError by remember { mutableStateOf(showExternalConnectionError) }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    val firstCard = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }

    fun connect(address: String) {
        if (checkUrlState == CheckUrlState.Pending || address.isBlank()) return
        hostname = address
        externalError = false
        checkUrlState = CheckUrlState.Pending
        coroutineScope.launch {
            val state = connectionHelper.checkServerUrl(address)
            checkUrlState = state
            if (state is CheckUrlState.Success) onConnected(state.address)
        }
    }

    LaunchedEffect(Unit) {
        // The server in use until now, first (it is not among the ones "used before")
        apiClientController.loadSavedServer()?.let { server ->
            hostname = server.hostname
            suggestions.add(ServerSuggestion(ServerSuggestion.Type.SAVED, server.hostname, server.hostname, server.lastUsedTimestamp))
        }
        collectServerSuggestions(apiClientController, connectionHelper, suggestions)
    }

    // The address sent from a phone, while this screen is on view
    DisposableEffect(Unit) {
        val receiver = AddressReceiver(context) { address -> connect(address) }
        phoneUrl = receiver.start(coroutineScope)
        onDispose { receiver.stop() }
    }

    // The remote starts on the first server as soon as there is one
    LaunchedEffect(suggestions.isNotEmpty()) {
        if (suggestions.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            firstCard.requestFocus()
        }
    }

    val pending = checkUrlState is CheckUrlState.Pending
    val errorText = when {
        externalError -> stringResource(R.string.connection_error_cannot_connect)
        else -> (checkUrlState as? CheckUrlState.Error)?.message
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.height(44.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tv_connect_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            if (suggestions.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(CARD_HEIGHT)) {
                    CircularProgressIndicator(color = ACCENT, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.tv_connect_searching), color = TEXT_SOFT, fontSize = 16.sp)
                }
            } else {
                // Room for the focused card, which grows
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                    itemsIndexed(suggestions, key = { _, server -> server.address }) { index, server ->
                        ServerCard(
                            server = server,
                            connecting = pending && hostname == server.address,
                            modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                            onClick = { connect(server.address) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.tv_connect_or_type), color = TEXT_SOFT, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { value ->
                        externalError = false
                        checkUrlState = CheckUrlState.Unchecked
                        hostname = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        // Up and down leave the field (a one-line field would only move its cursor)
                        .onPreviewKeyEvent { event ->
                            val direction = when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                                else -> null
                            }
                            if (direction != null && event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                            direction != null
                        }
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                                connect(hostname)
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text(stringResource(R.string.host_input_hint)) },
                    isError = errorText != null,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.body1.copy(fontSize = 18.sp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = Color.White,
                        placeholderColor = TEXT_SOFT,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedBorderColor = ACCENT,
                        cursorColor = ACCENT,
                        backgroundColor = CARD,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect(hostname) }),
                )
                Spacer(Modifier.width(12.dp))
                TvButton(
                    text = stringResource(R.string.connect_button_text),
                    enabled = hostname.isNotBlank() && !pending,
                    onClick = { connect(hostname) },
                )
            }
            if (errorText != null) {
                Text(errorText, color = MaterialTheme.colors.error, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (pending) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    CircularProgressIndicator(color = ACCENT, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.tv_connect_connecting, hostname), color = TEXT_SOFT, fontSize = 15.sp)
                }
            }
        }
        phoneUrl?.let { url -> PhonePanel(url, Modifier.weight(1f)) }
    }
}

private val CARD_HEIGHT = 116.dp

@Composable
private fun ServerCard(server: ServerSuggestion, connecting: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .scale(if (focused) 1.05f else 1f)
            .width(220.dp)
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) CARD_FOCUSED else CARD)
            .border(3.dp, if (focused) ACCENT else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (server.type == ServerSuggestion.Type.DISCOVERED) Icons.Outlined.Wifi else Icons.Outlined.History,
                    contentDescription = null,
                    tint = if (focused) ACCENT else TEXT_SOFT,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (server.type == ServerSuggestion.Type.DISCOVERED) R.string.tv_connect_found else R.string.tv_connect_saved,
                    ),
                    color = TEXT_SOFT,
                    fontSize = 13.sp,
                )
                if (connecting) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(color = ACCENT, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(server.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.name != server.address) {
                Text(server.address, color = TEXT_SOFT, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TvButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                when {
                    !enabled -> CARD
                    focused -> Color.White
                    else -> ACCENT
                },
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp),
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> TEXT_SOFT
                focused -> Color.Black
                else -> Color.White
            },
        )
    }
}

@Composable
private fun PhonePanel(url: String, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(CARD)
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_connect_phone_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.h6.copy(brush = COSMIC_GRADIENT),
        )
        Spacer(Modifier.height(16.dp))
        QrImage(
            text = url,
            description = stringResource(R.string.tv_connect_phone_title),
            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tv_connect_phone_hint),
            color = TEXT_SOFT,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(url, color = Color.White, fontSize = 14.sp)
    }
}
