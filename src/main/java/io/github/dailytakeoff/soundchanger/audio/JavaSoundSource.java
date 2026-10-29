package io.github.dailytakeoff.soundchanger.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** WAV, AIFF and AU through Java's own audio classes (8/16/24/32-bit, float, µ-law, A-law). */
final class JavaSoundSource implements PcmSource {
	private final InputStream raw;
	private final AudioInputStream in;
	private final int sampleRate;
	private final int channels;
	private byte[] bytes = new byte[0];

	JavaSoundSource(Path path) throws IOException {
		this.raw = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
		try {
			AudioInputStream src = AudioSystem.getAudioInputStream(raw);
			AudioFormat f = src.getFormat();
			int ch = f.getChannels();
			float rate = f.getSampleRate();
			if (ch < 1 || rate <= 0) {
				throw new IOException("unknown channel count or sample rate");
			}
			AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
			AudioInputStream pcm = target.matches(f) ? src : AudioSystem.getAudioInputStream(target, src);
			this.in = pcm;
			this.sampleRate = Math.round(rate);
			this.channels = ch;
		} catch (UnsupportedAudioFileException | IllegalArgumentException e) {
			raw.close();
			throw new IOException("Java can't read this file: " + e.getMessage(), e);
		} catch (IOException | RuntimeException e) {
			raw.close();
			throw e;
		}
	}

	@Override
	public int read(short[] buf, int off, int len) throws IOException {
		len -= len % channels;
		int want = len * 2;
		if (bytes.length < want) {
			bytes = new byte[want];
		}
		int got = 0;
		while (got < want) {
			int n = in.read(bytes, got, want - got);
			if (n < 0) {
				break;
			}
			got += n;
		}
		int samples = got / 2;
		samples -= samples % channels;
		if (samples == 0) {
			return -1;
		}
		for (int i = 0; i < samples; i++) {
			buf[off + i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
		}
		return samples;
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
			in.close();
		} finally {
			raw.close();
		}
	}
}
