package io.github.dailytakeoff.soundchanger.audio;

import io.github.dailytakeoff.soundchanger.Config;
import io.github.dailytakeoff.soundchanger.SoundChanger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks a decoder by looking at the first bytes of the file (not its name): OGG Vorbis, WAV/AIFF/AU,
 * MP3 and FLAC are decoded inside the game; everything else (and anything those fail on) goes to ffmpeg.
 */
public final class Decoders {
	/** File endings the folder scanner and the file browser treat as audio. */
	public static final Set<String> AUDIO_EXTENSIONS = Set.of("ogg", "oga", "opus", "mp3", "mp2", "wav", "wave", "aif", "aiff",
			"aifc", "au", "snd", "flac", "m4a", "m4b", "aac", "mp4", "wma", "webm", "weba", "mka", "mkv", "caf", "amr", "ac3",
			"spx", "ape", "wv", "mpc", "3gp", "mov", "alac");

	public enum Format {
		OGG_VORBIS("OGG"), OGG_OTHER("OGG (Opus/other)"), WAV("WAV"), AIFF("AIFF"), AU("AU"), MP3("MP3"), FLAC("FLAC"), OTHER("other");

		public final String label;

		Format(String label) {
			this.label = label;
		}

		public boolean needsFfmpeg() {
			return this == OGG_OTHER || this == OTHER;
		}
	}

	private static volatile Path ffmpeg;
	private static volatile boolean ffmpegSearched;

	private Decoders() {
	}

	public static boolean isAudioFile(Path p) {
		String name = p.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	public static Format detect(Path path) throws IOException {
		byte[] h = new byte[64];
		int n;
		try (InputStream in = Files.newInputStream(path)) {
			n = in.readNBytes(h, 0, h.length);
		}
		if (n >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') {
			// the first page holds the codec header: "\x01vorbis" for Vorbis, "OpusHead" for Opus
			return indexOf(h, n, "vorbis") >= 0 ? Format.OGG_VORBIS : Format.OGG_OTHER;
		}
		if (n >= 12 && ascii(h, 0, "RIFF") && ascii(h, 8, "WAVE")) {
			return Format.WAV;
		}
		if (n >= 12 && ascii(h, 0, "FORM") && (ascii(h, 8, "AIFF") || ascii(h, 8, "AIFC"))) {
			return Format.AIFF;
		}
		if (n >= 4 && ascii(h, 0, ".snd")) {
			return Format.AU;
		}
		if (n >= 4 && ascii(h, 0, "fLaC")) {
			return Format.FLAC;
		}
		if (n >= 3 && ascii(h, 0, "ID3")) {
			// ID3 tags sit in front of MP3s, but sometimes in front of FLAC/AAC too: look behind the tag
			int size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
			byte[] after = new byte[4];
			try (InputStream in = Files.newInputStream(path)) {
				in.skipNBytes(10L + size);
				if (in.readNBytes(after, 0, 4) == 4) {
					if (ascii(after, 0, "fLaC")) {
						return Format.FLAC;
					}
					if (isAdts(after[0], after[1])) {
						return Format.OTHER;
					}
				}
			} catch (IOException ignored) {
			}
			return Format.MP3;
		}
		if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
			return isAdts(h[0], h[1]) ? Format.OTHER : Format.MP3; // raw AAC (ADTS) also starts with a frame sync
		}
		return Format.OTHER;
	}

	/** ADTS (raw AAC) has layer bits 00, MP3 frames never do. */
	private static boolean isAdts(byte b0, byte b1) {
		return (b0 & 0xFF) == 0xFF && (b1 & 0xF0) == 0xF0 && ((b1 >> 1) & 3) == 0;
	}

	private static boolean ascii(byte[] b, int at, String s) {
		for (int i = 0; i < s.length(); i++) {
			if (b[at + i] != s.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int indexOf(byte[] b, int n, String s) {
		outer:
		for (int i = 0; i + s.length() <= n; i++) {
			for (int j = 0; j < s.length(); j++) {
				if (b[i + j] != s.charAt(j)) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * Opens a decoder for the file. {@code monoHint} only matters for ffmpeg (it can mix down itself);
	 * the in-game decoders always return the file's own channel layout.
	 */
	public static PcmSource open(Path path, boolean monoHint) throws IOException {
		if (!Files.isRegularFile(path)) {
			throw new IOException("file not found: " + path.getFileName());
		}
		Format format = detect(path);
		IOException nativeError = null;
		if (!format.needsFfmpeg()) {
			try {
				return switch (format) {
					case OGG_VORBIS -> new OggSource(path);
					case WAV, AIFF, AU -> new JavaSoundSource(path);
					case MP3 -> new Mp3Source(path);
					case FLAC -> new FlacSource(path);
					default -> throw new IllegalStateException();
				};
			} catch (IOException | RuntimeException e) {
				nativeError = e instanceof IOException io ? io : new IOException(e.toString(), e);
			}
		}
		Path ff = ffmpeg();
		if (ff == null) {
			if (nativeError != null) {
				throw new IOException(nativeError.getMessage() + " (install ffmpeg to play it anyway)", nativeError);
			}
			throw new IOException(format.label + " files need ffmpeg, which wasn't found");
		}
		if (nativeError != null) {
			SoundChanger.LOGGER.info("Built-in {} decoder failed on {} ({}), trying ffmpeg", format.label, path.getFileName(),
					nativeError.getMessage());
		}
		return new FfmpegSource(ff, path, monoHint ? 1 : 2);
	}

	/** Forget where ffmpeg is (looked up again next time, e.g. after the setting changed). */
	public static void resetFfmpeg() {
		ffmpegSearched = false;
		ffmpeg = null;
	}

	public static Path ffmpeg() {
		if (!ffmpegSearched) {
			ffmpeg = findFfmpeg();
			ffmpegSearched = true;
			SoundChanger.LOGGER.info("ffmpeg: {}", ffmpeg == null ? "not found (only OGG, WAV, AIFF, MP3 and FLAC will play)" : ffmpeg);
		}
		return ffmpeg;
	}

	private static Path findFfmpeg() {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String exe = windows ? "ffmpeg.exe" : "ffmpeg";
		List<Path> candidates = new ArrayList<>();
		String configured = Config.get().ffmpegPath;
		if (configured != null && !configured.isBlank()) {
			Path p = Path.of(configured.trim());
			candidates.add(Files.isDirectory(p) ? p.resolve(exe) : p);
		}
		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
				if (!dir.isBlank()) {
					try {
						candidates.add(Path.of(dir.trim()).resolve(exe));
					} catch (RuntimeException ignored) {
					}
				}
			}
		}
		Path game = FabricLoader.getInstance().getGameDir();
		candidates.add(game.resolve("soundchanger").resolve(exe));
		candidates.add(game.resolve("multimedia").resolve("bin").resolve(exe)); // Sharing Craft downloads one here on Windows
		if (windows) {
			String local = System.getenv("LOCALAPPDATA");
			if (local != null) {
				candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", exe));
			}
			candidates.add(Path.of("C:\\ffmpeg\\bin", exe));
			String pf = System.getenv("ProgramFiles");
			if (pf != null) {
				candidates.add(Path.of(pf, "ffmpeg", "bin", exe));
			}
		} else {
			candidates.add(Path.of("/usr/bin", exe));
			candidates.add(Path.of("/usr/local/bin", exe));
			candidates.add(Path.of("/opt/homebrew/bin", exe));
			candidates.add(Path.of("/var/lib/flatpak/exports/bin", exe));
		}
		for (Path c : candidates) {
			try {
				if (Files.isRegularFile(c) && (windows || Files.isExecutable(c))) {
					return c;
				}
			} catch (RuntimeException ignored) {
			}
		}
		return null;
	}
}
