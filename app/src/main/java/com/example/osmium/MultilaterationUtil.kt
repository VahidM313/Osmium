package com.example.osmium

import kotlin.math.pow
import kotlin.math.sqrt

object MultilaterationUtil {
    data class Point(val latitude: Double, val longitude: Double)

    fun performMultilateration(points: List<Point>, distances: List<Double>): Point? {
        if (points.size < 3 || points.size != distances.size) return null

        // Initialize matrices
        val A = Array(points.size) { DoubleArray(2) }
        val B = DoubleArray(points.size)

        // Fill matrices
        for (i in points.indices) {
            A[i][0] = 2 * (points[i].latitude - points.last().latitude)
            A[i][1] = 2 * (points[i].longitude - points.last().longitude)
            B[i] = distances.last().pow(2) - distances[i].pow(2) -
                    points.last().latitude.pow(2) + points[i].latitude.pow(2) -
                    points.last().longitude.pow(2) + points[i].longitude.pow(2)
        }

        // Solve using least squares method
        val AT = transpose(A)
        val ATA = multiply(AT, A)
        val ATB = multiply(AT, B)
        val X = solve(ATA, ATB)

        return if (X != null) Point(X[0], X[1]) else null
    }

    private fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> {
        return Array(matrix[0].size) { i ->
            DoubleArray(matrix.size) { j -> matrix[j][i] }
        }
    }

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(a.size) { DoubleArray(b[0].size) }
        for (i in a.indices) {
            for (j in b[0].indices) {
                for (k in b.indices) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }

    private fun multiply(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val result = DoubleArray(a.size)
        for (i in a.indices) {
            for (j in b.indices) {
                result[i] += a[i][j] * b[j]
            }
        }
        return result
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        if (a.size != 2 || a[0].size != 2 || b.size != 2) return null
        val det = a[0][0] * a[1][1] - a[0][1] * a[1][0]
        if (det == 0.0) return null
        return doubleArrayOf(
            (a[1][1] * b[0] - a[0][1] * b[1]) / det,
            (-a[1][0] * b[0] + a[0][0] * b[1]) / det
        )
    }
}