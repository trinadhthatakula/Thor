package com.valhalla.thor.data.source.local.shizuku

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

object Targets {

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val Q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val S = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val T = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val U = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @get:ChecksSdkIntAtLeast(api = 35)
    val V = Build.VERSION.SDK_INT >= 35

    @get:ChecksSdkIntAtLeast(api = 36)
    val B = Build.VERSION.SDK_INT >= 36

    @get:ChecksSdkIntAtLeast(api = 37)
    val B_MINOR = Build.VERSION.SDK_INT >= 37
}