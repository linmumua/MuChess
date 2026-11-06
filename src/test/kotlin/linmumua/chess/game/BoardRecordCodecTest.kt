package linmumua.chess.game

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import linmumua.chess.board.BoardFacing
import org.junit.jupiter.api.Test

/**
 * 棋盘存档序列化测试。
 *
 * 坐标或朝向写错不会抛异常，只会让恢复出来的棋盘悄悄偏一格或转个方向，
 * 玩家看到的是"棋子全错位了"。这类静默错误只能靠往返测试兜住。
 */
class BoardRecordCodecTest {

    private fun sample(
        id: UUID = UUID.randomUUID(),
        facing: BoardFacing = BoardFacing.NORTH,
        phase: GamePhase = GamePhase.PLAYING,
        red: UUID? = UUID.randomUUID(),
        black: UUID? = UUID.randomUUID(),
        handle: String? = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
        gameType: GameType = GameType.XIANGQI,
        bgmEnabled: Boolean = true,
        turnTimeoutSeconds: Int? = null,
    ) = BoardRecord(
        id = id,
        world = "world",
        originX = 100,
        originY = 64,
        originZ = -200,
        surfaceY = 64.02,
        facing = facing,
        fen = "rheakaehr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RHEAKAEHR r 0",
        phase = phase,
        redPlayer = red,
        blackPlayer = black,
        visualHandle = handle,
        gameType = gameType,
        bgmEnabled = bgmEnabled,
        turnTimeoutSeconds = turnTimeoutSeconds,
    )

    /**
     * 一条记录写出再读回必须完全一致。
     *
     * 任何一个字段丢了或类型变了，恢复的棋盘就是错的。
     */
    @Test
    fun `单条记录往返不丢信息`() {
        val original = sample()
        val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    /**
     * 四个朝向都要能往返。
     *
     * 朝向决定红方在哪一侧，转错 90 度整副棋就是镜像的。
     */
    @Test
    fun `四种朝向都能往返`() {
        BoardFacing.entries.forEach { facing ->
            val original = sample(facing = facing)
            val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
            assertEquals(facing, decoded?.facing, "朝向 $facing 往返后变了")
        }
    }

    /** 三种对局阶段都要能往返 */
    @Test
    fun `三种对局阶段都能往返`() {
        GamePhase.entries.forEach { phase ->
            val original = sample(phase = phase)
            val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
            assertEquals(phase, decoded?.phase, "阶段 $phase 往返后变了")
        }
    }

    /** 空座位与没有家具句柄的情况要能正常往返 */
    @Test
    fun `空座位与无家具句柄能往返`() {
        val original = sample(red = null, black = null, handle = null)
        val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded?.redPlayer)
        assertNull(decoded?.visualHandle)
    }

    /** 整份存档往返 */
    @Test
    fun `多条记录整份往返`() {
        val records = listOf(sample(), sample(facing = BoardFacing.WEST), sample(handle = null))
        val decoded = BoardRecordCodec.decodeAll(BoardRecordCodec.encodeAll(records))
        assertEquals(records, decoded)
    }

    /**
     * 坏掉的记录要被跳过，而不是让整份存档失败。
     *
     * 一副棋盘的数据写坏了不该拖垮其余几十副。
     */
    @Test
    fun `坏记录被跳过而不影响其他记录`() {
        val good = sample()
        val broken = mapOf("id" to "not-a-uuid", "world" to "world")
        val root = mapOf(
            BoardRecordCodec.ROOT_KEY to listOf(
                BoardRecordCodec.encode(good),
                broken,
            ),
        )
        val decoded = BoardRecordCodec.decodeAll(root)
        assertEquals(1, decoded.size, "坏记录应被跳过，好记录要保留")
        assertEquals(good, decoded.first())
    }

    /** 缺必填字段的记录读不出来，返回 null 而不是造一个默认棋盘 */
    @Test
    fun `缺必填字段返回空`() {
        assertNull(BoardRecordCodec.decode(mapOf("world" to "world")))
        assertNull(BoardRecordCodec.decode(emptyMap()))
    }

    /** 空存档读出空列表，不抛异常 */
    @Test
    fun `空存档读出空列表`() {
        assertTrue(BoardRecordCodec.decodeAll(emptyMap()).isEmpty())
    }

    /**
     * 局面串必须原样保存。
     *
     * FEN 串是局面的唯一真源，改动一个字符整个棋局就变了。
     */
    @Test
    fun `局面串原样保存`() {
        val original = sample()
        val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
        assertEquals(original.fen, decoded?.fen)
    }

    /**
     * 棋种必须存进存档并原样读回。
     *
     * 不存棋种的后果不是报错而是错乱：五子棋盘重启后被当成象棋恢复，
     * 15×15 的局面串喂给象棋规则解析，整副棋盘直接消失。
     */
    @Test
    fun `棋种往返不丢`() {
        for (type in GameType.entries) {
            val original = sample(gameType = type)
            val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
            assertEquals(type, decoded?.gameType, "棋种 ${type.id} 往返丢了")
        }
    }

    /**
     * 1.10.0 之前的老存档没有 game-type 键，必须当象棋读出来。
     *
     * 这条是升级路径的底线：老档缺键时如果返回 null，服主升级后
     * 世界里所有已放置的棋盘会全部消失。
     */
    @Test
    fun `老存档缺棋种键时按象棋恢复`() {
        val legacy = BoardRecordCodec.encode(sample()).toMutableMap()
        legacy.remove("game-type")
        val decoded = BoardRecordCodec.decode(legacy)
        assertEquals(GameType.XIANGQI, decoded?.gameType)
    }

    /**
     * 棋种名写错（服主手改存档）时退象棋，不丢整条记录。
     *
     * 局面串还在，退象棋至少棋盘还能恢复出来；返回 null 会让这副棋盘直接消失。
     */
    @Test
    fun `棋种名无法识别时退象棋而不是丢弃记录`() {
        val broken = BoardRecordCodec.encode(sample()).toMutableMap()
        broken["game-type"] = "shogi"
        val decoded = BoardRecordCodec.decode(broken)
        assertEquals(GameType.XIANGQI, decoded?.gameType)
    }

    /**
     * 每局的背景音乐开关必须存进存档并原样读回。
     *
     * 不存的后果是玩家关掉音乐、重启后又响起来 —— 一个"关不掉的开关"，
     * 而且没有任何报错，只能靠往返断言钉住。
     */
    @Test
    fun `背景音乐开关往返不丢`() {
        listOf(true, false).forEach { flag ->
            val original = sample(bgmEnabled = flag)
            val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
            assertEquals(flag, decoded?.bgmEnabled, "背景音乐开关 $flag 往返后变了")
            assertEquals(original, decoded)
        }
    }

    /**
     * 1.12.0 之前的老存档没有 bgm-enabled 键，读出来当"没人关过音乐"（true）。
     *
     * 取 true 而不是 false 的理由是两级闸：全局总闸默认关着，缺键给 true 也不会
     * 凭空放出音乐；反过来给 false 则会让服主开了总闸后，所有老棋盘都是哑的，
     * 玩家还得进菜单逐副打开，看起来像功能坏了。
     */
    @Test
    fun `老存档缺背景音乐键时当没人关过`() {
        val legacy = BoardRecordCodec.encode(sample()).toMutableMap()
        legacy.remove("bgm-enabled")
        assertTrue(BoardRecordCodec.decode(legacy)?.bgmEnabled == true)
    }

    /**
     * 服主手改存档把开关写成字符串时退 true，不丢整条记录。
     *
     * 一个音乐开关不值得让这副棋盘从世界里消失。
     */
    @Test
    fun `背景音乐键类型写错时退默认而不是丢弃记录`() {
        val broken = BoardRecordCodec.encode(sample()).toMutableMap()
        broken["bgm-enabled"] = "no"
        val decoded = BoardRecordCodec.decode(broken)
        assertTrue(decoded?.bgmEnabled == true)
    }

    /**
     * 每局单独设的时限必须存进存档并原样读回，0 也要能存住。
     *
     * 玩家在菜单里选了"这局不限时"或"每步 10 秒"，重启后必须还是那个设定；
     * 0 尤其要测，它最容易在某处被当成"没设过"而退回全局值。
     */
    @Test
    fun `每局时限往返不丢`() {
        listOf(null, 0, 10, 300).forEach { seconds ->
            val original = sample(turnTimeoutSeconds = seconds)
            val decoded = BoardRecordCodec.decode(BoardRecordCodec.encode(original))
            assertEquals(seconds, decoded?.turnTimeoutSeconds, "每局时限 $seconds 往返后变了")
            assertEquals(original, decoded)
        }
    }

    /**
     * 1.12.0 之前的老存档没有 turn-timeout-seconds 键，读出来必须是 null（跟随全局）。
     *
     * 这里的兜底方向与 bgm-enabled 相反，必须是"跟随全局"而不是某个具体数字：
     * 缺键当 0 会把升级前的所有棋盘变成永久不限时，服主之后怎么调全局时限都不生效，
     * 而且没有任何报错。
     */
    @Test
    fun `老存档缺每局时限键时跟随全局`() {
        val legacy = BoardRecordCodec.encode(sample(turnTimeoutSeconds = 30)).toMutableMap()
        legacy.remove("turn-timeout-seconds")
        assertNull(BoardRecordCodec.decode(legacy)?.turnTimeoutSeconds)
    }

    /**
     * 手改出负数时退成"跟随全局"，不丢整条记录。
     *
     * 负数在时限里没有意义。退 null 而不是退 0，是因为服主写 -1 更可能是想表达
     * "取消这局的特殊设置"，而不是"永久不限时"。
     */
    @Test
    fun `每局时限为负数时退回跟随全局`() {
        val broken = BoardRecordCodec.encode(sample(turnTimeoutSeconds = 30)).toMutableMap()
        broken["turn-timeout-seconds"] = -5
        assertNull(BoardRecordCodec.decode(broken)?.turnTimeoutSeconds)
    }

    @Test
    fun `UUID 句柄识别为当前单家具`() {        val uuid = UUID.randomUUID()
        val parsed = assertIs<StoredBoardVisualHandle.Furniture>(
            BoardVisualHandleFormat.parse(uuid.toString()),
        )
        assertEquals(uuid, parsed.uuid)
    }

    @Test
    fun `坐标句柄识别为旧四方块并支持世界名冒号`() {
        val parsed = assertIs<StoredBoardVisualHandle.LegacyBlocks>(
            BoardVisualHandleFormat.parse("dimension:custom:-1:64:0"),
        )
        assertEquals("dimension:custom", parsed.world)
        assertEquals(-1, parsed.x)
        assertEquals(64, parsed.y)
        assertEquals(0, parsed.z)
    }

    @Test
    fun `损坏句柄不会误判成家具或旧方块`() {
        val parsed = assertIs<StoredBoardVisualHandle.Unknown>(
            BoardVisualHandleFormat.parse("not-a-board-handle"),
        )
        assertEquals("not-a-board-handle", parsed.raw)
        assertNull(BoardVisualHandleFormat.parse(null))
    }
}
