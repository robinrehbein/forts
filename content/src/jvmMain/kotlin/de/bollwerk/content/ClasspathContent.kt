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
        val (index, texts) = loadTexts(classLoader)
        return ContentLoader.fromJson(texts, index.version)
    }

    /** Index und rohe JSON-Texte (Dateiname → Text) aller im Index gelisteten Dateien. */
    fun loadTexts(classLoader: ClassLoader = ClasspathContent::class.java.classLoader): Pair<ContentIndex, Map<String, String>> {
        val index = ContentLoader.parseIndex(read(classLoader, INDEX_PATH))
        return index to index.files.associateWith { read(classLoader, "content/$it") }
    }

    private fun read(cl: ClassLoader, path: String): String =
        (cl.getResourceAsStream(path) ?: throw ContentException("missing resource '$path'"))
            .use { it.readBytes().decodeToString() }
}
