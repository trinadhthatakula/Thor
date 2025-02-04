package com.valhalla.thor

import android.Manifest
import android.app.Application
import com.valhalla.thor.model.getPermissions
import com.valhalla.thor.model.rootAvailable

class ThorApplication: Application(){

    override fun onCreate() {
        super.onCreate()
        val freezingPermissions = arrayOf(
            Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            "android.permission.MANAGE_USERS",
            "android.permission.SUSPEND_APPS"
        )
        if(rootAvailable()){
            getPermissions(freezingPermissions)
        }
    }

}