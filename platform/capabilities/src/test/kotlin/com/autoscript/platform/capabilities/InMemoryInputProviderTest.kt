package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryInputProviderTest {

    private fun swipe() = GestureInput(
        listOf(GestureStroke(listOf(GesturePoint(100, 800), GesturePoint(100, 200)), durationMillis = 300)),
    )

    @Test
    fun `开门派发记录并回 true`() = runBlocking {
        val input = InMemoryInputProvider(canPerformGestures = true)
        assertTrue(input.dispatchGesture(swipe()))
        assertEquals(1, input.dispatched.size)
        assertEquals(2, input.dispatched.single().strokes.single().points.size)
    }

    @Test
    fun `关门回 false 不记录`() = runBlocking {
        val input = InMemoryInputProvider(canPerformGestures = false)
        assertEquals(false, input.dispatchGesture(swipe()))
        assertTrue(input.dispatched.isEmpty())
    }
}
