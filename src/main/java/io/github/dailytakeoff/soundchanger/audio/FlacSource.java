package io.github.dailytakeoff.soundchanger.audio;

import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** FLAC through jFLAC (8, 16 and 24 bit). */
final class FlacSource extends ChunkedSource {
	private final InputStream in;
	private final FLACDecoder decoder;
	private final int sampleRate;
	private final int channels;
	private final int bits;
	private ByteData pcm;
	private short[] samples = new short[0];

	FlacSource(Path path) throws IOException {
		this.in = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
		try {
			this.decoder = new FLACDecoder(in);
			StreamInfo info = decoder.readStreamInfo();
			decoder.readMetadata(info); // skip the other metadata blocks (tags, cover art, ...)
			this.sampleRate = info.getSampleRate();
			this.channels = info.getChannels();
			this.bits = info.getBitsPerSample();
			if (bits != 8 && bits != 16 && bits != 24) {
				throw new IOException("unsupported FLAC bit depth " + bits);
			}
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e instanceof IOException io ? io : new IOException(e);
		}
	}

	@Override
	protected boolean nextChunk() throws IOException {
		Frame frame;
		try {
			frame = decoder.readNextFrame();
		} catch (RuntimeException e) {
			throw new IOException("broken FLAC: " + e, e);
		}
		if (frame == null) {
			return false;
		}
		pcm = decoder.decodeFrame(frame, pcm);
		byte[] data = pcm.getData();
		int bytesPerSample = bits / 8;
		int count = pcm.getLen() / bytesPerSample;
		if (samples.length < count) {
			samples = new short[count];
		}
		for (int i = 0, p = 0; i < count; i++, p += bytesPerSample) {
			samples[i] = switch (bits) {
				case 8 -> (short) (((data[p] & 0xFF) - 0x80) << 8);
				case 16 -> (short) ((data[p] & 0xFF) | (data[p + 1] << 8));
				default -> (short) ((data[p + 1] & 0xFF) | (data[p + 2] << 8)); // 24 bit: keep the top 16
			};
		}
		fill(samples, count);
		return true;
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
		in.close();
	}
}
