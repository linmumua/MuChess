package linmumua.chess.render

import java.util.UUID
import linmumua.chess.board.BoardAnchor
import linmumua.chess.config.BoardLabelSettings
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameType
import linmumua.chess.game.GameResult
import linmumua.chess.rule.core.Side
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import taboolib.common.platform.function.warning

/**
 * 悬浮字要表达的对局状态快照。
 *
 * 把散着传的几个参数收成一个值，是为了让 [BoardLabelRenderer.refresh] 的幂等判据从
 * "解析出来的文案一样"退回到"状态一样" —— 后者不必先解析文案就能短路，也不会因为
 * 两种状态恰好共用一条语言键（比如两种和棋）而误判成没变。
 *
 * 刻意不设 `finished: Boolean` 之类的冗余字段：那会允许出现"phase 是 PLAYING 但 finished 为真"
 * 这种非法组合，与本类"收窄出错面"的目的相反。终局的四种呈现（判胜、超时、和棋、作废）
 * 由 result 穷尽 when 分流，见 labelKeyOf。
 *
 * @param phase 对局阶段
 * @param seatFull 是否已坐满两方，只在 [GamePhase.WAITING] 下有意义
 * @param seatedCount 已入座人数，只在 [GamePhase.WAITING] 下有意义。
 *   它必须进状态参与等值比较：少了这个字段，第一个人坐下时 phase 与 seatFull 都没变化，
 *   [BoardLabelRenderer.refresh] 的幂等短路会判成"状态没变"而不重发文本，
 *   牌子上的人数就一直停在 0/2。
 * @param moveCount 已走手数，只在 [GamePhase.PLAYING] 下有意义
 * @param sideToMove 该谁走，只在 [GamePhase.PLAYING] 下有意义
 * @param result 终局结果，未终局时为 null
 */
data class BoardLabelState(
    val phase: GamePhase,
    val seatFull: Boolean,
    val seatedCount: Int = 0,
    val moveCount: Int = 0,
    val sideToMove: Side? = null,
    val result: GameResult? = null,
) {

    /**
     * 这个状态的牌子是不是按钮，也就是要不要挂那只 [Interaction] 判定框。
     *
     * 只有 [GamePhase.WAITING] 是：那时牌子写"点击入座"或"点击开始"，点了真有动作。
     * 对局中显示手数轮次、终局显示胜负，都只是播报。
     *
     * 判定框不能在这两个阶段留着：它会拦下穿过它的右键（`BoardLabelListener` 认出自家
     * 判定框就取消事件），玩家从棋盘外侧斜看远端棋子时准星先穿过牌子，那一下点击被吞掉，
     * 棋就走不了。所以这不是"点了没反应"的小事，是会挡住下棋。
     *
     * 放在状态类上而不是渲染器里，是为了能单测：渲染器要操作 Bukkit 实体，测试类路径上没有
     * paper-api。这条规则本身只跟阶段有关，是纯逻辑。
     */
    val needsHitbox: Boolean get() = phase == GamePhase.WAITING
}

/**
 * 棋盘上方那行悬浮字的渲染器。
 *
 * 一副棋盘对应一个实例，整局棋都靠它对外说话：等人时显示可点的入座/开局提示，
 * 对局中显示手数与轮次，终局显示胜负。
 *
 * 为什么是两个实体：[TextDisplay] 自己没有命中箱，右键点不到，所以必须在同一位置叠一个
 * [Interaction] 当判定框。文字负责好看，判定框负责被点。
 *
 * 判定框**只在 [GamePhase.WAITING] 存在**。开局后它必须消失：[Interaction] 会拦下穿过它的
 * 右键（`BoardLabelListener` 拿到自家判定框就 `isCancelled = true`），玩家从棋盘外侧斜着
 * 看向远端棋子时，准星会先穿过牌子那一小块判定框，那一下点击就被吞掉，棋走不了。
 * 对局中与终局的牌子只报状态（手数轮次、胜负），没有任何可点的动作，判定框留着纯是拦路。
 *
 * 所以两者的生命周期是"文字长存、判定框按阶段挂摘"，不再成对创建销毁。唯一仍然成对的是
 * WAITING：那时牌子写着"点击入座"，判定框建不出来就等于按钮失效，宁可整个牌子不显示，
 * 也不留一行点不动的字骗人。
 *
 * 实体索引与 [BoardRenderer] 同一套纪律：内存字段是唯一真源，不扫世界找实体，
 * 增删只经过本类。判定框到会话的反查走 [sessionIdOf]，监听器因此不需要射线或距离判断。
 *
 * 所有方法都必须在主线程调用：Bukkit 的实体操作不是线程安全的。
 *
 * 开关与尺寸全部取自 config.yml 的 `board-label` 段。这里在 [refresh] 里现读
 * [ChessConfig] 而不是像 [BoardRenderer] 那样在构造时收一份快照：本类因此不需要
 * `updateSettings` 那样的透传链，任何一次 [refresh] 都会拿到当前生效的配置。
 * `/chess reload` 之后由 `GameManager.applySettings` 对每副棋盘调一次
 * `GameSession.refreshLabel`，新的高度与缩放就地生效。
 *
 * @param sessionId 所属会话身份，登记进反查表用
 * @param anchor 坐标标定，取棋盘中心与棋盘面高度
 * @param gameType 棋种，牌子上的阵营名按它取键（象棋红/黑，围棋与五子棋黑/白）
 */
class BoardLabelRenderer(
    private val sessionId: UUID,
    private val anchor: BoardAnchor,
    private val gameType: GameType,
) {

    /** 悬浮字实体，隐藏状态下为 null */
    private var display: TextDisplay? = null

    /** 悬浮字的命中判定框，与 [display] 同生共死 */
    private var hitbox: Interaction? = null

    /**
     * 当前牌子对应的状态，用于幂等判断：状态没变就不重发文本。
     *
     * 存状态而不是存语言键：对局中每走一手手数都在变，键却始终是 `label-playing`，
     * 只比键会让牌子永远停在"第 1 手"。
     */
    private var shownState: BoardLabelState? = null

    /**
     * 生成当前这对实体时用的那份配置。
     *
     * 高度、缩放、判定框尺寸是在 spawn 时写进实体的，配置改了必须重建才能生效。
     * 存一份快照跟现值比，就能在 `/chess reload` 后自动重建；[BoardLabelSettings]
     * 是 data class，直接用等值比较，不必逐字段列。
     */
    private var shownSettings: BoardLabelSettings? = null

    /** 悬浮字当前是否在世界里，供日志与测试观察 */
    val isShown: Boolean get() = display != null

    /**
     * 按当前对局状态刷新悬浮字。
     *
     * 文案切换、隐藏与显示都走这一个入口，可以反复调用：
     *
     * - [GamePhase.WAITING] 且有空座 → `label-join`，点了入座
     * - [GamePhase.WAITING] 且满座 → `label-start`，点了开局
     * - [GamePhase.PLAYING] → `label-playing`，显示手数与该谁走
     * - [GamePhase.FINISHED] → 按 [GameResult] 分流到胜负 / 超时 / 和棋 / 作废
     *
     * 幂等靠 [shownState]：状态没变就只补实体有效性，不重复下发文本，
     * 免得每次巡检都给客户端刷一遍相同的字。
     *
     * 谁来调用：WAITING 期间的入座、离座由各自命令触发，还有 `GameManager` 的巡检兜底；
     * 对局中由 `MoveExecutor.afterMove` 每走一手调一次 —— 手数变了必须跟着变，
     * 靠巡检会让牌子慢半拍。终局由结算路径调最后一次。
     *
     * @param state 要表达的对局状态
     */
    fun refresh(state: BoardLabelState) {
        val settings = ChessConfig.settings.boardLabel
        if (!settings.enabled) {
            clear()
            return
        }
        val key = labelKeyOf(state)
        if (key == null) {
            // 没有对应文案的状态不留空文本实体，直接清掉
            clear()
            return
        }
        // 这个阶段的牌子该不该有判定框。只有 WAITING 的牌子是按钮，其余阶段的判定框
        // 会吞掉射向棋盘的右键，必须摘掉，见类注释。
        val wantHitbox = state.needsHitbox

        // 实体可能因为区块卸载被服务端回收，也可能被服主 `/kill @e[type=text_display]`
        // 这类清场命令直接删掉。两种情况下 isValid 都是 false（Bukkit 的契约：实体已死、
        // 被以其他原因移除、或还没加入世界时返回 false），必须重建而不是接着用旧引用；
        // 配置改过同样要重建，尺寸是 spawn 时写死进实体的。
        //
        // 判定框不参与 alive：它按阶段挂摘，"该没有的时候没有"是正常态，
        // 若把 hitbox == null 也算坏了，对局中每次刷新都会重建整个牌子。
        val alive = display?.isValid == true && shownSettings == settings
        if (alive && shownState == state && hitboxAlive() == wantHitbox) {
            // 状态没变就在这里短路，不必再解析一遍同样的文案
            return
        }

        // 文案必须在动实体之前取出来。缺键时这里直接隐藏并返回，
        // 不会出现"先 spawn 一对实体、发现没文案又立刻删掉"的空转，
        // 也不会留下 shownState 有值而实体为 null 的错位状态。
        val component = resolveText(key, state)
        if (component == null) {
            clear()
            return
        }

        if (!alive) {
            clear()
            if (!spawnText(settings)) {
                return
            }
        }
        // 判定框按阶段挂摘。这里不动文字实体：开局时只摘判定框，牌子上的字要接着显示手数。
        if (wantHitbox && !hitboxAlive()) {
            if (!spawnHitbox(settings)) {
                // WAITING 的牌子写着"点击入座"，判定框建不出来就是个骗人的按钮，
                // 整块收掉比留一行点不动的字好。失败原因已在 spawnHitbox 里记了日志。
                clear()
                return
            }
        } else if (!wantHitbox && hitbox != null) {
            removeHitbox()
        }
        // TextDisplay.setText(String) 在 Paper 26.2 已废弃，只能用吃 Component 的 text()
        display?.text(component)
        shownState = state
    }

    /** 判定框当前是否在世界里且可用 */
    private fun hitboxAlive(): Boolean = hitbox?.isValid == true

    /** 摘掉判定框，反查表登记一起摘，留着会让监听器认领一只已死的实体 */
    private fun removeHitbox() {
        hitbox?.let { entity ->
            SESSION_BY_HITBOX.remove(entity.uniqueId)
            entity.remove()
        }
        hitbox = null
    }

    /**
     * 清理悬浮字与判定框实体。
     *
     * 关服（`GameManager.shutdown` → `GameSession.close`）与收棋盘都会走到这里。
     * 项目硬要求关服后世界里不能残留显示实体，所以反查表登记也要一起摘掉，
     * 不能只 remove 实体留下映射。
     */
    fun clear() {
        removeHitbox()
        display?.remove()
        display = null
        shownState = null
        shownSettings = null
    }

    /**
     * 本渲染器当前在册的实体 UUID（悬浮字与判定框）。
     *
     * 只读，供清扫构造"活会话在册实体全集"用，见 [OrphanDecision]。
     * 隐藏状态下两个字段都是 null，返回空集 —— 那时世界里也确实没有这两只实体。
     */
    fun trackedEntityIds(): Set<UUID> {
        val result = HashSet<UUID>(2)
        display?.let { result += it.uniqueId }
        hitbox?.let { result += it.uniqueId }
        return result
    }

    /**
     * 本次该显示哪条文案，没有可显示内容时返回 null。
     *
     * 终局按 [GameResult] 分四路，分类依据是"这局到底算什么结果"，不是"用哪个色"：
     *
     * - 判胜：将死、困毙、认输、连五、围棋数子有胜者
     * - 超时：单列一条，因为 `game-over-timeout` 的渐变起手色与其他判胜条目不同，
     *   同一个终局在聊天栏与牌子上必须同色
     * - 和棋：限着、满盘、围棋数子平局
     * - 作废：[GameResult.Quit]。它只记 loser，注释写明"一方离开，这局作废"，
     *   所以不能当成对手获胜 —— 那等于凭空发一场胜绩
     *
     * [GameResult.ScoreWin] 的 winner 是可空的，null 即平局，因此它同时落在判胜与和棋两路，
     * 不能整类归到判胜。
     *
     * result 为 null 表示阶段是 FINISHED 但结果没落下来，属于不该出现的状态，
     * 这里返回 null 让牌子消失而不是编一个结果。
     */
    private fun labelKeyOf(state: BoardLabelState): String? = when (state.phase) {
        GamePhase.WAITING -> if (state.seatFull) KEY_START else KEY_JOIN
        GamePhase.PLAYING -> KEY_PLAYING
        GamePhase.FINISHED -> when (val result = state.result) {
            is GameResult.Checkmate,
            is GameResult.Stalemate,
            is GameResult.Resign,
            is GameResult.LineWin,
            -> KEY_FINISHED_WIN
            is GameResult.Timeout -> KEY_FINISHED_TIMEOUT
            is GameResult.ScoreWin -> if (result.winner == null) KEY_FINISHED_DRAW else KEY_FINISHED_WIN
            is GameResult.DrawByMoveLimit, GameResult.DrawByFullBoard, GameResult.DrawByAgreement -> KEY_FINISHED_DRAW
            is GameResult.Quit -> KEY_FINISHED_VOID
            null -> null
        }
    }

    /**
     * 这条文案要填的占位符。
     *
     * 只有需要占位符的三条会返回非空：对局中要填手数与该谁走，判胜与超时要填胜者。
     * 阵营名走 [Msg.sideName]，它返回的是已经带好阵营色的 MiniMessage 片段，
     * 所以语言文件里的 `<side>` / `<winner>` 外层不再包色。
     *
     * 胜者取自各自的 winner 字段：[GameResult.Resign] 与 [GameResult.Timeout] 没有显式
     * winner，用的是它们自带的 `loser.opposite` 派生属性。
     */
    private fun placeholdersOf(state: BoardLabelState): Array<Pair<String, Any?>> {
        val console = Bukkit.getConsoleSender()
        return when (state.phase) {
            GamePhase.PLAYING -> {
                val side = state.sideToMove?.let { Msg.sideName(console, it, gameType) }
                // 手数从 0 开始记，牌子上显示"第几手"要看的是即将走的那一手
                arrayOf("move" to state.moveCount + 1, "side" to side)
            }
            GamePhase.FINISHED -> {
                val winner = when (val result = state.result) {
                    is GameResult.Checkmate -> result.winner
                    is GameResult.Stalemate -> result.winner
                    is GameResult.Resign -> result.winner
                    is GameResult.Timeout -> result.winner
                    is GameResult.LineWin -> result.winner
                    is GameResult.ScoreWin -> result.winner
                    // 和棋与作废的文案里没有 <winner>，多给一个空占位符也无害
                    is GameResult.DrawByMoveLimit, GameResult.DrawByFullBoard, GameResult.DrawByAgreement,
                    is GameResult.Quit, null,
                    -> null
                }
                arrayOf("winner" to winner?.let { Msg.sideName(console, it, gameType) })
            }
            GamePhase.WAITING -> arrayOf("current" to state.seatedCount)
        }
    }

    /**
     * 取这条文案的组件形式。
     *
     * 文本走 [Msg.component]，语言键缺失时它返回 null 并已记日志；这里跟着返回 null 让
     * 调用方隐藏实体，不自己拼兜底文字 —— 项目规定玩家可见文本只能来自语言文件，
     * 宁可不显示也不能把裸键名挂到世界里。
     *
     * 收件人用控制台：悬浮字是全服一份的世界内实体，没有"某个玩家"这个概念，
     * 取的是语言文件的默认语言。`/chess reload` 这类命令本来就允许控制台执行并走同一条
     * `Msg.send(sender, ...)` 链路，所以这个收件人在本项目里是既有用法。
     */
    private fun resolveText(key: String, state: BoardLabelState): Component? =
        Msg.component(Bukkit.getConsoleSender(), key, *placeholdersOf(state))

    /**
     * 生成悬浮字实体。
     *
     * 判定框不在这里建：它按阶段挂摘，见 [spawnHitbox]。
     *
     * @return 是否成功。世界不可用时返回 false，调用方停止后续写文本。
     */
    private fun spawnText(settings: BoardLabelSettings): Boolean {
        val base = anchor.centerLocation()
        val world = base.world ?: run {
            warning("棋盘所在世界不可用，跳过悬浮字渲染：session=$sessionId")
            return false
        }
        // 悬浮字锚点在棋盘中心正上方；TextDisplay 未变换时实体位置对应文字底部中心
        val textLocation = Location(world, base.x, anchor.surfaceY + settings.yOffset, base.z)
        val text = world.spawn(textLocation, TextDisplay::class.java) { entity: TextDisplay ->
            // CENTER 让文字始终正对玩家，绕开棋盘四向与玩家站位的组合
            entity.billboard = Display.Billboard.CENTER
            entity.setAlignment(TextDisplay.TextAlignment.CENTER)
            // 单行短文案，给足行宽避免被折行
            entity.setLineWidth(LINE_WIDTH)
            // -1 是默认全不透明；alpha 小于 26 的背景会被客户端当全透明，所以底板给 0x40
            entity.setTextOpacity(TEXT_OPACITY)
            entity.setDefaultBackground(false)
            entity.setBackgroundColor(BACKGROUND)
            entity.setShadowed(true)
            // 不穿墙显示：隔着建筑还能看到入座提示会让人以为附近就有棋盘
            entity.setSeeThrough(false)
            entity.setTransformation(labelTransformation(settings.scale))
            // viewRange 是倍率不是格数，实际距离约为它乘 64 格，再受客户端设置限制
            entity.setViewRange(settings.viewRange.toFloat())
            // 悬浮字不该有阴影，棋盘面上会糊一片
            entity.shadowRadius = 0f
            entity.shadowStrength = 0f
            // 不持久化：与棋子实体同一约定，存进区块会在重启后变成孤儿实体
            entity.isPersistent = false
            // 归属标记，供索引丢引用后的清扫识别，见 DisplayMarker。
            // 悬浮字是最容易残留的一类：会话没经过 close() 就消失时，
            // 它会一直挂在空棋盘上方，而且每次区块重载都重新长出来。
            DisplayMarker.mark(entity, sessionId, DisplayMarker.Role.LABEL)
        }

        // 调用方（[refresh]）在这之前已经 clear 过，所以此处赋值不会覆盖掉还活着的实体。
        display = text
        shownSettings = settings
        return true
    }

    /**
     * 生成判定框。
     *
     * 只在 WAITING 阶段被调用，开局后由 [removeHitbox] 摘掉。文字实体不在这里动：
     * 失败时是否要连文字一起收，由调用方按阶段决定（见 [refresh]）。
     *
     * @return 是否成功
     */
    private fun spawnHitbox(settings: BoardLabelSettings): Boolean {
        val base = anchor.centerLocation()
        val world = base.world ?: run {
            warning("棋盘所在世界不可用，跳过悬浮字判定框：session=$sessionId")
            return false
        }
        // world.spawn 在世界正在卸载、或实体数撞到服务端上限时会抛。
        // 此刻 [hitbox] 字段还没赋值，异常一旦抛出去就再没有引用指向那只实体，
        // [clear] 也删不掉它，世界里会留一只看不见的拦路判定框 —— 必须接住。
        val hit = runCatching {
            world.spawn(hitboxLocation(world, base, settings), Interaction::class.java) { entity: Interaction ->
                entity.interactionWidth = settings.hitboxWidth.toFloat()
                entity.interactionHeight = settings.hitboxHeight.toFloat()
                // 响应式判定框会给客户端摆手反馈，玩家点了知道点上了
                entity.isResponsive = true
                entity.isPersistent = false
                // 判定框也要打归属标记，否则索引丢引用后的清扫会漏掉它
                DisplayMarker.mark(entity, sessionId, DisplayMarker.Role.LABEL_HITBOX)
            }
        }.getOrElse { error ->
            // 不允许吞异常：判定框建不出来等于这副棋盘的入座入口没了，服主必须能从日志查到原因
            warning(
                "悬浮字判定框生成失败：session=$sessionId，" +
                    "${error.javaClass.simpleName} ${error.message}",
            )
            return false
        }
        // 反查表登记排在实体落地之后：失败路径没有登记过，不会留下半条映射
        hitbox = hit
        SESSION_BY_HITBOX[hit.uniqueId] = sessionId
        return true
    }

    /**
     * 判定框的落点。
     *
     * [Interaction] 的实体位置对应判定框**底部中心**，[TextDisplay] 未变换时对应文字
     * **底部中心**，两者锚点语义一致。文字缩放后的目视高度约为
     * `TEXT_LINE_HEIGHT × board-label.scale`，把判定框的竖直中心对到文字的竖直中心，
     * 于是判定框底部要落在"文字中心 - 半个判定框高"。
     */
    private fun hitboxLocation(
        world: org.bukkit.World,
        base: Location,
        settings: BoardLabelSettings,
    ): Location {
        val textBottomY = anchor.surfaceY + settings.yOffset
        val textCenterY = textBottomY + TEXT_LINE_HEIGHT * settings.scale / 2.0
        return Location(world, base.x, textCenterY - settings.hitboxHeight / 2.0, base.z)
    }

    /**
     * 悬浮字的基础变换。
     *
     * 只做等比缩放：朝向交给 [Display.Billboard.CENTER]，位置已经由实体坐标给准，
     * 这里再叠平移或旋转只会让判定框对不上文字。
     */
    private fun labelTransformation(scale: Double): Transformation = Transformation(
        Vector3f(0f, 0f, 0f),
        Quaternionf(),
        Vector3f(scale.toFloat()),
        Quaternionf(),
    )

    companion object {

        /**
         * 判定框 UUID → 会话 id。
         *
         * 监听器靠它把一次右键精确还原成"点的是哪副棋盘的悬浮字"，不用射线也不用距离。
         * 全插件共享一张表，条目由 [spawn] 登记、[clear] 摘除，成对维护不会漏。
         */
        private val SESSION_BY_HITBOX = HashMap<UUID, UUID>()

        /** 反查这个判定框属于哪个会话，不是本插件的悬浮字时返回 null */
        fun sessionIdOf(interactionUuid: UUID): UUID? = SESSION_BY_HITBOX[interactionUuid]

        /**
         * 摘掉一条判定框登记，供清扫用。
         *
         * 正常路径由 [clear] 成对摘除，用不到这个方法。清扫是例外：它直接删世界里的
         * 残留实体，绕开了渲染器实例（那个实例可能已经不存在了），
         * 于是表里会留下一条指向已删实体的死记录。条目不多但会随开服时间累积，
         * 而且反查到一个已经不存在的会话只会让排查更难。
         */
        fun forgetHitbox(interactionUuid: UUID) {
            SESSION_BY_HITBOX.remove(interactionUuid)
        }

        /** 等人入座时的文案键 */
        private const val KEY_JOIN = "label-join"

        /** 坐满待开局时的文案键 */
        private const val KEY_START = "label-start"

        /** 对局中的文案键，带手数与轮次 */
        private const val KEY_PLAYING = "label-playing"

        /** 终局有胜者的文案键 */
        private const val KEY_FINISHED_WIN = "label-finished-win"

        /** 终局超时判负的文案键，与判胜分开只因为渐变色要跟 game-over-timeout 对齐 */
        private const val KEY_FINISHED_TIMEOUT = "label-finished-timeout"

        /** 终局和棋的文案键 */
        private const val KEY_FINISHED_DRAW = "label-finished-draw"

        /** 一方离开导致对局作废的文案键 */
        private const val KEY_FINISHED_VOID = "label-finished-void"

        /** 单行行宽，短文案给足即可，避免被折成两行 */
        private const val LINE_WIDTH = 200

        /** 文本不透明度，byte 的 -1 表示默认全不透明 */
        private const val TEXT_OPACITY: Byte = -1

        /**
         * 缩放为 1 时一行文字的目视高度，单位方块。
         *
         * 原版字体一行 9 像素，按名牌那套 0.025 格/像素折算约 0.225 格，这里取 0.25 兜一点余量。
         * 只用于把判定框的竖直中心对到文字中心，差个几毫米不影响点击，所以是近似值不是精确值。
         */
        private const val TEXT_LINE_HEIGHT = 0.25

        /**
         * 悬浮字底板颜色，半透明黑。
         *
         * alpha 必须不小于 26，否则客户端把整块底板当全透明处理，字会糊在背景上看不清。
         */
        private val BACKGROUND: Color = Color.fromARGB(0x40, 0x00, 0x00, 0x00)
    }
}

