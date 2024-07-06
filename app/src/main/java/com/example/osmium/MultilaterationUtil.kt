package com.example.osmium

import android.util.Log
import kotlin.math.*

object MultilaterationUtil {
    private const val EARTH_RADIUS = 6371009.0 // in meters
    private const val MAX_ITERATIONS = 100
    private const val CONVERGENCE_THRESHOLD = 0.0001

    data class Point(val latitude: Double, val longitude: Double)
    data class Result(val point: Point, val accuracy: Double)

    fun performMultilateration(points: List<Point>, distances: List<Double>): Point? {
        if (points.size < 3 || points.size != distances.size) {
            Log.e("Multilateration", "Invalid input: Less than 3 points or mismatch between points and distances.")
            return null
        }

        val referencePoint = points[0]
        val cartesianPoints = points.map { toCartesian(it, referencePoint) }

        // Normalize distances
        val maxDistance = distances.maxOrNull() ?: return null
        val normalizedDistances = distances.map { it / maxDistance }

        Log.d("Multilateration", "Reference Point: $referencePoint")
        Log.d("Multilateration", "Cartesian Points: $cartesianPoints")
        Log.d("Multilateration", "Distances: $distances")
        Log.d("Multilateration", "Normalized Distances: $normalizedDistances")

        // Initial guess: centroid of the points
        var estimate = cartesianPoints.reduce { acc, pair -> Pair(acc.first + pair.first, acc.second + pair.second) }
        estimate = Pair(estimate.first / points.size, estimate.second / points.size)

        Log.d("Multilateration", "Initial Estimate: $estimate")

        var iteration = 0
        var prevEstimate = Pair(Double.MAX_VALUE, Double.MAX_VALUE)

        while (iteration < MAX_ITERATIONS) {
            val jacobian = Array(points.size) { DoubleArray(2) }
            val residuals = DoubleArray(points.size)

            for (i in points.indices) {
                val dx = estimate.first - cartesianPoints[i].first
                val dy = estimate.second - cartesianPoints[i].second
                val distance = sqrt(dx * dx + dy * dy)

                jacobian[i][0] = dx / distance
                jacobian[i][1] = dy / distance
                residuals[i] = normalizedDistances[i] - (distance / maxDistance)

                Log.d("Multilateration", "Iteration $iteration - Point $i: dx=$dx, dy=$dy, estimated distance=$distance, residual=${residuals[i]}")
            }

            val JTJ = multiply(transpose(jacobian), jacobian)
            val JTr = multiply(transpose(jacobian), residuals)

            Log.d("Multilateration", "Iteration $iteration - Jacobian Transpose * Jacobian (JTJ): ${JTJ.contentDeepToString()}")
            Log.d("Multilateration", "Iteration $iteration - Jacobian Transpose * Residuals (JTr): ${JTr.contentToString()}")

            val delta = solve(JTJ, JTr)
            if (delta == null) {
                Log.e("Multilateration", "Iteration $iteration - Failed to solve linear system. JTJ might be singular.")
                return null
            }

            estimate = Pair(estimate.first + delta[0], estimate.second + delta[1])

            Log.d("Multilateration", "Iteration $iteration - Updated Estimate: $estimate")

            if (sqrt((estimate.first - prevEstimate.first).pow(2) + (estimate.second - prevEstimate.second).pow(2)) < CONVERGENCE_THRESHOLD) {
                Log.d("Multilateration", "Converged after $iteration iterations.")
                break
            }

            prevEstimate = estimate
            iteration++
        }

        val geographicResult = toGeographic(estimate, referencePoint)

        Log.d("Multilateration", "Final Geographic Result: $geographicResult")

        return Point(geographicResult.latitude, geographicResult.longitude)
    }

    private fun toCartesian(point: Point, referencePoint: Point): Pair<Double, Double> {
        val dLat = Math.toRadians(point.latitude - referencePoint.latitude)
        val dLon = Math.toRadians(point.longitude - referencePoint.longitude)
        val refLat = Math.toRadians(referencePoint.latitude)

        val x = EARTH_RADIUS * dLon * cos(refLat)
        val y = EARTH_RADIUS * dLat

        Log.d("Multilateration", "Converted to Cartesian: Point $point -> Cartesian ($x, $y)")

        return Pair(x, y)
    }

    private fun toGeographic(point: Pair<Double, Double>, referencePoint: Point): Point {
        val refLat = Math.toRadians(referencePoint.latitude)
        val refLon = Math.toRadians(referencePoint.longitude)

        val dLat = point.second / EARTH_RADIUS
        val dLon = point.first / (EARTH_RADIUS * cos(refLat))

        val lat = Math.toDegrees(refLat + dLat)
        val lon = Math.toDegrees(refLon + dLon)

        Log.d("Multilateration", "Converted to Geographic: Cartesian $point -> Geographic ($lat, $lon)")

        return Point(lat, lon)
    }

    private fun solve(A: Array<DoubleArray>, B: DoubleArray): DoubleArray? {
        val n = A.size
        val X = DoubleArray(n)

        val augmentedMatrix = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                augmentedMatrix[i][j] = A[i][j]
            }
            augmentedMatrix[i][n] = B[i]
        }

        Log.d("Multilateration", "Augmented Matrix: ${augmentedMatrix.contentDeepToString()}")

        for (i in 0 until n) {
            var maxRow = i
            for (j in i + 1 until n) {
                if (abs(augmentedMatrix[j][i]) > abs(augmentedMatrix[maxRow][i])) {
                    maxRow = j
                }
            }
            if (augmentedMatrix[maxRow][i] == 0.0) {
                Log.e("Multilateration", "Singular matrix detected. Cannot solve.")
                return null
            }

            val temp = augmentedMatrix[i]
            augmentedMatrix[i] = augmentedMatrix[maxRow]
            augmentedMatrix[maxRow] = temp

            for (j in i + 1 until n) {
                val factor = augmentedMatrix[j][i] / augmentedMatrix[i][i]
                for (k in i until n + 1) {
                    augmentedMatrix[j][k] -= factor * augmentedMatrix[i][k]
                }
            }
        }

        for (i in n - 1 downTo 0) {
            X[i] = augmentedMatrix[i][n] / augmentedMatrix[i][i]
            for (j in 0 until i) {
                augmentedMatrix[j][n] -= augmentedMatrix[j][i] * X[i]
            }
        }

        Log.d("Multilateration", "Solved Linear System Solution: ${X.contentToString()}")

        return X
    }

    private fun multiply(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val rowsA = A.size
        val colsA = A[0].size
        val colsB = B[0].size
        val result = Array(rowsA) { DoubleArray(colsB) }

        for (i in 0 until rowsA) {
            for (j in 0 until colsB) {
                for (k in 0 until colsA) {
                    result[i][j] += A[i][k] * B[k][j]
                }
            }
        }

        return result
    }

    private fun multiply(A: Array<DoubleArray>, B: DoubleArray): DoubleArray {
        val rowsA = A.size
        val colsA = A[0].size
        val result = DoubleArray(rowsA)

        for (i in 0 until rowsA) {
            for (j in 0 until colsA) {
                result[i] += A[i][j] * B[j]
            }
        }

        return result
    }

    private fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        val transposed = Array(cols) { DoubleArray(rows) }

        for (i in 0 until rows) {
            for (j in 0 until cols) {
                transposed[j][i] = matrix[i][j]
            }
        }

        return transposed
    }
}