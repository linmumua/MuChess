package linmumua.chess.render

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

/**
 * 棋盘放置失败时的视觉反馈。
 *
 * [HighlightRenderer] 绑定单个 session（构造需要 anchor），放置失败时还没有 session，
 * 不能复用。这里提供一个不依赖 session 的轻量工具，只负责"在给定 Location 列表上
 * 撒一次失败粒子"，职责不与 HighlightRenderer 交叉。
 */
object PlacementFeedback {

    /**
     * 在每个被阻挡的方块中心撒一次失败粒子，只发给放置者本人。
     *
     * 粒子色系与失败提示语义呼应（对应 `#fda4af`，即 220/164/175 的暖红）。
     * 沿用 [HighlightRenderer] 已有的 `DUST_COLOR_TRANSITION` 写法，不引入新的粒子类型。
     *
     * @param player 放置者，粒子只对他可见
     * @param blocked 被阻挡的方块 Location 列表，来自 [PlaceResult.FurnitureOverlap.blocked]
     */
    fun markBlocked(player: Player, blocked: List<Location>) {
        if (blocked.isEmpty()) return
        // 暖红：与语言文件里 #fda4af 失败色系对应（R=253, G=164, B=175 → R=220, G=100, B=115 过渡）
        val fromColor = Color.fromRGB(0xfd, 0xa4, 0xaf)
        val toColor = Color.fromRGB(0xdc, 0x64, 0x73)
        val data = Particle.DustTransition(fromColor, toColor, DUST_SIZE)

        for (location in blocked) {
            if (location.world != player.world) continue
            // 在方块中心偏上一点撒粒子，让玩家容易看清是哪格挡住的
            val center = location.clone().add(0.5, 0.6, 0.5)
            player.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                center,
                PARTICLE_COUNT,
                SPREAD,
                SPREAD,
                SPREAD,
                data,
            )
        }
    }

    /** 粒子大小，参考 HighlightRenderer.CHECK_DUST_SIZE */
    private const val DUST_SIZE = 1.2f

    /** 每个方块的粒子数量 */
    private const val PARTICLE_COUNT = 8

    /** XYZ 扩散半径，让粒子稍微散开一点而不是全在一个点 */
    private const val SPREAD = 0.2
}
