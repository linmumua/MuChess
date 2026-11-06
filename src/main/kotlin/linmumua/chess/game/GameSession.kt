package linmumua.chess.game

import java.util.UUID
import linmumua.chess.board.BoardAnchor
import linmumua.chess.config.ChessSettings
import linmumua.chess.render.BoardLabelRenderer
import linmumua.chess.render.BoardLabelState
import linmumua.chess.render.BoardRenderer
import linmumua.chess.render.BoardVisualHandle
import linmumua.chess.render.HighlightRenderer
import linmumua.chess.rule.core.Side
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * 把一局棋绑到世界里的一副棋盘上。
 *
 * [ChessGame] 只有规则与轮次，不知道自己摆在哪；[BoardAnchor] 只知道坐标；
 * 这个类负责把两者连起来，再挂上渲染器与观战者名单。
 *
 * 一副已放置的棋盘对应一个 session，[id] 是它的身份，落盘与选中态都用这个 id 索引。
 *
 * 所有涉及实体的方法必须在主线程调用。
 */
class GameSession(
    /** 棋盘身份，落盘后仍保持不变 */
    val id: UUID,
    /** 世界坐标标定 */
    val anchor: BoardAnchor,
    /** 这副棋盘上的对局 */
    val game: ChessGame,
    settings: ChessSettings,
    /**
     * 这副棋盘的棋种。
     *
     * 与 `game.rules` 是两个角度的同一件事：规则实例回答"这步能不能走"，
     * 棋种回答"这副棋盘该用哪个家具、铺多大、棋子多大、存档里记什么"。
     * 默认象棋是为了让既有调用点与老存档不必逐个改。
     */
    val gameType: GameType = GameType.XIANGQI,
) {

    /** 棋子渲染器，独占本棋盘的显示实体 */
    val renderer = BoardRenderer(anchor, settings, gameType, id)

    /** 白圈落点与棋子发光提示渲染器 */
    val highlight = HighlightRenderer(anchor, renderer, settings, id)

    /**
     * 棋盘上方那行悬浮字的渲染器。
     *
     * 整局都在显示：等人时是"加入 / 开始对局"的可点入口，对局中是手数与轮次，
     * 终局是胜负结果。刷新时机由调用方在放置棋盘、入座、开局、走子、终局这几处调
     * [refreshLabel]，渲染器本身是幂等的。
     */
    val label = BoardLabelRenderer(id, anchor, gameType)

    /** 棋盘家具句柄，CE 缺失时为 null */
    var visualHandle: BoardVisualHandle? = null

    /**
     * 这一局是否允许放开局背景音乐（只对围棋有意义）。
     *
     * 与 `config.yml` 的 `go.opening-bgm.enabled` 是**两级闸**：总闸关着时这里为真也不响。
     * 玩家在对局菜单里点开关改的就是这个字段，它随棋盘落盘（见 `BoardRecord.bgmEnabled`），
     * 重启后仍然保持关着。
     *
     * 放在 session 而不是 ChessGame：它是这副棋盘的表现设定，跟规则和局面无关，
     * 规则层不该知道有音乐这回事。
     */
    var bgmEnabled: Boolean = true

    /** 观战者，不参与走棋 */
    private val spectators = HashSet<UUID>()

    /** 配置重载后把新参数透传给两个渲染器 */
    fun updateSettings(settings: ChessSettings) {
        renderer.updateSettings(settings)
        highlight.updateSettings(settings)
    }

    /** 加一个观战者，已在对局中的人不算观战 */
    fun addSpectator(player: UUID) {
        if (!game.isPlayer(player)) {
            spectators += player
        }
    }

    /** 移除观战者 */
    fun removeSpectator(player: UUID) {
        spectators -= player
    }

    /** 是否为本局观战者 */
    fun isSpectator(player: UUID): Boolean = spectators.contains(player)

    /**
     * 该玩家在本局的阵营，观战者与路人返回 null。
     *
     * 交互层用它决定"这次点击算不算走棋"。
     */
    fun sideOf(player: UUID): Side? = game.sideOf(player)

    /** 对局双方中在线的玩家 */
    fun onlinePlayers(): List<Player> =
        listOfNotNull(game.redPlayer, game.blackPlayer).mapNotNull { Bukkit.getPlayer(it) }

    /**
     * 需要收到本局播报的所有在线玩家。
     *
     * 双方加观战者。走棋提示、将军播报、终局播报都发给这一组，
     * 不广播全服 —— 别人的棋局刷屏是很讨人厌的。
     */
    fun audience(): List<Player> {
        val result = ArrayList<Player>(spectators.size + 2)
        result += onlinePlayers()
        for (uuid in spectators) {
            Bukkit.getPlayer(uuid)?.let { result += it }
        }
        return result
    }

    /** 能看到将军警示粒子的玩家，与播报对象一致 */
    fun viewers(): List<Player> = audience()

    /**
     * 本会话当前在册的全部显示实体 UUID。
     *
     * 三个渲染器各报自己那份，这里并起来。清扫用它判断"世界里这只带标记的实体
     * 是否还有主"，见 [linmumua.chess.render.OrphanDecision]。
     *
     * 只读聚合，不碰实体本身。
     */
    fun trackedEntityIds(): Set<UUID> {
        val result = HashSet<UUID>()
        result += renderer.trackedEntityIds()
        result += highlight.trackedEntityIds()
        result += label.trackedEntityIds()
        return result
    }

    /** 清掉白圈、棋子描边并把当前浮起棋子放回棋盘面 */
    fun clearSelectionVisuals() {
        highlight.clear()
        renderer.settleHovered()
    }

    /**
     * 结束这局并清场。
     *
     * 必须清掉全部显示实体：项目要求关服或终局后世界里不能残留实体。
     * 棋盘家具本身不在这里收 —— 棋盘是玩家放的，一局下完棋盘还在，
     * 收家具由 `/chess remove` 走 [releaseVisual]。
     */
    fun close() {
        clearSelectionVisuals()
        renderer.clear()
        // 悬浮字与它的判定框也是显示实体，关服与收棋盘都必须清掉，不能留在世界里
        label.clear()
        // 背景音乐是发到客户端的，收棋盘或关服后它不会自己停：曲子会一直放到那一遍结束。
        // 关服路径经 GameManager.shutdown 走到这里，收棋盘走 /chess remove，两条都覆盖了
        GoBgmController.stop(this)
        spectators.clear()
    }

    /**
     * 按当前对局状态刷新棋盘上方的悬浮字。
     *
     * 存在的理由是**收窄出错面**：悬浮字要显示什么，取决于 phase、是否满座、手数、
     * 该谁走、终局结果这几个状态。它们本来就在本会话里，让每个调用点自己去取再传进渲染器，
     * 等于把同一段判断抄十几遍（放置棋盘、入座、开局、加撤机器人、走子、终局、离座、区块重载）。
     * 抄错一处不会报错，只会让某条路径上的牌子停在过时的文案上 —— 玩家看到"开始对局"
     * 点下去却提示座位没满，而这种错很难复现。
     *
     * 所以对外只留这一个无参入口，状态由会话自己读。
     */
    fun refreshLabel() {
        label.refresh(
            BoardLabelState(
                phase = game.phase,
                seatFull = game.isSeatFull(),
                seatedCount = game.seatedCount(),
                moveCount = game.moveCount,
                sideToMove = game.sideToMove,
                result = game.result,
            ),
        )
    }

    /** 把棋盘家具句柄交出去，供拆棋盘时移除外观 */
    fun releaseVisual(): BoardVisualHandle? {
        val handle = visualHandle
        visualHandle = null
        return handle
    }
}
