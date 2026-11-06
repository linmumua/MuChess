package linmumua.chess.render

import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameResult
import linmumua.chess.rule.core.Side
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 悬浮字的刷新判据。
 *
 * `BoardLabelRenderer.refresh` 靠比较前后两个 [BoardLabelState] 决定要不要重发文本。
 * 判据以前是"语言键有没有变"，牌子只有"加入对局 / 开始对局"两种文案时那样够用；
 * 现在对局中那条 `label-playing` 里带了手数与该谁走两个占位符，**键不变而内容会变**，
 * 判据必须跟着退到"状态有没有变"。
 *
 * 这些用例守的就是这次换判据的理由：少带一个字段不会让插件报错，只会让牌子悄悄停在旧数字上，
 * 那种问题在测试里不写下来就只能靠人盯棋盘发现。
 *
 * 这里只测这个纯数据类，不碰 [BoardLabelRenderer] 本体：它要操作 TextDisplay 与 Interaction，
 * 而 paper-api 只在 compileOnly 里，测试的类路径上没有 Bukkit。
 */
class BoardLabelStateTest {

    /**
     * 第一个人坐下，状态就必须不同。
     *
     * 这条针对牌子上的"加入对局 <current>/2"：空盘和坐了一个人这两种局面，
     * `phase` 都是 WAITING、`seatFull` 都是 false、语言键都是 `label-join`，
     * 唯一的区别就是人数。少了 [BoardLabelState.seatedCount] 这个字段，
     * 幂等短路会判成"状态没变"而不重发文本，牌子会一直写着 0/2 —— 插件不报错，
     * 但玩家看不出还差几人，只能靠点一下试试。
     */
    @Test
    @DisplayName("入座人数变化会让状态不等，牌子才会把 0/2 翻成 1/2")
    fun `seated count changes the state`() {
        val empty = BoardLabelState(phase = GamePhase.WAITING, seatFull = false, seatedCount = 0)
        val one = empty.copy(seatedCount = 1)

        assertNotEquals(empty, one, "人数决定牌子上的 <current>，不能比出相等")
    }

    /**
     * 走了一手，状态就必须不同。
     *
     * 这是换判据的直接动机：两个状态的语言键都是 `label-playing`，
     * 按老的键比较会判为"没变"而短路返回，牌子上的手数就永远停在第 1 手。
     */
    @Test
    @DisplayName("手数变化会让状态不等，牌子才会跟着走子更新")
    fun `move count changes the state`() {
        val first = BoardLabelState(
            phase = GamePhase.PLAYING,
            seatFull = true,
            moveCount = 1,
            sideToMove = Side.SECOND,
        )
        val second = first.copy(moveCount = 2)

        assertNotEquals(first, second, "手数是牌子上显示的内容，变了就必须触发刷新")
    }

    /**
     * 轮次换人也要不同。
     *
     * 手数与轮次是两个独立占位符，围棋虚着这类"手数涨了但轮次也换"的情形靠手数就能兜住，
     * 但反过来单独盯手数是不够的：这条用例把轮次固定成独立判据，
     * 避免以后有人把 sideToMove 从状态里删掉。
     */
    @Test
    @DisplayName("该谁走变化会让状态不等")
    fun `side to move changes the state`() {
        val first = BoardLabelState(
            phase = GamePhase.PLAYING,
            seatFull = true,
            moveCount = 3,
            sideToMove = Side.FIRST,
        )
        val second = first.copy(sideToMove = Side.SECOND)

        assertNotEquals(first, second, "轮到谁走是牌子上显示的内容，变了就必须触发刷新")
    }

    /**
     * 什么都没变时必须相等。
     *
     * 这是幂等的另一半，也是 `GameManager.refreshWaitingLabels` 敢每轮巡检的前提：
     * 一副没人动过的棋盘每轮都比出"没变"，就不会重发文本、不碰实体。
     * 这条要是坏了，症状不是显示错误而是每轮都白重建一次牌子。
     */
    @Test
    @DisplayName("状态原样不变时相等，巡检才不会反复重发文本")
    fun `identical state stays equal`() {
        val state = BoardLabelState(
            phase = GamePhase.WAITING,
            seatFull = false,
        )

        assertEquals(state, state.copy(), "同样的状态必须比出相等，否则幂等短路失效")
    }

    /**
     * 阶段不同必须不等，哪怕别的字段一模一样。
     *
     * 等人和对局中都可能是"满座、零手、没结果"，只差一个 phase。
     * 状态里要是漏了 phase，满座待开局的"开始对局"按钮就不会在开局后换成手数行 ——
     * 玩家会看到一块还能点的按钮挂在一局正在下的棋上面。
     */
    @Test
    @DisplayName("只有阶段不同也必须判为不等")
    fun `phase alone distinguishes the state`() {
        val waiting = BoardLabelState(phase = GamePhase.WAITING, seatFull = true)
        val playing = waiting.copy(phase = GamePhase.PLAYING)

        assertNotEquals(waiting, playing, "阶段决定牌子是按钮还是状态行，不能比出相等")
    }

    /**
     * 终局结果不同必须不等。
     *
     * 判胜、和棋、作废分别对应不同的 `label-finished-*`，光靠 phase 区分不开。
     * 认输与超时更接近：两者都只记败方、都由败方推出胜方，但文案与配色是两条
     * （超时跟着 `game-over-timeout` 的失败粉，认输跟着判胜的黄橙），不能混为一谈。
     */
    @Test
    @DisplayName("终局结果不同会让状态不等")
    fun `finished results are told apart`() {
        val base = BoardLabelState(
            phase = GamePhase.FINISHED,
            seatFull = true,
            moveCount = 40,
        )
        val resign = base.copy(result = GameResult.Resign(Side.FIRST))
        val timeout = base.copy(result = GameResult.Timeout(Side.FIRST))
        val quit = base.copy(result = GameResult.Quit(Side.FIRST))

        assertNotEquals(resign, timeout, "认输与超时的牌子文案与配色都不同")
        assertNotEquals(resign, quit, "认输判胜，弃局是这局作废，不能比出相等")
    }

    /**
     * 围棋数子的胜者可空，两种结果必须分开。
     *
     * `ScoreWin` 是唯一一个"同一个类既可能判胜又可能和棋"的终局：winner 为 null 即平局。
     * 分流时它按 winner 走两条路，状态这一层要是把它们比成相等，
     * 数子结果从平局翻成有胜者时牌子就不会换。
     *
     * 分数写成小数不是随手写的：那两个字段是 Double，装的是**含贴子**的子数，
     * 贴子本身就带 0.5 这类零头。
     */
    @Test
    @DisplayName("围棋数子的平局与判胜是两个状态")
    fun `score win separates draw from victory`() {
        val base = BoardLabelState(
            phase = GamePhase.FINISHED,
            seatFull = true,
            moveCount = 180,
        )
        val draw = base.copy(result = GameResult.ScoreWin(null, 180.5, 180.5))
        val victory = base.copy(result = GameResult.ScoreWin(Side.FIRST, 184.5, 176.5))

        assertNotEquals(draw, victory, "数子平局与数子判胜对应不同的牌子文案")
    }

    /**
     * 开局后牌子不许再挂判定框 —— 这条挡的是**棋走不了**。
     *
     * 判定框是只 [org.bukkit.entity.Interaction] 实体，会拦下穿过它的右键：
     * `BoardLabelListener` 认出是自家判定框就 `isCancelled = true`，然后因为不是 WAITING
     * 直接返回，什么也不做。于是玩家从棋盘外侧斜着看向远端棋子、准星正好穿过牌子那一小块时，
     * 这一次点击被白白吞掉，子选不中也落不下。
     *
     * 所以判据必须只认 WAITING 一个阶段。写成用例是因为这个问题在代码里看不出来：
     * 多挂一只判定框不报错、不留日志，只是偶尔点不动棋，只能靠人在棋盘前反复试才发现。
     */
    @Test
    @DisplayName("只有等人阶段的牌子是按钮，开局后判定框必须摘掉，否则会吞掉射向棋盘的右键")
    fun `only waiting phase keeps the hitbox`() {
        // 等人：牌子写"点击入座 / 点击开始"，判定框就是那个按钮，必须在
        assertTrue(
            BoardLabelState(phase = GamePhase.WAITING, seatFull = false).needsHitbox,
            "等人阶段的牌子是入座按钮，没有判定框就点不到，棋盘等于没有入口",
        )
        assertTrue(
            BoardLabelState(phase = GamePhase.WAITING, seatFull = true).needsHitbox,
            "坐满后牌子翻成开始按钮，仍然要能点",
        )

        // 对局中：牌子只报手数与轮次，判定框留着就会挡住下棋
        assertFalse(
            BoardLabelState(phase = GamePhase.PLAYING, seatFull = true, moveCount = 10, sideToMove = Side.SECOND)
                .needsHitbox,
            "对局中的牌子只是播报，判定框会吞掉射向棋盘的右键，让玩家走不了棋",
        )

        // 终局：同理，胜负播报不需要被点
        assertFalse(
            BoardLabelState(
                phase = GamePhase.FINISHED,
                seatFull = true,
                result = GameResult.Checkmate(Side.FIRST, Side.SECOND),
            ).needsHitbox,
            "终局牌子没有可点动作，判定框只会拦路",
        )
    }

    /**
     * 判定框的有无只看阶段，不受手数、人数、结果影响。
     *
     * 这条防的是把判据写成"状态变了就重挂一次"那类实现：判定框在对局中反复挂摘会
     * 一会儿能点一会儿不能点，表现成"这步棋有时走得动有时走不动"，比一直挡着更难查。
     */
    @Test
    @DisplayName("同一阶段内判定框的有无必须稳定，不能随手数或人数摇摆")
    fun `hitbox decision depends only on phase`() {
        for (moveCount in 0..5) {
            assertFalse(
                BoardLabelState(
                    phase = GamePhase.PLAYING,
                    seatFull = true,
                    moveCount = moveCount,
                    sideToMove = if (moveCount % 2 == 0) Side.FIRST else Side.SECOND,
                ).needsHitbox,
                "对局中第 $moveCount 手的牌子都不该有判定框",
            )
        }
        for (seated in 0..1) {
            assertTrue(
                BoardLabelState(phase = GamePhase.WAITING, seatFull = false, seatedCount = seated).needsHitbox,
                "等人阶段坐了 $seated 个人时牌子都要能点",
            )
        }
    }
}
