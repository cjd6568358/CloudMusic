package com.example.cloudmusic.player

import kotlin.random.Random

/**
 * docs/03-06 §8 / §15 T07：**随机顺序由业务层生成后下发**，不读 native 队列的随机实现。
 * 同一 seed 在同一 size 下两次生成结果完全一致，冷启动 / 拖拽后可复现。
 */
object ShuffleOrder {
    fun generate(seed: Long, size: Int): List<Int> {
        if (size <= 0) return emptyList()
        val indices = MutableList(size) { it }
        val random = Random(seed)
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return indices
    }
}

/** §8.2 播放模式的下一首决策；返回 null 表示列表结束（[PlayerPhase.ENDED]）。 */
object PlayModeOrdering {

    fun nextIndex(
        current: Int,
        size: Int,
        mode: PlayMode,
        shuffle: List<Int> = emptyList(),
    ): Int? {
        if (size <= 0) return null
        val safe = current.coerceIn(0, size - 1)
        return when (mode) {
            PlayMode.SINGLE_LOOP -> safe
            PlayMode.SHUFFLE -> {
                val order = if (shuffle.size == size) shuffle else ShuffleOrder.generate(0L, size)
                val at = order.indexOf(safe).let { if (it < 0) 0 else it }
                order.getOrNull(at + 1)
            }

            PlayMode.SEQUENCE -> if (safe + 1 < size) safe + 1 else null
            PlayMode.LIST_LOOP -> if (safe + 1 < size) safe + 1 else 0
        }
    }

    fun prevIndex(current: Int, size: Int): Int? {
        if (size <= 0) return null
        val safe = current.coerceIn(0, size - 1)
        return if (safe > 0) safe - 1 else 0
    }
}
