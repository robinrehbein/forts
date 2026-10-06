package de.bollwerk.content

/**
 * Lädt Content aus Classpath-Ressourcen (`content/index.json` + gelistete Dateien).
 * Funktioniert auf der JVM (Tests, Simrunner) und auf Android, da Java-Ressourcen aus dem
 * `:content`-JAR ins APK übernommen werden.
 */
object ClasspathContent {
    const val INDEX_PATH: String = "content/index.json"

    /** Lädt und löst den gesamten Content auf. */
    fun load(classLoader: ClassLoader = ClasspathContent::class.java.classLoader): ContentDb {
        val index = ContentLoader.parseIndex(read(classLoader, INDEX_PATH))
        val texts = index.files.associateWith { read(classLoader, "content/$it") }
        return ContentLoader.fromJson(texts, index.version)
    }

    private fun read(cl: ClassLoader, path: String): String =
        (cl.getResourceAsStream(path) ?: throw ContentException("missing resource '$path'"))
            .use { it.readBytes().decodeToString() }
}
