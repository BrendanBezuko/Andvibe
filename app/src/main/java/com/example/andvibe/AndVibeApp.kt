package com.example.andvibe

import android.app.Application

class AndVibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Read by JGit the first time its filesystem helper loads.
        System.setProperty("jgit.fs.useFileAttributesCache", "false")
        DebugMcp.start()
    }
}
