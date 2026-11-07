package ai.longkaka.seller.speech.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Direct Kotlin/ONNX implementation of the pinned VieNeu v3 Nano reference path.
 * It is deliberately separate from Android's existing Offline TTS engine.
 */
data class VieNeuChunkTiming(val text: String, val startMs: Long, val endMs: Long)

internal class VieNeuNanoEngine(
    modelDirectory: File,
    private val threads: Int = 2
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 24_000
        private const val LATENT_CHANNELS = 144
        private const val MAX_CHUNK_SECONDS = 15.0
        private const val DEFAULT_STEPS = 8
        private const val DEFAULT_CFG = 3.0f
        private const val DEFAULT_SWAY = -1.0f
        private const val MAX_PHONEME_CHARS = 256
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private val config = VieNeuNanoAssets.loadConfig(modelDirectory)
    val voices: List<VieNeuNanoVoice> = VieNeuNanoAssets.loadVoices(modelDirectory)
    private val constants = VieNeuNanoAssets.loadConstants(modelDirectory)
    private val textEncoder: OrtSession
    private val durationPredictor: OrtSession
    private val vectorEstimator: OrtSession
    private val codecDecoder: OrtSession
    private val phonemizer = SeaG2pNative()
    private val phonemizerHandle: Long
    private val nullContext: FloatArray
    private val nullMask = booleanArrayOf(true, true)
    private val defaultVoice: VieNeuNanoVoice

    init {
        require(voices.isNotEmpty()) { "Không tìm thấy giọng VieNeu Nano." }
        defaultVoice = voices.firstOrNull { it.name == config.defaultVoice } ?: voices.first()
        phonemizerHandle = phonemizer.nativeOpen(File(modelDirectory, "sea_g2p.bin").absolutePath)
        check(phonemizerHandle != 0L) {
            "Không mở được từ điển SEA-G2P: ${phonemizer.nativeLastError().orEmpty()}"
        }

        options.setIntraOpNumThreads(threads.coerceIn(1, 4))
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")

        try {
            textEncoder = environment.createSession(File(modelDirectory, "text_encoder.onnx").absolutePath, options)
            durationPredictor = environment.createSession(File(modelDirectory, "duration_predictor.onnx").absolutePath, options)
            vectorEstimator = environment.createSession(File(modelDirectory, "vector_estimator.onnx").absolutePath, options)
            codecDecoder = environment.createSession(File(modelDirectory, "codec_decoder.onnx").absolutePath, options)
            nullContext = createNullContext()
        } catch (error: Throwable) {
            phonemizer.nativeClose(phonemizerHandle)
            options.close()
            throw error
        }
    }

    @Synchronized
    fun synthesizeToWav(
        text: String,
        voiceName: String,
        output: File,
        steps: Int = DEFAULT_STEPS,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onChunkTiming: (VieNeuChunkTiming) -> Unit = {}
    ): File {
        require(text.isNotBlank()) { "Kịch bản trống." }
        val voice = voices.firstOrNull { it.name == voiceName } ?: defaultVoice
        val chunks = splitIntoChunks(text)
        require(chunks.isNotEmpty()) { "Không tách được nội dung để đọc." }
        output.parentFile?.let { if (!it.exists()) check(it.mkdirs()) }
        val partial = File(output.parentFile, output.name + ".part")

        try {
            java.io.RandomAccessFile(partial, "rw").use { file ->
                file.setLength(0L)
                file.write(ByteArray(44))
                var totalSamples = 0L
                chunks.forEachIndexed { index, chunk ->
                    val chunkStartSamples = totalSamples
                    val phones = phonemizeWithEmotionCues(chunk.text)
                    val ids = encodePhones(phones)
                    val audio = trimAndFade(inferChunk(ids, voice, steps.coerceIn(4, 16)))
                    if (audio.isNotEmpty()) {
                        writePcmSamples(file, audio)
                        totalSamples += audio.size.toLong()
                    }
                    val chunkEndSamples = totalSamples
                    if (chunkEndSamples > chunkStartSamples) {
                        onChunkTiming(VieNeuChunkTiming(
                            text = chunk.text,
                            startMs = chunkStartSamples * 1000L / SAMPLE_RATE,
                            endMs = chunkEndSamples * 1000L / SAMPLE_RATE
                        ))
                    }
                    if (chunk.pauseAfterSeconds > 0f && index < chunks.lastIndex) {
                        val silenceSamples = (chunk.pauseAfterSeconds * SAMPLE_RATE).roundToInt().coerceAtLeast(0)
                        writeSilence(file, silenceSamples)
                        totalSamples += silenceSamples.toLong()
                    }
                    onProgress(index + 1, chunks.size)
                }
                require(totalSamples > SAMPLE_RATE / 2L) { "VieNeu Nano không tạo được âm thanh hợp lệ." }
                val dataBytes = totalSamples * 2L
                require(dataBytes <= 0xFFFFFFFFL - 36L) { "Tệp WAV vượt giới hạn RIFF 4 GB." }
                file.seek(0L)
                file.write(createWavHeader(dataBytes))
                file.fd.sync()
            }
            if (output.exists()) check(output.delete()) { "Không thể thay thế tệp âm thanh cũ." }
            check(partial.renameTo(output)) { "Không thể hoàn tất tệp âm thanh." }
            return output
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun createNullContext(): FloatArray {
        val idsTensor = OnnxTensor.createTensor(environment, arrayOf(longArrayOf(config.bosId, config.eosId)))
        val styleTensor = floatTensor(constants.nullStyle, longArrayOf(1, 50, 256))
        try {
            textEncoder.run(mapOf("ids" to idsTensor, "style" to styleTensor)).use { result ->
                return readFloats(result.get("ctx").get() as OnnxTensor)
            }
        } finally {
            idsTensor.close()
            styleTensor.close()
        }
    }

    private fun inferChunk(ids: LongArray, voice: VieNeuNanoVoice, steps: Int): FloatArray {
        val length = ids.size
        val idsTensor = OnnxTensor.createTensor(environment, arrayOf(ids))
        val styleTensor = floatTensor(voice.style, longArrayOf(1, 50, 256))
        try {
            textEncoder.run(mapOf("ids" to idsTensor, "style" to styleTensor)).use { textResult ->
                val ctxTensor = textResult.get("ctx").get() as OnnxTensor
                val ctx = readFloats(ctxTensor)
                val mask = BooleanArray(length) { ids[it] != config.padId }
                val maskTensor = OnnxTensor.createTensor(environment, arrayOf(mask))
                val speakerTensor = floatTensor(voice.speakerEmbedding, longArrayOf(1, 192))
                val vectorStyleTensor = floatTensor(voice.style, longArrayOf(1, 50, 256))
                val nullContextTensor = floatTensor(nullContext, longArrayOf(1, 2, 256))
                val nullMaskTensor = OnnxTensor.createTensor(environment, arrayOf(nullMask))
                val nullSpeakerTensor = floatTensor(constants.nullSpeaker, longArrayOf(1, 192))
                val nullStyleTensor = floatTensor(constants.nullStyle, longArrayOf(1, 50, 256))
                try {
                    val logSeconds = durationPredictor.run(
                        mapOf("ctx" to ctxTensor, "ctx_mask" to maskTensor, "spk" to speakerTensor)
                    ).use { result ->
                        (result.get(0) as OnnxTensor).floatBuffer.get(0)
                    }
                    val seconds = min(exp(logSeconds.toDouble()), MAX_CHUNK_SECONDS)
                    val frameCount = max(2, (seconds * config.flowFps.toDouble()).roundToInt())
                    val random = Random()
                    var latent = FloatArray(LATENT_CHANNELS * frameCount) {
                        random.nextGaussian().toFloat()
                    }

                    val times = DoubleArray(steps + 1) { i ->
                        val u = i.toDouble() / steps.toDouble()
                        u + DEFAULT_SWAY.toDouble() * (cos(PI * 0.5 * u) - 1.0 + u)
                    }
                    for (step in 0 until steps) {
                        val t = floatArrayOf(times[step].toFloat())
                        val conditional = runVectorEstimator(
                            latent, frameCount, t, ctxTensor, maskTensor,
                            speakerTensor, vectorStyleTensor
                        )
                        val guided = if (DEFAULT_CFG > 0f) {
                            val unconditional = runVectorEstimator(
                                latent, frameCount, t, nullContextTensor, nullMaskTensor,
                                nullSpeakerTensor, nullStyleTensor
                            )
                            FloatArray(conditional.size) { i ->
                                unconditional[i] + DEFAULT_CFG * (conditional[i] - unconditional[i])
                            }
                        } else conditional
                        val delta = (times[step + 1] - times[step]).toFloat()
                        for (i in latent.indices) latent[i] += delta * guided[i]
                    }
                    val latentTensor = floatTensor(latent, longArrayOf(1, LATENT_CHANNELS.toLong(), frameCount.toLong()))
                    try {
                        codecDecoder.run(mapOf("x" to latentTensor)).use { result ->
                            val decoded = readFloats(result.get(0) as OnnxTensor)
                            return FloatArray(decoded.size) { i -> decoded[i].coerceIn(-1f, 1f) }
                        }
                    } finally {
                        latentTensor.close()
                    }
                } finally {
                    maskTensor.close()
                    speakerTensor.close()
                    vectorStyleTensor.close()
                    nullContextTensor.close()
                    nullMaskTensor.close()
                    nullSpeakerTensor.close()
                    nullStyleTensor.close()
                }
            }
        } finally {
            idsTensor.close()
            styleTensor.close()
        }
    }

    private fun runVectorEstimator(
        latent: FloatArray,
        frames: Int,
        time: FloatArray,
        contextTensor: OnnxTensor,
        maskTensor: OnnxTensor,
        speakerTensor: OnnxTensor,
        styleTensor: OnnxTensor
    ): FloatArray {
        val latentTensor = floatTensor(latent, longArrayOf(1, LATENT_CHANNELS.toLong(), frames.toLong()))
        val timeTensor = floatTensor(time, longArrayOf(1))
        try {
            vectorEstimator.run(
                mapOf(
                    "x" to latentTensor,
                    "t" to timeTensor,
                    "ctx" to contextTensor,
                    "ctx_mask" to maskTensor,
                    "spk" to speakerTensor,
                    "style" to styleTensor
                )
            ).use { result ->
                return readFloats(result.get(0) as OnnxTensor)
            }
        } finally {
            latentTensor.close()
            timeTensor.close()
        }
    }

    private fun phonemizeWithEmotionCues(text: String): String {
        val cueRegex = Regex("""\[[^\]]+\]""")
        val output = StringBuilder()
        var cursor = 0
        fun appendPhones(fragment: String) {
            if (fragment.isBlank()) return
            val phones = phonemizer.nativePhonemize(phonemizerHandle, fragment, false)
                ?: error("SEA-G2P không phiên âm được: ${phonemizer.nativeLastError().orEmpty()}")
            if (phones.isNotBlank()) {
                if (output.isNotEmpty() && !phones.first().isWhitespace() && phones.first() !in ".,!?;:…)]}") {
                    output.append(' ')
                }
                output.append(phones)
            }
        }

        cueRegex.findAll(text).forEach { match ->
            appendPhones(text.substring(cursor, match.range.first))
            val cue = match.value.trim('[', ']').lowercase()
            val token = when (cue) {
                "cười", "cuoi", "chuckle" -> "<|emotion_1|>"
                "thở dài", "tho dai", "sigh" -> "<|emotion_2|>"
                "hắng giọng", "hang giong", "clear throat" -> "<|emotion_3|>"
                else -> null
            }
            if (token == null) appendPhones(match.value)
            else {
                if (output.isNotEmpty()) output.append(' ')
                output.append(token)
            }
            cursor = match.range.last + 1
        }
        appendPhones(text.substring(cursor))
        return phonemizer.nativePunctuationNormalize(output.toString())
            ?: error("Không chuẩn hóa được dấu câu SEA-G2P: ${phonemizer.nativeLastError().orEmpty()}")
    }

    private fun encodePhones(phonemes: String): LongArray {
        var source = phonemes
        config.emotionCharacters.forEach { (token, character) -> source = source.replace(token, character.toString()) }
        val ids = ArrayList<Long>(source.length + 2)
        ids += config.bosId
        source.forEach { character ->
            config.vocabulary[character]?.let { ids += it }
        }
        ids += config.eosId
        return ids.toLongArray()
    }

    private fun splitIntoChunks(source: String): List<VieNeuTextChunk> =
        VieNeuTextChunker.split(source)

    private fun trimAndFade(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        val window = max(1, SAMPLE_RATE / 100)
        val threshold = 10.0.pow(-45.0 / 20.0).toFloat()
        var first = -1
        var last = -1
        var start = 0
        while (start + window <= input.size) {
            var sum = 0.0
            for (i in start until start + window) sum += kotlin.math.abs(input[i].toDouble())
            if (sum / window > threshold) {
                if (first < 0) first = start
                last = start + window
            }
            start += window
        }
        if (first < 0) return FloatArray(0)
        val keep = (0.04f * SAMPLE_RATE).roundToInt()
        val from = max(0, first - keep)
        val to = min(input.size, last + keep)
        val output = input.copyOfRange(from, to)
        val fade = min((0.015f * SAMPLE_RATE).roundToInt(), output.size / 2)
        for (i in 0 until fade) {
            val gain = (0.5 - 0.5 * cos(PI * i / max(1, fade - 1))).toFloat()
            output[i] *= gain
            output[output.lastIndex - i] *= gain
        }
        return output
    }

    private fun writePcmSamples(file: java.io.RandomAccessFile, audio: FloatArray) {
        val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        audio.forEach { sample ->
            if (buffer.remaining() < 2) {
                file.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            val pcm = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            buffer.putShort(pcm.toShort())
        }
        if (buffer.position() > 0) file.write(buffer.array(), 0, buffer.position())
    }

    private fun writeSilence(file: java.io.RandomAccessFile, samples: Int) {
        var remainingBytes = samples.toLong() * 2L
        val zeros = ByteArray(64 * 1024)
        while (remainingBytes > 0L) {
            val count = min(remainingBytes, zeros.size.toLong()).toInt()
            file.write(zeros, 0, count)
            remainingBytes -= count.toLong()
        }
    }

    private fun createWavHeader(dataBytes: Long): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((36L + dataBytes).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes.toInt())
        return buffer.array()
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)

    private fun readFloats(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() {
        runCatching { textEncoder.close() }
        runCatching { durationPredictor.close() }
        runCatching { vectorEstimator.close() }
        runCatching { codecDecoder.close() }
        runCatching { phonemizer.nativeClose(phonemizerHandle) }
        runCatching { options.close() }
    }
}

