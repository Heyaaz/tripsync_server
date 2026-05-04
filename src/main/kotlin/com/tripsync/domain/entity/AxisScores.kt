package com.tripsync.domain.entity

data class AxisScores(
    val mobility: Int,
    val photo: Int,
    val budget: Int,
    val theme: Int,
) {
    fun l1Distance(other: AxisScores): Int {
        return kotlin.math.abs(mobility - other.mobility) +
                kotlin.math.abs(photo - other.photo) +
                kotlin.math.abs(budget - other.budget) +
                kotlin.math.abs(theme - other.theme)
    }

    fun average(other: AxisScores): AxisScores {
        return AxisScores(
            mobility = (mobility + other.mobility) / 2,
            photo = (photo + other.photo) / 2,
            budget = (budget + other.budget) / 2,
            theme = (theme + other.theme) / 2,
        )
    }
}
