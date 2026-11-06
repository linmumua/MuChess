package linmumua.chess.command

import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.game.BotPlayer
import linmumua.chess.game.GameManager
import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameType
import linmumua.chess.game.JoinResult
import linmumua.chess.game.PassAttempt
import linmumua.chess.game.StartResult
import linmumua.chess.interact.MoveExecutor
import linmumua.chess.pack.PackService
import linmumua.chess.render.DisplayMarker
import linmumua.chess.rule.core.Side
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync

/**
 * 命令的具体动作。
 *
 * 与 [ChessCommand] 分开是为了让命令树只剩声明，动作能单独读、单独改。
 * 每个方法都对应一条或几条语言键，代码里不出现玩家可见文本。
 */
object ChessCommandHandler {

    /**
     * 收棋盘。
     *
     * 局面没下完时不让收，提示先认输 —— 否则一方可以靠拆棋盘逃掉必败局。
     */
    fun remove(player: Player) {
        val session = GameManager.nearestSession(player.location)
        if (session == null) {
            Msg.send(player, "board-not-found")
            return
        }
        if (session.game.phase == GamePhase.PLAYING) {
            // 走动作栏：挖家具与打命令都可能连着来好几次，这条属于"当下拦住你"的即时反馈，
            // 留在聊天栏会把终局播报挤走。同一条键在 CraftEnginePlaceListener 里也是动作栏。
            Msg.actionBar(player, "board-remove-in-game")
            return
        }
        GameManager.remove(session)
    }

    /**
     * 管理员强拆：收掉**正对着**的那副棋盘，对局进行中也拆。
     *
     * 与 [remove] 的两处区别，都是为了它的用途 —— 收拾坏掉的棋盘：
     *
     * 1. 认视线不认距离。棋盘挨着摆时按距离会选错身后那副，而这条命令能拆正在下的棋，
     *    选错的代价是毁掉一局别人的对局。视线判定见 [linmumua.chess.board.BoardGaze]。
     * 2. 不拦 PLAYING。[remove] 在对局中拒绝并让人先认输，但棋盘卡住时那条路走不通 ——
     *    卡住的原因往往就是没人能操作它，这时只能强拆。
     *
     * 拆之前先通知局中的人。[GameManager.remove] 本身是静默的，正在下棋的两个人只会看到
     * 棋盘凭空消失，会当成插件崩了来报错；说一句是管理员拆的，他们才知道去找谁。
     */
    fun forceRemove(player: Player) {
        val session = GameManager.lookedSession(player.eyeLocation)
        if (session == null) {
            Msg.actionBar(player, "board-not-looked")
            return
        }
        val wasPlaying = session.game.phase == GamePhase.PLAYING
        if (wasPlaying) {
            // 通知必须排在 remove 之前：remove 会 close 会话并清掉观战名单，
            // 之后就没有收件人可发了。
            // audience() 是对局播报的既有口径：双方玩家 + 观战者，与终局播报同一批收件人
            session.audience().forEach { viewer ->
                Msg.send(viewer, "board-force-removed-notice", "admin" to player.name)
            }
        }
        GameManager.remove(session)
        // 回执给管理员，顺带说清拆掉的是不是一局正在下的棋 —— 强拆的后果得让执行的人看见
        Msg.actionBar(
            player,
            if (wasPlaying) "board-force-removed-playing" else "board-force-removed",
        )
    }

    /**
     * 按距离随机入座附近棋盘。
     *
     * `/chess join` 子命令已经删掉。入座入口是棋盘上方的悬浮字与 shift+右键的
     * 箱子菜单，两者都点定了具体棋盘，走的是 `join(player, session)` 那个重载。
     * 所以本重载当前**没有调用方**。保留是因为"按距离找棋盘再入座"与那条路径
     * 是两回事：那边点的是确定的那副棋盘，这里是"我脚下这副"。
     */
    fun join(player: Player) {
        val session = GameManager.nearestSession(player.location)
        if (session == null) {
            Msg.send(player, "board-not-found")
            return
        }
        join(player, session)
    }

    /**
     * 随机入座指定棋盘。
     *
     * 双方都空时随机红黑；只剩一边时直接补到空位。菜单传入精确会话，不能在点击后
     * 再按距离查一次，否则玩家移动或附近有多副棋盘时可能坐错桌。
     *
     * 坐满**不开局**，也不摆棋子：那两件事都归 [start]。
     */
    fun join(player: Player, session: linmumua.chess.game.GameSession) {
        val game = session.game
        if (game.phase == GamePhase.FINISHED) {
            Msg.send(player, "game-finished")
            return
        }
        if (game.isPlayer(player.uniqueId)) {
            // 已经入座的人再点牌子，不给任何反馈。
            //
            // 这条路径是被动触发的：玩家点棋盘上方的牌子或点家具，手快就会连点好几次。
            // 而"我已经坐在这桌了"这件事，盘面、牌子文字、自己的座位全都在说，
            // 再刷一行字是把玩家已经看见的信息复述一遍，连点时直接糊满屏。
            // seat-already 这条语言键因此整条删掉，不是挪去动作栏。
            return
        }
        val side = game.availableSides().randomOrNull()
        if (side == null) {
            Msg.send(player, "seat-full")
            return
        }
        // 一次只能坐一局：已经坐在别的棋盘上，就先退掉那边再坐这边。
        //
        // 位置不能往上提。必须等上面挑到空位、确认这副棋盘真能坐进去之后才动原局 ——
        // 若这副已经坐满，上面那个 seat-full 已经 return，原局必须保持原样。
        // 提前退会出现"原局退了、新局又坐不进去"的两头空。
        //
        // 退的是 seatedSessionOf 查到的那副，不是 sessionOf：观战不占座位，
        // 允许一边看别人的棋一边到这里入座，只有真占着座位的那副才需要退。
        val seated = GameManager.seatedSessionOf(player.uniqueId)
        if (seated != null && seated !== session) {
            // 走既有离座路径，弃局判负的语义不在这里重写一遍。
            // 原局在进行中会按 GameResult.Quit 判负并给对手播报终局 —— 这是定好的口径：
            // 点另一副棋盘就直接换，不做二次确认。所以走路误触棋盘牌子会输掉正在下的棋。
            leave(player, seated)
        }

        when (game.join(player.uniqueId, side)) {
            // 坐满与没坐满在这里没有区别：两种情况都只是坐下，等有人点"开始"。
            // 机器人第一手也不在这里踢，挪到了 start()，否则棋子还没摆它就先走了。
            JoinResult.WAITING, JoinResult.SEAT_FULL -> {
                session.removeSpectator(player.uniqueId)
                // 入座回执走动作栏，只说世界里看不出来的两件事：现在坐了几个人、自己执哪一方。
                // 阵营是从空位里随机挑的（见上面的 availableSides），盘面上看不出来，必须告知；
                // 人数决定还能不能开局。不进聊天栏是延续"成功类播报不占聊天记录"那条决定 ——
                // 动作栏过几秒自己消失，手快连点也不会糊屏。人数用 seatedCount() 现取，
                // 与牌子上那个 1/2 是同一个口径。
                Msg.actionBar(
                    player,
                    "seat-joined",
                    "current" to game.seatedCount(),
                    "side" to Msg.sideName(player, side, session.gameType),
                )
                // 坐满那一刻牌子要从"加入对局"翻成"开始对局"，否则玩家点了还是入座逻辑
                session.refreshLabel()
                GameManager.markDirty()
            }
            // 所有入口都在主线程，挑完空位到 join 之间不会被并发抢座；仍保留兜底反馈。
            JoinResult.SEAT_TAKEN -> Msg.send(player, "seat-full")
            // 与上面那处前置检查同理：重复入座静默收下，不提示
            JoinResult.ALREADY_JOINED -> Unit
            JoinResult.GAME_FINISHED -> Msg.send(player, "game-finished")
        }
    }

    /**
     * 开局。
     *
     * 这是棋子出现在棋盘上的**唯一**时刻：放棋盘只给一副空盘，入座也不摆子，
     * 直到这里才按初始局面铺满。这么排是为了让"棋盘上有子"等价于"这局在下"，
     * 玩家看一眼盘面就知道能不能坐。
     *
     * 只允许本局双方开：路人能开别人的局等于替他们决定什么时候开始。
     */
    fun start(player: Player, session: linmumua.chess.game.GameSession) {
        val game = session.game
        if (!game.isPlayer(player.uniqueId)) {
            Msg.send(player, "start-not-player")
            return
        }
        when (game.start(System.currentTimeMillis())) {
            StartResult.STARTED -> beginGame(session)
            StartResult.SEAT_NOT_FULL -> Msg.send(player, "start-not-full")
            StartResult.ALREADY_PLAYING -> Msg.send(player, "start-already")
            StartResult.GAME_FINISHED -> Msg.send(player, "game-finished")
        }
    }

    /**
     * 开局成功后的收尾，[start] 与机器人自动开局共用。
     *
     * 抽出来是因为这几步的**顺序有讲究**，抄第二遍很容易漏：
     * 先摆棋子再隐藏牌子（反过来会有一瞬间空盘上没牌子也没子），
     * 最后才踢机器人（棋子还没摆就让它走，渲染层会去搬一枚不存在的子）。
     */
    private fun beginGame(session: linmumua.chess.game.GameSession) {
        val game = session.game
        GameManager.markDirty()
        // 开局这一刻才摆棋子。renderAll 自带清场，重复开局不会叠出两副子。
        session.renderer.renderAll(game.board)
        // 开局后牌子自己隐藏，不然它会一直挡在棋盘上方碍事
        session.refreshLabel()
        Msg.sendAll(session.audience(), "game-started")
        // 机器人执先手时它先走，这里得踢一脚，否则双方都在等对方
        MoveExecutor.scheduleBotIfNeeded(session)
    }

    /**
     * 加测试机器人。
     *
     * @param side 指定坐哪一方；null 表示自动挑空位
     */
    fun botAdd(player: Player, side: Side?) {
        val session = GameManager.nearestSession(player.location)
        if (session == null) {
            Msg.send(player, "board-not-found")
            return
        }
        val game = session.game
        if (game.phase == GamePhase.FINISHED) {
            Msg.send(player, "bot-game-finished")
            return
        }
        // 围棋现在可以加机器人：搜索前会用 GameRules.forSearch() 取一份独立的规则实例，
        // 不再与主局共用撤销栈、劫快照与虚着计数（见 MoveExecutor.moveAsBot）。
        // 棋力很弱，只会提子、救子、收官停手，不懂死活与定式 —— 见 GoEvaluator 类注释。

        // 没指定就挑空位；两方都空时坐黑方，让玩家执红先走
        val target = side ?: when {
            game.playerOf(Side.SECOND) == null -> Side.SECOND
            game.playerOf(Side.FIRST) == null -> Side.FIRST
            else -> null
        }
        if (target == null) {
            Msg.send(player, "bot-no-empty-seat")
            return
        }
        if (game.playerOf(target) != null) {
            Msg.send(player, "seat-taken", "side" to Msg.sideName(player, target, session.gameType))
            return
        }

        val botUuid = BotPlayer.uuidOf(target)
        when (game.join(botUuid, target)) {
            // 机器人补齐最后一个座位同样不开局：人类得有机会先摆好视角再点"开始"。
            // 它的第一手由 start() 负责踢，行为不变，只是时机推后到开局那一刻。
            JoinResult.WAITING, JoinResult.SEAT_FULL -> {
                BotPlayer.register(session.id, target)
                GameManager.markDirty()
                // 两边都是机器人时没有人类能点"开始"，只能自动开局。
                // 不这么做的话 `/chess bot add` 两次会把棋盘锁死在满座但永不开局的状态 ——
                // 机器人对机器人本来就是自测用的，保持原来"加满就开打"的行为。
                // 有人类在局中时一律不自动开局：开局时机该由玩家自己决定。
                var started = false
                if (game.isSeatFull() && isAllBots(session)) {
                    started = game.start(System.currentTimeMillis()) == StartResult.STARTED
                    if (started) {
                        beginGame(session)
                    }
                }
                // 牌子只在**没有**自动开局时才刷。
                // 顺序不能反过来：自动开局那条路径上 phase 已经变成 PLAYING，
                // 而先刷会在 phase 仍是 WAITING 时按"开始对局"真的 spawn 出文字与判定框，
                // 紧接着 beginGame 里的 refreshLabel 读到 PLAYING 又把判定框摘掉。
                // 同一 tick 内建了又删，客户端白收一轮 spawn/destroy 包。没自动开局时这次刷新不能省：机器人补进座位会让牌子
                // 从"加入对局"翻成"开始对局"，没坐满也要保持"加入对局"的显示。
                if (!started) {
                    session.refreshLabel()
                }
            }
            // 上面已经查过空位，走到这里说明状态被并发改过，按座位被占提示
            JoinResult.SEAT_TAKEN -> Msg.send(
                player,
                "seat-taken",
                "side" to Msg.sideName(player, target, session.gameType),
            )
            JoinResult.ALREADY_JOINED -> Msg.send(player, "bot-already", "side" to Msg.sideName(player, target, session.gameType))
            JoinResult.GAME_FINISHED -> Msg.send(player, "bot-game-finished")
        }
    }

    /**
     * 这副棋盘的两个座位是否都由机器人占着。
     *
     * 判"座位上的 UUID 是不是机器人"而不是查 [BotPlayer] 的登记表：登记表是运行时状态，
     * 重启后会清空，而座位 UUID 会落盘。两者不一致时该以座位为准。
     */
    private fun isAllBots(session: linmumua.chess.game.GameSession): Boolean =
        Side.entries.all { side ->
            session.game.playerOf(side)?.let { BotPlayer.isBot(it) } == true
        }

    /**
     * 撤下测试机器人。
     *
     * @param side 指定撤哪一方；null 表示这副棋盘上只有一个机器人时自动认定
     */
    fun botRemove(player: Player, side: Side?) {
        val session = GameManager.nearestSession(player.location)
        if (session == null) {
            Msg.send(player, "board-not-found")
            return
        }

        val registered = BotPlayer.sidesOf(session.id)
        if (registered.isEmpty()) {
            Msg.send(player, "bot-none")
            return
        }
        // 不指定边时，只有一个机器人才能自动认定，两个都在就要求说清撤哪个
        val target = side ?: registered.singleOrNull()
        if (target == null) {
            Msg.send(player, "bot-ambiguous")
            return
        }
        if (!BotPlayer.isBotSeat(session.id, target)) {
            Msg.send(player, "bot-none")
            return
        }

        val botUuid = BotPlayer.uuidOf(target)
        // 对局进行中撤机器人算它弃局，与玩家离座一致，避免留下半局残棋
        if (session.game.phase == GamePhase.PLAYING) {
            val result = linmumua.chess.game.GameResult.Quit(target)
            session.game.finishWith(result)
            MoveExecutor.announceResult(session, result)
        }
        session.game.leave(botUuid)
        BotPlayer.unregister(session.id, target)
        // 未开局时撤机器人只是空出座位，走不到 announceResult，牌子得自己翻回"加入对局"
        session.refreshLabel()
        GameManager.markDirty()
    }

    /**
     * 离座。
     *
     * 对局进行中离座算弃局，由 [linmumua.chess.game.GameResult.Quit] 结算。
     */
    fun leave(player: Player) {
        val session = GameManager.sessionOf(player.uniqueId)
        if (session == null) {
            // 命令应答走动作栏：玩家敲了命令必须有反馈，但这条只在当下有意义，
            // 不需要留在聊天记录里。下同。
            Msg.actionBar(player, "not-in-game")
            return
        }
        leave(player, session)
    }

    /**
     * 从**指定**棋盘离座。
     *
     * 抽出这个重载是给换桌用的：那条路径已经用 [GameManager.seatedSessionOf] 精确查到了
     * 要退哪一副，不能在这里再用 [GameManager.sessionOf] 查一次 —— 观战与入座可能落在
     * 不同棋盘上，重查会退错桌。弃局判负的语义只写在这一处，两条路径共用，不各写一遍。
     */
    fun leave(player: Player, session: linmumua.chess.game.GameSession) {
        val side = session.game.sideOf(player.uniqueId)
        if (side == null) {
            session.removeSpectator(player.uniqueId)
            return
        }
        if (session.game.phase == GamePhase.PLAYING) {
            val result = linmumua.chess.game.GameResult.Quit(side)
            session.game.finishWith(result)
            MoveExecutor.announceResult(session, result)
        }
        session.game.leave(player.uniqueId)
        MoveExecutor.releaseAllSelections(session)
        // 同上：未开局时离座走不到 announceResult，牌子要从"开始对局"翻回"加入对局"
        session.refreshLabel()
        GameManager.markDirty()
    }

    /**
     * 认输。
     *
     * 需要二次确认，避免误触直接判负。确认状态存在内存里，
     * 重启后失效是可以接受的 —— 那时候本来也要重新确认一次。
     */
    private val resignPending = HashSet<java.util.UUID>()

    fun resign(player: Player) {
        val session = GameManager.sessionOf(player.uniqueId)
        if (session == null || session.game.sideOf(player.uniqueId) == null) {
            Msg.actionBar(player, "not-in-game")
            return
        }
        if (session.game.phase != GamePhase.PLAYING) {
            Msg.actionBar(player, "not-in-game")
            return
        }
        if (resignPending.add(player.uniqueId)) {
            Msg.send(player, "resign-confirm")
            return
        }
        resignPending -= player.uniqueId
        val side = session.game.resign(player.uniqueId) ?: return
        MoveExecutor.announceResult(session, linmumua.chess.game.GameResult.Resign(side))
        GameManager.markDirty()
    }

    /**
     * 虚着（围棋 pass）。
     *
     * 只有围棋有虚着，其余棋种由 [linmumua.chess.game.ChessGame.pass] 返回
     * `NotSupported`，这里翻成提示 —— "哪些棋种支持虚着"是规则性质，命令层不自己认棋种。
     *
     * 不做二次确认（与 [resign] 不同）：虚着是可恢复的正常着法，对手接着下就行；
     * 认输一按就结束整局，那才需要确认。
     *
     * 双方连续虚着会直接终局并数子，终局播报走与走子同一条 [MoveExecutor.announceResult]。
     */
    fun pass(player: Player) {
        val session = GameManager.sessionOf(player.uniqueId)
        if (session == null || session.game.sideOf(player.uniqueId) == null) {
            Msg.actionBar(player, "not-in-game")
            return
        }
        val game = session.game
        val side = game.sideOf(player.uniqueId) ?: return

        when (val attempt = game.pass(player.uniqueId, System.currentTimeMillis())) {
            is PassAttempt.Passed -> {
                announcePass(session, side)
                val result = game.result
                if (attempt.outcome.isFinished && result != null) {
                    // 双方连续虚着 → 数子终局。不排落地延迟：虚着没有棋子动画要等。
                    MoveExecutor.announceResult(session, result)
                } else {
                    // 对局继续，可能轮到机器人走（当前围棋不允许机器人，
                    // 但这条调用与走子路径保持一致，将来阶段6 放开时不必再补）
                    MoveExecutor.scheduleBotIfNeeded(session)
                }
                GameManager.markDirty()
            }
            PassAttempt.NotSupported -> Msg.send(player, "pass-not-go")
            // 轮次提示走动作栏：not-your-turn 这条聊天键已随走棋高频提示一起删掉
            // （见 RejectionMessagesTest），这里不能把它加回来。
            PassAttempt.WrongTurn -> Msg.actionBar(player, "pass-not-your-turn")
            // 不在对局中与不是对手：都归到同一条提示，玩家看到的现象是一样的
            is PassAttempt.NotPlaying, PassAttempt.NotAPlayer -> Msg.actionBar(player, "not-in-game")
        }
    }

    /**
     * 把一手虚着播报给整桌。
     *
     * 放在命令层而不是 [MoveExecutor]：虚着是从命令发起的，而且交互层有一条
     * "不往聊天栏发东西"的硬约束（由 RejectionMessagesTest 守着）。
     *
     * 虚着**要进聊天栏**而不是动作栏：它是一次持久的回合事件，对手需要知道
     * "该我下了"并且可以往上翻记录确认；动作栏会自动淡出，错过就没了。
     * 这与禁自杀、打劫那两条即时状态提示的处理不同。
     *
     * 阵营名按各自语言取（[Msg.sideName] 与接收者相关），所以逐个发而不是拼一条群发。
     */
    fun announcePass(session: linmumua.chess.game.GameSession, side: Side) {
        for (viewer in session.audience()) {
            Msg.send(viewer, "pass-done", "side" to Msg.sideName(viewer, side, session.gameType))
        }
    }

    /**
     * 重载配置与语言文件。
     *
     * 文件读取放异步线程，读完回主线程套用到已有会话 ——
     * 项目禁止主线程做文件 I/O，而渲染参数的套用又必须在主线程。
     */
    fun reload(sender: CommandSender) {
        val start = System.currentTimeMillis()
        submitAsync {
            runCatching {
                ChessConfig.reload()
            }.onFailure { error ->
                submit {
                    Msg.send(sender, "reload-failed", "reason" to (error.message ?: ""))
                }
                return@submitAsync
            }
            submit {
                // 插件配置重载也检查 CE 的 muchess 目录；已存在时不会读取或改写任何资源。
                PackService.ensureDirectory()
                GameManager.applySettings()
                Msg.send(sender, "reload-done", "ms" to (System.currentTimeMillis() - start))
            }
        }
    }

    /**
     * 重新生成资源包。
     *
     * 生成逻辑属于 `pack/` 模块，这里只触发并把结果翻成文案。
     * CE 缺失时走 pack-skipped，不报错。
     */
    fun pack(sender: CommandSender) {
        Msg.send(sender, "pack-generating")
        PackHook.regenerate { outcome ->
            when (outcome) {
                is PackHook.Outcome.Done -> Msg.send(sender, "pack-done", "count" to outcome.fileCount)
                PackHook.Outcome.Skipped -> Msg.send(sender, "pack-skipped")
                is PackHook.Outcome.Failed -> Msg.send(
                    sender,
                    "pack-failed",
                    "reason" to outcome.reason,
                )
            }
        }
    }

    /**
     * 清掉玩家附近脱离索引的残留显示实体。
     *
     * 存在的理由是**立即可用的恢复路径**：区块加载时会自动清一次，但已经加载的区块
     * 要等玩家走远再回来才会触发，眼前的残留只能靠这条命令收掉。
     *
     * 半径参数没给或不是正整数时用默认值；超过上限按上限截断，
     * 不报错也不拒绝执行 —— 管理员要的是把东西清掉，不是被参数格式挡回来。
     *
     * @param radiusRaw 命令里的半径文本，null 表示没给
     */
    fun sweep(player: Player, radiusRaw: String?) {
        val radius = (radiusRaw?.toIntOrNull() ?: GameManager.SWEEP_RADIUS_DEFAULT)
            .coerceIn(1, GameManager.SWEEP_RADIUS_MAX)
        val result = GameManager.sweepAround(player.location, radius)
        if (result.total == 0) {
            Msg.send(player, "debug-sweep-none", "radius" to radius)
            return
        }
        val byRole = result.byRole
        Msg.send(
            player,
            "debug-sweep-done",
            "count" to result.total,
            "radius" to radius,
            "pieces" to (byRole[DisplayMarker.Role.PIECE] ?: 0),
            "markers" to (byRole[DisplayMarker.Role.MARKER] ?: 0),
            // 悬浮字与判定框都属于"牌子"，合成一个数报给服主。
            // 判定框只在等人阶段存在（开局后会摘掉，否则拦右键），所以对局中的棋盘
            // 只会报出文字那一只，两者数量不一定相等
            "labels" to (
                (byRole[DisplayMarker.Role.LABEL] ?: 0) +
                    (byRole[DisplayMarker.Role.LABEL_HITBOX] ?: 0)
                ),
        )
    }

    /** 清掉认输待确认状态，玩家退出与重载时调用 */
    fun clearResignPending() {
        resignPending.clear()
    }
}
