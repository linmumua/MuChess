package linmumua.chess.command

import linmumua.chess.config.Msg
import linmumua.chess.game.GameRequestFlow
import linmumua.chess.game.GameType
import linmumua.chess.game.request.GameRequestKind
import linmumua.chess.rule.core.Side
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.expansion.createHelper

/**
 * `/chess` 命令树。
 *
 * 子命令与 `lang/zh_CN.yml` 里的 `help-*` 键一一对应：
 * remove、bot、leave、resign、draw、undo、accept、decline、pass、debug、forceremove、reload、pack。
 *
 * 其中 forceremove、debug、reload、pack 挂 `muchess.admin`，其余走 header 上的 `muchess.use`。
 *
 * 这里也**没有**入座命令：入座与开局走棋盘上方那行可点击的悬浮字
 * （见 BoardLabelRenderer），按对局状态在"加入对局"与"开始对局"之间切换；
 * shift+右键棋盘弹出的箱子菜单（见 BoardMenu）是同两个动作的第二入口，
 * 供关掉 `board-label.enabled` 时使用。再加一条命令入座只会让人不知道该用哪个。
 * 入座与开局动作本身仍在 [ChessCommandHandler.join] 与 [ChessCommandHandler.start]，
 * 悬浮字与菜单都直接调它们，并且都传入精确会话。
 *
 * 这里**没有**放置命令：棋盘就是 CraftEngine 家具，只能用 CE 物品右键放下
 * （见 CraftEnginePlaceListener）。物品由 CE 自己的给予命令发放，本插件不再代发。
 *
 * 命令层只做"取参数、调业务、回消息"，不写业务判断 ——
 * 放置校验在 GameManager，走棋判定在规则层。
 */
@CommandHeader(name = "chess", aliases = ["muchess"], permission = "muchess.use")
object ChessCommand {

    /** 不带参数时打帮助 */
    @CommandBody
    val main = mainCommand {
        createHelper()
    }

    /** 收走附近的棋盘 */
    @CommandBody(description = "help-remove")
    val remove = subCommand {
        execute<CommandSender> { sender, _, _ ->
            val player = sender.asPlayer() ?: return@execute
            ChessCommandHandler.remove(player)
        }
    }

    /**
     * 机器人。
     *
     * `add` 不带边时自动坐空着的那一方，两方都空则坐黑方（让玩家执红先走）。
     *
     * 机器人用 alpha-beta 搜索（见 [linmumua.chess.game.bot.BotBrain]），默认 2 层：
     * 会吃子、会堵连四、会避开明显亏损的交换。但没有开局库与残局库，熟手能赢它。
     */
    @CommandBody(description = "help-bot")
    val bot = subCommand {
        literal("add") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { ChessCommandHandler.botAdd(it, null) }
            }
            literal("red") {
                execute<CommandSender> { sender, _, _ ->
                    sender.asPlayer()?.let { ChessCommandHandler.botAdd(it, Side.FIRST) }
                }
            }
            literal("black") {
                execute<CommandSender> { sender, _, _ ->
                    sender.asPlayer()?.let { ChessCommandHandler.botAdd(it, Side.SECOND) }
                }
            }
        }
        literal("remove") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { ChessCommandHandler.botRemove(it, null) }
            }
            literal("red") {
                execute<CommandSender> { sender, _, _ ->
                    sender.asPlayer()?.let { ChessCommandHandler.botRemove(it, Side.FIRST) }
                }
            }
            literal("black") {
                execute<CommandSender> { sender, _, _ ->
                    sender.asPlayer()?.let { ChessCommandHandler.botRemove(it, Side.SECOND) }
                }
            }
        }
    }

    /** 离座 */
    @CommandBody(description = "help-leave")
    val leave = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.asPlayer()?.let { ChessCommandHandler.leave(it) }
        }
    }

    /** 认输 */
    @CommandBody(description = "help-resign")
    val resign = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.asPlayer()?.let { ChessCommandHandler.resign(it) }
        }
    }

    /**
     * 求和。
     *
     * 对手会收到一条带"同意/拒绝"按钮的提示，那两颗按钮点出来的就是下面的
     * [accept] 与 [decline]。任何一方在自己或对手的回合都能开口 ——
     * 求和只是一个态度，不像悔棋那样撤具体某一手。
     *
     * 这四条命令直接调 [GameRequestFlow] 而不经 [ChessCommandHandler]：
     * 请求的全部业务（前置校验、登记、通知、落地）已经在那个对象里，
     * 在 handler 里再包一层纯转发的方法不会让任何一侧更清楚。
     */
    @CommandBody(description = "help-draw")
    val draw = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.asPlayer()?.let { GameRequestFlow.requestDraw(it) }
        }
    }

    /**
     * 悔棋。
     *
     * 只能撤自己刚走的那一手，而且要对手同意。`config.yml` 的 `allow-undo`
     * 关着时这条命令直接拒绝（默认就是关的）。
     */
    @CommandBody(description = "help-undo")
    val undo = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.asPlayer()?.let { GameRequestFlow.requestUndo(it) }
        }
    }

    /**
     * 同意对手的求和或悔棋。
     *
     * 必须带上类型（`draw` / `undo`），不带类型不执行任何动作。
     * 不做"自动答复当前唯一那条请求"的省事写法：求和与悔棋可以同时挂着，
     * 那时候"唯一"不成立，猜错一次的代价是玩家莫名同意了和棋。
     * 这两条命令基本上都由提示里的按钮点出来，按钮永远带全参数。
     */
    @CommandBody(description = "help-accept")
    val accept = subCommand {
        literal("draw") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { GameRequestFlow.answer(it, GameRequestKind.DRAW, true) }
            }
        }
        literal("undo") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { GameRequestFlow.answer(it, GameRequestKind.UNDO, true) }
            }
        }
    }

    /** 拒绝对手的求和或悔棋，参数与 [accept] 一致 */
    @CommandBody(description = "help-decline")
    val decline = subCommand {
        literal("draw") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { GameRequestFlow.answer(it, GameRequestKind.DRAW, false) }
            }
        }
        literal("undo") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { GameRequestFlow.answer(it, GameRequestKind.UNDO, false) }
            }
        }
    }

    /**
     * 虚着，围棋专用。
     *
     * 双方连续虚着即终局并数子。其余棋种执行会得到"这个棋种没有虚着"的提示，
     * 判定在 [ChessCommandHandler.pass] 转交给规则层，命令树不认棋种。
     *
     * 虚着只有命令入口，没有做进棋盘悬浮字或箱子菜单：它是围棋收官阶段才用的动作，
     * 挂在牌子上会让另两个棋种的玩家看到一个点了没反应的按钮。
     */
    @CommandBody(description = "help-pass")
    val pass = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.asPlayer()?.let { ChessCommandHandler.pass(it) }
        }
    }

    /**
     * 压测：批量铺棋盘、报性能数据、一键收走。
     *
     * `test <数量> <类型>` 在脚下铺一片网格棋盘并直接摆上棋子，报净耗时与实体量；
     * `remove` 撤掉本次会话测出来的那批。
     *
     * 走的是与正常放置同一套校验与渲染（见 [DebugBench]），只是家具由插件自己放。
     * 属于管理命令，权限与 reload/pack 一致。
     */
    @CommandBody(description = "help-debug", permission = "muchess.admin")
    val debug = subCommand {
        literal("test") {
            dynamic("count") {
                // 只给几个常用量级，手输任意数字仍然可以
                suggestion<CommandSender>(uncheck = true) { _, _ ->
                    listOf("1", "8", "16", "32", "64")
                }
                dynamic("type") {
                    suggestion<CommandSender>(uncheck = true) { _, _ ->
                        GameType.entries.map { it.id }
                    }
                    execute<CommandSender> { sender, ctx, _ ->
                        val player = sender.asPlayer() ?: return@execute
                        val count = ctx["count"].toIntOrNull()
                        if (count == null) {
                            Msg.send(player, "debug-count-invalid", "max" to DebugBench.MAX_COUNT)
                            return@execute
                        }
                        // 棋种输错走已有的 unknown-game-type，那条键本来就是留给这个场景的
                        val type = GameType.byId(ctx["type"])
                        if (type == null) {
                            Msg.send(player, "unknown-game-type", "type" to ctx["type"])
                            return@execute
                        }
                        DebugBench.test(player, count, type)
                    }
                }
            }
        }
        literal("remove") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { DebugBench.remove(it) }
            }
        }
        // 清掉附近脱离索引的残留显示实体。不带参数用默认半径，带一个区块半径可自定。
        literal("sweep") {
            execute<CommandSender> { sender, _, _ ->
                sender.asPlayer()?.let { ChessCommandHandler.sweep(it, null) }
            }
            dynamic("radius") {
                suggestion<CommandSender>(uncheck = true) { _, _ ->
                    listOf("1", "4", "8", "16")
                }
                execute<CommandSender> { sender, ctx, _ ->
                    sender.asPlayer()?.let { ChessCommandHandler.sweep(it, ctx["radius"]) }
                }
            }
        }
    }

    /**
     * 管理员强拆正对着的棋盘。
     *
     * 与 [remove] 并存而不是替换它：那条是给玩家收自己摆的棋盘用的，认距离、且对局中会拒绝。
     * 这条认视线、对局中也拆，用途是收拾坏掉或没人管的棋盘，因此挂 `muchess.admin`。
     */
    @CommandBody(description = "help-forceremove", permission = "muchess.admin")
    val forceremove = subCommand {
        execute<CommandSender> { sender, _, _ ->
            val player = sender.asPlayer() ?: return@execute
            ChessCommandHandler.forceRemove(player)
        }
    }

    /** 重载配置与语言文件 */
    @CommandBody(description = "help-reload", permission = "muchess.admin")
    val reload = subCommand {
        execute<CommandSender> { sender, _, _ ->
            ChessCommandHandler.reload(sender)
        }
    }

    /**
     * 重新生成 CraftEngine 资源包。
     *
     * 资源包生成属于 `pack/` 模块，由并行开发的桥接侧提供。
     * 这里只留调用点，实际生成通过 [ChessCommandHandler.pack] 转交。
     */
    @CommandBody(description = "help-pack", permission = "muchess.admin")
    val pack = subCommand {
        execute<CommandSender> { sender, _, _ ->
            ChessCommandHandler.pack(sender)
        }
    }

    /**
     * 取玩家身份，控制台执行时提示只能玩家用。
     *
     * 抽出来是因为六个子命令里有四个都需要这一步。
     */
    private fun CommandSender.asPlayer(): Player? {
        if (this is Player) {
            return this
        }
        Msg.send(this, "player-only")
        return null
    }
}
