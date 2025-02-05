package com.valhalla.thor

import android.app.Application
import com.valhalla.thor.model.initIntegrityManager
import com.valhalla.thor.model.initStandardIntegrityProvider

class ThorApplication: Application(){

    override fun onCreate() {
        super.onCreate()

        initStandardIntegrityProvider()

    }

}