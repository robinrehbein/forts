package de.bollwerk.engine.view

import java.util.concurrent.atomic.AtomicInteger

actual class AtomicInt actual constructor(initial: Int) {
    private val v = AtomicInteger(initial)
    actual fun get(): Int = v.get()
    actual fun getAndSet(value: Int): Int = v.getAndSet(value)
    actual fun compareAndSet(expected: Int, value: Int): Boolean = v.compareAndSet(expected, value)
}
