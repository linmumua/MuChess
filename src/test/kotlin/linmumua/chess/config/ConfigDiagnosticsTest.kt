package linmumua.chess.config

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 配置项被忽略时必须报出来。
 *
 * 为什么这条重要：解析器逐项容错，写错类型退默认、超出范围钳边界。
 * 这保证插件不会因为一个拼写错误起不来，但服主完全看不出哪一项没生效 ——
 * 文件里写着 `move-ticks: 1`，实际跑 2，控制台一声不响，只能靠猜。
 *
 * 同时必须**不误报**：没写的项用默认值是应有行为，照写生效的项也不该刷日志，
 * 否则每次启动一屏警告，真正的问题就被埋掉了。
 */
class ConfigDiagnosticsTest {

    private fun inspect(root: Map<String, Any?>) =
        ConfigDiagnostics.inspect(root, ChessConfigLoader.parse(root))

    /**
     * 空配置不产生任何警告。
     *
     * 什么都没写就是全默认，这是正常状态，不是"被忽略"。
     */
    @Test
    @DisplayName("空配置不报警告")
    fun `empty config reports nothing`() {
        assertTrue(inspect(emptyMap()).isEmpty(), "什么都没写不该报警告")
    }

    /**
     * 照写生效的值不产生警告。
     */
    @Test
    @DisplayName("值在范围内时不报警告")
    fun `values within range report nothing`() {
        val root = mapOf(
            "piece" to mapOf("move-ticks" to 10, "base-scale" to 0.2),
            "highlight" to mapOf("marker-scale" to 0.08),
        )
        assertTrue(inspect(root).isEmpty(), "范围内的值应当照写生效，不该报警告")
    }

    /**
     * 低于下限被钳时必须报出来，且说清写的是什么、实际是什么。
     *
     * 这是最容易踩的一种：服主想要更快的动画，写了 1，实际拿到 2。
     */
    @Test
    @DisplayName("低于下限被钳时报出原值与生效值")
    fun `clamped below the minimum is reported`() {
        val ignored = inspect(mapOf("piece" to mapOf("move-ticks" to 1)))

        assertEquals(1, ignored.size)
        assertEquals("piece.move-ticks", ignored[0].key)
        assertEquals("1", ignored[0].written)
        assertEquals("2", ignored[0].effective)
        assertTrue(ignored[0].reason.contains("钳"), "原因应说明是被钳到边界")
    }

    /**
     * 超过上限同样要报。
     */
    @Test
    @DisplayName("超过上限被钳时也报")
    fun `clamped above the maximum is reported`() {
        val ignored = inspect(mapOf("piece" to mapOf("move-ticks" to 9999)))

        assertEquals(1, ignored.size)
        assertEquals("60", ignored[0].effective)
    }

    /**
     * 类型写错退默认时必须报。
     *
     * `move-ticks: fast` 这种写法解析器会静默退回默认值，服主以为自己配了。
     */
    @Test
    @DisplayName("类型写错退默认时报出来")
    fun `unreadable values are reported`() {
        val ignored = inspect(mapOf("piece" to mapOf("move-ticks" to "fast")))

        assertEquals(1, ignored.size)
        assertEquals("piece.move-ticks", ignored[0].key)
        assertEquals("fast", ignored[0].written)
        assertTrue(ignored[0].reason.contains("读不成"), "原因应说明是读不成对应类型")
    }

    /**
     * 数字写成字符串是允许的写法，不该报。
     *
     * 解析器有意支持 `move-ticks: "10"`，那是照写生效，不是被忽略。
     */
    @Test
    @DisplayName("数字写成字符串不算被忽略")
    fun `numeric strings are not reported`() {
        assertTrue(
            inspect(mapOf("piece" to mapOf("move-ticks" to "10"))).isEmpty(),
            "字符串数字是支持的写法，应照写生效",
        )
    }

    /**
     * 弧线高度被浮起高度顶上去时要报。
     *
     * `move-lift-height` 会被钳到不低于 `hover-height`。服主把它配得更低时
     * 拿到的是 hover-height，这属于没照写生效。
     */
    @Test
    @DisplayName("弧线高度被浮起高度顶上去时报出来")
    fun `lift height raised to hover height is reported`() {
        val ignored = inspect(
            mapOf("piece" to mapOf("hover-height" to 0.2, "move-lift-height" to 0.05)),
        )

        assertEquals(1, ignored.size)
        assertEquals("piece.move-lift-height", ignored[0].key)
        assertEquals("0.05", ignored[0].written)
        assertEquals("0.2", ignored[0].effective)
    }

    /**
     * 多项同时出错要全部报出，不能只报第一条。
     */
    @Test
    @DisplayName("多项同时被忽略时全部报出")
    fun `all ignored keys are reported`() {
        val ignored = inspect(
            mapOf(
                "piece" to mapOf("move-ticks" to 1, "base-scale" to -1.0),
                "highlight" to mapOf("marker-y-offset" to -0.5),
                "storage" to mapOf("save-interval-seconds" to 1),
            ),
        )

        val keys = ignored.map { it.key }.toSet()
        assertEquals(
            setOf(
                "piece.move-ticks",
                "piece.base-scale",
                "highlight.marker-y-offset",
                "storage.save-interval-seconds",
            ),
            keys,
        )
    }

    /**
     * 读失败时必须保留上次生效的配置，不能退默认值。
     *
     * 为什么这条重要：`/chess reload` 时配置写错一个冒号，退默认就等于把服主调好的
     * 每一项都悄悄换成插件默认值 —— 表现全变而文件看着没动，排查方向会被彻底带偏。
     * 返回 null 让调用方保留旧值，语义是"这次重载没生效"，清楚得多。
     */
    @Test
    @DisplayName("读失败时保留上次生效值，不退默认")
    fun `failed read keeps the previous settings`() {
        assertEquals(null, ChessConfig.resolve(null), "读失败必须返回 null 以保留旧值")
    }

    /**
     * 读成功就完全按文件来。
     *
     * 文件里没写的项用默认值是应有语义 —— 那和"把服主写的值换成默认值"是两件事。
     */
    @Test
    @DisplayName("读成功时完全按文件解析")
    fun `successful read uses the file`() {
        val parsed = ChessConfig.resolve(mapOf("highlight" to mapOf("marker-scale" to 0.03)))

        assertEquals(0.03, parsed?.highlight?.markerScale, "文件里写的值必须生效")
        // 没写的项走默认值，这是"缺配置"而不是"覆盖配置"
        assertEquals(
            ChessSettings.DEFAULT.piece.moveTicks,
            parsed?.piece?.moveTicks,
            "文件里没写的项用默认值",
        )
    }

    /**
     * 白圈缩放调到很小是**合法**的，不该报。
     *
     * 下限只有 0.01。服主嫌白圈大就是要往小调，这条路必须畅通 ——
     * 这正是本轮的起因：默认值改了但磁盘没变，服主该能自己直接改小。
     */
    @Test
    @DisplayName("白圈调小是合法配置，不报警告")
    fun `shrinking the marker is legitimate`() {
        for (scale in listOf(0.05, 0.03, 0.02, 0.01)) {
            val root = mapOf("highlight" to mapOf("marker-scale" to scale))
            assertTrue(
                inspect(root).isEmpty(),
                "marker-scale=$scale 在允许范围内，应照写生效",
            )
            assertEquals(scale, ChessConfigLoader.parse(root).highlight.markerScale)
        }
    }
}
