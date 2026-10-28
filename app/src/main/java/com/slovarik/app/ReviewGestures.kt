package com.slovarik.app

import kotlin.math.abs

/** User-defined directions: left easy, down hard, right again, up good. */
internal fun swipeGrade(x: Float, y: Float): Int = if (abs(x) > abs(y)) {
    if (x < 0) 5 else 1
} else {
    if (y < 0) 4 else 3
}
