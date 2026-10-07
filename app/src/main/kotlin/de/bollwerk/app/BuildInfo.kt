package de.bollwerk.app

import de.bollwerk.engine.GameInfo

/** App-Metadaten (Titel aus [GameInfo], Version aus dem Gradle-Build). */
object BuildInfo {
    const val TITLE: String = GameInfo.TITLE
    val VERSION_NAME: String get() = BuildConfig.VERSION_NAME
}
