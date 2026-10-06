package de.bollwerk.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClasspathContentTest {
    @Test
    fun loadsBundledExampleContent() {
        val db = ClasspathContent.load()
        assertTrue(db.version.isNotBlank())
        val wood = db.material("wood")
        assertEquals(4f, wood.costPerM)
        assertEquals(100f, wood.hp)
        assertTrue(wood.flammable)

        val mortar = db.weapon("mortar")
        assertEquals(120f, mortar.splashDamage)
        assertEquals(2.5f, mortar.splashRadius)
        assertEquals(WeaponModeDef.BALLISTIC, mortar.mode)
        assertEquals(1100f, mortar.explosionImpulse)
        assertEquals(1.05f, db.device("mortar").barrelLength)

        val mortarDevice = db.deviceIndex("mortar")
        assertEquals(db.weaponIndex("mortar"), db.deviceWeapon[mortarDevice])
        assertEquals(db.techIndex("workshop"), db.deviceRequiredTech[mortarDevice])
        assertEquals(db.techIndex("workshop"), db.deviceGrantsTech[db.deviceIndex("workshop")])
        assertEquals(DeviceCategory.REACTOR, db.device("reactor").category)

        val map = db.map("schlucht")
        assertEquals(120f, map.width)
        assertEquals(2, map.plateaus.size)
        assertEquals(5, map.foundations.count { it.owner == 0 })
        assertEquals(listOf(34f, 34f), map.baseY)
        assertEquals(2, map.startForts.size)
        assertTrue(db.blueprint(map.startForts[0].blueprint).devices.any { it.type == "reactor" })
        assertTrue(db.fingerprint != 0L)
    }
}
