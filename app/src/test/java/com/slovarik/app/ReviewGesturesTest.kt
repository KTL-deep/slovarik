package com.slovarik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReviewGesturesTest {
    @Test fun exactUserRequestedDirections() {
        assertEquals(5, swipeGrade(-200f, 0f))
        assertEquals(3, swipeGrade(0f, 200f))
        assertEquals(1, swipeGrade(200f, 0f))
        assertEquals(4, swipeGrade(0f, -200f))
    }
    @Test fun diagonalsUseDominantAxis() {
        assertEquals(5, swipeGrade(-200f, 60f))
        assertEquals(4, swipeGrade(60f, -200f))
    }
    @Test fun everySwipeDirectionHasItsOwnColor() {
        val colors = listOf(5, 3, 1, 4).map(::swipeRatingColor)
        assertEquals(4, colors.distinct().size)
        colors.forEach { assertNotEquals(Lavender, it) }
    }
}
