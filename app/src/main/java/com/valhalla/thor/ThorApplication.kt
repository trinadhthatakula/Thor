package com.valhalla.thor

import android.app.Application

class ThorApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        copyReInstallerScript()
    }

    private fun copyReInstallerScript() {
        assets.open("reinstaller.sh").copyTo(filesDir.resolve("reinstall.sh").outputStream())
    }

}