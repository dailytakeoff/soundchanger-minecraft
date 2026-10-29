package io.github.dailytakeoff.soundchanger.gui;

import io.github.dailytakeoff.soundchanger.audio.Decoders;
import io.github.dailytakeoff.soundchanger.pack.PackEntry;
import io.github.dailytakeoff.soundchanger.pack.PackFile;
import io.github.dailytakeoff.soundchanger.pack.PackManager;
import io.github.dailytakeoff.soundchanger.pack.SoundPack;
import io.github.dailytakeoff.soundchanger.sound.CustomAudio;
import io.github.dailytakeoff.soundchanger.sound.PreviewSound;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Left: every sound in the game (searchable). Right: what this pack does to the selected one,
 * with buttons to listen, add your own files, switch between replacing and adding, and mute.
 */
public final class PackEditorScreen extends Screen {
	private static final int FILE_ROW = 16;

	private final Screen parent;
	private final SoundPack pack;
	private final ListView<Identifier> events;
	private @Nullable EditBox searchBox;
	private String search = "";
	private boolean changedOnly;
	private @Nullable Identifier selected;
	/** Mode for the first file added to a sound this pack doesn't touch yet. */
	private PackEntry.Mode newMode = PackEntry.Mode.REPLACE;
	private int fileScroll;
	private String status = "";
	private int statusColor = Ui.GRAY;

	public PackEditorScreen(Screen parent, SoundPack pack) {
		super(Component.literal("Sound pack: " + pack.id()));
		this.parent = parent;
		this.pack = pack;
		this.events = new ListView<>(this::label, this::color, e -> e.equals(selected));
		this.changedOnly = pack.size() > 0;
		if (!pack.problems().isEmpty()) {
			status = pack.problems().size() + " problem(s): " + pack.problems().get(0);
			statusColor = Ui.RED;
		}
	}

	// ---------------------------------------------------------------- layout

	private int leftW() {
		return Math.min(260, (this.width - 18) / 2);
	}

	private int rightX() {
		return 6 + leftW() + 8;
	}

	private int rightW() {
		return this.width - rightX() - 6;
	}

	private int filesTop() {
		return 100;
	}

	private int filesBottom() {
		return this.height - 68;
	}

	private int fileRows() {
		return Math.max(1, (filesBottom() - filesTop()) / FILE_ROW);
	}

	@Override
	protected void init() {
		int lw = leftW();
		boolean hadFocus = searchBox != null && searchBox.isFocused();
		searchBox = new EditBox(this.font, 6, 20, lw - 58, 16, Component.literal("Search"));
		searchBox.setHint(Component.literal("Search sounds…"));
		searchBox.setValue(search);
		searchBox.setResponder(s -> {
			search = s;
			events.resetScroll();
			refreshList();
		});
		addRenderableWidget(searchBox);
		addRenderableWidget(Ui.button(changedOnly ? "Changed" : "All",
				changedOnly ? "Showing only sounds this pack changes. Click to show all." : "Showing every sound. Click to show only changed ones.",
				6 + lw - 54, 20, 54, 16, b -> {
					changedOnly = !changedOnly;
					events.resetScroll();
					rebuildWidgets();
				}));
		events.setBounds(6, 40, lw, this.height - 40 - 32);
		refreshList();
		if (hadFocus) {
			setFocused(searchBox);
			searchBox.setFocused(true);
		}

		int rx = rightX();
		int rw = rightW();
		if (selected != null) {
			Identifier ev = selected;
			int half = (rw - 4) / 2;
			addRenderableWidget(Ui.button("▶ Original", "Play the normal Minecraft sound", rx, 44, half, 18, b -> PreviewSound.original(ev)));
			addRenderableWidget(Ui.button("▶ With packs", "Play it the way you'll hear it in game (random pick each time)", rx + half + 4, 44, rw - half - 4, 18,
					b -> PreviewSound.withPacks(ev)));
			PackEntry.Mode mode = currentMode();
			addRenderableWidget(Ui.button(mode == PackEntry.Mode.REPLACE ? "Mode: Replace Minecraft's sound" : "Mode: Add to Minecraft's sound",
					mode == PackEntry.Mode.REPLACE ? "Only your files play. Click to keep Minecraft's sound too."
							: "Your files and Minecraft's play at random. Click to only play yours.",
					rx, 66, rw, 18, b -> toggleMode()));

			List<PackFile> files = files();
			fileScroll = Math.max(0, Math.min(fileScroll, files.size() - fileRows()));
			for (int r = 0; r < fileRows() && fileScroll + r < files.size(); r++) {
				PackFile f = files.get(fileScroll + r);
				int y = filesTop() + r * FILE_ROW;
				if (!f.fromFolder()) {
					addRenderableWidget(Ui.button("✕", "Remove from this sound (the file stays in the pack's files folder)",
							rx + rw - 16, y, 16, 15, b -> {
								pack.removeFile(ev, f);
								changed("Removed " + f.fileName(), Ui.GRAY);
							}));
				}
				addRenderableWidget(Ui.button("▶", "Play this file", rx + rw - (f.fromFolder() ? 16 : 34), y, 16, 15, b -> playFile(ev, f)));
			}

			int by = this.height - 50;
			int third = (rw - 8) / 3;
			addRenderableWidget(Ui.button("+ Add file…", "Pick an audio file (or drag files onto this window)", rx, by, rw - 2 * third - 8, 18,
					b -> this.minecraft.gui.setScreen(new FileBrowserScreen(this, this::addFile))));
			PackEntry combined = pack.combined(ev);
			boolean muted = combined != null && combined.isMute();
			addRenderableWidget(Ui.button(muted ? "Unmute" : "Mute", muted ? "Play this sound again" : "Silence this sound completely",
					rx + rw - 2 * third - 4, by, third, 18, b -> toggleMute()));
			Button reset = Ui.button("Reset", "Undo everything this pack's editor did to this sound", rx + rw - third, by, third, 18,
					b -> confirm("Reset " + shortName(ev) + "?", "Your files are unassigned (they stay in the pack's files folder).",
							() -> {
								pack.reset(ev);
								changed("Reset " + shortName(ev), Ui.GRAY);
							}, pack.editorEntry(ev) != null && !pack.editorEntry(ev).files.isEmpty()));
			reset.active = pack.editorEntry(ev) != null;
			addRenderableWidget(reset);
		}

		int bw = Math.min(120, (this.width - 20) / 2);
		addRenderableWidget(Ui.button("Open pack folder", "Drop files into replace/ or add/ named after a sound, then press Reload",
				this.width / 2 - bw - 2, this.height - 26, bw, 20, b -> Ui.openFolder(pack.dir())));
		addRenderableWidget(Ui.button("Done", this.width / 2 + 2, this.height - 26, bw, 20, b -> onClose()));
	}

	private void refreshList() {
		TreeSet<Identifier> all = new TreeSet<>(pack.events());
		if (!changedOnly) {
			all.addAll(this.minecraft.getSoundManager().getAvailableSounds());
		}
		String q = search.trim().toLowerCase(Locale.ROOT);
		List<Identifier> shown = new ArrayList<>();
		for (Identifier id : all) {
			if (q.isEmpty() || id.toString().contains(q) || id.getPath().replace('.', ' ').contains(q)) {
				shown.add(id);
			}
		}
		events.setItems(shown);
		events.setEmptyText(changedOnly && q.isEmpty()
				? "This pack doesn't change any sounds yet. Click \"Changed\" to see every sound, pick one and add a file."
				: "No sounds match \"" + search + "\"");
	}

	// ---------------------------------------------------------------- data

	private List<PackFile> files() {
		if (selected == null) {
			return List.of();
		}
		PackEntry c = pack.combined(selected);
		return c == null ? List.of() : c.files;
	}

	private PackEntry.Mode currentMode() {
		PackEntry c = selected == null ? null : pack.combined(selected);
		return c != null ? c.mode : newMode;
	}

	private String label(Identifier id) {
		PackEntry e = pack.combined(id);
		String prefix = e == null ? "  " : e.isMute() ? "∅ " : e.mode == PackEntry.Mode.REPLACE ? "● " : "+ ";
		return prefix + shortName(id);
	}

	private int color(Identifier id) {
		PackEntry e = pack.combined(id);
		if (e == null) {
			return 0xFFD8D8D8;
		}
		return e.isMute() ? Ui.RED : e.mode == PackEntry.Mode.REPLACE ? Ui.YELLOW : Ui.GREEN;
	}

	private static String shortName(Identifier id) {
		return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
	}

	// ---------------------------------------------------------------- actions

	/** Selects a sound and scrolls the list to it (also used by the automated screenshots). */
	public void selectSound(Identifier id) {
		select(id);
		events.ensureVisible(id);
	}

	private void select(Identifier id) {
		if (!id.equals(selected)) {
			selected = id;
			fileScroll = 0;
			newMode = PackEntry.Mode.REPLACE;
			rebuildWidgets();
		}
	}

	private void toggleMode() {
		if (selected == null) {
			return;
		}
		PackEntry.Mode next = currentMode() == PackEntry.Mode.REPLACE ? PackEntry.Mode.ADD : PackEntry.Mode.REPLACE;
		PackEntry editor = pack.editorEntry(selected);
		PackEntry folder = pack.folderEntry(selected);
		if (editor == null && folder == null) {
			newMode = next;
			rebuildWidgets();
			return;
		}
		if (next == PackEntry.Mode.ADD && folder != null && folder.mode == PackEntry.Mode.REPLACE) {
			setStatus("This sound is in the pack's replace/ folder: move it to add/ to keep Minecraft's sound", Ui.YELLOW);
			return;
		}
		pack.setMode(selected, next);
		changed(next == PackEntry.Mode.REPLACE ? "Now replacing Minecraft's sound" : "Now playing next to Minecraft's sound", Ui.GRAY);
	}

	private void toggleMute() {
		if (selected == null) {
			return;
		}
		Identifier ev = selected;
		PackEntry combined = pack.combined(ev);
		if (combined != null && combined.isMute()) {
			PackEntry folder = pack.folderEntry(ev);
			if (folder != null && folder.isMute()) {
				setStatus("Muted by an empty folder in replace/ — delete that folder to unmute", Ui.YELLOW);
				return;
			}
			pack.setMuted(ev, false);
			changed("Unmuted " + shortName(ev), Ui.GRAY);
			return;
		}
		PackEntry editor = pack.editorEntry(ev);
		confirm("Mute " + shortName(ev) + "?", "Your files for this sound are unassigned (they stay in the pack's files folder).", () -> {
			pack.setMuted(ev, true);
			changed("Muted " + shortName(ev), Ui.GRAY);
		}, editor != null && !editor.files.isEmpty());
	}

	/** Runs the action straight away, or after a yes when {@code ask} is true. */
	private void confirm(String title, String detail, Runnable action, boolean ask) {
		if (!ask) {
			action.run();
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				action.run();
			}
			this.minecraft.gui.setScreen(this);
		}, Component.literal(title), Component.literal(detail)));
	}

	private void addFile(Path file) {
		if (selected == null) {
			setStatus("Pick a sound on the left first", Ui.YELLOW);
			return;
		}
		if (!Files.isRegularFile(file)) {
			setStatus("Not a file: " + file.getFileName(), Ui.RED);
			return;
		}
		try {
			Decoders.Format format = Decoders.detect(file);
			PackFile added = pack.addFile(selected, file, newMode);
			if (format.needsFfmpeg() && Decoders.ffmpeg() == null) {
				changed("Added " + added.fileName() + ", but " + format.label + " needs ffmpeg (or convert it to MP3/OGG/WAV/FLAC)", Ui.YELLOW);
			} else {
				changed("Added " + added.fileName() + " (" + format.label + ")", Ui.GREEN);
			}
			List<PackFile> files = files();
			fileScroll = Math.max(0, files.size() - fileRows());
			rebuildWidgets();
		} catch (IOException e) {
			setStatus("Couldn't add " + file.getFileName() + ": " + e.getMessage(), Ui.RED);
		}
	}

	private void playFile(Identifier event, PackFile f) {
		String err = CustomAudio.error(f.path());
		if (err != null) {
			setStatus(f.fileName() + ": " + err, Ui.RED);
		}
		PreviewSound.file(event, f.path());
	}

	/** After an edit: recombine the packs so the change is audible now, and redraw. */
	private void changed(String message, int color) {
		PackManager.rebuildPlans();
		setStatus(message, color);
		rebuildWidgets();
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Identifier hit = events.itemAt(event.x(), event.y());
		if (hit != null) {
			select(hit);
			if (doubleClick) {
				PreviewSound.withPacks(hit);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (events.scroll(x, y, scrollY)) {
			return true;
		}
		if (selected != null && x >= rightX() && y >= filesTop() && y < filesBottom()) {
			int max = Math.max(0, files().size() - fileRows());
			int next = Math.max(0, Math.min(max, fileScroll - (int) Math.signum(scrollY)));
			if (next != fileScroll) {
				fileScroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (selected == null) {
			setStatus("Pick a sound on the left, then drop the files again", Ui.YELLOW);
			return;
		}
		int added = 0;
		for (Path p : paths) {
			if (Files.isRegularFile(p) && Decoders.isAudioFile(p)) {
				addFile(p);
				added++;
			}
		}
		if (added == 0) {
			setStatus("Those don't look like audio files", Ui.YELLOW);
		} else if (added > 1) {
			setStatus("Added " + added + " files to " + shortName(selected), Ui.GREEN);
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		events.render(g, this.font, mouseX, mouseY);
		int rx = rightX();
		int rw = rightW();
		g.fill(rx - 3, 18, rx + rw + 3, this.height - 30, 0x60000000);
		if (selected != null) {
			g.fill(rx, filesTop() - 2, rx + rw, filesBottom(), 0x50000000);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, Ui.fit(this.font, this.title.getString(), this.width - 12), this.width / 2, 6, Ui.WHITE);

		if (selected == null) {
			int y = 26;
			for (String line : ListView.wrap(this.font, "Pick a sound on the left. Try searching for creeper, step, door, music or chest.\n\n"
					+ "● replaced\n+ added next to Minecraft's\n∅ muted\n\n"
					+ "Double-click a sound to hear it.", rw - 8)) {
				g.text(this.font, line, rx + 2, y, Ui.GRAY, false);
				y += 11;
			}
		} else {
			g.text(this.font, Ui.fit(this.font, shortName(selected), rw - 4), rx + 2, 22, Ui.WHITE, false);
			g.text(this.font, Ui.fit(this.font, summary(), rw - 4), rx + 2, 32, Ui.GRAY, false);
			List<PackFile> files = files();
			g.text(this.font, files.isEmpty() ? "Your files" : "Your files (" + files.size() + ")", rx + 2, filesTop() - 12, Ui.WHITE, false);
			if (files.isEmpty()) {
				PackEntry c = pack.combined(selected);
				String hint = c != null && c.isMute() ? "Muted — nothing plays." : "None yet. Add one below or drag audio files onto this window.";
				int y = filesTop() + 4;
				for (String line : ListView.wrap(this.font, hint, rw - 10)) {
					g.text(this.font, line, rx + 4, y, Ui.DARK_GRAY, false);
					y += 11;
				}
			}
			for (int r = 0; r < fileRows() && fileScroll + r < files.size(); r++) {
				PackFile f = files.get(fileScroll + r);
				int y = filesTop() + r * FILE_ROW;
				int textW = rw - (f.fromFolder() ? 22 : 40);
				String name = f.fileName() + (f.fromFolder() ? "  (folder)" : "");
				boolean missing = !Files.isRegularFile(f.path());
				boolean broken = CustomAudio.error(f.path()) != null;
				int color = missing || broken ? Ui.RED : f.fromFolder() ? Ui.BLUE : Ui.WHITE;
				g.text(this.font, Ui.fit(this.font, (missing ? "missing: " : broken ? "! " : "") + name, textW), rx + 4, y + 4, color, false);
			}
			if (files.size() > fileRows()) {
				g.text(this.font, "scroll ↕", rx + rw - this.font.width("scroll ↕") - 2, filesTop() - 12, Ui.DARK_GRAY, false);
			}
		}
		if (!status.isEmpty()) {
			g.text(this.font, Ui.fit(this.font, status, rw - 4), rx + 2, this.height - 64, statusColor, false);
		}
	}

	private String summary() {
		PackEntry c = pack.combined(selected);
		if (c == null) {
			return "Not changed by this pack";
		}
		if (c.isMute()) {
			return "Muted";
		}
		int n = c.files.size();
		String files = n == 1 ? "1 file" : n + " files";
		return c.mode == PackEntry.Mode.REPLACE ? "Replaced by " + files : "Minecraft's sound + " + files;
	}

	@Override
	public void removed() {
		PreviewSound.stop();
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
