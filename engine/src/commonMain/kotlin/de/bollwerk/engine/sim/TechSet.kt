package de.bollwerk.engine.sim

/** Nur-Lese-Sicht auf ein [TechSet]. */
interface TechSetView {
    operator fun contains(index: Int): Boolean
    /** Anzahl 64-Bit-Wörter. */
    val wordCount: Int
    /** Wort [i] (Bit k = Tech 64·i + k). */
    fun word(i: Int): Long
}

/**
 * Bitset über Tech-Indizes (Index in `ContentDb.techs`). Wächst automatisch, kein Hashing.
 */
class TechSet(initialBits: Int = 64) : TechSetView {
    var words: LongArray = LongArray((initialBits + 63) / 64)
        private set

    override operator fun contains(index: Int): Boolean {
        val w = index ushr 6
        return w < words.size && (words[w] and (1L shl (index and 63))) != 0L
    }

    override val wordCount: Int get() = words.size
    override fun word(i: Int): Long = words[i]

    fun add(index: Int) {
        val w = index ushr 6
        if (w >= words.size) words = words.copyOf(w + 1)
        words[w] = words[w] or (1L shl (index and 63))
    }

    fun remove(index: Int) {
        val w = index ushr 6
        if (w < words.size) words[w] = words[w] and (1L shl (index and 63)).inv()
    }

    fun clear() { for (i in words.indices) words[i] = 0L }

    fun copyFrom(other: TechSet) { words = other.words.copyOf() }
}
