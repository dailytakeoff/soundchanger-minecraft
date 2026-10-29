package io.github.dailytakeoff.soundchanger.pack;

import io.github.dailytakeoff.soundchanger.Config;
import io.github.dailytakeoff.soundchanger.SoundChanger;
import io.github.dailytakeoff.soundchanger.audio.Decoders;
import io.github.dailytakeoff.soundchanger.sound.SoundInjector;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All packs in .minecraft/soundpacks, their order, and what they add up to. */
public final class PackManager {
	/** The combined result of every enabled pack for one sound event. */
	public record EventPlan(boolean keepVanilla, List<PackFile> files) {
		public boolean muted() {
			return !keepVanilla && files.isEmpty();
		}
	}

	private static volatile List<SoundPack> packs = List.of();
	private static volatile Map<Identifier, EventPlan> plans = Map.of();

	private PackManager() {
	}

	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("soundpacks");
	}

	/** Packs, highest priority first. */
	public static List<SoundPack> packs() {
		return packs;
	}

	public static EventPlan plan(Identifier event) {
		return plans.get(event);
	}

	public static boolean hasPlans() {
		return !plans.isEmpty();
	}

	public static SoundPack find(String id) {
		for (SoundPack p : packs) {
			if (p.id().equals(id)) {
				return p;
			}
		}
		return null;
	}

	/** Reads every pack from disk again and makes the changes audible right away. */
	public static synchronized void reload() {
		Path root = root();
		try {
			Files.createDirectories(root);
			writeReadme(root);
		} catch (IOException e) {
			SoundChanger.LOGGER.warn("Could not create {}: {}", root, e.toString());
		}
		List<SoundPack> found = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, Files::isDirectory)) {
			for (Path dir : ds) {
				if (!dir.getFileName().toString().startsWith(".")) {
					found.add(SoundPack.load(dir));
				}
			}
		} catch (IOException e) {
			SoundChanger.LOGGER.warn("Could not list {}: {}", root, e.toString());
		}
		Config cfg = Config.get();
		found.sort(Comparator.<SoundPack>comparingInt(p -> {
			int i = cfg.order.indexOf(p.id());
			return i < 0 ? Integer.MAX_VALUE : i;
		}).thenComparing(p -> p.id().toLowerCase(Locale.ROOT)));
		for (SoundPack p : found) {
			p.enabled = !cfg.disabled.contains(p.id());
			for (String problem : p.problems()) {
				SoundChanger.LOGGER.warn("Pack {}: {}", p.id(), problem);
			}
		}
		packs = Collections.unmodifiableList(found);
		rebuildPlans();
		Decoders.resetFfmpeg();
		long files = plans.values().stream().mapToLong(pl -> pl.files().size()).sum();
		SoundChanger.LOGGER.info("Loaded {} sound pack(s): {} sound(s) changed, {} file(s)", found.stream().filter(SoundPack::enabled).count(),
				plans.size(), files);
	}

	/** Recombines the packs (after a change in the editor) without reading the disk. */
	public static synchronized void rebuildPlans() {
		Map<Identifier, List<PackFile>> files = new HashMap<>();
		Map<Identifier, Boolean> keep = new HashMap<>();
		List<SoundPack> list = packs;
		// lowest priority first, so higher packs can replace what lower ones did
		for (int i = list.size() - 1; i >= 0; i--) {
			SoundPack p = list.get(i);
			if (!p.enabled()) {
				continue;
			}
			for (Identifier event : p.events()) {
				PackEntry e = p.combined(event);
				if (e == null) {
					continue;
				}
				List<PackFile> fl = files.computeIfAbsent(event, k -> new ArrayList<>());
				if (e.mode == PackEntry.Mode.REPLACE) {
					fl.clear();
					keep.put(event, false);
				} else {
					keep.putIfAbsent(event, true);
				}
				for (PackFile f : e.files) {
					if (Files.isRegularFile(f.path())) {
						fl.add(f);
					}
				}
			}
		}
		Map<Identifier, EventPlan> out = new HashMap<>();
		for (Map.Entry<Identifier, List<PackFile>> e : files.entrySet()) {
			boolean keepVanilla = keep.getOrDefault(e.getKey(), true);
			if (keepVanilla && e.getValue().isEmpty()) {
				continue; // "add" with no usable files changes nothing
			}
			out.put(e.getKey(), new EventPlan(keepVanilla, List.copyOf(e.getValue())));
		}
		plans = Map.copyOf(out);
		SoundInjector.invalidate();
	}

	public static SoundPack create(String rawName) throws IOException {
		String name = sanitize(rawName);
		if (name.isEmpty()) {
			throw new IOException("Give the pack a name");
		}
		Path dir = root().resolve(name);
		if (Files.exists(dir)) {
			throw new IOException("A pack called \"" + name + "\" already exists");
		}
		SoundPack.create(dir, "");
		Config cfg = Config.get();
		cfg.order.remove(name);
		cfg.order.add(0, name);
		cfg.save();
		reload();
		return find(name);
	}

	public static void setEnabled(SoundPack pack, boolean enabled) {
		Config cfg = Config.get();
		cfg.disabled.remove(pack.id());
		if (!enabled) {
			cfg.disabled.add(pack.id());
		}
		cfg.save();
		pack.enabled = enabled;
		rebuildPlans();
	}

	/** Moves a pack up (delta -1, higher priority) or down (+1). */
	public static synchronized void move(SoundPack pack, int delta) {
		List<SoundPack> list = new ArrayList<>(packs);
		int i = list.indexOf(pack);
		int j = i + delta;
		if (i < 0 || j < 0 || j >= list.size()) {
			return;
		}
		Collections.swap(list, i, j);
		packs = Collections.unmodifiableList(list);
		Config cfg = Config.get();
		cfg.order.clear();
		for (SoundPack p : list) {
			cfg.order.add(p.id());
		}
		cfg.save();
		rebuildPlans();
	}

	/** Folder names that work on Windows and Linux alike. */
	public static String sanitize(String name) {
		String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
		while (n.endsWith(".")) {
			n = n.substring(0, n.length() - 1).trim();
		}
		return n.length() > 48 ? n.substring(0, 48).trim() : n;
	}

	private static void writeReadme(Path root) throws IOException {
		Path readme = root.resolve("README.txt");
		if (Files.exists(readme)) {
			return;
		}
		Files.writeString(readme, """
				Sound Changer packs
				===================

				Every folder in here is one sound pack. Make and edit them in game (default key: J),
				or by hand:

				  My Pack/
				    replace/                       your sounds INSTEAD of Minecraft's
				      entity.creeper.primed.mp3    one file named after the sound
				      block.stone.break/           or a folder of files (one is picked at random)
				      entity.cow.ambient/          an empty folder here mutes that sound
				    add/                           your sounds NEXT TO Minecraft's (random pick)
				      entity.chicken.ambient.wav
				    files/                         files the in-game editor copied in
				    pack.json                      what the in-game editor set up

				Sound names are the ones from /playsound, without "minecraft:".
				For other mods' sounds use modid~sound.name (for example create~whistle.mp3).

				OGG, WAV, AIFF, MP3 and FLAC play as they are. Anything else (M4A, Opus, WMA, video
				files, ...) plays if ffmpeg is installed.

				To share a pack, zip its folder. Your friends need Sound Changer to hear it.
				""".replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
	}
}
