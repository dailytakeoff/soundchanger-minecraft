package io.github.dailytakeoff.soundchanger.audio;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small helpers for 16-bit PCM: channel mixing and the buffers OpenAL wants. */
public final class Pcm {
	/** OpenAL takes samples in the machine's own byte order. */
	public static final ByteOrder ORDER = ByteOrder.nativeOrder();

	private Pcm() {
	}

	/** Output channel count: mono when asked for (needed for sounds placed in the world), otherwise mono or stereo. */
	public static int outChannels(int inChannels, boolean mono) {
		return mono ? 1 : Math.min(2, Math.max(1, inChannels));
	}

	public static AudioFormat format(int sampleRate, int channels) {
		return new AudioFormat(sampleRate, 16, channels, true, ORDER == ByteOrder.BIG_ENDIAN);
	}

	/**
	 * Converts {@code n} interleaved samples with {@code inCh} channels to {@code outCh} channels (1 or 2), in place
	 * is not allowed. Returns how many samples were written.
	 */
	public static int mix(short[] in, int n, int inCh, short[] out, int outOff, int outCh) {
		int frames = n / inCh;
		if (inCh == outCh) {
			System.arraycopy(in, 0, out, outOff, frames * inCh);
			return frames * inCh;
		}
		int o = outOff;
		if (outCh == 1) {
			for (int f = 0, i = 0; f < frames; f++, i += inCh) {
				int sum = 0;
				for (int c = 0; c < inCh; c++) {
					sum += in[i + c];
				}
				out[o++] = (short) (sum / inCh);
			}
		} else {
			// 2 out: mono is doubled, surround keeps front left/right
			for (int f = 0, i = 0; f < frames; f++, i += inCh) {
				short l = in[i];
				short r = inCh == 1 ? l : in[i + 1];
				out[o++] = l;
				out[o++] = r;
			}
		}
		return o - outOff;
	}

	/** Decoded audio, already in the channel layout that will be played. */
	public record Decoded(short[] samples, int length, int sampleRate, int channels) {
		public double seconds() {
			return length / (double) channels / sampleRate;
		}

		public ByteBuffer toBuffer() {
			ByteBuffer b = ByteBuffer.allocateDirect(length * 2).order(ORDER);
			b.asShortBuffer().put(samples, 0, length);
			b.limit(length * 2);
			return b;
		}
	}

	/** Reads everything (up to maxSeconds) into memory. */
	public static Decoded readAll(PcmSource src, boolean mono, int maxSeconds) throws IOException {
		int inCh = src.channels();
		int outCh = outChannels(inCh, mono);
		int rate = src.sampleRate();
		long max = (long) maxSeconds * rate * outCh;
		short[] tmp = new short[4096 * inCh];
		short[] out = new short[Math.max(4096, rate * outCh)];
		int len = 0;
		short[] mixed = new short[tmp.length];
		while (len < max) {
			int n = src.read(tmp, 0, tmp.length);
			if (n < 0) {
				break;
			}
			int m = mix(tmp, n, inCh, mixed, 0, outCh);
			if (len + m > out.length) {
				out = java.util.Arrays.copyOf(out, Math.max(len + m, out.length * 2));
			}
			System.arraycopy(mixed, 0, out, len, m);
			len += m;
		}
		return new Decoded(out, len, rate, outCh);
	}
}
