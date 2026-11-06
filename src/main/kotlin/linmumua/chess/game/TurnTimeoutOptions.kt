package linmumua.chess.game

/**
 * 每局超时时长的可选档位与循环顺序。
 *
 * 菜单里那颗按钮点一下换一档，档位表就在这里。做成纯函数是为了能单测 ——
 * "点五下回到原点""服主手改出个表外的值也不会卡住"这两条靠肉眼核对不可靠。
 *
 * 档位含义与 [ChessGame.turnTimeoutSeconds] 一致：
 * - `null`：跟随 `config.yml` 的全局 `turn-timeout-seconds`
 * - `0`：这一局不限时
 * - 正数：这一局每手限时几秒
 *
 * 保留 `null` 这一档而不是让玩家只在三个具体秒数里选，是因为存档里 `null` 与 `0`
 * 本来就是两回事（一个是"没设过"，一个是"明确要求不限时"）。菜单若给不出回到"没设过"的路，
 * 玩家点过一次之后就再也回不去服主设定的默认值了。
 */
object TurnTimeoutOptions {

    /**
     * 循环顺序：跟随全局 → 不限时 → 30 秒 → 60 秒 → 120 秒 → 回到跟随全局。
     *
     * 从"跟随全局"起步是因为新开的局就是这个值，玩家第一次点按钮时看到的变化
     * 才对得上"我刚点了一下"。三档具体秒数取 30/60/120：
     * 30 秒是快棋，60 秒是常规，120 秒够想一步中局。再细分的档位没人会用。
     */
    val CYCLE: List<Int?> = listOf(null, 0, 30, 60, 120)

    /**
     * 取下一档。
     *
     * [current] 不在 [CYCLE] 里时（服主手改 `boards.yml` 填了个 45）返回表里第一档，
     * 而不是原地不动或抛异常：按钮必须点得动。留在表外的值会让这颗按钮变成死键，
     * 玩家只会觉得菜单坏了，不会想到是存档里有个奇怪的数。
     */
    fun next(current: Int?): Int? {
        val index = CYCLE.indexOf(current)
        if (index < 0) {
            return CYCLE.first()
        }
        return CYCLE[(index + 1) % CYCLE.size]
    }
}
