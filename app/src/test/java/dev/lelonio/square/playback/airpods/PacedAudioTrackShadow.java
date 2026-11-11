package dev.lelonio.square.playback;

import android.media.AudioTrack;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAudioTrack;

/**
 * Test-only PCM AudioTrack with a finite queue and elapsed-time consumption at unity speed.
 * Scoped to the sdk=35 renderer fixture; device routing and track construction stay inherited.
 * Does not override getPlayState, any Player state, renderer callbacks or application receipts.
 */
@Implements(value = AudioTrack.class, minSdk = 35, maxSdk = 35)
public class PacedAudioTrackShadow extends ShadowAudioTrack {
  @RealObject private AudioTrack realTrack;
  private long writtenFrames;
  private long playedFrames;
  private long fractionalFrames;
  private long lastElapsedMs = SystemClock.elapsedRealtime();
  private boolean consuming;
  private long fullWrites;

  private int frameBytes() {
    return realTrack.getFormat().getFrameSizeInBytes();
  }

  private long capacityFrames() {
    return Math.max(1, realTrack.getBufferSizeInFrames());
  }

  /** No consumption credit accumulates during pause or underrun. Fractional frames are retained. */
  private void consume() {
    long now = SystemClock.elapsedRealtime();
    long elapsed = Math.max(0, now - lastElapsedMs);
    lastElapsedMs = now;
    if (!consuming) return;
    long queued = writtenFrames - playedFrames;
    if (queued == 0) {
      fractionalFrames = 0;
      return;
    }
    long scaled = elapsed * realTrack.getSampleRate() + fractionalFrames;
    long frames = Math.min(queued, scaled / 1000);
    playedFrames += frames;
    fractionalFrames = frames == queued ? 0 : scaled % 1000;
  }

  private int acceptableBytes(int requestedBytes) {
    consume();
    long free = Math.max(0, capacityFrames() - (writtenFrames - playedFrames));
    int accepted = (int) Math.min(requestedBytes / frameBytes(), free) * frameBytes();
    if (accepted == 0 && requestedBytes > 0) fullWrites++;
    return accepted;
  }

  @Override
  @Implementation(minSdk = 29)
  protected synchronized int native_write_native_bytes(
      ByteBuffer audioData, int positionInBytes, int sizeInBytes, int format, boolean blocking) {
    int accepted = acceptableBytes(sizeInBytes);
    if (accepted == 0) return 0;
    // Keep normal AudioTrack.write buffer-position handling and the parent's data listeners.
    int result = super.native_write_native_bytes(audioData, positionInBytes, accepted, format, false);
    if (result > 0) writtenFrames += result / frameBytes();
    return result;
  }

  @Override
  @Implementation(minSdk = 23)
  protected synchronized int native_write_byte(
      byte[] audioData, int offsetInBytes, int sizeInBytes, int format, boolean blocking) {
    int accepted = acceptableBytes(sizeInBytes);
    if (accepted == 0) return 0;
    int result = super.native_write_byte(audioData, offsetInBytes, accepted, format, false);
    if (result > 0) writtenFrames += result / frameBytes();
    return result;
  }

  @Override
  @Implementation
  protected synchronized int getPlaybackHeadPosition() {
    consume();
    return (int) (playedFrames & 0xffffffffL);
  }

  @Override
  @Implementation
  protected synchronized void play() {
    consume();
    super.play(); // Real AudioTrack.play updates its actual play state, as in the default shadow.
    consuming = true;
    lastElapsedMs = SystemClock.elapsedRealtime();
  }

  @Implementation
  protected synchronized void pause() {
    consume();
    Shadow.directlyOn(realTrack, AudioTrack.class, "pause");
    consuming = false;
    lastElapsedMs = SystemClock.elapsedRealtime();
  }

  @Implementation
  protected synchronized void stop() {
    consume();
    Shadow.directlyOn(realTrack, AudioTrack.class, "stop");
    consuming = false;
    // Public AudioTrack contract resets its head on stop. This model does not certify
    // the asynchronous presentation/draining of the old native hardware buffer.
    writtenFrames = 0;
    playedFrames = 0;
    fractionalFrames = 0;
    lastElapsedMs = SystemClock.elapsedRealtime();
  }

  @Override
  @Implementation
  protected synchronized void flush() {
    super.flush();
    writtenFrames = 0;
    playedFrames = 0;
    fractionalFrames = 0;
    lastElapsedMs = SystemClock.elapsedRealtime();
  }

  public synchronized Snapshot snapshot() {
    consume();
    return new Snapshot(writtenFrames, playedFrames, capacityFrames(), fullWrites);
  }

  public static final class Snapshot {
    public final long writtenFrames;
    public final long playedFrames;
    public final long capacityFrames;
    public final long fullWrites;
    private Snapshot(long written, long played, long capacity, long full) {
      writtenFrames = written;
      playedFrames = played;
      capacityFrames = capacity;
      fullWrites = full;
    }
  }
}
