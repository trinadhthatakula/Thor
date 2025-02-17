package com.valhalla.thor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import com.valhalla.thor.model.AppListener
import com.valhalla.thor.model.initStandardIntegrityProvider

class ThorApplication : Application() {


    override fun onCreate() {
        super.onCreate()

        initStandardIntegrityProvider()



    }

}
