package de.bollwerk.app

import android.app.Application
import de.bollwerk.app.di.AppGraph

/** Application: erzeugt den [AppGraph] einmal pro Prozess. */
class BollwerkApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
    }
}
