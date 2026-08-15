package com.eve.agent

/**
 * Owns one temporary default uncaught-exception handler installation.
 *
 * Restoring is conditional: if another component installed a handler after this
 * lease, that newer handler remains authoritative.
 */
internal class UncaughtExceptionHandlerLease(
    private val current: () -> Thread.UncaughtExceptionHandler? = { Thread.getDefaultUncaughtExceptionHandler() },
    private val replace: (Thread.UncaughtExceptionHandler?) -> Unit = { Thread.setDefaultUncaughtExceptionHandler(it) }
) {
    private var previous: Thread.UncaughtExceptionHandler? = null
    private var installed: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun install(handler: Thread.UncaughtExceptionHandler) {
        if (installed === handler) return
        previous = current()
        installed = handler
        replace(handler)
    }

    @Synchronized
    fun restore() {
        val handler = installed
        if (handler != null && current() === handler) replace(previous)
        previous = null
        installed = null
    }
}
