package com.wj.sceneengine.entity

sealed interface Condition {
    val signalKey: String
    val durationMs: Long

    /**
     * 触发条件：相等
     * key：信号键
     * expected：期望的值
     * duration：持续时间，单位毫秒
     */
    data class Equals(val key: String, val expected: Any, val duration: Long = 0L) : Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }

    /**
     * 触发条件：大于
     * key：信号键
     * threshold：阈值
     * duration：持续时间，单位毫秒
     */
    data class GreaterThan(val key: String, val threshold: Number, val duration: Long = 0L) :
        Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }

    /**
     * 触发条件：小于
     * key：信号键
     * threshold：阈值
     * duration：持续时间，单位毫秒
     */
    data class LessThan(val key: String, val threshold: Number, val duration: Long = 0L) :
        Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }


    /**
     * 触发条件：包含
     * key：信号键
     * value：包含的值
     * duration：持续时间，单位毫秒
     */
    data class Contains(val key: String, val value: Any, val duration: Long = 0L) : Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }

    /**
     * 触发条件：在范围内
     * key：信号键
     * start：开始值
     * end：结束值
     * duration：持续时间，单位毫秒
     */
    data class Between(
        val key: String,
        val start: Number,
        val end: Number,
        val duration: Long = 0L
    ) : Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }

    /***
     * 触发条件：在集合中
     * key：信号键
     * allowedValues：允许的值
     * duration：持续时间，单位毫秒
     */
    data class InSet(val key: String, val allowedValues: Set<Any>, val duration: Long = 0L) :
        Condition {
        override val signalKey: String = key
        override val durationMs: Long = duration
    }

}