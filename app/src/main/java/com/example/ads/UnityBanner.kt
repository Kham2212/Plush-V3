package com.example.ads

import android.app.Activity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

@Composable
internal fun UnityBanner(activity: Activity) {
    BoxWithConstraints {
        if (maxWidth >= 320.dp) {
            val banner = remember(activity) {
                BannerView(activity, BANNER_PLACEMENT, UnityBannerSize(320, 50))
            }
            DisposableEffect(banner) {
                banner.load()
                onDispose { banner.destroy() }
            }
            AndroidView(factory = { banner }, modifier = Modifier.width(320.dp).height(50.dp))
        }
    }
}
