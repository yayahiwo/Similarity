package com.yayahiwo.similarity

import kotlin.math.sqrt

infix fun FloatArray.dot(other: FloatArray) =
    foldIndexed(0.0) { i, acc, cur -> acc + cur * other[i] }.toFloat()

fun normalizeL2(inputArray: FloatArray): FloatArray {
    var normSq = 0.0f
    for (i in inputArray.indices) normSq += inputArray[i] * inputArray[i]
    val norm = sqrt(normSq)
    if (norm == 0.0f) return inputArray
    val inv = 1.0f / norm
    for (i in inputArray.indices) inputArray[i] *= inv
    return inputArray
}
