import java.io.File

/**
 * 音频产出自检。
 *
 * 音频出错的方式跟贴图一样安静：复制成 0 字节、复制到错的路径、素材被换成另一首曲子，
 * 构建全都不会失败，直到服主进服听见"没声音"或者"两段音乐叠在一起响"。
 *
 * 所以这里做三件事：确认文件真的在包内该在的位置且是 Ogg、确认 CE 配置与字幕键指向
 * 同一个事件 id、确认音频时长与 [PackSpec.GO_BGM_DURATION_SECONDS] 对得上。
 *
 * 最后一条是本特性最容易静默踩坑的地方：`config.yml` 的重播周期默认值是按 65.55 秒
 * 这首曲子定的，谁换了素材却没改周期，客户端就会叠播。构建期拦住比进服听出来便宜。
 */
object VerifyAudio {

    /** 时长比对容差，秒。Ogg 的 granule 精确到采样，留 1 秒足够吸收取整误差 */
    private const val DURATION_TOLERANCE_SECONDS = 1.0

    /** 文件大小下限，字节。96kbps 一分钟约 720KB，30KB 连一秒都装不下，只能是复制出错 */
    private const val MIN_SIZE_BYTES = 30 * 1024

    /** 一个音频文件的检查结果 */
    data class Report(
        val name: String,
        val sizeBytes: Long,
        val sampleRate: Int,
        val channels: Int,
        val durationSeconds: Double,
    )

    /**
     * 解析 Ogg Vorbis 的采样率、声道数与总时长。
     *
     * 采样率与声道数取自第一页的 Vorbis identification header：
     * payload 是 `0x01 "vorbis"` + version(4) + channels(1) + sampleRate(4, 小端)。
     *
     * 时长取自**最后一个 Ogg 页**的 granule position（页头第 6 字节起 8 字节小端）。
     * Vorbis 的 granule 就是已解码采样数，除以采样率即得秒数 —— 不必解码整个流。
     */
    fun inspect(file: File): Report {
        val bytes = file.readBytes()
        if (bytes.size < 64) {
            error("音频文件太小，读不出 Ogg 页头：${file.name}（${bytes.size} 字节）")
        }
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "OggS") {
            error("音频不是 Ogg 容器，前四字节应为 OggS：${file.name}")
        }

        // 第一页页头长 27 字节 + 段表，段表长度写在第 26 字节
        val segmentCount = bytes[26].toInt() and 0xFF
        val payloadStart = 27 + segmentCount
        val vorbisTag = String(bytes, payloadStart, 7, Charsets.US_ASCII)
        if (vorbisTag != "\u0001vorbis") {
            error("音频不是 Vorbis 编码（首包标识为 $vorbisTag）：${file.name}")
        }
        // identification header：跳过 7 字节标识 + 4 字节版本号
        val channels = bytes[payloadStart + 11].toInt() and 0xFF
        val sampleRate = readLittleEndianInt(bytes, payloadStart + 12)
        if (sampleRate <= 0) {
            error("音频采样率解析异常（$sampleRate）：${file.name}")
        }

        // 从尾部倒着找最后一个页头，它的 granule 是整个流的总采样数
        var lastPage = -1
        for (index in bytes.size - 27 downTo 0) {
            if (bytes[index] == 'O'.code.toByte() &&
                bytes[index + 1] == 'g'.code.toByte() &&
                bytes[index + 2] == 'g'.code.toByte() &&
                bytes[index + 3] == 'S'.code.toByte()
            ) {
                lastPage = index
                break
            }
        }
        if (lastPage < 0) {
            error("音频找不到结尾的 Ogg 页，文件可能被截断：${file.name}")
        }
        val granule = readLittleEndianLong(bytes, lastPage + 6)
        return Report(
            name = file.name,
            sizeBytes = file.length(),
            sampleRate = sampleRate,
            channels = channels,
            durationSeconds = granule.toDouble() / sampleRate,
        )
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    private fun readLittleEndianLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /**
     * 检查整套音频产出，任何一项不对就让构建失败。
     *
     * @param root 已生成的包根目录（`build/generated/craftengine/muchess`）
     */
    fun verifyAll(root: File) {
        val audio = File(
            root,
            "resourcepack/assets/${PackSpec.NAMESPACE}/sounds/${PackSpec.GO_BGM_SOUND_PATH}.ogg",
        )
        if (!audio.isFile) {
            error("包内缺少围棋 BGM 音频：${audio.path}（检查 assets-src/audio/${PackSpec.GO_BGM_SOURCE_FILE} 是否存在）")
        }
        if (audio.length() < MIN_SIZE_BYTES) {
            error("围棋 BGM 音频只有 ${audio.length()} 字节，复制多半出错了：${audio.path}")
        }

        val report = inspect(audio)
        val expected = PackSpec.GO_BGM_DURATION_SECONDS
        val drift = kotlin.math.abs(report.durationSeconds - expected)
        if (drift > DURATION_TOLERANCE_SECONDS) {
            error(
                "围棋 BGM 时长与预期不符：实测 ${"%.2f".format(report.durationSeconds)} 秒，" +
                    "PackSpec.GO_BGM_DURATION_SECONDS 写的是 $expected 秒。" +
                    "换了素材就要同步改该常量与 config.yml 的 loop-period-seconds，" +
                    "否则重播周期小于时长会让客户端叠播。",
            )
        }

        // CE 声音配置与客户端字幕必须都指向同一个事件 id，缺一个就是"有文件但不响"或"响了没字幕"
        val soundsConfig = File(root, "configuration/sounds.yml")
        if (!soundsConfig.isFile) {
            error("包内缺少 configuration/sounds.yml，CE 不会注册声音事件")
        }
        val fullId = "${PackSpec.NAMESPACE}:${PackSpec.GO_BGM_SOUND_ID}"
        val soundsText = soundsConfig.readText()
        if (!soundsText.contains(fullId)) {
            error("configuration/sounds.yml 里找不到声音事件 $fullId")
        }
        if (!soundsText.contains("${PackSpec.NAMESPACE}:${PackSpec.GO_BGM_SOUND_PATH}")) {
            error("configuration/sounds.yml 没有指向音频路径 ${PackSpec.GO_BGM_SOUND_PATH}")
        }
        val langConfig = File(root, "configuration/lang.yml")
        val subtitleKey = "subtitles.${PackSpec.NAMESPACE}.${PackSpec.GO_BGM_SOUND_ID}"
        if (!langConfig.isFile || !langConfig.readText().contains(subtitleKey)) {
            error("configuration/lang.yml 缺少字幕键 $subtitleKey，开了字幕的玩家看不到曲名")
        }

        println(
            "[MuChess] 音频自检通过：${report.name} " +
                "${report.sizeBytes / 1024}KB ${report.sampleRate}Hz " +
                "${report.channels}ch ${"%.2f".format(report.durationSeconds)}s",
        )
    }
}
