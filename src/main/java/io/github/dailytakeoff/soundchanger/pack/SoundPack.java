package io.github.dailytakeoff.soundchanger.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailytakeoff.soundchanger.SoundChanger;
import io.github.dailytakeoff.soundchanger.audio.Decoders;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A folder in soundpacks/. Sounds come from two places, which are combined:
 * <ul>
 * <li>pack.json, written by the in-game editor (files it copies go to files/)</li>
 * <li>the replace/ and add/ folders: a file or folder named after a sound event, e.g.
 * replace/entity.creeper.primed.mp3 or add/block.stone.break/ with several files inside.
 * Other mods' sounds: othermod~some.sound.ogg. An empty folder under replace/ mutes that sound.</li>
 * </ul>
 */
public final class SoundPack {
	public static final String PACK_JSON = "pack.json";
	public static final String FILES_DIR = "files";
	public static final String REPLACE_DIR = "replace";
	public static final String ADD_DIR = "add";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final String id;
	private final Path dir;
	private String description = "";
	/** From pack.json (the editor changes these). */
	private final Map<Identifier, PackEntry> editor = new TreeMap<>();
	/** From replace/ and add/ (read-only in game). */
	private final Map<Identifier, PackEntry> folder = new TreeMap<>();
	private final List<String> problems = new ArrayList<>();
	boolean enabled = true;

	private SoundPack(String id, Path dir) {
		this.id = id;
		this.dir = dir;
	}

	public String id() {
		return id;
	}

	public Path dir() {
		return dir;
	}

	public String description() {
		return description;
	}

	public boolean enabled() {
		return enabled;
	}

	public List<String> problems() {
		return Collections.unmodifiableList(problems);
	}

	/** Every sound event this pack touches. */
	public Set<Identifier> events() {
		Set<Identifier> all = new java.util.TreeSet<>(editor.keySet());
		all.addAll(folder.keySet());
		return all;
	}

	public int size() {
		return events().size();
	}

	/** The editor's entry, or null. */
	public PackEntry editorEntry(Identifier event) {
		return editor.get(event);
	}

	/** The folder entry, or null. */
	public PackEntry folderEntry(Identifier event) {
		return folder.get(event);
	}

	/** Folder and editor combined: replace if either replaces, folder files first. Null if this pack doesn't touch the event. */
	public PackEntry combined(Identifier event) {
		PackEntry f = folder.get(event);
		PackEntry e = editor.get(event);
		if (f == null) {
			return e;
		}
		if (e == null) {
			return f;
		}
		PackEntry c = new PackEntry(f.mode == PackEntry.Mode.REPLACE || e.mode == PackEntry.Mode.REPLACE
				? PackEntry.Mode.REPLACE : PackEntry.Mode.ADD);
		c.files.addAll(f.files);
		c.files.addAll(e.files);
		return c;
	}

	// ---------------------------------------------------------------- loading

	static SoundPack load(Path dir) {
		SoundPack p = new SoundPack(dir.getFileName().toString(), dir);
		p.readJson();
		p.scanFolder(REPLACE_DIR, PackEntry.Mode.REPLACE);
		p.scanFolder(ADD_DIR, PackEntry.Mode.ADD);
		return p;
	}

	private void readJson() {
		Path json = dir.resolve(PACK_JSON);
		if (!Files.isRegularFile(json)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("description") && root.get("description").isJsonPrimitive()) {
				description = root.get("description").getAsString();
			}
			JsonObject sounds = root.has("sounds") && root.get("sounds").isJsonObject() ? root.getAsJsonObject("sounds") : new JsonObject();
			for (Map.Entry<String, JsonElement> e : sounds.entrySet()) {
				Identifier event = Identifier.tryParse(e.getKey().trim().toLowerCase(Locale.ROOT));
				if (event == null || !e.getValue().isJsonObject()) {
					problems.add("pack.json: \"" + e.getKey() + "\" is not a valid sound name");
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				String mode = o.has("mode") ? o.get("mode").getAsString().toLowerCase(Locale.ROOT) : "replace";
				PackEntry entry = new PackEntry(mode.equals("add") ? PackEntry.Mode.ADD : PackEntry.Mode.REPLACE);
				if (o.has("files") && o.get("files").isJsonArray()) {
					for (JsonElement f : o.getAsJsonArray("files")) {
						PackFile pf = parseFile(f);
						if (pf != null) {
							entry.files.add(pf);
						}
					}
				}
				editor.put(event, entry);
			}
		} catch (IOException | RuntimeException e) {
			problems.add("pack.json could not be read: " + e.getMessage());
			SoundChanger.LOGGER.warn("Pack {}: pack.json could not be read", id, e);
		}
	}

	private PackFile parseFile(JsonElement f) {
		String file;
		float volume = 1F;
		float pitch = 1F;
		int weight = 1;
		Boolean stream = null;
		Boolean mono = null;
		if (f.isJsonPrimitive()) {
			file = f.getAsString();
		} else if (f.isJsonObject()) {
			JsonObject o = f.getAsJsonObject();
			if (!o.has("file")) {
				return null;
			}
			file = o.get("file").getAsString();
			if (o.has("volume")) {
				volume = o.get("volume").getAsFloat();
			}
			if (o.has("pitch")) {
				pitch = o.get("pitch").getAsFloat();
			}
			if (o.has("weight")) {
				weight = Math.max(1, o.get("weight").getAsInt());
			}
			if (o.has("stream")) {
				stream = o.get("stream").getAsBoolean();
			}
			if (o.has("mono")) {
				mono = o.get("mono").getAsBoolean();
			}
		} else {
			return null;
		}
		Path path = resolveInside(file);
		if (path == null) {
			problems.add("pack.json: \"" + file + "\" is outside the pack folder");
			return null;
		}
		if (!Files.isRegularFile(path)) {
			problems.add("missing file: " + file);
		}
		return new PackFile(file.replace('\\', '/'), path, volume, pitch, weight, stream, mono, false);
	}

	/** Resolves a path from pack.json, refusing anything that would leave the pack folder. */
	private Path resolveInside(String file) {
		try {
			Path p = dir.resolve(file.replace('\\', '/')).normalize();
			return p.startsWith(dir.normalize()) ? p : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void scanFolder(String sub, PackEntry.Mode mode) {
		Path base = dir.resolve(sub);
		if (!Files.isDirectory(base)) {
			return;
		}
		List<Path> children = list(base);
		for (Path child : children) {
			String name = child.getFileName().toString();
			if (name.startsWith(".")) {
				continue;
			}
			boolean isDir = Files.isDirectory(child);
			if (!isDir && !Decoders.isAudioFile(child)) {
				continue;
			}
			String eventName = isDir ? name : name.substring(0, name.lastIndexOf('.'));
			Identifier event = parseEventName(eventName);
			if (event == null) {
				problems.add(sub + "/" + name + ": not a valid sound name");
				continue;
			}
			PackEntry entry = folder.computeIfAbsent(event, k -> new PackEntry(mode));
			if (mode == PackEntry.Mode.REPLACE) {
				entry.mode = PackEntry.Mode.REPLACE; // in both folders: replace wins
			}
			if (isDir) {
				try (Stream<Path> walk = Files.walk(child)) {
					walk.filter(Files::isRegularFile).filter(Decoders::isAudioFile)
							.filter(p -> !p.getFileName().toString().startsWith("."))
							.sorted(Comparator.comparing(Path::toString))
							.forEach(p -> entry.files.add(folderFile(p)));
				} catch (IOException e) {
					problems.add(sub + "/" + name + ": " + e.getMessage());
				}
			} else {
				entry.files.add(folderFile(child));
			}
		}
	}

	private PackFile folderFile(Path p) {
		return new PackFile(dir.relativize(p).toString().replace('\\', '/'), p, 1F, 1F, 1, null, null, true);
	}

	/** "entity.creeper.primed" → minecraft:entity.creeper.primed, "othermod~beep" → othermod:beep. */
	public static Identifier parseEventName(String name) {
		String n = name.trim().toLowerCase(Locale.ROOT);
		int tilde = n.indexOf('~');
		if (tilde > 0) {
			return Identifier.tryBuild(n.substring(0, tilde), n.substring(tilde + 1));
		}
		if (n.contains(":")) {
			return Identifier.tryParse(n);
		}
		return Identifier.tryBuild(Identifier.DEFAULT_NAMESPACE, n);
	}

	/** The file/folder name for a sound event inside replace/ or add/. */
	public static String folderName(Identifier event) {
		return event.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? event.getPath() : event.getNamespace() + "~" + event.getPath();
	}

	private static List<Path> list(Path dir) {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			ds.forEach(out::add);
		} catch (IOException ignored) {
		}
		out.sort(Comparator.comparing(p -> p.getFileName().toString()));
		return out;
	}

	// ---------------------------------------------------------------- editing (pack.json only)

	/** Copies the file into files/ (unless it is already inside this pack) and assigns it to the event. */
	public PackFile addFile(Identifier event, Path source, PackEntry.Mode modeIfNew) throws IOException {
		Path src = source.toAbsolutePath().normalize();
		Path target;
		if (src.startsWith(dir.toAbsolutePath().normalize())) {
			target = src;
		} else {
			Path files = dir.resolve(FILES_DIR);
			Files.createDirectories(files);
			target = uniqueTarget(files, src);
			if (!Files.exists(target)) {
				Files.copy(src, target, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
		String rel = dir.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
		PackFile pf = new PackFile(rel, target, 1F, 1F, 1, null, null, false);
		PackEntry entry = editor.computeIfAbsent(event, k -> new PackEntry(modeIfNew));
		if (entry.files.stream().noneMatch(f -> f.file().equals(rel))) {
			entry.files.add(pf);
		}
		save();
		return pf;
	}

	/** Same name already in files/: reuse it if it's the same file, otherwise pick "name (2).ext". */
	private static Path uniqueTarget(Path files, Path src) throws IOException {
		String name = src.getFileName().toString();
		Path t = files.resolve(name);
		if (!Files.exists(t) || Files.size(t) == Files.size(src) && Files.mismatch(t, src) == -1) {
			return t;
		}
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2; ; i++) {
			t = files.resolve(stem + " (" + i + ")" + ext);
			if (!Files.exists(t) || Files.size(t) == Files.size(src) && Files.mismatch(t, src) == -1) {
				return t;
			}
		}
	}

	public void removeFile(Identifier event, PackFile file) {
		PackEntry entry = editor.get(event);
		if (entry == null) {
			return;
		}
		entry.files.removeIf(f -> f.file().equals(file.file()));
		if (entry.files.isEmpty() && entry.mode == PackEntry.Mode.ADD) {
			editor.remove(event);
		}
		save();
	}

	/** Switches between replacing and adding for this event. */
	public void setMode(Identifier event, PackEntry.Mode mode) {
		PackEntry entry = editor.get(event);
		if (entry == null) {
			if (mode == PackEntry.Mode.REPLACE && folder.containsKey(event)) {
				editor.put(event, new PackEntry(PackEntry.Mode.REPLACE)); // turns folder "add" files into a replacement
			} else {
				return;
			}
		} else {
			entry.mode = mode;
			if (entry.files.isEmpty() && mode == PackEntry.Mode.ADD) {
				editor.remove(event);
			}
		}
		save();
	}

	/** Mute: replace with nothing. Unmute removes the (empty) editor entry. */
	public void setMuted(Identifier event, boolean muted) {
		if (muted) {
			PackEntry entry = editor.computeIfAbsent(event, k -> new PackEntry(PackEntry.Mode.REPLACE));
			entry.mode = PackEntry.Mode.REPLACE;
			entry.files.clear();
		} else {
			PackEntry entry = editor.get(event);
			if (entry != null && entry.files.isEmpty()) {
				editor.remove(event);
			}
		}
		save();
	}

	/** Removes the editor's changes to this event (the folder ones stay). */
	public void reset(Identifier event) {
		if (editor.remove(event) != null) {
			save();
		}
	}

	/** Every audio file in files/ (for picking one that's already in the pack). */
	public List<Path> packFiles() {
		Path files = dir.resolve(FILES_DIR);
		if (!Files.isDirectory(files)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(files)) {
			return s.filter(Files::isRegularFile).filter(Decoders::isAudioFile).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public void save() {
		JsonObject root = new JsonObject();
		root.addProperty("description", description);
		JsonObject sounds = new JsonObject();
		for (Map.Entry<Identifier, PackEntry> e : editor.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("mode", e.getValue().mode == PackEntry.Mode.ADD ? "add" : "replace");
			JsonArray files = new JsonArray();
			for (PackFile f : e.getValue().files) {
				if (f.isDefault()) {
					files.add(f.file());
				} else {
					JsonObject fo = new JsonObject();
					fo.addProperty("file", f.file());
					if (f.volume() != 1F) {
						fo.addProperty("volume", f.volume());
					}
					if (f.pitch() != 1F) {
						fo.addProperty("pitch", f.pitch());
					}
					if (f.weight() != 1) {
						fo.addProperty("weight", f.weight());
					}
					if (f.stream() != null) {
						fo.addProperty("stream", f.stream());
					}
					if (f.mono() != null) {
						fo.addProperty("mono", f.mono());
					}
					files.add(fo);
				}
			}
			o.add("files", files);
			sounds.add(e.getKey().toString(), o);
		}
		root.add("sounds", sounds);
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(PACK_JSON), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SoundChanger.LOGGER.warn("Could not save {}: {}", dir.resolve(PACK_JSON), e.toString());
		}
	}

	/** Creates an empty pack folder with a pack.json and the replace/ and add/ folders. */
	static SoundPack create(Path dir, String description) throws IOException {
		Files.createDirectories(dir.resolve(REPLACE_DIR));
		Files.createDirectories(dir.resolve(ADD_DIR));
		Files.createDirectories(dir.resolve(FILES_DIR));
		SoundPack p = new SoundPack(dir.getFileName().toString(), dir);
		p.description = description;
		p.save();
		return p;
	}
}
