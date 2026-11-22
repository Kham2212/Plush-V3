package com.example.vpn

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigRepository
import com.example.model.VpnConnectionState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VpnMaintenanceTest {
    @Test
    fun `sync stops a running service and cached maintenance rejects queued and sticky starts`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = VmessUriParser.DEFAULT_VMESS_URI
        var body = """{"servers":[{"id":"existing","uri":"$uri"}]}"""
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val repository = ConfigRepository(app, client)
        assertEquals("existing", runBlocking { repository.syncFromRemote().getOrThrow() }.selectedServerId)
        val controller = Robolectric.buildService(VmessVpnService::class.java).create()
        val service = controller.get()

        try {
            VpnStateHolder.updateState(VpnConnectionState.Connected)
            body = """{"maintenance":true,"maintenance_message":"Back soon."}"""
            val synced = runBlocking { repository.syncFromRemote().getOrThrow() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(VpnConnectionState.Disconnected, VpnStateHolder.connectionState.value)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals("existing", synced.selectedServerId)
            assertEquals("Back soon.", synced.maintenance.message)

            code = 503
            assertTrue(runBlocking { repository.syncFromRemote() }.isFailure)
            assertTrue(ConfigRepository(app).loadCachedConfig().maintenance.enabled)
            val connect = Intent(app, VmessVpnService::class.java).setAction(VmessVpnService.ACTION_CONNECT)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(connect, 0, 1))
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertEquals(VpnConnectionState.Disconnected, VpnStateHolder.connectionState.value)

            code = 200
            body = """{"maintenance":false}"""
            val restored = runBlocking { repository.syncFromRemote().getOrThrow() }
            assertFalse(restored.maintenance.enabled)
            assertEquals("existing", restored.selectedServerId)
            assertEquals(uri, restored.selectedUri)
        } finally {
            controller.destroy()
            VpnStateHolder.updateState(VpnConnectionState.Disconnected)
        }
    }
}
