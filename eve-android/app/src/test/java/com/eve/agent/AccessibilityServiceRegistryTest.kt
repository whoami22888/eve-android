package com.eve.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityServiceRegistryTest {
    @Test
    fun unbindClearsOnlyTheCurrentService() {
        val registry = AccessibilityServiceRegistry<Any>()
        val first = Any()
        val second = Any()

        assertNull(registry.get())

        registry.bind(first)
        assertSame(first, registry.get())

        registry.unbind(second)
        assertSame(first, registry.get())

        registry.bind(second)
        registry.unbind(first)
        assertSame(second, registry.get())

        registry.unbind(second)
        assertNull(registry.get())
    }
}
