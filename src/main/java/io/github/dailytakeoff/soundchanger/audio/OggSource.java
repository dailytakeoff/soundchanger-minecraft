package io.github.dailytakeoff.soundchanger.audio;

import net.minecraft.client.sounds.JOrbisAudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** OGG Vorbis through Minecraft's own decoder. */
final class OggSource implements PcmSource {
	private final InputStream in;
	private final JOrbisAudioStream ogg;
	private final int sampleRate;
	private final int channels;
	private final int bytesPerSample;
	private final boolean bigEndian;
	private final boolean signed;
	private final boolean floats;
	private ByteBuffer pending;

	OggSource(Path path) throws IOException {
		this.in = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
		try {
			this.ogg = new JOrbisAudioStream(in);
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e instanceof IOException io ? io : new IOException(e.toString(), e);
		}
		AudioFormat f = ogg.getFormat();
		this.sampleRate = Math.round(f.getSampleRate());
		this.channels = f.getChannels();
		this.bytesPerSample = Math.max(1, f.getSampleSizeInBits() / 8);
		this.bigEndian = f.isBigEndian();
		this.floats = f.getEncoding() == AudioFormat.Encoding.PCM_FLOAT;
		this.signed = f.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED;
	}

	@Override
	public int read(short[] buf, int off, int len) throws IOException {
		len -= len % channels;
		int done = 0;
		while (done < len) {
			if (pending == null || !pending.hasRemaining()) {
				pending = ogg.read(Math.max(4096, (len - done) * bytesPerSample));
				if (pending == null || !pending.hasRemaining()) {
					pending = null;
					break;
				}
				pending.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
			}
			while (done < len && pending.remaining() >= bytesPerSample) {
				buf[off + done++] = sample();
			}
			if (pending.remaining() < bytesPerSample) {
				pending.position(pending.limit());
			}
		}
		done -= done % channels;
		return done == 0 ? -1 : done;
	}

	private short sample() {
		if (floats) {
			float v = bytesPerSample == 8 ? (float) pending.getDouble() : pending.getFloat();
			return (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767F)));
		}
		return switch (bytesPerSample) {
			case 1 -> (short) ((signed ? pending.get() : (pending.get() & 0xFF) - 128) << 8);
			case 2 -> pending.getShort();
			case 3 -> {
				byte a = pending.get();
				byte b = pending.get();
				byte c = pending.get();
				yield bigEndian ? (short) ((a << 8) | (b & 0xFF)) : (short) ((c << 8) | (b & 0xFF));
			}
			default -> (short) (pending.getInt() >> 16);
		};
	}

	@Override
	public int sampleRate() {
		return sampleRate;
	}

	@Override
	public int channels() {
		return channels;
	}

	@Override
	public void close() throws IOException {
		try {
			ogg.close();
		} finally {
			in.close();
		}
	}
}
