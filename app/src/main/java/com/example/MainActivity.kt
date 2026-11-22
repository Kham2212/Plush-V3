package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.ads.RewardedAdsViewModel
import com.example.ads.UnityBanner
import com.example.model.VpnConnectionState
import com.example.ui.PulseVpnScreen
import com.example.ui.VpnViewModel
import com.example.ui.theme.PulseVpnTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()
    private val rewardedAds: RewardedAdsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            PulseVpnTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val adState by rewardedAds.state.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current
                val context = LocalContext.current

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        viewModel.startVpnConnection(context)
                    } else {
                        viewModel.onVpnPermissionDenied()
                    }
                }

                val launchVpnAfterNotificationCheck: () -> Unit = {
                    val prepareIntent = viewModel.prepareVpnIntent(context)
                    if (prepareIntent != null) {
                        vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        viewModel.startVpnConnection(context)
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) {
                    // Proceed to VPN consent regardless of notification choice; Android keeps FGS active
                    launchVpnAfterNotificationCheck()
                }

                val onConnectionAllowed by rememberUpdatedState(newValue = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        launchVpnAfterNotificationCheck()
                    }
                })
                LaunchedEffect(lifecycleOwner, rewardedAds) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        rewardedAds.connectionReady.collect { ready ->
                            if (ready && rewardedAds.consumeConnection()) onConnectionAllowed()
                        }
                    }
                }

                PulseVpnScreen(
                    uiState = uiState,
                    adState = adState,
                    banner = { if (adState.initialized) UnityBanner(this@MainActivity) },
                    onToggleConnection = {
                        when (uiState.connectionState) {
                            is VpnConnectionState.Connected,
                            is VpnConnectionState.Connecting -> {
                                viewModel.disconnectVpn(context)
                            }

                            is VpnConnectionState.Disconnected,
                            is VpnConnectionState.Error -> {
                                rewardedAds.request()
                            }
                        }
                    },
                    onSelectServer = { serverId -> viewModel.selectServer(serverId, context) },
                    onSyncRemote = { viewModel.syncRemoteConfig(isAutoLaunch = false) },
                    onDismissError = { viewModel.clearErrorState() },
                    onConsumeSnackbar = { viewModel.consumeSnackbar() },
                    onConsumeAdUnavailable = { rewardedAds.consumeUnavailableMessage() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rewardedAds.attach(this)
    }

    override fun onPause() {
        rewardedAds.detach(this)
        super.onPause()
    }
}
