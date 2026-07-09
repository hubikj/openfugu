package org.hubik.openfugu.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * centeredOnZeroPressure shifts a pattern so its vertical midpoint sits at
 * 0.5 — zero pressure in the expert-mode mapping — without changing its
 * shape. Patterns are authored against the normal-mode mapping where
 * fraction 0 is zero pressure.
 */
class FuguFlowPatternTest {

    private val eps = 1e-6f

    private fun pattern(vararg fractions: Float) = FlowPattern(
        name = "test",
        description = "",
        durationSec = fractions.size.toFloat(),
        keyframes = fractions.mapIndexed { i, f -> FlowKeyframe(i.toFloat(), f) }
    )

    @Test
    fun `midpoint of a low-lying pattern moves to mid-screen`() {
        // Gentle Wave's real span: 0.05..0.45, all below the expert-mode zero line
        val centered = pattern(0.05f, 0.45f, 0.25f).centeredOnZeroPressure()
        val fractions = centered.keyframes.map { it.targetFraction }
        assertEquals(0.5f, (fractions.min() + fractions.max()) / 2f, eps)
    }

    @Test
    fun `recentering preserves the pattern shape`() {
        val original = pattern(0.05f, 0.55f, 0.05f, 0.75f)
        val centered = original.centeredOnZeroPressure()
        original.keyframes.zip(centered.keyframes).zipWithNext { (a0, b0), (a1, b1) ->
            assertEquals(
                a1.targetFraction - a0.targetFraction,
                b1.targetFraction - b0.targetFraction,
                eps
            )
        }
        original.keyframes.zip(centered.keyframes).forEach { (a, b) ->
            assertEquals(a.timeSec, b.timeSec, eps)
        }
    }

    @Test
    fun `an already centered pattern is unchanged`() {
        val original = pattern(0.3f, 0.7f, 0.5f)
        val centered = original.centeredOnZeroPressure()
        original.keyframes.zip(centered.keyframes).forEach { (a, b) ->
            assertEquals(a.targetFraction, b.targetFraction, eps)
        }
    }
}
