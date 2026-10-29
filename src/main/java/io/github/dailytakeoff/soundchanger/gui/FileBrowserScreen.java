package io.github.dailytakeoff.soundchanger.gui;

import io.github.dailytakeoff.soundchanger.Config;
import io.github.dailytakeoff.soundchanger.audio.Decoders;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Pick an audio file from anywhere on the computer, inside the game. Drag and drop works here too. */
public final class FileBrowserScreen extends Screen {
	/** One row: a folder, an audio file, or a drive (Windows). */
	private record Entry(Path path, boolean dir, long size, String name) {
	}

	private final Screen parent;
	private final Consumer<Path> onPick;
	private final ListView<Entry> list;
	/** null = list of drives (Windows) */
	private @Nullable Path dir;
	private @Nullable EditBox filterBox;
	private String filter = "";
	private String error = "";
	private List<Entry> all = List.of();

	public FileBrowserScreen(Screen parent, Consumer<Path> onPick) {
		super(Component.literal("Choose a sound file"));
		this.parent = parent;
		this.onPick = onPick;
		this.list = new ListView<>(this::label, e -> e.dir() ? Ui.BLUE : Ui.WHITE, e -> false);
		this.dir = startDir();
		load();
	}

	private static Path home() {
		return Path.of(System.getProperty("user.home", "."));
	}

	private static Path startDir() {
		String last = Config.get().lastBrowseDir;
		if (last != null && !last.isBlank()) {
			try {
				Path p = Path.of(last);
				if (Files.isDirectory(p)) {
					return p;
				}
			} catch (RuntimeException ignored) {
			}
		}
		Path music = home().resolve("Music");
		return Files.isDirectory(music) ? music : home();
	}

	private String label(Entry e) {
		if (e.dir()) {
			return "▸ " + e.name() + (dir == null ? "" : "/");
		}
		return "♪ " + e.name() + "   " + Ui.size(e.size());
	}

	private void load() {
		error = "";
		List<Entry> out = new ArrayList<>();
		if (dir == null) {
			for (File root : File.listRoots()) {
				out.add(new Entry(root.toPath(), true, 0, root.getPath()));
			}
		} else {
			List<Entry> dirs = new ArrayList<>();
			List<Entry> files = new ArrayList<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
				for (Path p : ds) {
					String name = p.getFileName().toString();
					if (name.startsWith(".")) {
						continue;
					}
					try {
						if (Files.isDirectory(p)) {
							dirs.add(new Entry(p, true, 0, name));
						} else if (Decoders.isAudioFile(p)) {
							files.add(new Entry(p, false, Files.size(p), name));
						}
					} catch (IOException | RuntimeException ignored) {
					}
				}
			} catch (IOException | RuntimeException e) {
				error = "Can't open this folder: " + e.getMessage();
			}
			Comparator<Entry> byName = Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
			dirs.sort(byName);
			files.sort(byName);
			out.addAll(dirs);
			out.addAll(files);
		}
		all = out;
		list.resetScroll();
		applyFilter();
	}

	private void applyFilter() {
		String q = filter.trim().toLowerCase(Locale.ROOT);
		list.setItems(q.isEmpty() ? all : all.stream().filter(e -> e.name().toLowerCase(Locale.ROOT).contains(q)).toList());
		list.setEmptyText(error.isEmpty() ? (q.isEmpty() ? "No audio files or folders here." : "Nothing matches \"" + filter + "\"") : error);
	}

	private void go(@Nullable Path to) {
		dir = to;
		filter = "";
		load();
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int w = Math.min(420, this.width - 16);
		int left = (this.width - w) / 2;
		int bw = (w - 12) / 4;
		Button up = Ui.button("↑ Up", left, 30, bw, 18, b -> go(dir == null ? null : dir.getParent()));
		up.active = dir != null;
		addRenderableWidget(up);
		addRenderableWidget(Ui.button("Home", left + bw + 4, 30, bw, 18, b -> go(home())));
		Path music = home().resolve("Music");
		Button musicBtn = Ui.button("Music", left + 2 * (bw + 4), 30, bw, 18, b -> go(music));
		musicBtn.active = Files.isDirectory(music);
		addRenderableWidget(musicBtn);
		Path downloads = home().resolve("Downloads");
		Button dl = Ui.button("Downloads", left + 3 * (bw + 4), 30, w - 3 * (bw + 4), 18, b -> go(downloads));
		dl.active = Files.isDirectory(downloads);
		addRenderableWidget(dl);

		filterBox = new EditBox(this.font, left, 52, w, 16, Component.literal("Filter"));
		filterBox.setHint(Component.literal("Filter this folder…"));
		filterBox.setValue(filter);
		filterBox.setResponder(s -> {
			filter = s;
			list.resetScroll();
			applyFilter();
		});
		addRenderableWidget(filterBox);

		list.setBounds(left, 72, w, this.height - 72 - 34);
		addRenderableWidget(Ui.button("Cancel", this.width / 2 - 60, this.height - 26, 120, 20, b -> onClose()));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Entry e = list.itemAt(event.x(), event.y());
		if (e != null) {
			if (e.dir()) {
				go(e.path());
			} else {
				pick(e.path());
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return list.scroll(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		for (Path p : paths) {
			if (Files.isRegularFile(p)) {
				pick(p);
				return;
			}
		}
	}

	private void pick(Path file) {
		Path folder = file.getParent();
		if (folder != null) {
			Config cfg = Config.get();
			cfg.lastBrowseDir = folder.toString();
			cfg.save();
		}
		this.minecraft.gui.setScreen(parent);
		onPick.accept(file);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		list.render(g, this.font, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 6, Ui.WHITE);
		int w = Math.min(420, this.width - 16);
		String where = dir == null ? "Drives" : dir.toString();
		g.centeredText(this.font, Ui.fitEnd(this.font, where, w), this.width / 2, 18, Ui.GRAY);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
