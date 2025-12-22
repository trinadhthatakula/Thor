package com.valhalla.thor.core

import com.valhalla.superuser.Shell
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.core.ThorShellConfig.init

/**
 * Centralized configuration for the Root Shell.
 * Call [init] in your Application.onCreate().
 */
object ThorShellConfig {

    fun init() {
        // Set logging based on build type
        Shell.enableVerboseLogging = BuildConfig.DEBUG

        // Configure the default builder.
        // FLAG_MOUNT_MASTER: Essential for global namespace operations (mounting, etc.)
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
            // If you have specific initializers, add them here
            // .setInitializers(MyInitializer::class.java)
        )
    }
}