package com.wj.sceneengine.entity

data class SequenceStep(
    val condition: Condition,
    val timeoutMs: Long = 5000L // 与上一步的最大间隔时间
)