package com.eve.agent

import org.junit.Assert.assertSame
import org.junit.Test

class UncaughtExceptionHandlerLeaseTest {
    private fun handler() = Thread.UncaughtExceptionHandler { _, _ -> }

    @Test
    fun restoreReinstatesThePreviousHandler() {
        val original = handler()
        var current: Thread.UncaughtExceptionHandler? = original
        val lease = UncaughtExceptionHandlerLease({ current }, { current = it })
        val eveHandler = handler()

        lease.install(eveHandler)
        assertSame(eveHandler, current)

        lease.restore()
        assertSame(original, current)
    }

    @Test
    fun restoreDoesNotReplaceANewerHandler() {
        val original = handler()
        var current: Thread.UncaughtExceptionHandler? = original
        val lease = UncaughtExceptionHandlerLease({ current }, { current = it })
        val eveHandler = handler()
        val newerHandler = handler()

        lease.install(eveHandler)
        current = newerHandler
        lease.restore()

        assertSame(newerHandler, current)
    }
}
