package com.wj.sceneengine.entity

import java.util.concurrent.atomic.AtomicReference

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
            onEnter: SceneAction = {},
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