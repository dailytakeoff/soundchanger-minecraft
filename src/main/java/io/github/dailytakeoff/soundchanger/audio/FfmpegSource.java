package io.github.dailytakeoff.soundchanger.audio;

import io.github.dailytakeoff.soundchanger.SoundChanger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Anything ffmpeg can read (M4A/AAC, Opus, WMA, WebM, video files, ...), decoded by an ffmpeg process
 * into raw 16-bit PCM on its standard output.
 */
final class FfmpegSource implements PcmSource {
	static final int RATE = 48000;

	private final Process process;
	private final InputStream out;
	private final int channels;
	private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
	private byte[] bytes = new byte[0];
	private boolean anyOutput;

	FfmpegSource(Path ffmpeg, Path file, int channels) throws IOException {
		this.channels = channels;
		ProcessBuilder pb = new ProcessBuilder(List.of(ffmpeg.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
				"-i", file.toAbsolutePath().toString(), "-vn", "-sn", "-dn",
				"-f", "s16le", "-acodec", "pcm_s16le", "-ac", String.valueOf(channels), "-ar", String.valueOf(RATE), "-"));
		pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
		this.process = pb.start();
		this.out = new BufferedInputStream(process.getInputStream(), 1 << 16);
		Thread t = new Thread(() -> {
			try (InputStream err = process.getErrorStream()) {
				byte[] b = new byte[4096];
				int n;
				while ((n = err.read(b)) >= 0) {
					synchronized (errors) {
						if (errors.size() < 8192) {
							errors.write(b, 0, n);
						}
					}
				}
			} catch (IOException ignored) {
			}
		}, "SoundChanger-ffmpeg-err");
		t.setDaemon(true);
		t.start();
	}

	private static java.io.File nullFile() {
		return new java.io.File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
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
			int n = out.read(bytes, got, want - got);
			if (n < 0) {
				break;
			}
			got += n;
		}
		int samples = got / 2;
		samples -= samples % channels;
		if (samples == 0) {
			if (!anyOutput) {
				String err = errorText();
				close();
				throw new IOException("ffmpeg could not decode the file" + (err.isEmpty() ? "" : ": " + err));
			}
			return -1;
		}
		anyOutput = true;
		for (int i = 0; i < samples; i++) {
			buf[off + i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
		}
		return samples;
	}

	private String errorText() {
		try {
			process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (errors) {
			return errors.toString(StandardCharsets.UTF_8).trim().lines().reduce((a, b) -> b).orElse("");
		}
	}

	@Override
	public int sampleRate() {
		return RATE;
	}

	@Override
	public int channels() {
		return channels;
	}

	@Override
	public void close() {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		try {
			out.close();
		} catch (IOException e) {
			SoundChanger.LOGGER.debug("closing ffmpeg output", e);
		}
	}
}
