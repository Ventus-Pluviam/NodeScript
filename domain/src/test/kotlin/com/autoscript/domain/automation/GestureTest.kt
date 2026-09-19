package com.autoscript.domain.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GestureTest {

    @Test
    fun `合法手势构造通过`() {
        val g = GestureInput(
            listOf(
                GestureStroke(listOf(GesturePoint(100, 200), GesturePoint(300, 400))),
                GestureStroke(listOf(GesturePoint(0, 0)), startDelayMillis = 50, durationMillis = 200),
            ),
        )
        assertEquals(2, g.strokes.size)
    }

    @Test
    fun `空笔画拒绝`() {
        assertThrows<IllegalArgumentException> { GestureInput(emptyList()) }
    }

    @Test
    fun `空点笔画拒绝`() {
        assertThrows<IllegalArgumentException> { GestureStroke(emptyList()) }
    }

    @Test
    fun `负坐标拒绝`() {
        assertThrows<IllegalArgumentException> { GesturePoint(-1, 0) }
        assertThrows<IllegalArgumentException> { GesturePoint(0, -5) }
    }

    @Test
    fun `非正 duration 与负 startDelay 拒绝`() {
        assertThrows<IllegalArgumentException> {
            GestureStroke(listOf(GesturePoint(1, 1)), durationMillis = 0)
        }
        assertThrows<IllegalArgumentException> {
            GestureStroke(listOf(GesturePoint(1, 1)), startDelayMillis = -1)
        }
    }
}
