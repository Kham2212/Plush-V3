package com.example.vpn

import android.content.Context
import com.example.model.LibboxAvailability
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

data class TunPrefixSpec(
    val address: String,
    val prefix: Int,
)

data class TunSpec(
    val mtu: Int,
    val inet4Addresses: List<TunPrefixSpec>,
    val inet6Addresses: List<TunPrefixSpec>,
    val dnsServerAddress: String?,
    val inet4Routes: List<TunPrefixSpec> = emptyList(),
    val inet6Routes: List<TunPrefixSpec> = emptyList(),
)

data class NetworkInterfaceSpec(
    val name: String,
    val index: Int,
    val mtu: Int,
    val flags: Int,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val metered: Boolean,
    val libboxType: String,
)

interface LibboxPlatformCallbacks {
    /** Returns the Android TUN descriptor, kept open by the service until libbox stops. */
    fun openTun(spec: TunSpec): Int
    fun protectSocket(fd: Int)
    fun networkInterfaces(): List<NetworkInterfaceSpec> = emptyList()
    fun startDefaultInterfaceMonitor(listener: Any?)
    fun closeDefaultInterfaceMonitor(listener: Any?)
    fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int
    fun packageNameByUid(uid: Int): String
    fun uidByPackageName(packageName: String): Int
    fun onLog(message: String)
}

/**
 * Dynamic bridge to io.nekohasekai.libbox (from app/libs/libbox.aar).
 * Starts Android VPNs through a libbox API that accepts a PlatformInterface.
 */
object LibboxBridge {

    private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
    private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
    private const val BRIDGE_OPTIONS_CLASS = "io.nekohasekai.libbox.BridgeOptions"
    private const val PLATFORM_INTERFACE_CLASS = "io.nekohasekai.libbox.PlatformInterface"
    private const val INTERFACE_LISTENER_CLASS = "io.nekohasekai.libbox.InterfaceUpdateListener"

    private val SERVICE_METHOD_NAMES = listOf(
        "newService",
        "newBoxService",
        "createService",
        "startService",
        "newInstance",
    )

    @Volatile
    private var coreInitialized = false

    fun detectLibbox(): LibboxAvailability {
        return try {
            val libboxClass = Class.forName(LIBBOX_CLASS)
            val version = runCatching {
                libboxClass.getMethod("version").invoke(null) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "detected"
            LibboxAvailability(isBundled = true, versionLabel = version)
        } catch (_: Throwable) {
            LibboxAvailability(isBundled = false, versionLabel = "Not bundled")
        }
    }

    @Synchronized
    fun initializeCore(context: Context) {
        if (coreInitialized) return
        val libboxClass = Class.forName(LIBBOX_CLASS)
        val setupOptionsClass = Class.forName(SETUP_OPTIONS_CLASS)
        val setupOptions = setupOptionsClass.getDeclaredConstructor().newInstance()

        val baseDir = File(context.filesDir, "sing-box").apply { mkdirs() }
        val workDir = File(baseDir, "work").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "sing-box").apply { mkdirs() }

        invokeOptionalSetter(setupOptions, "setBasePath", baseDir.absolutePath)
        invokeOptionalSetter(setupOptions, "setWorkingPath", workDir.absolutePath)
        invokeOptionalSetter(setupOptions, "setTempPath", tempDir.absolutePath)
        invokeOptionalBooleanSetter(setupOptions, "setFixAndroidStack", true)

        val setupMethod = libboxClass.methods.firstOrNull {
            it.name == "setup" && it.parameterTypes.size == 1
        } ?: error("Libbox.setup(SetupOptions) method not found in libbox.aar")

        unwrapInvocation { setupMethod.invoke(null, setupOptions) }
        coreInitialized = true
    }

    fun startService(callbacks: LibboxPlatformCallbacks, buildConfig: (modern: Boolean) -> String): Any {
        val libboxClass = Class.forName(LIBBOX_CLASS)
        val platformInterfaceClass = Class.forName(PLATFORM_INTERFACE_CLASS)

        val platformProxy = Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass),
        ) { _, method, args ->
            handlePlatformMethod(method, args, callbacks)
        }

        val commandFactory = libboxClass.methods.firstOrNull {
            it.name == "newCommandServer" && it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == platformInterfaceClass
        }
        if (commandFactory != null) {
            return startCommandService(commandFactory, platformProxy, buildConfig(true), callbacks)
        }

        val boxService = invokeLegacyServiceFactory(libboxClass, buildConfig(false), platformProxy)
            ?: error("Service factory returned null")

        val startMethod = boxService.javaClass.methods.firstOrNull {
            it.name == "start" && it.parameterTypes.isEmpty()
        }
        if (startMethod != null) {
            try {
                unwrapInvocation { startMethod.invoke(boxService) }
            } catch (t: Throwable) {
                stopService(boxService)
                throw t
            }
        }
        return boxService
    }

    private fun startCommandService(
        factory: Method,
        platformProxy: Any,
        configJson: String,
        callbacks: LibboxPlatformCallbacks,
    ): AutoCloseable {
        val handlerType = factory.parameterTypes[0]
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { _, method, args ->
            when (method.name) {
                "writeDebugMessage" -> callbacks.onLog(args?.firstOrNull()?.toString().orEmpty())
                else -> defaultReturnValue(method.returnType)
            }
        }
        val server = unwrapInvocation { factory.invoke(null, handler, platformProxy) }
            ?: error("Command server factory returned null")
        val serverType = factory.returnType
        val handle = AutoCloseable {
            try {
                unwrapInvocation { serverType.getMethod("closeService").invoke(server) }
            } finally {
                unwrapInvocation { serverType.getMethod("close").invoke(server) }
            }
        }
        try {
            // No command socket is needed: this app controls the VPN in process.
            val overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions")
            val overrides = overrideType.getDeclaredConstructor().newInstance()
            unwrapInvocation {
                serverType.getMethod("startOrReloadService", String::class.java, overrideType)
                    .invoke(server, configJson, overrides)
            }
        } catch (t: Throwable) {
            stopService(handle)
            throw t
        }
        return handle
    }

    private fun invokeLegacyServiceFactory(
        libboxClass: Class<*>,
        configJson: String,
        platformProxy: Any,
    ): Any? {
        for (methodName in SERVICE_METHOD_NAMES) {
            val candidates = libboxClass.methods.filter { it.name == methodName }

            val twoArg = candidates.firstOrNull { it.parameterTypes.size == 2 }
            if (twoArg != null) {
                return unwrapInvocation { twoArg.invoke(null, configJson, platformProxy) }
            }

            val threeArg = candidates.firstOrNull { it.parameterTypes.size == 3 }
            if (threeArg != null) {
                val thirdArg = buildFallbackArg(threeArg.parameterTypes[2])
                return unwrapInvocation { threeArg.invoke(null, configJson, platformProxy, thirdArg) }
            }
        }

        error("No libbox service factory supports Android PlatformInterface")
    }

    /**
     * Standalone Linux bridge for privileged callers. This API creates its own TUN and
     * must never be used to start the Android VPN.
     */
    fun createStandaloneBridge(): Any {
        val libboxClass = Class.forName(LIBBOX_CLASS)
        val optionsClass = Class.forName(BRIDGE_OPTIONS_CLASS)
        val options = optionsClass.getDeclaredConstructor().newInstance()
        optionsClass.getMethod("setInet4Port", String::class.java).invoke(options, "127.0.0.1")
        optionsClass.getMethod("setBridgeName", String::class.java).invoke(options, "pulse-bridge")
        optionsClass.getMethod("setMTU", Int::class.javaPrimitiveType).invoke(options, 1400)
        optionsClass.getMethod("setRouteTable", Int::class.javaPrimitiveType).invoke(options, 1)
        optionsClass.getMethod("setRuleIndex", Int::class.javaPrimitiveType).invoke(options, 1)
        return unwrapInvocation {
            libboxClass.getMethod("newBridgeService", optionsClass).invoke(null, options)
        } ?: error("Bridge factory returned null")
    }

    private fun buildFallbackArg(paramType: Class<*>): Any? {
        if (paramType.isPrimitive) {
            return defaultReturnValue(paramType)
        }
        return runCatching {
            paramType.getDeclaredConstructor().newInstance()
        }.getOrNull()
    }

    fun stopService(boxService: Any?) {
        if (boxService == null) return
        runCatching {
            if (boxService is AutoCloseable) {
                boxService.close()
            } else {
                val closeMethod = boxService.javaClass.interfaces
                    .firstNotNullOfOrNull { runCatching { it.getMethod("close") }.getOrNull() }
                    ?: boxService.javaClass.getMethod("close")
                closeMethod.invoke(boxService)
            }
        }
    }

    fun notifyDefaultInterface(
        listener: Any?,
        interfaceName: String,
        interfaceIndex: Int,
        isExpensive: Boolean,
        isConstrained: Boolean,
    ) {
        if (listener == null) return
        runCatching {
            val listenerClass = Class.forName(INTERFACE_LISTENER_CLASS)
            val method4 = listenerClass.methods.firstOrNull {
                it.name == "updateDefaultInterface" && it.parameterTypes.size == 4
            }
            if (method4 != null) {
                method4.invoke(listener, interfaceName, interfaceIndex, isExpensive, isConstrained)
                return
            }
            val method2 = listenerClass.methods.firstOrNull {
                it.name == "updateDefaultInterface" && it.parameterTypes.size == 2
            }
            method2?.invoke(listener, interfaceName, interfaceIndex)
        }
    }

    private fun handlePlatformMethod(
        method: Method,
        args: Array<Any?>?,
        callbacks: LibboxPlatformCallbacks,
    ): Any? {
        return when (method.name) {
            "openTun" -> {
                val tunOptions = args?.firstOrNull()
                callbacks.openTun(extractTunSpec(method.parameterTypes.firstOrNull(), tunOptions))
            }

            "usePlatformAutoDetectInterfaceControl" -> true

            "autoDetectInterfaceControl" -> {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                if (fd >= 0) callbacks.protectSocket(fd)
                null
            }

            "startDefaultInterfaceMonitor" -> {
                callbacks.startDefaultInterfaceMonitor(args?.firstOrNull())
                null
            }

            "closeDefaultInterfaceMonitor" -> {
                callbacks.closeDefaultInterfaceMonitor(args?.firstOrNull())
                null
            }

            "useProcFS" -> false

            "findConnectionOwner" -> {
                val proto = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                val srcAddr = args?.getOrNull(1)?.toString().orEmpty()
                val srcPort = (args?.getOrNull(2) as? Number)?.toInt() ?: 0
                val dstAddr = args?.getOrNull(3)?.toString().orEmpty()
                val dstPort = (args?.getOrNull(4) as? Number)?.toInt() ?: 0
                val uid = callbacks.findConnectionOwner(proto, srcAddr, srcPort, dstAddr, dstPort)
                if (method.returnType == Int::class.javaPrimitiveType) {
                    uid
                } else {
                    check(uid >= 0) { "Connection owner unavailable" }
                    val ownerType = method.returnType
                    val owner = ownerType.getDeclaredConstructor().newInstance()
                    ownerType.getMethod("setUserId", Int::class.javaPrimitiveType).invoke(owner, uid)
                    val setter = ownerType.methods.firstOrNull { it.name == "setAndroidPackageNames" }
                    if (setter != null) {
                        val packages = listOf(callbacks.packageNameByUid(uid)).filter { it.isNotBlank() }
                        setter.invoke(owner, toLibboxIterator(setter.parameterTypes.single(), packages))
                    }
                    owner
                }
            }

            "getInterfaces" -> buildNetworkInterfaces(method.returnType, callbacks.networkInterfaces())

            "packageNameByUid" -> {
                val uid = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                callbacks.packageNameByUid(uid)
            }

            "uidByPackageName" -> {
                val pkg = args?.firstOrNull()?.toString().orEmpty()
                callbacks.uidByPackageName(pkg)
            }

            "writeLog" -> {
                val msg = args?.firstOrNull()?.toString().orEmpty()
                callbacks.onLog(msg)
                null
            }

            "underNetworkExtension", "includeAllNetworks" -> false
            "clearDNSCache", "sendNotification" -> null

            else -> defaultReturnValue(method.returnType)
        }
    }

    private fun buildNetworkInterfaces(iteratorType: Class<*>, interfaces: List<NetworkInterfaceSpec>): Any {
        val ifaceType = iteratorType.getMethod("next").returnType
        val libboxClass = Class.forName(LIBBOX_CLASS)
        val values = interfaces.map { spec ->
            val iface = ifaceType.getDeclaredConstructor().newInstance()
            ifaceType.getMethod("setName", String::class.java).invoke(iface, spec.name)
            ifaceType.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(iface, spec.index)
            ifaceType.getMethod("setMTU", Int::class.javaPrimitiveType).invoke(iface, spec.mtu)
            ifaceType.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(iface, spec.flags)
            val type = libboxClass.getField(spec.libboxType).getInt(null)
            ifaceType.getMethod("setType", Int::class.javaPrimitiveType).invoke(iface, type)
            ifaceType.getMethod("setMetered", Boolean::class.javaPrimitiveType).invoke(iface, spec.metered)
            for ((setterName, strings) in listOf("setAddresses" to spec.addresses, "setDNSServer" to spec.dnsServers)) {
                val setter = ifaceType.methods.first { it.name == setterName }
                setter.invoke(iface, toLibboxIterator(setter.parameterTypes.single(), strings))
            }
            iface
        }
        return toLibboxIterator(iteratorType, values)
    }

    private fun toLibboxIterator(type: Class<*>, items: List<Any>): Any {
        var index = 0
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "len" -> items.size - index
                "hasNext" -> index < items.size
                "next" -> items[index++]
                else -> defaultReturnValue(method.returnType)
            }
        }
    }

    private fun extractTunSpec(optionsType: Class<*>?, tunOptions: Any?): TunSpec {
        if (optionsType == null || tunOptions == null) {
            return TunSpec(
                mtu = 1400,
                inet4Addresses = listOf(TunPrefixSpec("172.19.0.1", 30)),
                inet6Addresses = emptyList(),
                dnsServerAddress = "172.19.0.2",
            )
        }

        val mtu = runCatching {
            (optionsType.getMethod("getMTU").invoke(tunOptions) as? Number)?.toInt()
        }.getOrNull()?.takeIf { it > 0 } ?: 1400

        val inet4 = extractRoutePrefixList(optionsType, tunOptions, "getInet4Address")
            .ifEmpty { listOf(TunPrefixSpec("172.19.0.1", 30)) }

        val inet6 = extractRoutePrefixList(optionsType, tunOptions, "getInet6Address")

        val dnsAddress = runCatching {
            val getter = optionsType.getMethod("getDNSServerAddress")
            val result = getter.invoke(tunOptions) ?: return@runCatching null
            val resultType = getter.returnType
            if (resultType.simpleName == "StringIterator") {
                if (resultType.getMethod("hasNext").invoke(result) == true) {
                    resultType.getMethod("next").invoke(result) as? String
                } else {
                    null
                }
            } else {
                resultType.getMethod("getValue").invoke(result) as? String
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return TunSpec(
            mtu = mtu,
            inet4Addresses = inet4,
            inet6Addresses = inet6,
            dnsServerAddress = dnsAddress,
            inet4Routes = extractRoutePrefixList(optionsType, tunOptions, "getInet4RouteRange"),
            inet6Routes = extractRoutePrefixList(optionsType, tunOptions, "getInet6RouteRange"),
        )
    }

    private fun extractRoutePrefixList(
        optionsType: Class<*>,
        tunOptions: Any,
        methodName: String,
    ): List<TunPrefixSpec> {
        val result = mutableListOf<TunPrefixSpec>()
        runCatching {
            val getter = optionsType.getMethod(methodName)
            val iterator = getter.invoke(tunOptions) ?: return@runCatching
            val hasNext = getter.returnType.getMethod("hasNext")
            val next = getter.returnType.getMethod("next")
            while (hasNext.invoke(iterator) as? Boolean == true) {
                val prefixObj = next.invoke(iterator) ?: continue
                val address = next.returnType.getMethod("address").invoke(prefixObj)?.toString()
                    ?: continue
                val prefixLen =
                    (next.returnType.getMethod("prefix").invoke(prefixObj) as? Number)?.toInt()
                        ?: continue
                result.add(TunPrefixSpec(address, prefixLen))
            }
        }
        return result
    }

    private fun invokeOptionalSetter(target: Any, methodName: String, value: String) {
        runCatching {
            target.javaClass.getMethod(methodName, String::class.java).invoke(target, value)
        }
    }

    private fun invokeOptionalBooleanSetter(target: Any, methodName: String, value: Boolean) {
        runCatching {
            target.javaClass.getMethod(methodName, Boolean::class.javaPrimitiveType).invoke(target, value)
        }
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            String::class.java -> ""
            else -> null
        }
    }

    private inline fun <T> unwrapInvocation(block: () -> T): T {
        return try {
            block()
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}
