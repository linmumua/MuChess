package linmumua.chess.interact

import java.util.UUID
import linmumua.chess.compat.CraftEngineBridge
import linmumua.chess.config.CaptureExplosionSettings
import linmumua.chess.config.CheckmateTotemSettings
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.rule.xiangqi.MoveRejection
import linmumua.chess.game.BotPlayer
import linmumua.chess.game.GameManager
import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameResult
import linmumua.chess.game.GameSession
import linmumua.chess.game.GoBgmController
import linmumua.chess.game.MoveAttempt
import linmumua.chess.game.PassAttempt
import linmumua.chess.game.bot.BotBrain
import linmumua.chess.game.request.GameRequests
import linmumua.chess.command.ChessCommandHandler
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning

/**
 * 落子与选中的执行入口。
 *
 * [ClickResolver] 只判断"该做什么"，这里负责真的去做：改选中态、动显示实体、
 * 显示白圈与发光、发提示、播音效、推进对局。
 *
 * 所有方法必须在主线程调用。
 */
object MoveExecutor {

    /**
     * 处理一次右键。
     *
     * @param player 点击者
     * @param session 被点的棋盘
     * @param clicked 点到的交叉点
     */
    fun handleClick(player: Player, session: GameSession, clicked: Square) {
        val settings = ChessConfig.settings
        val uuid = player.uniqueId
        val mover = session.sideOf(uuid)

        // 观战者能不能点由配置决定，默认不能，避免看棋的人误触
        if (mover == null) {
            if (!settings.game.spectatorCanClick) {
                return
            }
        }

        val game = session.game
        // 还没开局或已终局时不受理落子，但要说清现在是什么状态
        if (game.phase != GamePhase.PLAYING) {
            when (game.phase) {
                GamePhase.WAITING -> Unit
                // 终局后点棋盘不再刷提示，避免复盘时刷屏
                GamePhase.FINISHED -> Unit
                GamePhase.PLAYING -> Unit
            }
            return
        }
        if (session.renderer.isMoveAnimating()) {
            return
        }

        val selection = GameManager.selections.get(uuid, session.id)
        val action = ClickResolver.resolve(
            board = game.board,
            clicked = clicked,
            selection = selection,
            mover = mover,
            isPlacement = game.rules.isPlacement,
        )
        when (action) {
            is ClickAction.Select -> select(player, session, action.square)
            is ClickAction.Deselect -> deselect(player, session)
            is ClickAction.Switch -> {
                // 换选中：旧的落回去，新的浮起来，中间不留残影
                clearSelection(player, session)
                select(player, session, action.to)
            }
            is ClickAction.Move -> execute(player, session, action.from, action.to)
            // 落子式：起终点同一个点，走同一条执行路径，规则层与渲染层各自认得出这是落子
            is ClickAction.Place -> execute(player, session, action.square, action.square)
            is ClickAction.IllegalTarget -> reportIllegal(player, session, action.from, action.to)
            // 点了不属于自己的子不提示：棋盘上棋子颜色本身就说明了归属，点不动就是最直接的反馈
            is ClickAction.NotOwnPiece -> Unit
            // 不是自己的回合：走动作栏提示，不进聊天框。
            // 需要提示是因为"点了没反应"会被当成插件卡住 —— 与点对方子不同，
            // 那种情况棋子颜色自明，这里玩家点的是自己的子，没反馈说不通。
            // 用动作栏而非聊天：等待方可能反复点，聊天会刷屏。
            is ClickAction.NotYourTurn -> Msg.actionBar(player, "click-not-your-turn")
            ClickAction.Spectate -> Unit
            ClickAction.Ignore -> Unit
        }
    }

    /**
     * 选中一枚子：浮起放大、橙色描边、空点白圈、可吃子红色描边。
     *
     * 合法落点在这里算一次就存进选中态；白圈是静态显示实体，不再需要周期刷新。
     */
    private fun select(player: Player, session: GameSession, square: Square) {
        // 空格子点不出选中态；这里只做存在性校验，棋子本身的信息不再用于发提示
        session.game.board[square] ?: return
        val moves = session.game.rules.legalMovesFrom(session.game.board, square)

        if (moves.isEmpty()) {
            // 一枚子被堵死时不进选中态：没有白圈可显示，浮起来反而让人以为选上了。
            // 这里不发提示，点不动本身就是反馈。
            return
        }

        // 一副棋盘同一时刻只保留一组可视选中态，避免两名玩家的白圈与描边互相覆盖。
        releaseAllSelections(session)
        val state = SelectionState(square, moves)
        GameManager.selections.select(player.uniqueId, session.id, state)
        session.renderer.hover(square)
        session.highlight.showSelection(
            square,
            state.emptyDestinations,
            state.captureDestinations,
        )
        playSound(player, ChessConfig.settings.sound.select)
    }

    /** 取消选中，棋子落回棋盘面 */
    private fun deselect(player: Player, session: GameSession) {
        GameManager.selections.clear(player.uniqueId)
        session.clearSelectionVisuals()
    }

    /** 清掉某玩家的选中态、白圈与棋子发光，并把浮起的子放回去 */
    private fun clearSelection(player: Player, session: GameSession) {
        GameManager.selections.clear(player.uniqueId)
        session.clearSelectionVisuals()
    }

    /**
     * 真正走一步棋。
     *
     * 落子一律经过 [linmumua.chess.game.ChessGame.move]，由规则层做最终裁定。
     * 即使前面 [ClickResolver] 已经认为这是高亮过的合法落点，这里也不跳过校验 ——
     * 选中态是缓存，缓存可能过期（比如对手在这中间走了一步），
     * 唯一可信的判断是走子那一刻的规则裁定。
     */
    private fun execute(player: Player, session: GameSession, from: Square, to: Square) {
        val settings = ChessConfig.settings
        val game = session.game
        // 选中态会在成功走子后立即清掉，先记住该子是否已经完成了抬起阶段。
        val alreadyLifted = GameManager.selections
            .get(player.uniqueId, session.id)
            ?.sourcePacked == from.packed

        when (val attempt = game.move(player.uniqueId, from, to, System.currentTimeMillis())) {
            is MoveAttempt.Success -> {
                // 围棋提子：必须在放新子之前删掉被提的显示实体。
                // 顺序不能反 —— 被提点可能与新落点相邻，先放后删会让 applyPlacement
                // 刚建立的索引被随后的 remove 波及（remove 会清 hovered 与动画 token）。
                // 象棋与五子棋这里恒为空列表，removeAll 直接返回，没有开销。
                session.renderer.removeAll(attempt.captured)
                // 先启动走子动画再清选中态，避免 settleHovered 把已浮起棋子提前压回棋盘。
                // 落子式（起终点同点）走另一条渲染路径：那边要新生成实体，
                // applyMove 第一步就是从起点摘实体，起点没有实体会直接返回 0，棋子不会出现。
                val landingDelay = if (from == to) {
                    session.renderer.applyPlacement(to, attempt.move.piece)
                } else {
                    session.renderer.applyMove(from, to, alreadyLifted)
                }
                // 局面变了，全盘选中态作废，否则别人会按旧高亮落子。
                releaseAllSelections(session)
                playMoveSound(
                    session,
                    attempt.move.isCapture,
                    settings.sound.move,
                    settings.sound.capture,
                    landingDelay,
                )
                playCaptureExplosion(session, attempt.move, landingDelay)
                afterMove(session, attempt, landingDelay)
                GameManager.markDirty()
            }
            // 象棋路径静默：白圈已经标出了全部合法落点，被拒说明玩家点在圈外，
            // 不再用文字复述规则。这条路径通常来自选中态缓存与局面不同步，
            // 属于内部状态问题而不是玩家操作错误。
            //
            // 但围棋的禁自杀与打劫是两条例外，必须给话：那条"白圈已标出"的理由对围棋
            // 不成立 —— 围棋是落子式，legalMovesFrom 返回空、交互层靠 isPlacement 分流，
            // 根本不显示白圈。玩家点在劫争点或自杀点上，棋子不落又零提示，
            // 无法区分"这手不合法"和"插件卡了"。而这恰好是围棋最需要解释的两条规则。
            //
            // 用白名单而不是黑名单：将来新增 rejection 默认仍然静默，不会意外开始刷屏。
            // SQUARE_OCCUPIED 不接线 —— 那里有子玩家自己看得见。
            // 走动作栏而不是聊天栏：与将军提示同一档处理。这两条都是"过一手就没意义"
            // 的即时状态，动作栏会自动淡出、不占聊天记录；玩家试探劫争点时也不会刷屏。
            // 交互层一律不往聊天栏发东西，这条约束由 RejectionMessagesTest 守着。
            is MoveAttempt.Rejected -> when (attempt.rejection) {
                MoveRejection.GO_SUICIDE -> Msg.actionBar(player, "place-illegal-suicide")
                MoveRejection.GO_KO -> Msg.actionBar(player, "place-illegal-ko")
                else -> Unit
            }
            is MoveAttempt.NotPlaying -> Unit
            MoveAttempt.NotAPlayer -> Unit
        }
    }

    /**
     * 玩家点了没高亮的点。
     *
     * 仍然要问一次规则层：选中态的落点是点选时算好缓存的，如果这期间局面变过，
     * 缓存里可能漏掉一个其实合法的点，那就直接按合法走，不能白吞玩家这一次点击。
     *
     * 确实非法时什么都不做。选中一枚子时所有合法落点已经用白圈标出来了，
     * 点在白圈之外就是不能走，再用文字解释一遍蹩马腿、塞象眼只会刷屏。
     */
    private fun reportIllegal(player: Player, session: GameSession, from: Square, to: Square) {
        val mover = session.sideOf(player.uniqueId) ?: return
        // 用 4 参重载，把归属与轮次也一起判进去
        val rejection = session.game.rules.rejectionOf(session.game.board, from, to, mover)
        if (rejection == null) {
            // 规则层说这步合法，说明选中态的落点缓存过期了，直接按合法走
            execute(player, session, from, to)
        }
    }

    /**
     * 走完之后的将军播报与终局结算。
     *
     * 规则状态即时推进，但声音、将军警示与终局演出等到棋子真正落地再出现，避免画面还在飞、
     * 声音却已经提前响完。动画关闭时 [landingDelay] 为 0，行为仍是立即执行。
     */
    private fun afterMove(session: GameSession, attempt: MoveAttempt.Success, landingDelay: Int) {
        val settings = ChessConfig.settings
        val game = session.game
        // 走完之后轮到对方，将军与终局都是针对对方判的
        val opponent = game.sideToMove
        val result = game.result

        if (attempt.outcome.isFinished && result != null) {
            afterLanding(session, landingDelay) {
                if (game.result != result) {
                    return@afterLanding
                }
                announceResult(session, result)
                playSoundToAll(session, settings.sound.gameOver)
                playCheckmateTotemAnimation(session, result)
            }
            return
        }

        if (attempt.opponentInCheck) {
            afterLanding(session, landingDelay) {
                if (game.phase != GamePhase.PLAYING || game.sideToMove != opponent) {
                    return@afterLanding
                }
                announceCheck(session)
                playSoundToAll(session, settings.sound.check)
                markCheckedKing(session, opponent)
            }
        }

        // 把牌子上的手数与轮次推到最新。对局中靠每手刷新而不是靠 GameManager 的周期巡检：
        // 这两个值每走一手都变，巡检那点间隔会让牌子肉眼可见地慢半拍。
        // 终局走不到这里 —— 上面那条分支已经 return，牌子由 announceResult 换成终局文案，
        // 免得这里先抢着刷一遍再被覆盖。
        //
        // 刻意不放进 afterLanding：牌子写的是"第几手、轮到谁"这类规则状态，而规则在 execute
        // 里已经推进完了，让牌子立刻如实显示是对的。声音与将军警示要等落地是因为那些是演出，
        // 提前响会和还在飞的棋子打架，牌子没这个问题。
        session.refreshLabel()

        // 机器人从棋子落稳后再开始思考，不能在 13 tick 视觉流程尚未结束时抢着回手。
        scheduleBotIfNeeded(session, landingDelay)
    }

    /**
     * 如果下一手是机器人，安排它落子。
     *
     * [waitForLandingTicks] 是前一手剩余的视觉时长；配置的 think-ticks 从落地后开始计算。
     * 延迟期间局面可能变（对手认输、棋盘被收），所以落子前仍要重新校验。
     */
    fun scheduleBotIfNeeded(session: GameSession, waitForLandingTicks: Int = 0) {
        val game = session.game
        if (game.phase != GamePhase.PLAYING) {
            return
        }
        val side = game.sideToMove
        if (!BotPlayer.isBotSeat(session.id, side)) {
            return
        }
        val delay = waitForLandingTicks.coerceAtLeast(0) + ChessConfig.settings.bot.thinkTicks
        submit(delay = delay.toLong()) {
            moveAsBot(session, side)
        }
    }

    /**
     * 机器人走一手。
     *
     * 不复用 [execute]：那条路要 `Player`，机器人没有。
     * 但**校验一律仍走** [linmumua.chess.game.ChessGame.move] ——
     * 不给机器人开后门，它和玩家受同一套规则裁定。
     *
     * 走完之后同样调 [afterMove]，所以机器人对机器人也能自己接着走下去。
     */
    private fun moveAsBot(session: GameSession, side: Side) {
        val game = session.game
        // 延迟期间局面可能已经变了：对局结束、轮次易手、机器人被撤下
        if (game.phase != GamePhase.PLAYING || game.sideToMove != side) {
            return
        }
        if (!BotPlayer.isBotSeat(session.id, side)) {
            return
        }
        if (session.renderer.isMoveAnimating()) {
            submit(delay = 1L) {
                moveAsBot(session, side)
            }
            return
        }

        // 搜索放异步线程：项目规则禁止主线程干重活，两层 alpha-beta 在五子棋上
        // 可能要算上万个局面。传进去的是**快照**而不是真实棋盘 ——
        // 异步线程动真实棋盘会与主线程的落子、渲染打架。
        val snapshot = game.board.copy()
        // 规则实例也要取独立的一份，不能共用主局那个：围棋规则持有撤销栈、劫快照与
        // 虚着计数，两个线程共用会互相踩 —— 最糟的是搜索展开虚着分支后主线程读到
        // 虚着计数 2，在无人虚着的情况下直接判终局数子。
        // 象棋与五子棋无状态，forSearch() 返回自身，零开销。
        val rules = game.rules.forSearch()
        val options = ChessConfig.settings.bot.toBotOptions()
        submitAsync {
            val chosen = BotBrain.chooseMove(rules, snapshot, side, options)
            submit {
                applyBotMove(session, side, chosen)
            }
        }
    }

    /**
     * 把异步算好的着法落到棋局上。
     *
     * 必须在主线程调用。异步搜索期间局面可能已经变了（玩家认输、棋盘被收、
     * 机器人被撤下），所以这里重新校验一遍再落子。
     */
    private fun applyBotMove(session: GameSession, side: Side, chosen: Move?) {
        val game = session.game
        if (game.phase != GamePhase.PLAYING || game.sideToMove != side) {
            return
        }
        if (!BotPlayer.isBotSeat(session.id, side)) {
            return
        }
        if (chosen == null) {
            // 无步可走：将死或困毙。这里不自己判结局，
            // 交给规则层在下一次 move 或终局检查里处理，避免两处判定打架
            warning("机器人无合法着法可走，会话 ${session.id}，执 $side")
            return
        }

        val botUuid = BotPlayer.uuidOf(side)

        // 虚着走另一条入口：它没有起终点，交给 game.move 会被当成"在 (0,0) 落子"。
        // 围棋不虚着就永远不会终局（outcome 只认双方连续虚着），所以这条分流是必需的，
        // 少了它机器人会一路把自己的空填满直到无处可下。
        if (chosen.pass) {
            passAsBot(session, side, botUuid)
            return
        }

        when (val attempt = game.move(botUuid, chosen.from, chosen.to, System.currentTimeMillis())) {
            is MoveAttempt.Success -> {
                // 围棋提子：先删被提的显示实体，与玩家落子那条路径同序
                session.renderer.removeAll(attempt.captured)
                // 落子式棋种（围棋、五子棋）起终点相同，必须走 applyPlacement：
                // applyMove 第一步是从起点摘实体，而落子式的起点本来就没有实体，
                // 它会直接返回 0，棋子根本不显示。机器人这条路径原先一律调 applyMove，
                // 五子棋机器人下的子看不见就是这个原因。
                // 机器人没有预选悬空态，所以走子路径让渲染器执行完整弧线。
                val landingDelay = if (chosen.from == chosen.to) {
                    session.renderer.applyPlacement(chosen.to, attempt.move.piece)
                } else {
                    session.renderer.applyMove(chosen.from, chosen.to)
                }
                releaseAllSelections(session)
                val settings = ChessConfig.settings
                playMoveSound(
                    session,
                    attempt.move.isCapture,
                    settings.sound.move,
                    settings.sound.capture,
                    landingDelay,
                )
                playCaptureExplosion(session, attempt.move, landingDelay)
                afterMove(session, attempt, landingDelay)
                GameManager.markDirty()
            }
            // 规则层拒了机器人的着法说明选步有 bug，必须记日志而不是静默重试
            is MoveAttempt.Rejected -> warning(
                "机器人着法被规则层拒绝：${chosen.from}->${chosen.to}，原因 ${attempt.rejection}",
            )
            is MoveAttempt.NotPlaying -> Unit
            MoveAttempt.NotAPlayer -> warning("机器人不在座位上，却被安排了落子，会话 ${session.id}")
        }
    }

    /**
     * 机器人虚着。
     *
     * 与 [applyBotMove] 的落子分支同构：播报、判终局、排下一手。
     * 虚着没有棋子动画，所以不必等落地延迟。
     *
     * 必须在主线程调用。
     */
    private fun passAsBot(session: GameSession, side: Side, botUuid: java.util.UUID) {
        val game = session.game
        when (val attempt = game.pass(botUuid, System.currentTimeMillis())) {
            is PassAttempt.Passed -> {
                // 播报在命令层：交互层有一条"不往聊天栏发东西"的硬约束
                // （见 RejectionMessagesTest），而虚着要进聊天栏让对手能翻记录
                ChessCommandHandler.announcePass(session, side)
                val result = game.result
                if (attempt.outcome.isFinished && result != null) {
                    announceResult(session, result)
                    playSoundToAll(session, ChessConfig.settings.sound.gameOver)
                } else {
                    scheduleBotIfNeeded(session)
                }
                GameManager.markDirty()
            }
            // 规则层拒了机器人的虚着说明选步有 bug，记日志而不是静默重试
            PassAttempt.NotSupported -> warning(
                "机器人在非围棋棋盘上选了虚着，会话 ${session.id}",
            )
            PassAttempt.WrongTurn -> warning("机器人虚着时轮次已变，会话 ${session.id}")
            is PassAttempt.NotPlaying -> Unit
            PassAttempt.NotAPlayer -> warning("机器人不在座位上，却被安排了虚着，会话 ${session.id}")
        }
    }



    /**
     * 将军提示，发到动作栏。
     *
     * 全场同一条"将军"，不再按被将方拆成两句：棋盘上谁被将一眼就看得出来，
     * 文字再解释一遍是多余的。走动作栏而不是聊天栏，因为这是即时状态，
     * 过一手就没意义了，留在聊天栏只会把终局播报挤走。
     */
    private fun announceCheck(session: GameSession) {
        Msg.actionBarAll(session.audience(), "check-warning")
    }

    /** 给被将军的将套一圈粒子，由配置控制 */
    private fun markCheckedKing(session: GameSession, checked: Side) {
        val king = session.game.board.kingSquare(checked) ?: return
        session.highlight.markCheckedKing(session.viewers(), king)
    }

    /**
     * 终局播报。
     *
     * 每种结局对应一条文案，占位符按结局类型填。
     * 播报完清掉选中态与浮起状态，但**不清棋子实体** ——
     * 终局后棋盘要留着让人复盘，收棋盘走 `/chess remove`。
     */
    fun announceResult(session: GameSession, result: GameResult) {
        // 先掐掉围棋背景音乐，再让调用方播 game-over 音效 —— 两声叠着谁也听不清，
        // 而且终局播报是这一局最该被听见的一句。挂在这里的理由与下面挂牌子相同：
        // 六条终局路径都汇到本方法，挂一处比挂六处不容易漏。没在放音乐时它自己短路。
        GoBgmController.stop(session)
        // 待答复的求和与悔棋请求也在这里一并清掉，理由与上面掐音乐、下面挂牌子相同：
        // 六条终局路径都汇到本方法。局已经结束，那些请求全都对不上了 ——
        // 留着它只会让"点了同意却什么也没发生"变成一种可复现的怪现象。
        GameRequests.clear(session.id)
        releaseAllSelections(session)
        // 把棋盘上方的牌子换成终局结果。放在这里而不是各个终局入口，是因为将死、困毙、限着、
        // 认输、弃局、超时六条路径最后都汇到本方法 —— 挂一处比挂六处不容易漏。
        //
        // 能这么挂的前提是这六条路径都已经把结果写进了 game.result（走子与虚着在
        // ChessGame.move/pass 里写，认输在 resign 里写，弃局与超时由调用方先 finishWith），
        // 牌子的文案正是按它分流的。谁要是新加一条终局路径却漏了这一步，
        // 牌子会因为拿不到结果而直接消失 —— 那说明该补的是那条路径，不是这里。
        session.refreshLabel()
        for (viewer in session.audience()) {
            when (result) {
                is GameResult.Checkmate -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "winner" to Msg.sideName(viewer, result.winner, session.gameType),
                )
                is GameResult.Stalemate -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "loser" to Msg.sideName(viewer, result.loser, session.gameType),
                )
                is GameResult.DrawByMoveLimit -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "limit" to result.fullMoves,
                )
                is GameResult.Resign -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "loser" to Msg.sideName(viewer, result.loser, session.gameType),
                    "winner" to Msg.sideName(viewer, result.winner, session.gameType),
                )
                // 双方同意的和棋，没有胜负方也没有回合数，因此没有占位符
                GameResult.DrawByAgreement -> Msg.sendRaw(viewer, result.messageKey)
                is GameResult.Timeout -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "loser" to Msg.sideName(viewer, result.loser, session.gameType),
                )
                is GameResult.Quit -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "loser" to Msg.sideName(viewer, result.loser, session.gameType),
                )
                // 五子棋连五
                is GameResult.LineWin -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "winner" to Msg.sideName(viewer, result.winner, session.gameType),
                )
                // 五子棋满盘和棋，没有占位符
                GameResult.DrawByFullBoard -> Msg.sendRaw(viewer, result.messageKey)
                // 围棋数子，平局与分胜负共用一个类型、两条文案键
                is GameResult.ScoreWin -> Msg.sendRaw(
                    viewer,
                    result.messageKey,
                    "winner" to result.winner?.let { Msg.sideName(viewer, it, session.gameType) },
                    "first" to formatScore(result.firstScore),
                    "second" to formatScore(result.secondScore),
                )
            }
        }
        GameManager.markDirty()
    }

    /**
     * 围棋子数的显示写法。
     *
     * 贴子是 3¾ 这类分数，所以分数是 Double。整数就不显示小数点，
     * 免得盘面明明是 181 子却播报成 181.0。
     */
    private fun formatScore(score: Double): String =
        if (score % 1.0 == 0.0) score.toInt().toString() else score.toString()

    /** 将死后给本局双方各播放一次败方将/帅物品；困毙、认输等其他终局不播放。 */
    private fun playCheckmateTotemAnimation(session: GameSession, result: GameResult) {
        val checkmate = result as? GameResult.Checkmate ?: return
        val configured = ChessConfig.settings.checkmateTotem
        val itemId = checkmateTotemItemId(checkmate.loser, configured)
        if (itemId.isBlank()) {
            return
        }
        val seen = HashSet<UUID>(2)
        for (side in Side.entries) {
            val uuid = session.game.playerOf(side) ?: continue
            if (!seen.add(uuid)) {
                continue
            }
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (!CraftEngineBridge.playTotemAnimation(player, itemId)) {
                warning(
                    "将死图腾未播放：session=${session.id}，player=${player.name}，item=$itemId",
                )
            }
        }
    }

    /** 在棋子实际落地后执行视觉与声音反馈；棋盘已被移除时丢弃过期回调。 */
    private fun afterLanding(session: GameSession, delayTicks: Int, action: () -> Unit) {
        if (delayTicks <= 0) {
            action()
            return
        }
        submit(delay = delayTicks.toLong()) {
            if (GameManager.session(session.id) === session) {
                action()
            }
        }
    }

    /** 清掉这副棋盘上所有人的选中态、白圈与棋子描边，并放下浮起棋子 */
    fun releaseAllSelections(session: GameSession) {
        GameManager.selections.clearBoard(session.id)
        session.clearSelectionVisuals()
    }

    /**
     * 播一个音效，配置留空表示不播。
     *
     * [volume] 既是响度也决定可听范围，原版 1.0 之外的值会按比例缩放衰减距离。
     */
    private fun playSound(player: Player, sound: String, volume: Float = 1f) {
        if (sound.isBlank() || volume <= 0f) {
            return
        }
        // playSound 接受原始 sound id 字符串，服主可以填资源包里的自定义音效
        player.playSound(player.location, sound, volume, 1f)
    }

    /** 给全场播一个音效 */
    private fun playSoundToAll(session: GameSession, sound: String, volume: Float = 1f) {
        if (sound.isBlank() || volume <= 0f) {
            return
        }
        session.audience().forEach { playSound(it, sound, volume) }
    }

    /** 走子与吃子用不同音效，并与显示实体真正落地的 tick 对齐。 */
    private fun playMoveSound(
        session: GameSession,
        isCapture: Boolean,
        moveSound: String,
        captureSound: String,
        landingDelay: Int,
    ) {
        val sound = if (isCapture) captureSound else moveSound
        afterLanding(session, landingDelay) {
            playSoundToAll(session, sound)
        }
    }

    /**
     * 炮吃子时补一团棋子大小的爆炸火光和一声压低的爆炸响。
     *
     * 只给炮：炮是唯一隔子远距离吃子的兵种，这团火光是它的意象；其它兵种贴身吃子，
     * 加爆炸只会吵。粒子与声音都排在落地那一 tick，跟走子动画对齐，
     * 否则棋子还在半空火光就先炸了。
     *
     * 爆炸音效是**叠加**在原有吃子音效上的，不替换它。
     */
    private fun playCaptureExplosion(
        session: GameSession,
        move: Move,
        landingDelay: Int,
    ) {
        val explosion = ChessConfig.settings.captureExplosion
        if (!shouldExplodeOnCapture(move, explosion)) {
            return
        }
        afterLanding(session, landingDelay) {
            session.highlight.showCaptureExplosion(session.viewers(), move.to)
            playSoundToAll(session, explosion.sound, explosion.volume.toFloat())
        }
    }
}

/**
 * 这一步该不该放爆炸。
 *
 * 三个条件同时成立才放：开关开着、这步真的吃了子、走的是炮。
 * 抽成纯函数是为了能脱离 Bukkit 单测 —— 判定错了会变成"每步棋都炸"或"炮吃子不炸"，
 * 这两种都是玩家一眼能看出的问题。
 */
internal fun shouldExplodeOnCapture(move: Move, settings: CaptureExplosionSettings): Boolean =
    settings.enabled && move.isCapture && move.piece.type == PieceType.CANNON

/** 按将死败方选择对应阵营的将/帅图腾物品。 */
internal fun checkmateTotemItemId(loser: Side, settings: CheckmateTotemSettings): String = when (loser) {
    Side.FIRST -> settings.red
    Side.SECOND -> settings.black
}
