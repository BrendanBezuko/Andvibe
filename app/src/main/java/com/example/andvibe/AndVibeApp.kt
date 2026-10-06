package com.example.andvibe

import android.app.Application

class AndVibeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Read by JGit the first time its filesystem helper loads.
        System.setProperty("jgit.fs.useFileAttributesCache", "false")
        graph = AppGraph(this)
        Notify.channels(this)
        DebugMcp.init(this)
    }
}
