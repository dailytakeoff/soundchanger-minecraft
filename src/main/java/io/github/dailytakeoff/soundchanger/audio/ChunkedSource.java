package io.github.dailytakeoff.soundchanger.audio;

import java.io.IOException;

/**
 * Base for decoders that naturally produce one block of samples at a time (an MP3 frame, a FLAC frame).
 * Subclasses implement {@link #nextChunk()}; this class hands the samples out in whatever sizes are asked for.
 */
abstract class ChunkedSource implements PcmSource {
	private short[] chunk = new short[0];
	private int chunkLen;
	private int chunkPos;
	private boolean ended;

	/**
	 * Decodes the next block into {@link #fill(short[], int)}. Returns false at the end.
	 * May produce an empty block (for example a skipped frame).
	 */
	protected abstract boolean nextChunk() throws IOException;

	/** Called by subclasses from {@link #nextChunk()} with the decoded block. */
	protected final void fill(short[] samples, int len) {
		chunk = samples;
		chunkLen = len;
		chunkPos = 0;
	}

	@Override
	public final int read(short[] buf, int off, int len) throws IOException {
		int ch = channels();
		len -= len % ch;
		int done = 0;
		while (done < len) {
			if (chunkPos >= chunkLen) {
				if (ended || !nextChunk()) {
					ended = true;
					break;
				}
				continue;
			}
			int n = Math.min(len - done, chunkLen - chunkPos);
			System.arraycopy(chunk, chunkPos, buf, off + done, n);
			chunkPos += n;
			done += n;
		}
		done -= done % ch;
		return done == 0 && ended ? -1 : done;
	}
}
