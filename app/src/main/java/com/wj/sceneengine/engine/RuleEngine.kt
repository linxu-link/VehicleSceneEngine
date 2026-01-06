package com.wj.sceneengine.engine

import com.wj.sceneengine.entity.Condition
import com.wj.sceneengine.entity.Scene
import com.wj.sceneengine.entity.SequenceCondition
import com.wj.sceneengine.signal.Signal
import com.wj.sceneengine.signal.SignalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class RuleEngine(private val signalManager: SignalManager) {
    private val scenes = ConcurrentHashMap<String, Scene>()
    private val activeScenes = ConcurrentHashMap.newKeySet<String>()

    // 反向索引
    private val enterSignalToScenes = ConcurrentHashMap<String, MutableSet<String>>()
    private val exitSignalToScenes = ConcurrentHashMap<String, MutableSet<String>>()

    // 条件状态记录
    private val enterStates = ConcurrentHashMap<String, MutableMap<Condition, Long>>()
    private val exitStates = ConcurrentHashMap<String, MutableMap<Condition, Long>>()

    // 序列进度追踪：sceneId -> 当前匹配到的步骤索引
    private val sequenceProgress = ConcurrentHashMap<String, Int>()
    private val lastStepTimestamp = ConcurrentHashMap<String, Long>()

    // 协程与任务管理
    private val enterJobs = ConcurrentHashMap<String, Job>()
    private val exitJobs = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // 监听信号变化
        signalManager.subscribe { signal ->
            scope.launch { evaluateScenesDependingOn(signal) }
        }
    }

    suspend fun addScene(scene: Scene) {
        mutex.withLock {
            scenes[scene.id] = scene

            // 构建反向索引，注意序列条件需要监听其步骤中的信号
            scene.enterConditions.forEach { cond ->
                registerConditionIndex(scene.id, cond, enterSignalToScenes)
            }
            scene.exitConditions.forEach { cond ->
                registerConditionIndex(scene.id, cond, exitSignalToScenes)
            }

            enterStates[scene.id] = ConcurrentHashMap()
            exitStates[scene.id] = ConcurrentHashMap()
        }

        scene.subscribe(
            onEnter = {
                activeScenes.add(scene.id)
                sequenceProgress.remove(scene.id) // 成功进入后重置序列进度
            },
            onExit = {
                activeScenes.remove(scene.id)
            }
        )
    }

    suspend fun removeScene(sceneId: String) {
        mutex.withLock {
            scenes.remove(sceneId)
            enterSignalToScenes.values.forEach { it.remove(sceneId) }
            exitSignalToScenes.values.forEach { it.remove(sceneId) }
            enterStates.remove(sceneId)
            exitStates.remove(sceneId)
        }
    }

    fun isSceneActive(sceneId: String): Boolean {
        return activeScenes.contains(sceneId)
    }

    fun getActiveScenes(): List<Scene> = activeScenes.mapNotNull { scenes[it] }

    private fun registerConditionIndex(
        sceneId: String,
        cond: Condition,
        indexMap: ConcurrentHashMap<String, MutableSet<String>>
    ) {
        if (cond is SequenceCondition) {
            cond.steps.forEach { step ->
                indexMap.getOrPut(step.condition.signalKey) { ConcurrentHashMap.newKeySet() }
                    .add(sceneId)
            }
        } else {
            indexMap.getOrPut(cond.signalKey) { ConcurrentHashMap.newKeySet() }.add(sceneId)
        }
    }

    private suspend fun evaluateScenesDependingOn(signal: Signal) {
        val sceneIdsToEvaluate = mutableSetOf<String>()
        enterSignalToScenes[signal.key]?.let { sceneIdsToEvaluate.addAll(it) }
        exitSignalToScenes[signal.key]?.let { sceneIdsToEvaluate.addAll(it) }

        sceneIdsToEvaluate.forEach { sceneId ->
            scenes[sceneId]?.let { evaluateScene(it, signal) }
        }
    }

    private suspend fun evaluateScene(scene: Scene, triggerSignal: Signal? = null) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wasActive = activeScenes.contains(scene.id)

            if (!wasActive) {
                processEnterLogic(scene, now, triggerSignal)
            } else {
                processExitLogic(scene, now)
            }
        }
    }

    private fun processEnterLogic(scene: Scene, now: Long, triggerSignal: Signal?) {
        val enterState = enterStates.getOrPut(scene.id) { ConcurrentHashMap() }

        // 1. 处理序列条件 (如果有)
        val seqCond = scene.enterConditions.filterIsInstance<SequenceCondition>().firstOrNull()
        val seqSatisfied = if (seqCond != null) {
            updateAndCheckSequence(scene.id, seqCond, triggerSignal, now)
        } else true

        // 2. 处理普通条件 (Duration 逻辑)
        val normalConds = scene.enterConditions.filter { it !is SequenceCondition }
        var allNormalSatisfied = true

        normalConds.forEach { cond ->
            if (evaluateCondition(cond)) {
                enterState.putIfAbsent(cond, now) // 记录首次满足的时间戳
            } else {
                enterState.remove(cond) // 一旦不满足立即清除计时
                allNormalSatisfied = false
            }
        }

        // 3. 综合判定
        if (allNormalSatisfied && seqSatisfied && scene.enterConditions.isNotEmpty()) {
            val allDurationMet = normalConds.all { cond ->
                val startTime = enterState[cond] ?: now
                (now - startTime) >= cond.durationMs
            }

            if (allDurationMet) {
                enterJobs[scene.id]?.cancel()
                scene.enter()
            } else {
                // 计算还需要等待多久
                val remainingWait = normalConds.maxOf { cond ->
                    val elapsed = now - (enterState[cond] ?: now)
                    (cond.durationMs - elapsed).coerceAtLeast(0L)
                }
                scheduleReEvaluation(scene, remainingWait, true)
            }
        } else {
            enterJobs[scene.id]?.cancel()
        }
    }

    private fun updateAndCheckSequence(sceneId: String, seq: SequenceCondition, signal: Signal?, now: Long): Boolean {
        val currentIndex = sequenceProgress.getOrDefault(sceneId, 0)
        if (currentIndex == 0 && signal == null) return false

        // 获取当前正在等待匹配的步骤
        val currentStep = seq.steps[currentIndex]

        // 策略：检查已经匹配成功的那些步骤是否依然有效
        // 如果序列要求 A -> B，当前在等 B，但 A 突然不满足了，序列应重置
        for (i in 0 until currentIndex) {
            if (!evaluateCondition(seq.steps[i].condition)) {
                sequenceProgress[sceneId] = 0 // 前置步骤失效，重置
                return false
            }
        }

        // 检查步骤间是否超时
        val lastTime = lastStepTimestamp.getOrDefault(sceneId, 0L)
        if (currentIndex > 0 && (now - lastTime) > currentStep.timeoutMs) {
            sequenceProgress[sceneId] = 0
            return false
        }

        // 处理当前信号触发的进度推进
        if (signal != null && signal.key == currentStep.condition.signalKey) {
            if (evaluateCondition(currentStep.condition)) {
                val nextIndex = currentIndex + 1
                if (nextIndex >= seq.steps.size) {
                    return true // 序列全匹配
                } else {
                    sequenceProgress[sceneId] = nextIndex
                    lastStepTimestamp[sceneId] = now
                }
            } else if (currentIndex > 0) {
                // 如果是当前步骤的信号发生了变化且变得不满足，重置
                sequenceProgress[sceneId] = 0
            }
        }
        return false
    }

    private fun processExitLogic(scene: Scene, now: Long) {
        val exitState = exitStates.getOrPut(scene.id) { ConcurrentHashMap() }
        var anySatisfied = false

        scene.exitConditions.forEach { cond ->
            if (evaluateCondition(cond)) {
                exitState.putIfAbsent(cond, now)
                if ((now - (exitState[cond] ?: now)) >= cond.durationMs) {
                    anySatisfied = true
                }
            } else {
                exitState.remove(cond)
            }
        }

        if (anySatisfied) {
            exitJobs[scene.id]?.cancel()
            scene.exit()
        } else {
            // 找出最快能达到退出时间条件的等待时间
            val minWait = scene.exitConditions
                .filter { exitState.containsKey(it) }.minOfOrNull { cond ->
                    val elapsed = now - (exitState[cond] ?: now)
                    (cond.durationMs - elapsed).coerceAtLeast(0L)
                }

            if (minWait != null && minWait > 0) {
                scheduleReEvaluation(scene, minWait, false)
            }
        }
    }

    private fun scheduleReEvaluation(scene: Scene, waitMs: Long, isEnter: Boolean) {
        val jobsMap = if (isEnter) enterJobs else exitJobs
        jobsMap[scene.id]?.cancel()
        jobsMap[scene.id] = scope.launch {
            delay(waitMs)
            evaluateScene(scene)
        }
    }

    private fun evaluateCondition(condition: Condition): Boolean {
        val currentValue = signalManager.getCurrentValue(condition.signalKey) ?: return false
        return when (condition) {
            is Condition.Equals -> currentValue == condition.expected
            is Condition.GreaterThan -> (currentValue as? Number)?.toDouble()
                ?.let { it > condition.threshold.toDouble() } ?: false

            is Condition.LessThan -> (currentValue as? Number)?.toDouble()
                ?.let { it < condition.threshold.toDouble() } ?: false

            is Condition.InSet -> condition.allowedValues.contains(currentValue)
            is Condition.Between -> (currentValue as? Number)?.toDouble()
                ?.let { it in condition.start.toDouble()..condition.end.toDouble() } ?: false

            is Condition.Contains -> currentValue.toString().contains(condition.value.toString())
            else -> false
        }
    }
}