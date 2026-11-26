package ru.nsu.vrmp.shaman.core.recognition

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Pure Kotlin signal processing and Dynamic Time Warping utilities. */
object Dtw {

    /**
     * Computes the path-length-normalized multivariate Dynamic Time Warping (DTW) distance
     * between [a] and [b] using Euclidean point distance and a Sakoe-Chiba warping band.
     *
     * The band width is `max(1, ceil(sakoeChibaBandRatio * max(n, m)), |n - m|)` so that
     * a valid warping path from `(0, 0)` to `(n - 1, m - 1)` is always reachable and
     * `distance(a, b) == distance(b, a)`.
     */
    fun distance(
        a: List<DoubleArray>,
        b: List<DoubleArray>,
        sakoeChibaBandRatio: Double = 0.15,
    ): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "DTW sequences must be non-empty" }
        require(sakoeChibaBandRatio in 0.0..1.0) {
            "sakoeChibaBandRatio must be in [0, 1], got $sakoeChibaBandRatio"
        }

        val dim = a[0].size
        require(dim > 0) { "Feature vector dimension must be positive" }
        require(a.all { it.size == dim } && b.all { it.size == dim }) {
            "All feature vectors must have dimension $dim"
        }

        val n = a.size
        val m = b.size
        val band = maxOf(
            1,
            ceil(sakoeChibaBandRatio * maxOf(n, m)).toInt(),
            abs(n - m),
        )

        var prevCost = DoubleArray(m) { Double.POSITIVE_INFINITY }
        var currCost = DoubleArray(m) { Double.POSITIVE_INFINITY }
        var prevLen = IntArray(m) { Int.MAX_VALUE }
        var currLen = IntArray(m) { Int.MAX_VALUE }

        for (i in 0 until n) {
            currCost.fill(Double.POSITIVE_INFINITY)
            currLen.fill(Int.MAX_VALUE)

            val jStart = maxOf(0, i - band)
            val jEnd = minOf(m - 1, i + band)
            val ai = a[i]

            for (j in jStart..jEnd) {
                val stepDist = euclideanDistance(ai, b[j])
                if (i == 0 && j == 0) {
                    currCost[0] = stepDist
                    currLen[0] = 1
                } else {
                    var bestCost = Double.POSITIVE_INFINITY
                    var bestLen = Int.MAX_VALUE

                    if (i > 0 && j > 0) {
                        val c = prevCost[j - 1]
                        val l = prevLen[j - 1]
                        if (c < bestCost || (c == bestCost && l < bestLen)) {
                            bestCost = c
                            bestLen = l
                        }
                    }
                    if (i > 0) {
                        val c = prevCost[j]
                        val l = prevLen[j]
                        if (c < bestCost || (c == bestCost && l < bestLen)) {
                            bestCost = c
                            bestLen = l
                        }
                    }
                    if (j > 0) {
                        val c = currCost[j - 1]
                        val l = currLen[j - 1]
                        if (c < bestCost || (c == bestCost && l < bestLen)) {
                            bestCost = c
                            bestLen = l
                        }
                    }

                    if (bestCost.isFinite()) {
                        currCost[j] = stepDist + bestCost
                        currLen[j] = 1 + bestLen
                    }
                }
            }

            val tmpCost = prevCost
            prevCost = currCost
            currCost = tmpCost

            val tmpLen = prevLen
            prevLen = currLen
            currLen = tmpLen
        }

        val totalCost = prevCost[m - 1]
        val pathLength = prevLen[m - 1]
        return if (totalCost.isFinite() && pathLength > 0) totalCost / pathLength else Double.POSITIVE_INFINITY
    }

    /**
     * Uniformly resamples a multivariate [sequence] to [targetLength] points using linear interpolation.
     */
    fun resample(
        sequence: List<DoubleArray>,
        targetLength: Int,
    ): List<DoubleArray> {
        require(sequence.isNotEmpty()) { "Sequence must be non-empty" }
        require(targetLength > 0) { "targetLength must be positive, got $targetLength" }

        val dim = sequence[0].size
        require(dim > 0) { "Feature vector dimension must be positive" }
        require(sequence.all { it.size == dim }) { "All feature vectors must have dimension $dim" }

        if (sequence.size == 1) {
            return List(targetLength) { sequence[0].copyOf() }
        }
        if (targetLength == 1) {
            return listOf(sequence[0].copyOf())
        }
        if (sequence.size == targetLength) {
            return sequence.map { it.copyOf() }
        }

        val scale = (sequence.size - 1).toDouble() / (targetLength - 1)
        return List(targetLength) { k ->
            val pos = k * scale
            val lo = pos.toInt().coerceIn(0, sequence.lastIndex)
            val hi = (lo + 1).coerceAtMost(sequence.lastIndex)
            val frac = pos - lo
            val vLo = sequence[lo]
            val vHi = sequence[hi]
            DoubleArray(dim) { c -> vLo[c] + frac * (vHi[c] - vLo[c]) }
        }
    }

    /**
     * Z-normalizes each feature channel independently to zero mean and unit population standard
     * deviation. Channels with `std < epsilon` (constant channels) are set to `0.0`.
     */
    fun zNormalize(
        sequence: List<DoubleArray>,
        epsilon: Double = 1e-6,
    ): List<DoubleArray> {
        require(sequence.isNotEmpty()) { "Sequence must be non-empty" }
        require(epsilon > 0.0) { "epsilon must be positive, got $epsilon" }

        val dim = sequence[0].size
        require(dim > 0) { "Feature vector dimension must be positive" }
        require(sequence.all { it.size == dim }) { "All feature vectors must have dimension $dim" }

        val n = sequence.size
        val mean = DoubleArray(dim)
        for (vec in sequence) {
            for (c in 0 until dim) {
                mean[c] += vec[c]
            }
        }
        for (c in 0 until dim) {
            mean[c] /= n
        }

        val variance = DoubleArray(dim)
        for (vec in sequence) {
            for (c in 0 until dim) {
                val diff = vec[c] - mean[c]
                variance[c] += diff * diff
            }
        }

        val std = DoubleArray(dim) { c -> sqrt(variance[c] / n) }

        return List(n) { i ->
            val src = sequence[i]
            DoubleArray(dim) { c ->
                val s = std[c]
                if (!s.isFinite() || s < epsilon) 0.0 else (src[c] - mean[c]) / s
            }
        }
    }

    private fun euclideanDistance(u: DoubleArray, v: DoubleArray): Double {
        var sumSq = 0.0
        for (i in u.indices) {
            val d = u[i] - v[i]
            sumSq += d * d
        }
        return sqrt(sumSq)
    }
}
