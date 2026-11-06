package linmumua.chess.interact

import kotlin.test.assertTrue
import linmumua.chess.config.CaptureExplosionSettings
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 炮吃子爆炸的触发判定。
 *
 * 爆炸是炮的专属表现：炮是唯一隔子远距离吃子的兵种，一团火光符合它的意象。
 * 判定写错会变成"每步棋都炸"或者"炮吃子不炸"，这两种玩家一眼就能看出来，
 * 所以每个分支都要钉住。
 */
class CaptureExplosionTest {

    private val settings = CaptureExplosionSettings()

    private fun move(type: PieceType, captured: Piece?): Move = Move(
        from = Square(1, 7),
        to = Square(1, 2),
        piece = Piece(type, Side.FIRST),
        captured = captured,
    )

    private val blackChariot = Piece(PieceType.CHARIOT, Side.SECOND)

    /** 炮吃子是唯一该放爆炸的情况 */
    @Test
    @DisplayName("炮吃子时放爆炸")
    fun `cannon capture explodes`() {
        assertTrue(shouldExplodeOnCapture(move(PieceType.CANNON, blackChariot), settings))
    }

    /**
     * 炮不吃子时走法同車，只是平移，没有撞击，不该有爆炸。
     */
    @Test
    @DisplayName("炮空走不放爆炸")
    fun `cannon quiet move does not explode`() {
        assertTrue(!shouldExplodeOnCapture(move(PieceType.CANNON, null), settings))
    }

    /**
     * 其它兵种是贴身吃子，全都加爆炸会让棋盘一直在炸，很吵。
     */
    @Test
    @DisplayName("其它兵种吃子不放爆炸")
    fun `other piece captures do not explode`() {
        val others = PieceType.entries.filter { it != PieceType.CANNON }
        for (type in others) {
            assertTrue(
                !shouldExplodeOnCapture(move(type, blackChariot), settings),
                "$type 吃子不该放爆炸，爆炸是炮的专属表现",
            )
        }
    }

    /** 关掉开关后连炮吃子也不能炸，否则这个配置项形同虚设 */
    @Test
    @DisplayName("关掉开关后炮吃子也不放爆炸")
    fun `disabled setting suppresses explosion`() {
        val disabled = settings.copy(enabled = false)
        assertTrue(!shouldExplodeOnCapture(move(PieceType.CANNON, blackChariot), disabled))
    }
}
