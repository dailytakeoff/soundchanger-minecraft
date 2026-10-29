package io.github.dailytakeoff.soundchanger.audio;

import java.io.Closeable;
import java.io.IOException;

/** A decoder that hands out interleaved signed 16-bit samples, a chunk at a time. */
public interface PcmSource extends Closeable {
	int sampleRate();

	int channels();

	/**
	 * Reads up to {@code len} interleaved samples into {@code buf}. Returns how many were read
	 * (always a whole number of frames), or -1 at the end.
	 */
	int read(short[] buf, int off, int len) throws IOException;

	@Override
	void close() throws IOException;
}
