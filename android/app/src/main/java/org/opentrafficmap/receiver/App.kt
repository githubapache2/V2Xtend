package org.opentrafficmap.receiver

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import org.opentrafficmap.shared.SharedFacade

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // M1 smoke check: shared KMP module is on the classpath
        Log.i("V2Xtend", SharedFacade.hello())
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.forceDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }
}
