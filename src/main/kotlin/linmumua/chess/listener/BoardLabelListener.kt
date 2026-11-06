package linmumua.chess.listener

import linmumua.chess.command.ChessCommandHandler
import linmumua.chess.game.GameManager
import linmumua.chess.game.GamePhase
import linmumua.chess.render.BoardLabelRenderer
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent

/**
 * 棋盘上方悬浮字的右键入口。
 *
 * 悬浮字本体是 TextDisplay，点不到；真正被点的是叠在同一位置的 Interaction 判定框。
 * 这里只做三件事：确认点的是本插件的判定框、取出会话、按对局阶段分派到已有命令入口。
 *
 * 入座与开局的业务逻辑一律复用 [ChessCommandHandler]，不在监听器里重写一份 ——
 * 判定"能不能坐"、"坐哪一方"是对局流程的职责，监听器只负责把点击翻译成意图。
 *
 * 只做右键。左键不触发 [PlayerInteractEntityEvent]，要接的是
 * `PrePlayerAttackEntityEvent`，本次不做：右键已经够用，少一条路径少一处状态不一致。
 *
 * 用 TabooLib 的 `@SubscribeEvent` 注册即可，这是原版事件，不需要像 CE 事件那样手动接线。
 */
object BoardLabelListener {

    /**
     * 右键悬浮字。
     *
     * 主副手各触发一次，所以用 [EquipmentSlot.HAND] 去重，否则一次点击会入座两遍。
     * 反查表拿不到会话就直接放过：那说明点的是别的插件或原版的 Interaction 实体，
     * 不能吞掉别人的事件。
     */
    @SubscribeEvent
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return
        }
        val clicked = event.rightClicked
        if (clicked !is Interaction) {
            return
        }
        val sessionId = BoardLabelRenderer.sessionIdOf(clicked.uniqueId) ?: return

        // 确认是本插件的悬浮字之后才取消：取消得太早会连别人的实体交互一起吞掉
        event.isCancelled = true

        val session = GameManager.session(sessionId) ?: return
        val player = event.player
        val game = session.game
        if (game.phase != GamePhase.WAITING) {
            // 现在这条路径是兜底，不再是常态：判定框只在 WAITING 存在，开局那一刻就被
            // BoardLabelRenderer 摘掉了（它会拦下射向棋盘的右键，见那边类注释）。
            // 能走到这里只有一种情况 —— 点击与摘除撞在同一 tick 前后。
            //
            // 保留它是因为上面已经 isCancelled = true：真撞上时必须在这里停住，
            // 不能往下走进入座与开局那两条分支，否则会把正在下的棋重新摆一遍。
            return
        }

        if (game.isSeatFull()) {
            ChessCommandHandler.start(player, session)
        } else {
            ChessCommandHandler.join(player, session)
        }
    }
}
