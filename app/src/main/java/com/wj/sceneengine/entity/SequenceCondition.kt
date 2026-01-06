package com.wj.sceneengine.entity


data class SequenceCondition(
    val steps: List<SequenceStep>
) : Condition {
    override val signalKey: String = steps.firstOrNull()?.condition?.signalKey ?: ""
    override val durationMs: Long = 0L
}