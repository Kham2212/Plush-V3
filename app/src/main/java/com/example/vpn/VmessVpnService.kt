package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ConfigRepository
import com.example.model.VmessProfile
import com.example.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VmessVpnService : VpnService(), LibboxPlatformCallbacks {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStopping = AtomicBoolean(false)
    private val tunnelStartup = Mutex()

    @Volatile
    private var activeConnectJob: Job? = null

    @Volatile
    private var trafficMonitorJob: Job? = null

    private val boxService = AtomicReference<Any?>(null)

    private val tunFd = AtomicReference<Int?>(null)

    @Volatile
    private var currentProfile: VmessProfile? = null

    @Volatile
    private var currentLocalBypass: Boolean = true

    @Volatile
    private var interfaceCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn(preserveError = false)
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                isStopping.set(false)
                val cached = ConfigRepository(applicationContext).loadCachedConfig()
                val providedUri = intent?.getStringExtra(EXTRA_VMESS_URI) ?: cached.selectedUri
                val localBypass = intent?.getBooleanExtra(EXTRA_LOCAL_BYPASS, cached.localBypass)
                    ?: cached.localBypass
                val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME)

                startForegroundWithNotification(
                    title = getString(R.string.notification_title_connecting),
                    content = getString(R.string.notification_text_establishing),
                )
                connectAsync(providedUri, localBypass, serverName)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        VpnStateHolder.appendLog("VPN permission revoked by system")
        stopVpn(preserveError = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(preserveError = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connectAsync(vmessUri: String, localBypass: Boolean, serverName: String?) {
        activeConnectJob?.cancel()
        stopTrafficMonitor()
        VpnStateHolder.updateState(VpnConnectionState.Connecting, serverName)
        activeConnectJob = serviceScope.launch {
            // Native startup may still be running after its coroutine is canceled.
            tunnelStartup.lock()
            try {
                if (!isActive) return@launch
                closeActiveTunnelOnly()

                val rawProfile = VmessUriParser.parse(vmessUri).getOrThrow()
                val profile = if (serverName.isNullOrBlank()) rawProfile else rawProfile.copy(remarks = serverName)
                val availability = LibboxBridge.detectLibbox()
                check(availability.isBundled) { "libbox.aar is missing or could not be loaded" }
                currentProfile = profile
                currentLocalBypass = localBypass

                VpnStateHolder.appendLog("Initializing sing-box (${availability.versionLabel})…")
                LibboxBridge.initializeCore(applicationContext)

                VpnStateHolder.appendLog(
                    "Starting BoxService -> ${profile.remarks} (${profile.address}:${profile.port})…",
                )
                val startedService = LibboxBridge.startService(this@VmessVpnService) { modern ->
                    SingBoxConfigBuilder.buildConfigJson(profile, localBypass, modern)
                }
                boxService.set(startedService)
                if (!isActive || isStopping.get()) {
                    closeActiveTunnelOnly()
                    return@launch
                }

                withContext(Dispatchers.Main.immediate) {
                    if (isStopping.get()) return@withContext
                    val bypassModeLabel = if (localBypass) "LAN direct" else "Full tunnel"
                    VpnStateHolder.updateState(VpnConnectionState.Connected, profile.remarks)
                    VpnStateHolder.appendLog("Tunnel active (DoH DNS + $bypassModeLabel)")
                    startTrafficMonitor()

                    updateNotification(
                        title = getString(R.string.notification_title_connected),
                        content = getString(R.string.notification_text_tunnel_active),
                    )
                }
            } catch (t: Throwable) {
                closeActiveTunnelOnly()
                if (isActive && !isStopping.get()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (isStopping.get()) return@withContext
                        Log.e(TAG, "Failed to start sing-box VPN", t)
                        val msg = t.message?.takeIf { it.isNotBlank() } ?: "Failed to start sing-box core"
                        VpnStateHolder.updateState(VpnConnectionState.Error(msg), serverName)
                        stopVpn(preserveError = true)
                    }
                }
            } finally {
                tunnelStartup.unlock()
                closeStoppedTunnelIfIdle()
            }
        }
    }

    private fun startTrafficMonitor() {
        stopTrafficMonitor()
        trafficMonitorJob = serviceScope.launch {
            val uid = Process.myUid()
            var prevSnapshot = readByteCounters(uid)
            var prevTimeMs = SystemClock.elapsedRealtime()

            while (isActive && VpnStateHolder.connectionState.value is VpnConnectionState.Connected) {
                delay(1_000L)
                if (!isActive || VpnStateHolder.connectionState.value !is VpnConnectionState.Connected) {
                    break
                }
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedMs = (nowMs - prevTimeMs).coerceAtLeast(1L)
                val currentSnapshot = readByteCounters(uid)

                val downRate = TrafficSpeedCalculator.calculateBytesPerSecond(
                    previousBytes = prevSnapshot.first,
                    currentBytes = currentSnapshot.first,
                    elapsedMillis = elapsedMs,
                )
                val upRate = TrafficSpeedCalculator.calculateBytesPerSecond(
                    previousBytes = prevSnapshot.second,
                    currentBytes = currentSnapshot.second,
                    elapsedMillis = elapsedMs,
                )

                VpnStateHolder.updateSpeed(
                    downloadBytesPerSec = downRate,
                    uploadBytesPerSec = upRate,
                )
                prevSnapshot = currentSnapshot
                prevTimeMs = nowMs
            }
            VpnStateHolder.resetSpeed()
        }
    }

    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        VpnStateHolder.resetSpeed()
    }

    private fun readByteCounters(uid: Int): Pair<Long, Long> {
        val uidRx = TrafficStats.getUidRxBytes(uid)
        val uidTx = TrafficStats.getUidTxBytes(uid)
        val rx = if (uidRx != TrafficStats.UNSUPPORTED.toLong()) {
            uidRx
        } else {
            TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        }
        val tx = if (uidTx != TrafficStats.UNSUPPORTED.toLong()) {
            uidTx
        } else {
            TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        }
        return rx.coerceAtLeast(0L) to tx.coerceAtLeast(0L)
    }

    override fun openTun(spec: TunSpec): Int {
        val profile = currentProfile
        val builder = Builder()
            .setSession("PulseVPN • ${profile?.remarks ?: "VMess"}")
            .setMtu(spec.mtu)

        for (addr in spec.inet4Addresses) {
            builder.addAddress(addr.address, addr.prefix)
        }
        for (addr in spec.inet6Addresses) {
            builder.addAddress(addr.address, addr.prefix)
        }

        if (currentLocalBypass) {
            val tunnelRoutes = BypassRoutesCalculator.computeTunnelIpv4Routes()
            for (route in tunnelRoutes) {
                builder.addRoute(route.address, route.prefixLength)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        val dnsIp = spec.dnsServerAddress ?: "172.19.0.2"
        val routedDnsIp = try {
            builder.addDnsServer(dnsIp)
            dnsIp
        } catch (_: IllegalArgumentException) {
            "1.1.1.1".also { builder.addDnsServer(it) }
        }
        // Keep the tunnel DNS reachable even when LAN routes bypass the VPN.
        builder.addRoute(routedDnsIp, if (':' in routedDnsIp) 128 else 32)

        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val established = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        val fd = established.use { it.detachFd() }
        // libbox duplicates the descriptor. Keep ownership of the original until teardown.
        closeTunFd(tunFd.getAndSet(fd))
        VpnStateHolder.appendLog(
            "TUN established (fd=$fd, mtu=${spec.mtu}, local_bypass=$currentLocalBypass)",
        )
        return fd
    }

    override fun protectSocket(fd: Int) {
        if (!protect(fd)) {
            throw IOException("Android could not protect the VPN socket")
        }
    }

    override fun networkInterfaces(): List<NetworkInterfaceSpec> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks.mapNotNull { network ->
            val links = cm.getLinkProperties(network) ?: return@mapNotNull null
            val capabilities = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = links.interfaceName ?: return@mapNotNull null
            val networkInterface = runCatching { NetworkInterface.getByName(name) }.getOrNull()
                ?: return@mapNotNull null
            if (networkInterface.index <= 0) return@mapNotNull null
            NetworkInterfaceSpec(
                name = name,
                index = networkInterface.index,
                mtu = networkInterface.mtu,
                flags = if (networkInterface.isUp) OsConstants.IFF_UP or OsConstants.IFF_RUNNING else 0,
                addresses = links.linkAddresses.mapNotNull { link ->
                    link.address.hostAddress?.substringBefore('%')?.let { "$it/${link.prefixLength}" }
                },
                dnsServers = links.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                libboxType = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "InterfaceTypeWIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "InterfaceTypeCellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "InterfaceTypeEthernet"
                    else -> "InterfaceTypeOther"
                },
            )
        }
    }

    override fun startDefaultInterfaceMonitor(listener: Any?) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        closeDefaultInterfaceMonitor(listener)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (interfaceCallback === this) dispatchInterfaceUpdate(cm, network, listener)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (interfaceCallback === this) dispatchInterfaceUpdate(cm, network, listener)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (interfaceCallback === this) dispatchInterfaceUpdate(cm, network, listener)
            }

            override fun onLost(network: Network) {
                if (interfaceCallback === this) {
                    val replacement = cm.activeNetwork?.takeUnless { it == network }
                    dispatchInterfaceUpdate(cm, replacement, listener)
                }
            }
        }

        interfaceCallback = callback
        try {
            // This app is excluded from its own VPN, so its default network is the uplink.
            cm.registerDefaultNetworkCallback(callback)
            cm.activeNetwork?.let { dispatchInterfaceUpdate(cm, it, listener) }
        } catch (e: Exception) {
            closeDefaultInterfaceMonitor(listener)
            throw e
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: Any?) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val existing = interfaceCallback ?: return
        interfaceCallback = null
        runCatching { cm.unregisterNetworkCallback(existing) }
    }

    private fun dispatchInterfaceUpdate(cm: ConnectivityManager, network: Network?, listener: Any?) {
        val name = network?.let { cm.getLinkProperties(it)?.interfaceName }
        val caps = network?.let(cm::getNetworkCapabilities)
        if (network != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false) return

        publishDefaultInterfaceUpdate(
            networkPresent = network != null,
            name = name,
            isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            lookupIndex = { NetworkInterface.getByName(it)?.index },
        ) { interfaceName, interfaceIndex, isExpensive ->
            LibboxBridge.notifyDefaultInterface(
                listener = listener,
                interfaceName = interfaceName,
                interfaceIndex = interfaceIndex,
                isExpensive = isExpensive,
                isConstrained = false,
            )
        }
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return -1
        return runCatching {
            cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
        }.getOrDefault(-1)
    }

    override fun packageNameByUid(uid: Int): String {
        return runCatching {
            packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        }.getOrDefault("")
    }

    override fun uidByPackageName(packageName: String): Int {
        if (packageName.isBlank()) return -1
        return runCatching {
            packageManager.getPackageUid(packageName, 0)
        }.getOrDefault(-1)
    }

    override fun onLog(message: String) {
        Log.d(TAG, message)
        VpnStateHolder.appendLog(message)
    }

    private fun closeActiveTunnelOnly() {
        stopTrafficMonitor()
        closeDefaultInterfaceMonitor(null)
        LibboxBridge.stopService(boxService.getAndSet(null))

        closeTunFd(tunFd.getAndSet(null))
    }

    private fun closeTunFd(fd: Int?) {
        if (fd != null) runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun closeStoppedTunnelIfIdle() {
        // If native startup still has the descriptor, its finally block will handle teardown.
        if (!isStopping.get() || !tunnelStartup.tryLock()) return
        try {
            if (isStopping.get()) closeActiveTunnelOnly()
        } finally {
            tunnelStartup.unlock()
        }
    }

    private fun stopVpn(preserveError: Boolean) {
        if (!isStopping.compareAndSet(false, true)) {
            return
        }
        activeConnectJob?.cancel()
        activeConnectJob = null

        closeStoppedTunnelIfIdle()

        if (!preserveError) {
            VpnStateHolder.updateState(VpnConnectionState.Disconnected)
            VpnStateHolder.appendLog("VPN disconnected")
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { stopSelf() }
    }

    private fun startForegroundWithNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VmessVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                disconnectIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                getString(R.string.notification_channel_id),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "VmessVpnService"
        private const val NOTIFICATION_ID = 4109

        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_VMESS_URI = "extra_vmess_uri"
        const val EXTRA_LOCAL_BYPASS = "extra_local_bypass"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        fun startConnect(
            context: Context,
            vmessUri: String,
            localBypass: Boolean = true,
            serverName: String? = null,
        ) {
            val intent = Intent(context, VmessVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_VMESS_URI, vmessUri)
                putExtra(EXTRA_LOCAL_BYPASS, localBypass)
                if (!serverName.isNullOrBlank()) {
                    putExtra(EXTRA_SERVER_NAME, serverName)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopDisconnect(context: Context) {
            val intent = Intent(context, VmessVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}

/** An incomplete lookup is not a network loss; keep the last usable interface. */
internal fun publishDefaultInterfaceUpdate(
    networkPresent: Boolean,
    name: String?,
    isExpensive: Boolean,
    lookupIndex: (String) -> Int?,
    publish: (String, Int, Boolean) -> Unit,
) {
    if (!networkPresent) {
        publish("", -1, false)
        return
    }
    if (name.isNullOrBlank()) return
    val index = try {
        lookupIndex(name)
    } catch (_: SocketException) {
        return
    }
    if (index == null || index <= 0) return
    publish(name, index, isExpensive)
}
