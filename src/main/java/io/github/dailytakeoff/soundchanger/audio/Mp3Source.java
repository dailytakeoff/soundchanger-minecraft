package io.github.dailytakeoff.soundchanger.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** MP3 (and MP2) through JLayer. */
final class Mp3Source extends ChunkedSource {
	/** Give up after this many broken frames in a row. */
	private static final int MAX_BAD_FRAMES = 64;

	private final InputStream in;
	private final Bitstream bitstream;
	private final Decoder decoder = new Decoder();
	private final int sampleRate;
	private final int channels;
	/** The first decoded frame, kept until it is handed out (it was needed to learn the format). */
	private short[] first;
	private int firstLen;

	Mp3Source(Path path) throws IOException {
		this.in = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
		this.bitstream = new Bitstream(in);
		try {
			if (!decodeOne()) {
				throw new IOException("no MP3 frames found");
			}
		} catch (IOException e) {
			close();
			throw e;
		}
		this.sampleRate = decoder.getOutputFrequency();
		this.channels = Math.max(1, decoder.getOutputChannels());
	}

	/** Decodes one frame into {@link #first} / the chunk. Returns false at the end. */
	private boolean decodeOne() throws IOException {
		int bad = 0;
		while (true) {
			Header header;
			try {
				header = bitstream.readFrame();
			} catch (BitstreamException e) {
				if (++bad > MAX_BAD_FRAMES) {
					throw new IOException("broken MP3: " + e.getMessage(), e);
				}
				continue;
			}
			if (header == null) {
				return false;
			}
			try {
				SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				int len = out.getBufferLength();
				short[] copy = new short[len];
				System.arraycopy(out.getBuffer(), 0, copy, 0, len);
				first = copy;
				firstLen = len;
				return true;
			} catch (DecoderException | RuntimeException e) {
				if (++bad > MAX_BAD_FRAMES) {
					throw new IOException("broken MP3: " + e.getMessage(), e);
				}
			} finally {
				bitstream.closeFrame();
			}
		}
	}

	@Override
	protected boolean nextChunk() throws IOException {
		if (first != null) {
			fill(first, firstLen);
			first = null;
			return true;
		}
		if (!decodeOne()) {
			return false;
		}
		fill(first, firstLen);
		first = null;
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
		try {
			bitstream.close();
		} catch (BitstreamException ignored) {
		}
		in.close();
	}
}
