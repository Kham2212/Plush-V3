package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigRepository
import com.example.model.VpnConnectionState
import com.example.vpn.VmessUriParser
import com.example.vpn.VmessVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VpnViewModelTest {

    @Test
    fun `changing servers while connecting starts the new server and keeps the managed settings`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val firstUri = VmessUriParser.DEFAULT_VMESS_URI
        val firstJson = String(Base64.getDecoder().decode(firstUri.removePrefix("vmess://")))
        val secondUri = "vmess://" + Base64.getEncoder().encodeToString(
            firstJson.replace("vpn.example.com", "hidden.example").toByteArray(),
        )
        val response = """
            {"local_bypass":false,"servers":[
                {"id":"first","name":"First","uri":"$firstUri"},
                {"id":"second","name":"hidden.example:443","uri":"$secondUri"},
                {"id":"second","name":"Duplicate","uri":"$firstUri"}
            ]}
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(response.toResponseBody())
                .build()
        }.build()
        val repository = ConfigRepository(app, client)
        val initial = runBlocking { repository.syncFromRemote().getOrThrow() }
        assertEquals(listOf("first", "second"), initial.servers.map { it.id })

        val viewModel = VpnViewModel(app, repository)
        val appShadow = shadowOf(app)
        try {
            viewModel.startVpnConnection(app)
            val first = appShadow.nextStartedService
            assertEquals(firstUri, first.getStringExtra(VmessVpnService.EXTRA_VMESS_URI))

            viewModel.selectServer("second", app)
            val second = appShadow.nextStartedService
            assertEquals(VmessVpnService.ACTION_CONNECT, second.action)
            assertEquals(secondUri, second.getStringExtra(VmessVpnService.EXTRA_VMESS_URI))
            assertFalse(second.getBooleanExtra(VmessVpnService.EXTRA_LOCAL_BYPASS, true))
            assertEquals(app.getString(com.example.R.string.label_numbered_server, 2), second.getStringExtra(VmessVpnService.EXTRA_SERVER_NAME))
            assertNotEquals(first.getStringExtra(VmessVpnService.EXTRA_SERVER_NAME), second.getStringExtra(VmessVpnService.EXTRA_SERVER_NAME))

            viewModel.disconnectVpn(app)
            assertEquals(VmessVpnService.ACTION_DISCONNECT, appShadow.nextStartedService.action)
        } finally {
            VpnStateHolder.updateState(VpnConnectionState.Disconnected)
        }
    }
}
