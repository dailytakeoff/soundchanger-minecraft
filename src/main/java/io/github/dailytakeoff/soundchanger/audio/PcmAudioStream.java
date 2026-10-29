package io.github.dailytakeoff.soundchanger.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Streams a file into Minecraft's sound engine piece by piece (for music and other long sounds),
 * starting the file over at the end when the sound loops.
 */
public final class PcmAudioStream implements AudioStream {
	public interface Opener {
		PcmSource open() throws IOException;
	}

	private final Opener opener;
	private final boolean loop;
	private final int inCh;
	private final int outCh;
	private final AudioFormat format;
	private PcmSource src;
	private short[] tmp = new short[0];
	private short[] mixed = new short[0];
	private boolean ended;

	public PcmAudioStream(Opener opener, boolean loop, boolean mono) throws IOException {
		this.opener = opener;
		this.loop = loop;
		this.src = opener.open();
		this.inCh = src.channels();
		this.outCh = Pcm.outChannels(inCh, mono);
		this.format = Pcm.format(src.sampleRate(), outCh);
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		if (ended) {
			return null;
		}
		int frames = Math.max(1, size / (2 * outCh));
		int wantOut = frames * outCh;
		ByteBuffer out = ByteBuffer.allocateDirect(wantOut * 2).order(Pcm.ORDER);
		int done = 0;
		boolean reopenedEmpty = false;
		while (done < wantOut) {
			int framesLeft = (wantOut - done) / outCh;
			int wantIn = Math.min(framesLeft, 8192) * inCh;
			if (tmp.length < wantIn) {
				tmp = new short[wantIn];
				mixed = new short[Math.min(framesLeft, 8192) * outCh];
			}
			int n = src.read(tmp, 0, wantIn);
			if (n < 0) {
				if (!loop || reopenedEmpty) {
					ended = true;
					break;
				}
				src.close();
				src = opener.open();
				if (src.channels() != inCh) {
					ended = true; // file changed under us
					break;
				}
				reopenedEmpty = true; // if the fresh copy gives nothing either, stop instead of spinning
				continue;
			}
			if (n > 0) {
				reopenedEmpty = false;
			}
			int m = Pcm.mix(tmp, n, inCh, mixed, 0, outCh);
			for (int i = 0; i < m; i++) {
				out.putShort(mixed[i]);
			}
			done += m;
		}
		if (done == 0) {
			ended = true;
			return null;
		}
		out.flip();
		return out;
	}

	@Override
	public void close() throws IOException {
		ended = true;
		src.close();
	}
}
