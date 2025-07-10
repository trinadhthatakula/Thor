package com.valhalla.thor

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shizuku.Packages
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.model.shizuku.ShizukuState
import com.valhalla.thor.ui.home.HomePage
import com.valhalla.thor.ui.theme.ThorTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class HomeActivity : ComponentActivity() {

    private val shizukuManager : ShizukuManager by viewModels()
    private val requestCode = 1001

    fun checkShizuku() {
        try {
            if (rootAvailable().not()) {
                Packages(this).getApplicationInfoOrNull(packageName = "moe.shizuku.privileged.api").let {
                    if (it == null) {
                        shizukuManager.updateState(ShizukuState.NotInstalled)
                    }else{
                        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
                        Shizuku.addBinderDeadListener (shizukuBinderDeadListener)
                        Shizuku.addRequestPermissionResultListener (shizukuPermissionListener)
                        Log.d("HomeActivity", "root not found trying shizuku")
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(requestCode)
                        }else{
                            shizukuManager.updateState(ShizukuState.Ready)
                            Log.d("HomeActivity", "Shizuku permission granted")
                        }
                    }
                }
            }else {
                Log.d("HomeActivity", "checkShizukuPermission: root found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            shizukuManager.updateState(ShizukuState.NotRunning)
        }
    }

    val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("HomeActivity", "Shizuku binder dead")
        shizukuManager.checkState()
    }

    val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("HomeActivity", "Shizuku binder received")
        shizukuManager.updateState(ShizukuState.NotRunning)
    }

    val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if(requestCode == 1001){
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.d("HomeActivity", "Shizuku permission granted")
                    shizukuManager.updateState(ShizukuState.Ready)
                } else {
                    Log.d("HomeActivity", "Shizuku permission denied")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(
                state = Lifecycle.State.RESUMED
            ){
                checkShizuku()
            }
        }
        setContent {
            ThorTheme {
                HomePage(
                    modifier = Modifier
                ) {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

}
