package linmumua.chess.game

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.game.request.GameRequestDecision
import linmumua.chess.game.request.GameRequestKind
import linmumua.chess.game.request.GameRequests
import linmumua.chess.interact.MoveExecutor
import linmumua.chess.rule.core.Side

/**
 * 求和与悔棋的完整流程。
 *
 * 判定在 [GameRequestDecision]（纯函数、可单测），存取在 [GameRequests]（无 Bukkit），
 * 这里只做三件带副作用的事：找座位、发消息、把答复落到对局状态上。
 * 命令层与菜单层都调本类，不各自实现一遍 —— 那样两条入口的行为迟早对不上。
 *
 * 为什么放在 `game/` 而不是 `game/request/`：那个子包里的两个文件不碰 Bukkit，
 * 是单测直接吃的部分；把带 Player 的流程混进去会毁掉这个性质。
 */
object GameRequestFlow {

    /**
     * 发起求和。
     *
     * 求和不检查轮次：任何一方在自己或对手的回合都可以开口。这与悔棋不同 ——
     * 悔棋撤的是具体某一手，求和只是一个态度。
     */
    fun requestDraw(player: Player) {
        val seat = seatOf(player) ?: return
        val opponent = opponentOf(seat, player) ?: return
        val now = System.currentTimeMillis()
        if (GameRequests.open(seat.session.id, GameRequestKind.DRAW, seat.side, now) == null) {
            Msg.actionBar(player, "request-pending")
            return
        }
        Msg.send(player, "draw-requested")
        Msg.send(opponent, "draw-prompt", "player" to player.name)
    }

    /**
     * 发起悔棋。
     *
     * 三道前置在请求发出**之前**就要过：配置是否允许、有没有可撤的手、最近那手是不是自己走的。
     * 让对手先答应再发现撤不了，白白打断一次对局节奏，而且玩家会以为是插件坏了。
     */
    fun requestUndo(player: Player) {
        val seat = seatOf(player) ?: return
        if (!ChessConfig.settings.game.allowUndo) {
            Msg.actionBar(player, "undo-disabled")
            return
        }
        val game = seat.session.game
        // 对手是机器人：不发请求，直接撤。理由见 GameRequestDecision.botUndoPlies 的注释 ——
        // 机器人不会答复，套上请求流程只会让请求挂到过期，玩家会以为按钮坏了。
        if (BotPlayer.isBotSeat(seat.session.id, seat.side.opposite)) {
            undoAgainstBot(seat, player)
            return
        }
        when (GameRequestDecision.undoEligibility(seat.side, game.board.sideToMove, game.moveCount)) {
            GameRequestDecision.UndoVerdict.NO_HISTORY -> {
                Msg.actionBar(player, "undo-no-history")
                return
            }
            GameRequestDecision.UndoVerdict.NOT_OWN_MOVE -> {
                Msg.actionBar(player, "undo-not-own-move")
                return
            }
            GameRequestDecision.UndoVerdict.ALLOWED -> Unit
        }
        val opponent = opponentOf(seat, player) ?: return
        val now = System.currentTimeMillis()
        if (GameRequests.open(seat.session.id, GameRequestKind.UNDO, seat.side, now) == null) {
            Msg.actionBar(player, "request-pending")
            return
        }
        Msg.send(player, "undo-requested")
        Msg.send(opponent, "undo-prompt", "player" to player.name)
    }

    /**
     * 人机对局的悔棋，单方面生效。
     *
     * 与机器人思考中的竞态不用在这里处理：撤手会让轮次落回玩家，在途那一手到
     * [linmumua.chess.interact.MoveExecutor] 落子前会被它自己的 `sideToMove != side`
     * 校验挡掉，不会落到已经回退的局面上。
     */
    private fun undoAgainstBot(seat: Seat, player: Player) {
        val game = seat.session.game
        val plies = GameRequestDecision.botUndoPlies(seat.side, game.board.sideToMove, game.moveCount)
        if (plies == 0) {
            Msg.actionBar(player, "undo-no-history")
            return
        }
        commitUndo(seat.session, seat.side, plies, System.currentTimeMillis())
    }

    /**
     * 答复一条请求。
     *
     * [accept] 为 true 表示同意。同意与拒绝走同一个入口，因为两者的前置校验完全一样，
     * 分成两个方法只会让"没有请求""已过期""这是你自己发的"这三段校验各写两遍。
     */
    fun answer(player: Player, kind: GameRequestKind, accept: Boolean) {
        val seat = seatOf(player) ?: return
        val now = System.currentTimeMillis()
        val answered = GameRequests.answer(seat.session.id, kind, seat.side, now)
        when (answered.verdict) {
            GameRequestDecision.AnswerVerdict.NONE -> {
                Msg.actionBar(player, "request-missing")
                return
            }
            GameRequestDecision.AnswerVerdict.EXPIRED -> {
                Msg.actionBar(player, "request-outdated")
                return
            }
            GameRequestDecision.AnswerVerdict.OWN_REQUEST -> {
                Msg.actionBar(player, "request-own")
                return
            }
            GameRequestDecision.AnswerVerdict.ALLOWED -> Unit
        }
        val request = answered.request ?: return
        // 发起方可能在这三十秒里下线了。答复照常生效（局面变化是双方的事，不该因为
        // 一方掉线就作废），只是那条"对手同意了"发不出去而已。
        val requester = playerAt(seat.session, request.requester)
        if (!accept) {
            Msg.send(player, if (kind == GameRequestKind.DRAW) "draw-decline-sent" else "undo-decline-sent")
            requester?.let { Msg.send(it, if (kind == GameRequestKind.DRAW) "draw-declined" else "undo-declined") }
            return
        }
        when (kind) {
            GameRequestKind.DRAW -> applyDraw(seat.session)
            GameRequestKind.UNDO -> applyUndo(seat.session, request.requester, player, requester, now)
        }
    }

    /** 和棋落地。终局播报会说明"双方同意和棋"，所以这里不再单独发一条"对手同意了" */
    private fun applyDraw(session: GameSession) {
        val result = GameResult.DrawByAgreement
        session.game.finishWith(result)
        MoveExecutor.announceResult(session, result)
        GameManager.markDirty()
    }

    /**
     * 悔棋落地。
     *
     * 这里**必须重新判一次** [GameRequestDecision.undoEligibility]：请求发出后对手若又走了一手，
     * 撤销撤掉的就是对手刚走那一手而不是发起方那一手 —— 同意悔棋的人反而丢了自己的着法。
     * 三十秒的过期窗口挡不住这种情况（走一手可能只要两秒），只有按当前局面重判才挡得住。
     * 这与菜单点击时重读状态是同一个原则：请求里的信息是快照，快照不能当作现状用。
     */
    private fun applyUndo(
        session: GameSession,
        requester: Side,
        responder: Player,
        requesterPlayer: Player?,
        now: Long,
    ) {
        val game = session.game
        if (GameRequestDecision.undoEligibility(requester, game.board.sideToMove, game.moveCount)
            != GameRequestDecision.UndoVerdict.ALLOWED
        ) {
            Msg.actionBar(responder, "undo-outdated")
            requesterPlayer?.let { Msg.actionBar(it, "undo-outdated") }
            return
        }
        commitUndo(session, requester, plies = 1, now = now)
    }

    /**
     * 把悔棋落到对局状态上。双人与人机两条路都走这里。
     *
     * 抽出来是因为这串副作用有顺序要求（见下面每一步的注释），两份实现迟早会漂移 ——
     * 漏掉其中任何一步都是很难复现的 bug：残留浮起实体、棋盘与局面不一致、
     * 或者悔棋后只剩三秒可想。
     *
     * @param plies 要撤几手。双人恒为 1，人机由 [GameRequestDecision.botUndoPlies] 算
     */
    private fun commitUndo(session: GameSession, requester: Side, plies: Int, now: Long) {
        val game = session.game
        // 先松开选中态再重绘：选中的棋子是浮起放大状态，松开要去改那个显示实体的
        // Transformation。反过来先重绘会把实体删掉，松开时就摸到一个已经不存在的实体。
        MoveExecutor.releaseAllSelections(session)
        // 撤不动就停下，但**仍要往下走完重绘**：已经撤掉的那几手必须反映到棋盘上，
        // 半途 return 会留下"局面退了、棋盘没退"的错位。
        // 不用 repeat：那里面的 return@repeat 只跳过一次迭代、不会终止循环，
        // 写成 for + break 才是想要的语义。
        for (i in 0 until plies) {
            if (game.undoLast() == null) {
                break
            }
        }
        // 局面回退了，整副重铺。renderAll 自带清场，不会叠出两副棋子。
        // 不做增量还原（只动起点终点两枚）的原因是围棋一手可提一整块，
        // 增量路径要把提子也逐个补回来，比全量重铺更容易漏。
        session.renderer.renderAll(game.board)
        session.refreshLabel()
        // 重置本手计时。不重置的话，撤回来的这一手会继承对手那一手用掉的时间 ——
        // 玩家会看到悔棋成功后只剩三秒可想，那不是他自己耗掉的。
        // 借 markStarted 而不是放开 lastMoveAt 的 setter：那个方法本来就是
        // "单独校准计时起点"，存档恢复也走它，悔棋要的正是同一件事。
        game.markStarted(now)
        for (viewer in session.audience()) {
            Msg.sendRaw(viewer, "undo-accepted", "side" to Msg.sideName(viewer, requester, session.gameType))
        }
        GameManager.markDirty()
    }

    /**
     * 找出这个玩家所在的座位，顺带校验对局阶段。
     *
     * 求和与悔棋都只在 [GamePhase.PLAYING] 有意义，观战者与旁人一律挡在外面。
     * 提示复用 `not-in-game` —— 对玩家来说"你不在对局里"和"这局还没开始"
     * 要做的下一步是一样的（先入座、先开局），不必分成两条文案。
     */
    private fun seatOf(player: Player): Seat? {
        val session = GameManager.sessionOf(player.uniqueId)
        val side = session?.game?.sideOf(player.uniqueId)
        if (session == null || side == null || session.game.phase != GamePhase.PLAYING) {
            Msg.actionBar(player, "not-in-game")
            return null
        }
        return Seat(session, side)
    }

    /**
     * 取对手的在线玩家对象，取不到就地报错并返回 null。
     *
     * 两种取不到的情况分开提示，因为玩家能做的事不同：
     * - 座位上是机器人：机器人不会答复求和，请求发出去只会挂到过期。
     *   人机局想结束就点认输（认输是单方面的，不经过这里）。
     *   悔棋不再走到这里 —— 它在 [requestUndo] 就分流到了 [undoAgainstBot]。
     * - 对手下线了：消息发不出去，等他回来或等弃局判定收场。
     */
    private fun opponentOf(seat: Seat, player: Player): Player? {
        val opponentSide = seat.side.opposite
        if (BotPlayer.isBotSeat(seat.session.id, opponentSide)) {
            Msg.actionBar(player, "request-bot")
            return null
        }
        val opponent = playerAt(seat.session, opponentSide)
        if (opponent == null) {
            Msg.actionBar(player, "request-offline")
            return null
        }
        return opponent
    }

    /** 按阵营取在线玩家，离线返回 null */
    private fun playerAt(session: GameSession, side: Side): Player? =
        session.game.playerOf(side)?.let { Bukkit.getPlayer(it) }

    /** 一个玩家在某局里的座位 */
    private data class Seat(val session: GameSession, val side: Side)
}
