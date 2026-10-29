package io.github.dailytakeoff.soundchanger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/soundchanger.json: which packs are on and in what order (this is per player, so it is not
 * stored inside the packs, which you can hand to friends).
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static Config instance;

	/** Pack folder names, highest priority first. Packs not listed go below, alphabetically. */
	public List<String> order = new ArrayList<>();
	/** Pack folder names that are switched off. */
	public List<String> disabled = new ArrayList<>();
	/** Optional: full path to ffmpeg (or its folder). Empty means "look on PATH and the usual places". */
	public String ffmpegPath = "";
	/** Where the file browser opened last time. */
	public String lastBrowseDir = "";

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("soundchanger.json");
	}

	public static synchronized Config get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Config load() {
		Path f = file();
		if (Files.isRegularFile(f)) {
			try {
				Config c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
				if (c != null) {
					if (c.order == null) {
						c.order = new ArrayList<>();
					}
					if (c.disabled == null) {
						c.disabled = new ArrayList<>();
					}
					if (c.ffmpegPath == null) {
						c.ffmpegPath = "";
					}
					if (c.lastBrowseDir == null) {
						c.lastBrowseDir = "";
					}
					return c;
				}
			} catch (IOException | RuntimeException e) {
				SoundChanger.LOGGER.warn("Could not read {}, using defaults: {}", f, e.toString());
			}
		}
		return new Config();
	}

	public synchronized void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.writeString(f, GSON.toJson(this), StandardCharsets.UTF_8);
			} catch (IOException e2) {
				SoundChanger.LOGGER.warn("Could not save {}: {}", f, e2.toString());
			}
		}
	}
}
