package de.bollwerk.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentLoaderTest {
    private val mats = """{"materials":[
        {"id":"wood","nameKey":"material_wood","costPerM":4,"hp":100,"density":15,"stiffness":6e6,"tensionLimit":0.05,"thickness":0.32},
        {"id":"metal","nameKey":"material_metal","costPerM":10,"hp":260,"density":50,"stiffness":6e7,"tensionLimit":0.02,"thickness":0.26,"requiresTech":"t1"}
    ]}"""
    private val techs = """{"techs":[{"id":"t1","nameKey":"tech_t1","unlocks":["metal"]},
        {"id":"t2","nameKey":"tech_t2","requires":["t1"]}]}"""

    @Test
    fun mergesFilesInSortedKeyOrderAndResolvesIds() {
        val db = ContentLoader.fromJson(mapOf("b.json" to techs, "a.json" to mats), version = "x")
        assertEquals("x", db.version)
        assertEquals(0, db.materialIndex("wood"))
        assertEquals(1, db.materialIndex("metal"))
        assertEquals(0, db.techIndex("t1"))
        assertEquals(0, db.materialRequiredTech[1])
        assertEquals(-1, db.materialRequiredTech[0])
        assertEquals(listOf(0), db.techRequires[1].toList())
    }

    @Test
    fun unknownReferenceFails() {
        val bad = """{"techs":[{"id":"t","nameKey":"k","requires":["nope"]}]}"""
        val e = assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("x" to bad)) }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun duplicateIdFails() {
        assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("a" to mats, "b" to mats)) }
    }

    @Test
    fun unknownFieldFails() {
        assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("a" to """{"materialz":[]}""")) }
    }

    @Test
    fun techCostFieldsAreNoLongerAccepted() {
        // Kosten/Bauzeit gehören ans Gebäude (DeviceDef), nicht an die Tech
        val old = """{"techs":[{"id":"t","nameKey":"k","costMetal":1}]}"""
        assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("x" to old)) }
    }

    @Test
    fun techBuildingMustRequirePrerequisite() {
        val devs = """{"devices":[{"id":"factory","nameKey":"k","category":"tech","hp":1,"mass":1,"hitRadius":1,"mountOffset":1,"grantsTech":"t2"}]}"""
        val e = assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("a" to mats, "b" to techs, "c" to devs)) }
        assertTrue(e.message!!.contains("factory"))
        val ok = devs.replace("\"grantsTech\"", "\"requiresTech\":\"t1\",\"grantsTech\"")
        ContentLoader.fromJson(mapOf("a" to mats, "b" to techs, "c" to ok))
    }

    @Test
    fun blueprintReferencesAreChecked() {
        val bp = """{"blueprints":[{"id":"bp","nameKey":"k","nodes":[{"x":0,"y":0,"anchored":true},{"x":2,"y":0}],
            "beams":[{"a":0,"b":1,"material":"wood"}]}]}"""
        val db = ContentLoader.fromJson(mapOf("a" to mats, "b" to bp, "c" to techs))
        assertEquals(1, db.blueprint("bp").beams.size)
        assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("a" to mats, "b" to bp.replace("\"wood\"", "\"stone\""), "c" to techs)) }
        assertFailsWith<ContentException> { ContentLoader.fromJson(mapOf("a" to mats, "b" to bp.replace("\"b\":1", "\"b\":5"), "c" to techs)) }
    }

    @Test
    fun fingerprintTracksValuesAndOrder() {
        val a = ContentLoader.fromJson(mapOf("a" to mats, "b" to techs))
        val same = ContentLoader.fromJson(mapOf("a" to mats, "b" to techs), version = "other")
        assertEquals(a.fingerprint, same.fingerprint, "Version ist nur Anzeige")
        val tuned = ContentLoader.fromJson(mapOf("a" to mats.replace("\"costPerM\":4", "\"costPerM\":5"), "b" to techs))
        assertTrue(a.fingerprint != tuned.fingerprint)
        val reordered = ContentLoader.fromJson(mapOf("z" to mats, "b" to techs)) // gleiche Listen, andere Datei-Reihenfolge
        assertEquals(a.materials, reordered.materials)
        val swapped = ContentLoader.fromJson(mapOf("a" to mats.replace("\"wood\"", "\"tmp\"").replace("\"metal\"", "\"wood\"").replace("\"tmp\"", "\"metal\""), "b" to techs))
        assertTrue(a.fingerprint != swapped.fingerprint)
    }

    @Test
    fun unknownIdLookupThrows() {
        val db = ContentLoader.fromJson(mapOf("a" to mats, "b" to techs))
        assertFailsWith<ContentException> { db.weaponIndex("laser") }
    }
}
