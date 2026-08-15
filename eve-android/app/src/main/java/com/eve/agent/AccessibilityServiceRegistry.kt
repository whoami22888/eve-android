package com.eve.agent

/**
 * Tracks the currently bound accessibility service without allowing an older
 * disconnect callback to clear a newer connection.
 */
internal class AccessibilityServiceRegistry<T : Any> {
    private var current: T? = null

    @Synchronized
    fun bind(service: T) {
        current = service
    }

    @Synchronized
    fun unbind(service: T) {
        if (current === service) {
            current = null
        }
    }

    @Synchronized
    fun get(): T? = current
}
