package de.bollwerk.renderandroid

import android.graphics.Typeface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TypefaceProviderTest {
    private val regular = allocateWithoutConstructor(Typeface::class.java)
    private val bold = allocateWithoutConstructor(Typeface::class.java)
    private val derived = allocateWithoutConstructor(Typeface::class.java)

    @Test
    fun missingBoldIsDerivedFromRegularOnceInsteadOfMixingFamilies() {
        var calls = 0
        val p = TypefaceProvider.of(regular, null) { calls++; assertSame(regular, it); derived }
        repeat(5) {
            assertSame(derived, p.typeface(true))
            assertSame(regular, p.typeface(false))
        }
        assertEquals(1, calls, "nur einmal abgeleitet, nicht je Zeichenaufruf")
    }

    @Test
    fun givenBoldIsUsedAndNothingIsDerived() {
        val p = TypefaceProvider.of(regular, bold) { error("darf nicht abgeleitet werden") }
        assertSame(bold, p.typeface(true))
        assertSame(regular, p.typeface(false))
    }

    @Test
    fun withoutAnyOwnTypefaceTheSystemFontApplies() {
        val p = TypefaceProvider.of(null, null) { error("nichts abzuleiten") }
        // android.jar-Attrappe: Typeface.DEFAULT/DEFAULT_BOLD sind dort null; entscheidend ist, dass nichts abgeleitet wird
        assertNull(p.typeface(true))
        assertNull(p.typeface(false))
    }
}
