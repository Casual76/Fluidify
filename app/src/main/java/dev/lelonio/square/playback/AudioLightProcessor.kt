package dev.lelonio.square.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer

/** Read-only tap after the existing effects. The original PCM is forwarded unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioLightProcessor : AudioProcessor {
    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var output = AudioProcessor.EMPTY_BUFFER
    private var ended = false
    internal var queuedMs = 0L
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        format = inputAudioFormat; return inputAudioFormat
    }
    override fun isActive() = format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT
    override fun queueInput(inputBuffer: ByteBuffer) {
        AudioReactive.capture(inputBuffer, inputBuffer.remaining(), format.sampleRate, format.channelCount,
            floatPcm = format.encoding == C.ENCODING_PCM_FLOAT, queuedMs = queuedMs, kind = AudioLightSource.MEDIA3)
        output = inputBuffer.slice(); inputBuffer.position(inputBuffer.limit())
    }
    override fun getOutput(): ByteBuffer = output.also { output = AudioProcessor.EMPTY_BUFFER }
    override fun queueEndOfStream() { ended = true }
    override fun isEnded() = ended && !output.hasRemaining()
    override fun flush() { output = AudioProcessor.EMPTY_BUFFER; ended = false; queuedMs = 0 }
    override fun reset() { flush(); format = AudioProcessor.AudioFormat.NOT_SET }
}
