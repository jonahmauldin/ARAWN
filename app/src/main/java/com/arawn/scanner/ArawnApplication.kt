package com.arawn.scanner

import android.app.Application

class ArawnApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Force the encrypted database to be built now, before any Service or
        // Activity can call ArawnDatabase.get(context) without the SQLCipher
        // factory. Application.onCreate always runs first, so the encrypted
        // instance wins the process-wide singleton and every later get() — the
        // scanner service and exporters included — reuses it.
        container.database
    }
}
