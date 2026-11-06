package linmumua.chess.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 每手限时档位循环的单测。
 *
 * 这颗按钮的两个坏结果都不靠肉眼能看出来：
 * - 循环接不上（点几下之后卡在最后一档）
 * - 存档里出现表外的值时按钮变成死键
 */
class TurnTimeoutOptionsTest {

    /**
     * 点满一圈回到起点。
     *
     * 逐档写死而不是循环断言，是因为档位顺序本身就是要钉住的东西：
     * 顺序若被人改成"先跳到 120 秒"，这条用例必须红。
     */
    @Test
    fun `点满一圈回到跟随全局`() {
        assertEquals(0, TurnTimeoutOptions.next(null))
        assertEquals(30, TurnTimeoutOptions.next(0))
        assertEquals(60, TurnTimeoutOptions.next(30))
        assertEquals(120, TurnTimeoutOptions.next(60))
        assertEquals(null, TurnTimeoutOptions.next(120))
    }

    /** 连点五下必须正好回到出发点，少一档或多一档都说明循环接错了 */
    @Test
    fun `连点五下回到原点`() {
        var value: Int? = null
        repeat(5) { value = TurnTimeoutOptions.next(value) }
        assertEquals(null, value)
    }

    /**
     * 服主手改 `boards.yml` 填了个表外的值时，按钮仍然点得动。
     *
     * 原地不动或抛异常都会让玩家以为菜单坏了，而真正的原因藏在存档文件里。
     */
    @Test
    fun `表外的值回到第一档而不是卡住`() {
        assertEquals(null, TurnTimeoutOptions.next(45))
        assertEquals(null, TurnTimeoutOptions.next(-1))
        assertEquals(null, TurnTimeoutOptions.next(9999))
    }

    /**
     * `null` 与 `0` 是两个不同的档。
     *
     * 前者是"没设过，跟随全局"，后者是"明确要求这局不限时"。
     * 循环表若把它们合成一档，玩家点过一次之后就再也回不到服主的默认值。
     */
    @Test
    fun `跟随全局与本局不限时是两档`() {
        assertEquals(5, TurnTimeoutOptions.CYCLE.size)
        assertEquals(true, TurnTimeoutOptions.CYCLE.contains(null))
        assertEquals(true, TurnTimeoutOptions.CYCLE.contains(0))
    }
}
