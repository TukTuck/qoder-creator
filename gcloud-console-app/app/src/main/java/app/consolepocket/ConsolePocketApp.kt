package app.consolepocket

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.webkit.WebView
import app.consolepocket.di.AppGraph

/**
 * Startpunkt. Drei Dinge passieren hier, bevor die erste UI sichtbar ist:
 *  1. Application-Graph aufbauen (billig, keine I/O)
 *  2. [AppGraph.warmUp] im Hintergrund starten: Regeln laden, Cache konfigurieren,
 *     WebView vorwärmen (L0), gelerntes Prewarm (L4)
 *  3. Remote-Inspection nur im Debug-Build erlauben (chrome://inspect)
 */
class ConsolePocketApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        graph = AppGraph(this)
        graph.warmUp()

        registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    graph.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }

                override fun onTrimMemory(level: Int) {
                    graph.onTrimMemory(level)
                }
            },
        )
    }

    override fun onTerminate() {
        graph.onAppBackgrounded()
        super.onTerminate()
    }
}
