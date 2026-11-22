# PulseVPN — Android VMess (WS+TLS) Client with `sing-box` (`libbox`)

PulseVPN is a clean Kotlin + Jetpack Compose Android VPN client using `VpnService` and `sing-box` (`libbox`) to connect to a VMess over WebSocket + TLS node with:

- **One-tap Connect / Disconnect** (`VpnService.prepare()` + `specialUse` foreground service notification)
- **Local / Private IP Bypass** (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, etc. routed direct)
- **DNS-over-HTTPS (DoH)** (`https://1.1.1.1/dns-query` via tunnel with direct bootstrap resolver)
- **Remote `vmess://` Config Sync** with `SharedPreferences` offline fallback and a selectable server list

---

## Where to Place `libbox.aar`

`app/build.gradle.kts` automatically links any `.aar` or `.jar` placed in `app/libs/`:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

Place your compiled `libbox.aar` at:

```text
app/libs/libbox.aar
```

### How to Build or Obtain `libbox.aar`

1. **From official `SagerNet/sing-box` source** (requires Go 1.22+, Android NDK, and `gomobile`):
   ```bash
   git clone https://github.com/SagerNet/sing-box.git
   cd sing-box
   go install github.com/sagernet/gomobile/cmd/gomobile@latest
   gomobile init
   make lib_android
   # Copy the generated libbox.aar into <your-repo>/app/libs/libbox.aar
   ```
2. **Or download a prebuilt `libbox.aar`** from your release artifacts / CI cache before running Gradle.

> Note: `LibboxBridge.kt` binds `io.nekohasekai.libbox.*` (`Libbox`, `SetupOptions`, `PlatformInterface`, `BoxService`) dynamically so the project compiles cleanly even before `libbox.aar` is dropped into `app/libs/`, and automatically runs the native Go core as soon as `app/libs/libbox.aar` is present.

---

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:c1b051bba1fe817ae2b81d1d740fd2d95c6dd18d32c49fe3773bd2f9126a4824 -->

## Building and testing the Android app

Use JDK 21, Gradle 9.3.1, and Android SDK Platform 36.1. Run these commands from the repository root:

```bash
gradle :app:testDebugUnitTest :app:lintDebug
gradle :app:assembleDebug
```

For a signed debug APK, place the pinned `libbox.aar` in `app/libs/` and generate `debug.keystore` using the commands in the [APK build workflow](.github/workflows/buildapk.yml). The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.


<!-- /coderabbit-ai-generated:v1 -->