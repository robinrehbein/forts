package de.bollwerk.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FingerprintTest {
    private val loaded = ClasspathContent.loadTexts()
    private val texts = loaded.second
    private val base = ContentLoader.fromJson(texts, loaded.first.version)

    @Test
    fun stableAcrossLoads() {
        assertEquals(base.fingerprint, ClasspathContent.load().fingerprint)
        assertEquals(base.fingerprint, ContentLoader.fromJson(texts.toSortedMap().toMap(), "other-version").fingerprint)
        assertNotEquals(0L, base.fingerprint)
    }

    private fun bump(p: JsonPrimitive): JsonPrimitive = p.content.toLongOrNull()?.let { JsonPrimitive(it * 3 / 2 + 1) } ?: JsonPrimitive(p.double * 1.5 + 1.0)

    /** Ersetzt das erste Blatt, das [pick] liefert, durch [change]; null, wenn es keins gibt. */
    private fun mutateFirst(e: JsonElement, key: String?, pick: (String?, JsonPrimitive) -> JsonPrimitive?): JsonElement? {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                val r = mutateFirst(v, k, pick) ?: continue
                return JsonObject(e.toMutableMap().also { it[k] = r })
            }
            is JsonArray -> for ((i, v) in e.withIndex()) {
                val r = mutateFirst(v, key, pick) ?: continue
                return JsonArray(e.toMutableList().also { it[i] = r })
            }
            is JsonPrimitive -> return pick(key, e)
        }
        return null
    }

    @Test
    fun everyContentFileChangesTheFingerprint() {
        val numberBump = { _: String?, p: JsonPrimitive -> if (!p.isString && p.content.toDoubleOrNull() != null) bump(p) else null }
        val nameKeyBump = { k: String?, p: JsonPrimitive -> if (k == "nameKey") JsonPrimitive(p.content + "_x") else null }
        for ((file, text) in texts) {
            var changed = 0
            for (pick in listOf(numberBump, nameKeyBump)) {
                val tree = ContentLoader.json.parseToJsonElement(text)
                val mutated = mutateFirst(tree, null, pick) ?: continue
                val db = ContentLoader.fromJson(texts + (file to mutated.toString()))
                assertNotEquals(base.fingerprint, db.fingerprint, "mutating $file must change the fingerprint")
                changed++
            }
            assertTrue(changed > 0, "no mutable leaf in $file")
        }
    }

    @Test
    fun everyLeafOfTheSmallFilesChangesTheFingerprint() {
        // Jedes einzelne Zahlen-Blatt von techs/devices/materials (Vollständigkeit der kanonischen Kodierung)
        for (file in listOf("materials.json", "weapons.json", "devices.json")) {
            val tree = ContentLoader.json.parseToJsonElement(texts.getValue(file))
            var n = 0
            fun walk(e: JsonElement, path: List<Any>) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> walk(v, path + k) }
                    is JsonArray -> e.forEachIndexed { i, v -> walk(v, path + i) }
                    is JsonPrimitive -> if (!e.isString && e.content.toDoubleOrNull() != null) {
                        fun rep(cur: JsonElement, idx: Int): JsonElement = if (idx == path.size) bump(e) else when (cur) {
                            is JsonObject -> JsonObject(cur.toMutableMap().also { m -> val k = path[idx] as String; m[k] = rep(cur.getValue(k), idx + 1) })
                            is JsonArray -> JsonArray(cur.toMutableList().also { l -> val i = path[idx] as Int; l[i] = rep(l[i], idx + 1) })
                            else -> error("bad path")
                        }
                        val db = ContentLoader.fromJson(texts + (file to rep(tree, 0).toString()))
                        assertNotEquals(base.fingerprint, db.fingerprint, "leaf $path in $file")
                        n++
                    }
                }
            }
            walk(tree, emptyList())
            assertTrue(n > 20, "walked only $n leaves of $file")
        }
    }
}
