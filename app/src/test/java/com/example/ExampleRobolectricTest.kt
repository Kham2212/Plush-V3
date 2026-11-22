package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfigRepository
import com.example.vpn.VmessUriParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read pulsevpn app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PulseVPN", appName)
    }

    @Test
    fun `config repository caches remote json server list and preserves fallback when offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val remoteJson = """
            {
              "local_bypass": true,
              "servers": [
                {
                  "id": "uk-1",
                  "name": "UK Free VMess 1",
                  "uri": "${VmessUriParser.DEFAULT_VMESS_URI}",
                  "enabled": true
                },
                {
                  "id": "de-2",
                  "name": "DE Disabled",
                  "uri": "${VmessUriParser.DEFAULT_VMESS_URI}",
                  "enabled": false
                }
              ]
            }
        """.trimIndent()

        var failNext = false
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (failNext) {
                    throw java.io.IOException("Offline")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(remoteJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = ConfigRepository(context, fakeClient)
        repo.resetToDefault()

        val syncOk = repo.syncFromRemote(VmessUriParser.DEFAULT_REMOTE_CONFIG_URL)
        assertTrue(syncOk.isSuccess)
        val synced = syncOk.getOrThrow()
        assertTrue(synced.localBypass)
        assertEquals(1, synced.servers.size)
        assertEquals("uk-1", synced.selectedServerId)

        // Now simulate offline failure: cached JSON server list must remain intact
        failNext = true
        val syncFail = repo.syncFromRemote(VmessUriParser.DEFAULT_REMOTE_CONFIG_URL)
        assertFalse(syncFail.isSuccess)
        val cached = repo.loadCachedConfig()
        assertEquals(1, cached.servers.size)
        assertEquals("UK Free VMess 1", cached.servers[0].name)
    }
}
