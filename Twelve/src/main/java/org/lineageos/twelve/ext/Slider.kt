/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ext

import com.google.android.material.slider.Slider

fun Slider.updateValueAndRange(
    value: Float,
    valueTo: Float = this.valueTo,
    isDragging: Boolean = false,
) {
    val targetValue = (if (isDragging) this.value else value).coerceIn(valueFrom, valueTo)

    if (valueTo < this.valueTo) {
        this.value = targetValue
        this.valueTo = valueTo
    } else {
        this.valueTo = valueTo
        this.value = targetValue
    }
}
