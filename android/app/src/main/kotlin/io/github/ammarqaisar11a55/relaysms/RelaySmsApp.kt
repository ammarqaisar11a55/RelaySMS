package io.github.ammarqaisar11a55.relaysms

import android.app.Application

class RelaySmsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
