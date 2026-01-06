package com.wj.sceneengine

import com.wj.sceneengine.engine.RuleEngine
import com.wj.sceneengine.entity.Action
import com.wj.sceneengine.entity.Condition
import com.wj.sceneengine.entity.Scene
import com.wj.sceneengine.entity.SequenceCondition
import com.wj.sceneengine.entity.SequenceStep
import com.wj.sceneengine.signal.Signal
import com.wj.sceneengine.signal.SignalManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class Test {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = runBlocking {
            val engine = RuleEngine(SignalManager)

//            // 1. 自动触发ECO模式
//            // 规则：车辆电量低于 20% 时，自动触发 ECO 模式，电量高于40％ 时，自动退出 ECO 模式
//            val ecoScene = Scene.create(
//                id = "eco_mode",
//                name = "ECO 模式",
//                enterActions = listOf(Action("enable_eco_mode", mapOf("mode" to "eco"))),
//                exitActions = listOf(Action("disable_eco_mode", mapOf("mode" to "eco"))),
//                enterConditions = listOf(
//                    Condition.LessThan("battery_level", 20)
//                ),
//                exitConditions = listOf(
//                    Condition.GreaterThan("battery_level", 60)
//                ),
//                onEnter = { actions ->
//                    println("🟢 [场景激活] 已进入 ECO 模式：开启节能模式\n")
//                },
//                onExit = { actions ->
//                    println("🔴 [场景退出] 已退出 ECO 模式：关闭节能模式\n")
//                },
//            )
//            engine.addScene(ecoScene)
//
//            // --- 测试 1: 序列事件验证 ---
//            println("--- 测试 1: 测试场景引擎（自动触发ECO模式） ---")
//            SignalManager.updateSignal(Signal("battery_level", 10))
//            delay(1000)
//            SignalManager.updateSignal(Signal("battery_level", 30))
//            delay(1000)
//            SignalManager.updateSignal(Signal("battery_level", 50))
//            delay(1000)
//            SignalManager.updateSignal(Signal("battery_level", 70))


//            // 2. 定义带持续时间的场景：高速巡航模式
//            // 规则：车速必须大于 80 km/h，且持续 5 秒，防止因瞬间加速误触发
//            val highSpeedScene = Scene.create(
//                id = "high_speed_cruise",
//                name = "高速巡航模式",
//                enterConditions = listOf(
//                    Condition.GreaterThan("speed", 80, duration = 5000) // 必须持续 5s
//                ),
//                exitConditions = listOf(
//                    Condition.LessThan("speed", 70) // 退出则不需要持续时间
//                ),
//                onEnter = { actions ->
//                    println("🟢 [场景激活] 已进入高速巡航模式：展开尾翼\n")
//                    // 解析 actions 执行其他操作，如调整发动机性能
//                },
//                onExit = { actions ->
//                    println("🔴 [场景退出] 退出高速巡航模式：关闭尾翼\n")
//                }
//            )
//            engine.addScene(highSpeedScene)
//
//            // --- 测试 2: 持续时间验证 ---
//            println("--- 测试 2: 开始持续时间验证 (Speed > 80 for 5s) ---")
//            SignalManager.updateSignal(Signal("speed", 100))
//            println("当前车速 100，等待引擎计时...")
//            // 模拟 3 秒后查看状态（此时不应激活，因为未满 5s）
//            delay(3000)
//            println("计时 3s 时，高速巡航场景是否激活: ${engine.isSceneActive(highSpeedScene.id)}")
//            // 再等 3 秒（总计 6s），应该激活
//            delay(3000)
//            println("计时 6s 时，高速巡航场景是否激活: ${engine.isSceneActive(highSpeedScene.id)}")
//            SignalManager.updateSignal(Signal("speed", 67))
//            delay(1000)
//
//            // --- 测试 3: 信号抖动拦截 ---
//            println("--- 测试 3: 测试信号抖动是否会重置计时 ---")
//            SignalManager.updateSignal(Signal("speed", 60)) // 先降速
//            delay(500)
//            SignalManager.updateSignal(Signal("speed", 120)) // 重新提速
//            delay(3000)
//            SignalManager.updateSignal(Signal("speed", 50)) // 在 5s 到达前突然降速
//            delay(3000)
//            println(
//                "信号抖动后（未满5s即中断），高速巡航场景是否激活: ${
//                    engine.isSceneActive(
//                        highSpeedScene.id
//                    )
//                }"
//            )
//
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
//            println(" ============= 测试场景引擎 end ============")


        }
    }
}