package io.github.dailytakeoff.soundchanger.gui;

import io.github.dailytakeoff.soundchanger.audio.Decoders;
import io.github.dailytakeoff.soundchanger.pack.PackManager;
import io.github.dailytakeoff.soundchanger.pack.SoundPack;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/** The pack list: switch packs on and off, change their order, create one, open one in the editor. */
public final class PacksScreen extends Screen {
	private static final int ROW = 24;
	private static final int TOP = 34;

	private final @Nullable Screen parent;
	private int scroll;
	private @Nullable EditBox nameBox;
	private String nameDraft = "";
	private String status = "";
	private int statusColor = Ui.GRAY;

	public PacksScreen(@Nullable Screen parent) {
		super(Component.translatable("soundchanger.packs.title"));
		this.parent = parent;
	}

	private int rowWidth() {
		return Math.min(360, this.width - 20);
	}

	private int rowLeft() {
		return (this.width - rowWidth()) / 2;
	}

	private int visibleRows() {
		return Math.max(1, (this.height - 70 - TOP) / ROW);
	}

	@Override
	protected void init() {
		List<SoundPack> packs = PackManager.packs();
		scroll = Math.max(0, Math.min(scroll, packs.size() - visibleRows()));
		int left = rowLeft();
		int right = left + rowWidth();
		for (int r = 0; r < visibleRows() && scroll + r < packs.size(); r++) {
			SoundPack p = packs.get(scroll + r);
			int index = scroll + r;
			int y = TOP + r * ROW;
			addRenderableWidget(Ui.button(p.enabled() ? "ON" : "OFF",
					p.enabled() ? "Click to switch this pack off" : "Click to switch this pack on", left, y, 30, 20, b -> {
						PackManager.setEnabled(p, !p.enabled());
						rebuildWidgets();
					}));
			Button up = Ui.button("▲", "Higher priority (wins when two packs change the same sound)", right - 104, y, 20, 20, b -> {
				PackManager.move(p, -1);
				rebuildWidgets();
			});
			up.active = index > 0;
			addRenderableWidget(up);
			Button down = Ui.button("▼", "Lower priority", right - 82, y, 20, 20, b -> {
				PackManager.move(p, 1);
				rebuildWidgets();
			});
			down.active = index < packs.size() - 1;
			addRenderableWidget(down);
			addRenderableWidget(Ui.button("Edit", right - 58, y, 58, 20, b -> this.minecraft.gui.setScreen(new PackEditorScreen(this, p))));
		}

		int createY = this.height - 58;
		int boxW = Math.min(220, rowWidth() - 84);
		int boxX = this.width / 2 - (boxW + 84) / 2;
		nameBox = new EditBox(this.font, boxX, createY, boxW, 20, Component.literal("Pack name"));
		nameBox.setMaxLength(48);
		nameBox.setHint(Component.literal("New pack name…"));
		nameBox.setValue(nameDraft);
		nameBox.setResponder(s -> nameDraft = s);
		addRenderableWidget(nameBox);
		addRenderableWidget(Ui.button("Create", boxX + boxW + 4, createY, 80, 20, b -> create()));

		int by = this.height - 28;
		int bw = Math.min(110, (this.width - 24) / 3);
		int bx = this.width / 2 - (bw * 3 + 8) / 2;
		addRenderableWidget(Ui.button("Open folder", "Opens .minecraft/soundpacks", bx, by, bw, 20, b -> Ui.openFolder(PackManager.root())));
		addRenderableWidget(Ui.button("Reload", "Read every pack from disk again (after you changed files by hand)", bx + bw + 4, by, bw, 20, b -> {
			PackManager.reload();
			setStatus("Reloaded " + PackManager.packs().size() + " pack(s)", Ui.GREEN);
			rebuildWidgets();
		}));
		addRenderableWidget(Ui.button("Done", bx + 2 * (bw + 4), by, bw, 20, b -> onClose()));
	}

	private void create() {
		try {
			SoundPack p = PackManager.create(nameDraft);
			nameDraft = "";
			if (p != null) {
				this.minecraft.gui.setScreen(new PackEditorScreen(this, p));
			} else {
				rebuildWidgets();
			}
		} catch (IOException e) {
			setStatus(e.getMessage(), Ui.RED);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		List<SoundPack> packs = PackManager.packs();
		int left = rowLeft();
		int right = left + rowWidth();
		for (int r = 0; r < visibleRows() && scroll + r < packs.size(); r++) {
			int y = TOP + r * ROW;
			g.fill(left - 2, y - 2, right + 2, y + 22, 0x60000000);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 10, Ui.WHITE);
		g.centeredText(this.font, Component.literal("Top pack wins when two packs change the same sound"), this.width / 2, 21, Ui.DARK_GRAY);
		for (int r = 0; r < visibleRows() && scroll + r < packs.size(); r++) {
			SoundPack p = packs.get(scroll + r);
			int y = TOP + r * ROW;
			int textX = left + 36;
			int textW = right - 108 - textX;
			g.text(this.font, Ui.fit(this.font, p.id(), textW), textX, y + 2, p.enabled() ? Ui.WHITE : Ui.DARK_GRAY, false);
			String info = p.size() == 1 ? "1 sound changed" : p.size() + " sounds changed";
			int infoColor = Ui.GRAY;
			if (!p.problems().isEmpty()) {
				info += " · " + p.problems().size() + " problem(s) — see Edit";
				infoColor = Ui.RED;
			}
			g.text(this.font, Ui.fit(this.font, info, textW), textX, y + 12, infoColor, false);
		}
		if (packs.isEmpty()) {
			int y = TOP + 10;
			for (String line : ListView.wrap(this.font,
					"No sound packs yet. Type a name below and press Create, or put pack folders in .minecraft/soundpacks.", rowWidth() - 20)) {
				g.centeredText(this.font, line, this.width / 2, y, Ui.GRAY);
				y += 11;
			}
		} else if (packs.size() > visibleRows()) {
			g.centeredText(this.font, "Scroll for more (" + packs.size() + " packs)", this.width / 2, TOP + visibleRows() * ROW, Ui.DARK_GRAY);
		}
		String line = status;
		int color = statusColor;
		if (line.isEmpty() && Decoders.ffmpeg() == null) {
			line = "ffmpeg not found: OGG, WAV, AIFF, MP3 and FLAC work, other formats need ffmpeg";
			color = Ui.DARK_GRAY;
		}
		if (!line.isEmpty()) {
			g.centeredText(this.font, Ui.fit(this.font, line, this.width - 16), this.width / 2, this.height - 70, color);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int max = Math.max(0, PackManager.packs().size() - visibleRows());
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		if (next != scroll) {
			scroll = next;
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
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
