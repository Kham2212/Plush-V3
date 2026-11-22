package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.model.VlessProfile
import com.example.model.VmessProfile
import com.example.vpn.VmessUriParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigRepositoryTest {
    @Test
    fun `sync fetches configured URL and preserves selected VLESS through refresh and offline restart`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val url = "https://raw.githubusercontent.com/example/config/main/files.json"
        val vless = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@edge.example.net:443?type=ws&security=tls"
        val vmess = VmessUriParser.DEFAULT_VMESS_URI
        var status = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(url, request.url.toString())
            assertEquals("GET", request.method)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(
                    """{"servers":[{"id":"old","uri":"$vmess"},{"id":"new","link":"$vless"}]}""".toResponseBody(),
                )
                .build()
        }.build()
        val repository = ConfigRepository(app, client)
        val synced = repository.syncFromRemote(url).getOrThrow()
        assertTrue(synced.selectedProfile is VmessProfile)
        assertEquals(listOf("old", "new"), synced.servers.map { it.id })
        assertTrue(repository.selectServer("new").selectedProfile is VlessProfile)
        assertEquals("new", repository.syncFromRemote().getOrThrow().selectedServerId)

        status = 503
        val reopened = ConfigRepository(app, client)
        assertTrue(reopened.syncFromRemote().isFailure)
        assertEquals(vless, reopened.loadCachedConfig().selectedUri)
        assertTrue(reopened.selectServer("old").selectedProfile is VmessProfile)
    }
}
