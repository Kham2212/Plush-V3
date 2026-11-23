package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.ads.AdMessage
import com.example.ads.AdState
import com.example.model.LibboxAvailability
import com.example.model.SyncStatus
import com.example.model.MaintenanceConfig
import com.example.model.VpnConnectionState
import com.example.ui.theme.ConnectedEmerald
import com.example.ui.theme.ConnectingAmber
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.IdleSlate
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PulseCyan
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.SlateSurfaceHigh
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.vpn.TrafficSpeedCalculator
import java.text.DateFormat
import java.util.Date

@Composable
fun PulseVpnScreen(
    uiState: VpnUiState,
    onToggleConnection: () -> Unit,
    onSelectServer: (String) -> Unit,
    onSyncRemote: () -> Unit,
    onDismissError: () -> Unit,
    onConsumeSnackbar: () -> Unit,
    onConsumeAdUnavailable: () -> Unit = {},
    adState: AdState = AdState(),
    onOpenSettings: (() -> Unit)? = null,
    banner: @Composable () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onConsumeSnackbar()
    }

    val adUnavailableMessage = stringResource(R.string.msg_ad_unavailable)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(adState.message, lifecycleOwner) {
        if (adState.message == AdMessage.UNAVAILABLE) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snackbarHostState.showSnackbar(adUnavailableMessage)
                onConsumeAdUnavailable()
            }
        }
    }

    val accentColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is VpnConnectionState.Connected -> ConnectedEmerald
            is VpnConnectionState.Connecting -> ConnectingAmber
            is VpnConnectionState.Error -> ErrorCoral
            is VpnConnectionState.Disconnected -> PulseCyan
        },
        animationSpec = tween(durationMillis = 280),
        label = "accentColor",
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .testTag("banner_container"),
                contentAlignment = Alignment.Center,
            ) { banner() }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.14f),
                                ObsidianBackground,
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.24f),
                            radius = maxOf(size.width, size.height) * 0.65f,
                        ),
                    )
                },
        ) {
            val isWide = maxWidth >= 600.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = if (isWide) 920.dp else 540.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TopStatusBar(
                        connectionState = uiState.connectionState,
                        accentColor = accentColor,
                    )

                    if (onOpenSettings != null) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.label_settings))
                        }
                    }

                    if (uiState.maintenance.enabled) MaintenanceBanner(uiState.maintenance)

                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                HeroShieldSection(
                                    uiState = uiState,
                                    adState = adState,
                                    accentColor = accentColor,
                                    onToggleConnection = onToggleConnection,
                                )
                                ErrorBanner(
                                    connectionState = uiState.connectionState,
                                    onDismissError = onDismissError,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1.15f),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ServerSelectorCard(
                                    servers = uiState.servers,
                                    selectedServerId = uiState.selectedServerId,
                                    onSelectServer = onSelectServer,
                                )
                                ConfigSyncCard(
                                    uiState = uiState,
                                    onSyncRemote = onSyncRemote,
                                )
                                CoreStatusCard(availability = uiState.libboxAvailability)
                            }
                        }
                    } else {
                        HeroShieldSection(
                            uiState = uiState,
                            adState = adState,
                            accentColor = accentColor,
                            onToggleConnection = onToggleConnection,
                        )
                        ErrorBanner(
                            connectionState = uiState.connectionState,
                            onDismissError = onDismissError,
                        )
                        ServerSelectorCard(
                            servers = uiState.servers,
                            selectedServerId = uiState.selectedServerId,
                            onSelectServer = onSelectServer,
                        )
                        ConfigSyncCard(
                            uiState = uiState,
                            onSyncRemote = onSyncRemote,
                        )
                        CoreStatusCard(availability = uiState.libboxAvailability)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TopStatusBar(
    connectionState: VpnConnectionState,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, HairlineBorder),
            ) {
                Box(
                    modifier = Modifier.size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = stringResource(R.string.app_name),
                        tint = PulseCyan,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }

        val statusLabel = when (connectionState) {
            is VpnConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
            is VpnConnectionState.Connecting -> stringResource(R.string.status_connecting)
            is VpnConnectionState.Connected -> stringResource(R.string.status_connected)
            is VpnConnectionState.Error -> stringResource(R.string.status_error)
        }
        val dotColor = when (connectionState) {
            is VpnConnectionState.Disconnected -> IdleSlate
            is VpnConnectionState.Connecting -> ConnectingAmber
            is VpnConnectionState.Connected -> ConnectedEmerald
            is VpnConnectionState.Error -> ErrorCoral
        }

        Surface(
            modifier = Modifier.testTag("connection_status_badge"),
            shape = RoundedCornerShape(50),
            color = dotColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                AnimatedContent(
                    targetState = statusLabel,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "statusLabel",
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor.takeIf { connectionState !is VpnConnectionState.Disconnected }
                            ?: TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroShieldSection(
    uiState: VpnUiState,
    adState: AdState,
    accentColor: Color,
    onToggleConnection: () -> Unit,
) {
    val isActive = uiState.connectionState is VpnConnectionState.Connected
    val isBusy = uiState.connectionState is VpnConnectionState.Connecting

    val infiniteTransition = rememberInfiniteTransition(label = "shieldPulse")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 1100 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseFraction",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shieldScale",
    )

    val actionLabel = when (uiState.connectionState) {
        is VpnConnectionState.Connected -> stringResource(R.string.action_disconnect)
        is VpnConnectionState.Connecting -> stringResource(R.string.action_connecting)
        is VpnConnectionState.Disconnected,
        is VpnConnectionState.Error -> stringResource(R.string.action_connect)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(210.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension * 0.37f

                    drawCircle(
                        color = accentColor.copy(alpha = 0.14f),
                        radius = baseRadius + 16.dp.toPx(),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )

                    if (isActive || isBusy) {
                        val rippleRadius = baseRadius + (34.dp.toPx() * pulseFraction)
                        drawCircle(
                            color = accentColor.copy(alpha = (1f - pulseFraction) * 0.45f),
                            radius = rippleRadius,
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(152.dp)
                        .scale(buttonScale)
                        .clip(CircleShape)
                        .testTag("power_shield_button")
                        .clickable(
                            enabled = !adState.busy && !uiState.maintenance.enabled,
                            interactionSource = interactionSource,
                            indication = ripple(bounded = true, color = accentColor),
                            onClick = onToggleConnection,
                        ),
                    shape = CircleShape,
                    color = if (isActive) {
                        ConnectedEmerald.copy(alpha = 0.18f)
                    } else {
                        SlateSurfaceElevated
                    },
                    border = BorderStroke(
                        width = 2.dp,
                        color = accentColor.copy(alpha = if (isActive || isBusy) 0.9f else 0.45f),
                    ),
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(38.dp),
                                color = ConnectingAmber,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = actionLabel,
                                tint = accentColor,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                    }
                }
            }

            if (!isActive && !isBusy) {
                Text(
                    text = when (adState.message) {
                        AdMessage.LOADING -> stringResource(R.string.msg_ad_loading)
                        AdMessage.UNAVAILABLE -> stringResource(R.string.msg_ad_unavailable)
                        AdMessage.INCOMPLETE -> stringResource(R.string.msg_ad_incomplete)
                        null -> stringResource(R.string.msg_watch_ad_to_connect)
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("ad_status"),
                )
            }

            // Live Download & Upload Speed Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpeedMeterCard(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_speed_card"),
                    valueTag = "download_speed_value",
                    icon = Icons.Rounded.ArrowDownward,
                    label = stringResource(R.string.label_download_speed),
                    formattedSpeed = TrafficSpeedCalculator.formatSpeed(uiState.trafficSpeed.downloadBytesPerSec),
                    accentColor = if (isActive) ConnectedEmerald else IdleSlate,
                )
                SpeedMeterCard(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("upload_speed_card"),
                    valueTag = "upload_speed_value",
                    icon = Icons.Rounded.ArrowUpward,
                    label = stringResource(R.string.label_upload_speed),
                    formattedSpeed = TrafficSpeedCalculator.formatSpeed(uiState.trafficSpeed.uploadBytesPerSec),
                    accentColor = if (isActive) PulseCyan else IdleSlate,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TelemetryPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Lock,
                    label = stringResource(R.string.label_transport),
                    value = if (uiState.tlsEnabled) {
                        stringResource(R.string.value_transport_ws_tls)
                    } else {
                        stringResource(R.string.value_transport_ws)
                    },
                )
                TelemetryPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Dns,
                    label = stringResource(R.string.label_dns),
                    value = stringResource(R.string.value_dns_doh),
                )
            }
        }
    }
}

@Composable
private fun SpeedMeterCard(
    modifier: Modifier = Modifier,
    valueTag: String,
    icon: ImageVector,
    label: String,
    formattedSpeed: String,
    accentColor: Color,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = SlateSurfaceElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formattedSpeed,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(valueTag),
                )
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = SlateSurfaceElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PulseCyan,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    connectionState: VpnConnectionState,
    onDismissError: () -> Unit,
) {
    val errorState = connectionState as? VpnConnectionState.Error
    AnimatedVisibility(visible = errorState != null) {
        if (errorState != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_banner"),
                shape = MaterialTheme.shapes.medium,
                color = ErrorCoral.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.5f)),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = stringResource(R.string.status_error),
                        tint = ErrorCoral,
                    )
                    Text(
                        text = if (errorState.message == stringResource(R.string.msg_vpn_permission_denied)) {
                            stringResource(R.string.msg_vpn_permission_denied)
                        } else {
                            stringResource(R.string.msg_connection_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissError,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("dismiss_error_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss error",
                            tint = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSelectorCard(
    servers: List<ServerChoice>,
    selectedServerId: String,
    onSelectServer: (String) -> Unit,
) {
    if (servers.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_list_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_server_list),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SlateSurfaceHigh,
                ) {
                    Text(
                        text = "${servers.size} enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                servers.forEachIndexed { index, server ->
                    val isSelected = server.id == selectedServerId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(MaterialTheme.shapes.small)
                            .testTag("server_item_$index")
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelectServer(server.id) },
                            ),
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) PulseCyan.copy(alpha = 0.12f) else SlateSurfaceElevated,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) PulseCyan.copy(alpha = 0.7f) else HairlineBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (isSelected) {
                                    Icons.Rounded.RadioButtonChecked
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (isSelected) PulseCyan else TextMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = server.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceBanner(maintenance: MaintenanceConfig) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, ConnectingAmber),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = maintenance.title.ifBlank { stringResource(R.string.maintenance_default_title) },
                style = MaterialTheme.typography.titleMedium,
                color = ConnectingAmber,
            )
            Text(
                text = maintenance.message.ifBlank { stringResource(R.string.maintenance_default_message) },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun ConfigSyncCard(
    uiState: VpnUiState,
    onSyncRemote: () -> Unit,
) {
    val isSyncing = uiState.syncStatus is SyncStatus.Syncing

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.label_remote_config),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            DetailRow(
                label = stringResource(R.string.label_last_sync),
                value = formatSyncTimestamp(
                    epochMs = uiState.lastSyncEpochMs,
                    neverLabel = stringResource(R.string.value_never_synced),
                ),
            )

            when (val sync = uiState.syncStatus) {
                is SyncStatus.Success -> {
                    SyncFeedbackStrip(
                        icon = Icons.Rounded.CheckCircle,
                        color = ConnectedEmerald,
                        message = sync.message,
                    )
                }

                is SyncStatus.Fallback -> {
                    SyncFeedbackStrip(
                        icon = Icons.Outlined.Info,
                        color = ConnectingAmber,
                        message = sync.message,
                    )
                }

                else -> Unit
            }

            HorizontalDivider(color = HairlineBorder)

            Button(
                onClick = onSyncRemote,
                enabled = !isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("sync_remote_button"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PulseCyan,
                    contentColor = ObsidianBackground,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ObsidianBackground,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSyncing) {
                        stringResource(R.string.action_syncing)
                    } else {
                        stringResource(R.string.action_sync_remote)
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncFeedbackStrip(
    icon: ImageVector,
    color: Color,
    message: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

@Composable
private fun CoreStatusCard(availability: LibboxAvailability) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiProtectedSetup,
                    contentDescription = null,
                    tint = if (availability.isBundled) ConnectedEmerald else ConnectingAmber,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.label_core_engine),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
            Text(
                text = if (availability.isBundled) {
                    stringResource(R.string.msg_libbox_ready_banner, availability.versionLabel)
                } else {
                    stringResource(R.string.msg_libbox_missing_banner)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (availability.isBundled) TextSecondary else ConnectingAmber,
            )
        }
    }
}

private fun formatSyncTimestamp(epochMs: Long, neverLabel: String): String {
    if (epochMs <= 0L) return neverLabel
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
}
