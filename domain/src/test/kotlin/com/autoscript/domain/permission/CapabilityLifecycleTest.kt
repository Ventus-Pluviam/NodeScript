package com.autoscript.domain.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityLifecycleTest {

    @Test
    fun `grant revoke cycle`() {
        assertEquals(CapabilityState.GRANTED, CapabilityLifecycle.onEvent(CapabilityState.DENIED, CapabilityEvent.GRANTED))
        assertEquals(CapabilityState.DENIED, CapabilityLifecycle.onEvent(CapabilityState.GRANTED, CapabilityEvent.REVOKED))
    }

    @Test
    fun `granted can degrade`() {
        assertEquals(CapabilityState.DEGRADED, CapabilityLifecycle.onEvent(CapabilityState.GRANTED, CapabilityEvent.DEGRADED))
        // degraded can be re-granted
        assertEquals(CapabilityState.GRANTED, CapabilityLifecycle.onEvent(CapabilityState.DEGRADED, CapabilityEvent.GRANTED))
        // degraded can be revoked
        assertEquals(CapabilityState.DENIED, CapabilityLifecycle.onEvent(CapabilityState.DEGRADED, CapabilityEvent.REVOKED))
    }

    @Test
    fun `denied cannot directly degrade`() {
        assertThrows(IllegalCapabilityTransition::class.java) {
            CapabilityLifecycle.onEvent(CapabilityState.DENIED, CapabilityEvent.DEGRADED)
        }
    }

    @Test
    fun `request grant and usability`() {
        assertTrue(CapabilityLifecycle.canRequestGrant(CapabilityState.DENIED))
        assertTrue(CapabilityLifecycle.canRequestGrant(CapabilityState.DEGRADED))
        assertFalse(CapabilityLifecycle.canRequestGrant(CapabilityState.GRANTED))

        assertTrue(CapabilityLifecycle.usable(CapabilityState.GRANTED))
        assertTrue(CapabilityLifecycle.usable(CapabilityState.DEGRADED))
        assertFalse(CapabilityLifecycle.usable(CapabilityState.DENIED))
    }
}