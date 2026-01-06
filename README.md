在智能汽车快速发展的今天，车载系统早已不再是简单的娱乐或导航工具，而是集成了感知、决策与服务于一体的智能交互平台。其中，“**场景引擎**”（一些车型上也称“**智能场景**”）作为提升用户体验、实现个性化服务的核心能力，正被越来越多的中高端智能汽车所采用。

本文将结合笔者在实际项目中的工作经验，深入分析车载“场景引擎”的核心需求，并探讨其高效设计方法。出于企业保密要求，文中所呈现的场景引擎为一个简化版的原型，仅用于技术原理的阐述与交流。

> 本文源码路径：https://github.com/linxu-link/VehicleSceneEngine




## 一、什么是“场景引擎”？

简单来说，场景引擎是一个**基于多维上下文感知的自动化决策与执行框架**。它能够实时采集车辆状态、用户行为、环境信息等数据，在满足预设条件时，自动触发一系列服务组合（如调节空调、切换媒体、发送通知等）。

虽然应用场景不同，但其底层逻辑与 Android 中的“规则引擎”“自动化任务”或“情境感知功能”（如 Do Not Disturb、自适应亮度）非常相似。

1.  ### 核心需求分析一个高效的场景引擎，首先依赖于对**多源上下文**的实时采集与融合。这些上下文可归纳为以下四类：

-   **设备/车辆状态** 如车速、档位、电量、车门/车窗状态、ADAS（高级驾驶辅助系统）是否启用等。
-   **用户状态** 通过 FaceID 或账号识别驾驶员身份，结合 DMS（驾驶员监控系统）判断疲劳度、注意力等。
-   **环境信息** 包括 GPS 位置、当前时间、天气、路况、室内外温度等。
-   **外设与应用状态** 手机是否连接、蓝牙耳机是否配对、座椅位置、空调设置、当前播放的媒体等。

这些数据共同构成了“上下文感知”的基础，也是触发智能场景的前提。




### 2. 场景定义与管理

仅仅能感知还不够，系统还需支持**灵活的场景编排**与**可靠的执行机制**：

1. 可视化规则编排

-   支持“如果…那么…”（If-Then）逻辑，允许 OEM 厂商或终端用户自定义场景。

> -   示例：`IF 车速 > 60km/h AND 时间在 22:00–6:00 → 自动调暗屏幕 + 关闭通知音`

-   支持复杂条件组合（AND/OR/NOT）、时间窗口限制、状态持续判断（如“连续5分钟未操作”）。

2. 场景优先级与冲突处理

-   当多个场景同时满足时，需按**安全等级**或**用户偏好**进行排序，避免指令冲突。
-   例如：“驾驶模式”应优先于“观影模式”。

3. 预置 + 自学习双模式

-   **出厂预置高频场景**：如“回家模式”“露营模式”“儿童模式”，降低用户使用门槛。
-   **行为学习与推荐**：随着大模型上车，通过 AI 大模型分析用户习惯（如每天 18:00 连接蓝牙、周五开启座椅加热），自动生成建议场景，也变得非常常见。




### 3. 核心设计思想：事件驱动与规则匹配

场景引擎的本质，是一个**轻量级规则引擎**，其核心流程如下：

1.  **事件驱动**：任何上下文信号的变化（如车速更新、时间跳转）都作为事件输入。
1.  **规则匹配**：每个场景对应一组条件规则，当所有条件满足时，该场景被激活。
1.  **解耦与可配置**：规则应以 JSON/YAML 或数据库形式外部化，便于 OTA 更新或 A/B 测试。
1.  **高效评估**：避免每次全量遍历所有规则，采用增量更新、缓存机制或反向索引优化性能。

* * *

## 二、技术实现

为支撑“**感知-决策-执行**”闭环，基于 Kotlin 协程与响应式思想，构建了一套轻量级、线程安全的场景引擎实现。整体架构遵循 **信号驱动 + 规则匹配 + 动作回调** ，核心模块如下：

### 1. 核心数据模型

-   `Signal`：代表上下文中的一个原子状态变化（如 `speed=90`），由 `SignalManager` 统一管理。

```
data class Signal(
    val key: String,
    val value: Any
)
```

-   `Condition`：定义触发条件，采用密封类（`sealed interface`）支持多种判断逻辑（等于、大于、在集合中等），并内建 `durationMs` 字段以支持“持续满足 N 秒才触发”的语义。

```
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
```

-   `Action`：描述要执行的操作（如 `enable_eco_mode`），携带参数字典，便于外部系统解析执行。

> 此处已经高度简化，实际项目中请根据产品需求进行扩展。

```
class Action(
    val name: String,
    val params: Map<String, Any> = emptyMap()
)
```

-   `Scene`：场景的核心载体，包含进入/退出条件列表、进入/退出动作列表，以及可订阅的 `onEnter`/`onExit` 回调。使用 `AtomicReference` 管理监听器列表，保证线程安全下的动态扩展能力。

```
typealias SceneAction = (List<Action>) -> Unit

data class Scene(
    val id: String,
    val name: String,
    val enterConditions: List<Condition>,
    val exitConditions: List<Condition>,
    val enterActions: List<Action>,
    val exitActions: List<Action>,
    private val _onEnterListeners: AtomicReference<List<SceneAction>>,
    private val _onExitListeners: AtomicReference<List<SceneAction>>
) {
    // 对外暴露的 onEnter / onExit
    val onEnter: SceneAction = { actions ->
_onEnterListeners.get().forEach {
it(actions)
        }
}

val onExit: SceneAction = { actions ->
 synchronized(_onExitListeners) {
_onExitListeners.get().forEach {
it(actions)
            }
}
}

 /**
* 忽略触发条件，直接执行场景
*/
fun enter() {
        onEnter(enterActions)
    }

    /**
* 忽略退出条件，直接退出场景
*/
fun exit() {
        onExit(exitActions)
    }

    /**
* 订阅新的进入/退出回调
*/
fun subscribe(onEnter: SceneAction, onExit: SceneAction) {
        _onEnterListeners.updateAndGet { list -> list + onEnter }
        _onExitListeners.updateAndGet { list -> list + onExit }
}

    companion object {
        fun create(
            id: String,
            name: String,
            enterConditions: List<Condition> = emptyList(),
            exitConditions: List<Condition> = emptyList(),
            enterActions: List<Action> = emptyList(),
            exitActions: List<Action> = emptyList(),
            onEnter: SceneAction = {} ,
            onExit: SceneAction = {}
): Scene {
            return Scene(
                id = id,
                name = name,
                enterConditions = enterConditions,
                exitConditions = exitConditions,
                enterActions = enterActions,
                exitActions = exitActions,
                _onEnterListeners = AtomicReference(listOf(onEnter)),
                _onExitListeners = AtomicReference(listOf(onExit))
            )
        }
    }
}
```




### 2. 信号中枢：`SignalManager`

作为全局单例，`SignalManager` 负责：

-   维护当前所有信号的最新值快照（`currentSignals`）；
-   提供 `updateSignal()` 接口供`CarService`或系统服务推送状态变更；
-   支持多观察者订阅（`subscribe`），当任一信号更新时，广播给所有监听者。

设计上数据源与消费逻辑的解耦，任何模块只需监听信号变化即可参与场景评估。

```
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
        println("[updateSignal]: $signal")
        currentSignals[signal.key] = signal.value
        listeners.forEach { it(signal) }
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
        listeners.add(listener)
    }

    /**
* 取消订阅，移除监听器
*/
fun unsubscribe(listener: (Signal) -> Unit) {
        listeners.remove(listener)
    }
}
```




### 3. 规则引擎：`RuleEngine`

`RuleEngine` 是整个系统的调度核心，其关键设计包括：

-   反向索引优化：构建 `signalKey → [sceneId]` 的映射（分为进入/退出两套），使得每次信号更新仅需评估可能受影响的场景，避免全量扫描，显著提升性能。
-   状态持久化：使用 `enterStates` / `exitStates` 记录每个条件的首次满足时间戳，用于计算是否达到 `duration` 要求。
-   协程调度：利用 `kotlinx.coroutines` 实现延迟重评。当条件满足但未达持续时间时，自动调度一个延迟任务，在精确时刻重新评估，避免轮询开销。
-   序列追踪：通过 `sequenceProgress` 和 `lastStepTimestamp` 两个并发安全的 Map，记录每个场景在序列匹配中的当前进度与上一步完成时间，确保时序逻辑正确。

代码太长，完整代码请参考：https://github.com/linxu-link/VehicleSceneEngine/blob/master/app/src/main/java/com/wj/sceneengine/engine/RuleEngine.kt

* * *

## 三、进阶需求分析

在基础规则匹配之上，真实车载场景常涉及更复杂的时空逻辑。引擎通过两种机制应对典型进阶需求：

1.  ### 持续满足 N 秒才触发

需求背景：瞬时信号波动（如雷达误报、GPS跳变）不应导致场景频繁切换。 实现方案：

-   在 `Condition` 中增加 `durationMs` 字段。
-   `RuleEngine` 在首次满足条件时记录时间戳，后续每次评估检查 `(now - startTime) >= durationMs`。
-   若条件中途不满足，则立即清除计时状态。
-   利用协程 `delay()` 精确调度重评任务，而非低效轮询。

```
// 条件状态记录
private val enterStates = ConcurrentHashMap<String, MutableMap<Condition, Long>>()
private val exitStates = ConcurrentHashMap<String, MutableMap<Condition, Long>>()
// 协程与任务管理
private val enterJobs = ConcurrentHashMap<String, Job>()
private val exitJobs = ConcurrentHashMap<String, Job>()
private val mutex = Mutex()
private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
```




### 2. 时序敏感型场景

需求背景：某些场景的触发需严格按顺序执行（如“踩刹车 → 按启动键”）。 实现方案：

-   引入 `SequenceCondition`，将多步操作封装为一个复合条件。

-   引擎内部维护每个场景的序列进度（`sequenceProgress`）和步骤间的时间窗口（`timeoutMs`）。

-   每次信号更新时：

    -   检查前置步骤是否仍有效（防止状态回退导致逻辑错误）；
    -   若当前信号匹配下一步条件且未超时，则推进进度；
    -   若序列完整匹配，则视为条件满足。


```
data class SequenceCondition(
    val steps: List<SequenceStep>
) : Condition {
    override val signalKey: String = steps.firstOrNull()?.condition?.signalKey ?: ""
    override val durationMs: Long = 0L
}

// 序列进度追踪：sceneId -> 当前匹配到的步骤索引
private val sequenceProgress = ConcurrentHashMap<String, Int>()
private val lastStepTimestamp = ConcurrentHashMap<String, Long>()

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
```  


## 四、使用方式

### 1. 满足条件即可自动触发的场景

规则：车辆电量低于 20% 时，自动触发 ECO 模式，电量高于60％ 时，自动退出 ECO 模式

```
// 1. 自动触发ECO模式
// 规则：车辆电量低于 20% 时，自动触发 ECO 模式，电量高于60％ 时，自动退出 ECO 模式
val ecoScene = Scene.create(
    id = "eco_mode",
    name = "ECO 模式",
    enterActions = listOf(Action("enable_eco_mode", mapOf("mode" to "eco"))),
    exitActions = listOf(Action("disable_eco_mode", mapOf("mode" to "eco"))),
    enterConditions = listOf(
        Condition.LessThan("battery_level", 20)
    ),
    exitConditions = listOf(
        Condition.GreaterThan("battery_level", 60)
    ),
    onEnter = { actions ->
 println("🟢 [场景激活] 已进入 ECO 模式：开启节能模式\n")
        // 解析 actions 执行其他操作
    } ,
    onExit = { actions ->
 println("🔴 [场景退出] 已退出 ECO 模式：关闭节能模式\n")
    } ,
)
engine.addScene(ecoScene)

// --- 测试 1: 序列事件验证 ---
println("--- 测试 1: 测试场景引擎（自动触发ECO模式） ---")
SignalManager.updateSignal(Signal("battery_level", 10))
delay(1000)
SignalManager.updateSignal(Signal("battery_level", 30))
delay(1000)
SignalManager.updateSignal(Signal("battery_level", 50))
delay(1000)
SignalManager.updateSignal(Signal("battery_level", 70))
```




执行结果：

-   当引擎接收到电量=10时，自动触发【Eco模式】
-   当引擎接收到电量=70时，自动退出【Eco模式】

```
--- 测试 1: 测试场景引擎（自动触发ECO模式） ---
[updateSignal]: Signal(key=battery_level, value=10)
🟢 [场景激活] 已进入 ECO 模式：开启节能模式

[updateSignal]: Signal(key=battery_level, value=30)
[updateSignal]: Signal(key=battery_level, value=50)
[updateSignal]: Signal(key=battery_level, value=70)
🔴 [场景退出] 已退出 ECO 模式：关闭节能模式
```




### 2. 持续满足规定的时间才可触发的场景

规则：车速必须大于 80 km/h，且持续 5 秒才能触发（防止因瞬间加速误触发）。

```
// 2. 定义带持续时间的场景：高速巡航模式
// 规则：车速必须大于 80 km/h，且持续 5 秒，防止因瞬间加速误触发
val highSpeedScene = Scene.create(
    id = "high_speed_cruise",
    name = "高速巡航模式",
    enterConditions = listOf(
        Condition.GreaterThan("speed", 80, duration = 5000) // 必须持续 5s
    ),
    exitConditions = listOf(
        Condition.LessThan("speed", 70) // 退出则不需要持续时间
    ),
    onEnter = { actions ->
 println("🟢 [场景激活] 已进入高速巡航模式：展开尾翼\n")
        // 解析 actions 执行其他操作，如调整发动机性能
    } ,
    onExit = { actions ->
 println("🔴 [场景退出] 退出高速巡航模式：关闭尾翼\n")
    }
)
engine.addScene(highSpeedScene)

// --- 测试 2: 持续时间验证 ---
println("--- 测试 2: 开始持续时间验证 (Speed > 80 for 5s) ---")
SignalManager.updateSignal(Signal("speed", 100))
println("当前车速 100，等待引擎计时...")
// 模拟 3 秒后查看状态（此时不应激活，因为未满 5s）
delay(3000)
println("计时 3s 时，高速巡航场景是否激活: ${engine.isSceneActive(highSpeedScene.id)}")
// 再等 3 秒（总计 6s），应该激活
delay(3000)
println("计时 6s 时，高速巡航场景是否激活: ${engine.isSceneActive(highSpeedScene.id)}")
SignalManager.updateSignal(Signal("speed", 67))
delay(1000)

// --- 测试 3: 信号抖动拦截 ---
println("--- 测试 3: 测试信号抖动是否会重置计时 ---")
SignalManager.updateSignal(Signal("speed", 60)) // 先降速
delay(500)
SignalManager.updateSignal(Signal("speed", 120)) // 重新提速
delay(3000)
SignalManager.updateSignal(Signal("speed", 50)) // 在 5s 到达前突然降速
delay(3000)
println(
    "信号抖动后（未满5s即中断），高速巡航场景是否激活: ${
        engine.isSceneActive(
            highSpeedScene.id
        )
    }"
)
```

执行结果：

-   车速=100时，【高速巡航模式】没有立即激活
-   持续5s后，自动触发【高速巡航模式】
-   车速=67时，自动退出【高速巡航模式】
-   当车速先递增至120，再下降至50，不会触发【高速巡航模式】

```
--- 测试 2: 开始持续时间验证 (Speed > 80 for 5s) ---
[updateSignal]: Signal(key=speed, value=100)
当前车速 100，等待引擎计时...
计时 3s 时，高速巡航场景是否激活: false
🟢 [场景激活] 已进入高速巡航模式：展开尾翼

计时 6s 时，高速巡航场景是否激活: true
[updateSignal]: Signal(key=speed, value=67)
🔴 [场景退出] 退出高速巡航模式：关闭尾翼

--- 测试 3: 测试信号抖动是否会重置计时 ---
[updateSignal]: Signal(key=speed, value=60)
[updateSignal]: Signal(key=speed, value=120)
[updateSignal]: Signal(key=speed, value=50)
信号抖动后（未满5s即中断），高速巡航场景是否激活: false
```




### 3. 满足规定的序列才可触发的场景

规则：必须先“踩下刹车”，然后在 3 秒内“按下启动键”，才能激活“动力系统”。

```
// 3. 带序列的安全启动校验场景
// 规则：必须先“踩下刹车”，然后在 3 秒内“按下启动键”，才能激活“动力系统”
val securitySequenceScene = Scene.create(
    id = "security_start",
    name = "安全启动模式",
    enterConditions = listOf(
        SequenceCondition(
            steps = listOf(
                SequenceStep(
                    Condition.Equals("brake_pedal", "pressed"), timeoutMs = 0
                ),
                SequenceStep(
                    Condition.Equals("start_button", "clicked"), timeoutMs = 3000
                )
            )
        )
    ),
    enterActions = listOf(Action("enable_motor", mapOf("power" to 100))),
    onEnter = { println("🟢 [场景激活] 安全校验通过：动力系统已就绪！\n") }
)
engine.addScene(securitySequenceScene)

// --- 测试 4: 序列事件验证 ---
println("--- 测试 4: 开始安全启动序列测试 ---")
println("先踩下制动踏板")
SignalManager.updateSignal(Signal("brake_pedal", "pressed"))
delay(1000)
println("松开制动踏板")
SignalManager.updateSignal(Signal("brake_pedal", "unpressed"))
delay(1000)
println("等待 1 秒后按下启动键")
SignalManager.updateSignal(Signal("start_button", "clicked"))
println("安全启动场景是否激活: ${engine.isSceneActive(securitySequenceScene.id)}")
delay(1000)
println("再次制动踏板")
SignalManager.updateSignal(Signal("brake_pedal", "pressed"))
delay(2000)
println("等待 1 秒后按下启动键")
SignalManager.updateSignal(Signal("start_button", "clicked"))
delay(1000)
println(" ============= 测试场景引擎 end ============")
```

执行结果：

-   先踩下制动踏板，紧接着松开制动踏板
-   等待 1 秒后按下启动键，【安全启动模式】未激活
-   再次踩下制动踏板
-   等待 1 秒后按下启动键，自动进入【安全启动模式】

```
--- 测试 4: 开始安全启动序列测试 ---
先踩下制动踏板
[updateSignal]: Signal(key=brake_pedal, value=pressed)
松开制动踏板
[updateSignal]: Signal(key=brake_pedal, value=unpressed)
等待 1 秒后按下启动键
[updateSignal]: Signal(key=start_button, value=clicked)
安全启动场景是否激活: false
再次制动踏板
[updateSignal]: Signal(key=brake_pedal, value=pressed)
等待 1 秒后按下启动键
[updateSignal]: Signal(key=start_button, value=clicked)
🟢 [场景激活] 安全校验通过：动力系统已就绪！
```




## 五、总结

回顾一下，“场景引擎”这个概念听起来颇为高端，但拆开来看，它的核心其实就是一个规则引擎。当然，我们当前的实现仅仅是一个开端，随着大模型的集成和用户行为数据的持续积累，场景引擎有望从被动响应规则演进到主动预测用户意图。希望这篇分享能为你提供一些灵感和思考。

本文源码：https://github.com/linxu-link/VehicleSceneEngine
