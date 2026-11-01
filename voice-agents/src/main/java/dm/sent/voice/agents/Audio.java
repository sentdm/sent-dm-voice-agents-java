// The binary audio frame of an audio session: a 2-byte big-endian stream number, then 16-bit
// little-endian
// mono PCM at the session's sample rate, a whole number of samples. The header keeps the samples
// aligned.
package dm.sent.voice.agents;

import java.util.Arrays;

/** The binary audio frame of an audio session. */
public final class Audio {
    public static final int HEADER_BYTES = 2;

    private Audio() {}

    /** The stream a frame belongs to: the session whose stream it is, on this connection. */
    public static int streamOf(byte[] frame) {
        return ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
    }

    /** The samples of a frame, as 16-bit little-endian mono PCM. */
    public static byte[] samplesOf(byte[] frame) {
        return Arrays.copyOfRange(frame, HEADER_BYTES, frame.length);
    }

    /** A frame carrying {@code samples} for {@code stream}. */
    public static byte[] frame(int stream, byte[] samples) {
        if (samples.length % 2 != 0) {
            throw new IllegalArgumentException("audio is a whole number of 16-bit samples");
        }
        byte[] frame = new byte[HEADER_BYTES + samples.length];
        frame[0] = (byte) (stream >> 8);
        frame[1] = (byte) stream;
        System.arraycopy(samples, 0, frame, HEADER_BYTES, samples.length);
        return frame;
    }

    /**
     * How many bytes of samples carry {@code milliseconds} of audio at {@code sampleRate}: 640 for
     * 20 ms at 16000 Hz.
     */
    public static int bytesPerFrame(int sampleRate, int milliseconds) {
        return sampleRate * milliseconds / 1000 * 2;
    }

    /** The bytes of 20 ms of audio at {@code sampleRate}. */
    public static int bytesPerFrame(int sampleRate) {
        return bytesPerFrame(sampleRate, 20);
    }
}
