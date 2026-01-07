package com.wj.sceneengine.signal

typealias SignalListener = (Signal) -> Unit

/**
 * 信号管理器，用于管理所有信号的变化和订阅
 * 车载应用中，会对接CarService和其他传感器，并在应用启动后立即将所有信号更新到信号管理器中
 */
object SignalManager {
    private val currentSignals = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<SignalListener>()

    /**
     * 更新信号值，并通知所有订阅者
     */
    fun updateSignal(signal: Signal) {
        synchronized(this) {
            println("[updateSignal]: $signal")
            currentSignals[signal.key] = signal.value
            listeners.forEach { it(signal) }
        }
    }

    /**
     * 获取当前信号值
     * @param key 信号键值
     * @return 当前信号值，如果不存在则返回null
     */
    fun getCurrentValue(key: String): Any? = currentSignals[key]

    /**
     * 订阅信号变化，并注册监听器
     */
    fun subscribe(listener: (Signal) -> Unit) {
        synchronized(this) {
            listeners.add(listener)
        }
    }

    /**
     * 取消订阅，移除监听器
     */
    fun unsubscribe(listener: (Signal) -> Unit) {
        synchronized(this) {
            listeners.remove(listener)
        }
    }
}