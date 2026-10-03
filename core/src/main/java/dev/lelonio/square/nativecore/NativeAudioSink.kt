package dev.lelonio.square.nativecore

import java.nio.ByteBuffer

/**
 * What the native sink calls on the object given to [NativeBridge.setAudioOutput].
 *
 * The sink (native/src/sink.rs) finds these three by name and signature, so an
 * output does not strictly have to implement this; implementing it is what
 * keeps R8 from renaming them (core/consumer-rules.pro) and the compiler
 * honest about the signatures. The watch's output does; the phone's AudioOutput
 * predates it and has a keep rule of its own.
 */
interface NativeAudioSink {
    /** Playback begins. */
    fun start()

    /** Playback stops; drop what is buffered. */
    fun stop()

    /**
     * Interleaved little-endian 16-bit PCM, [sizeInBytes] of it, valid only for
     * the duration of the call. Blocking here is what paces the player.
     */
    fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int)
}
