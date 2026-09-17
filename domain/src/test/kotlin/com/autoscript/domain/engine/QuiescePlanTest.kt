package com.autoscript.domain.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuiescePlanTest {

    @Test
    fun `full plan completes clean`() {
        val plan = QuiescePlan()
        val run = QuiesceRunningState(plan)

        for (step in plan.steps) {
            val p = run.onStepSuccess(step)
            assertEquals(if (step == plan.steps.last()) StepProgress.COMPLETED else StepProgress.CONTINUE, p)
        }
        assertTrue(run.allCompleted)
        assertEquals(StepResult.CLEAN, run.result())
        assertEquals(StopResult.Clean, run.toStopResult())
    }

    @Test
    fun `timeout mid-plan yields partial completion`() {
        val plan = QuiescePlan(steps = QuiesceStep.entries, stepTimeoutMillis = 1_000)
        val run = QuiesceRunningState(plan)

        assertEquals(StepProgress.CONTINUE, run.onStepSuccess(QuiesceStep.SUSPEND))
        assertEquals(StepProgress.CONTINUE, run.onStepSuccess(QuiesceStep.DRAIN_EVENTS))

        val result = run.onStepTimeout()
        assertEquals(StepResult.PARTIALLY_COMPLETED, result)
        assertFalse(run.allCompleted)
        assertTrue(run.aborted)

        val stop = run.toStopResult()
        assertTrue(stop is StopResult.TimedOut)
        assertTrue((stop as StopResult.TimedOut).partial)   // 已完成至少一步
    }

    @Test
    fun `immediate abort means no partial step done`() {
        val run = QuiesceRunningState(QuiescePlan())
        run.onStepTimeout()
        val stop = run.toStopResult() as StopResult.TimedOut
        assertFalse(stop.partial)
    }

    @Test
    fun `step after abort is ignored`() {
        val run = QuiesceRunningState(QuiescePlan())
        run.onStepTimeout()
        assertEquals(StepProgress.ABORTED, run.onStepSuccess(QuiesceStep.SUSPEND))
    }
}