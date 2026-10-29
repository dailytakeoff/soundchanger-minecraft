package io.github.dailytakeoff.soundchanger.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** A plain scrolling list of one-line rows, drawn and clicked by the screen that owns it. */
final class ListView<T> {
	static final int ROW = 12;
	private static final int BAR = 4;

	int x;
	int y;
	int w;
	int h;
	private List<T> items = List.of();
	private int scroll;
	private final Function<T, String> label;
	private final ToIntFunction<T> color;
	private final Predicate<T> selected;
	private String emptyText = "";

	ListView(Function<T, String> label, ToIntFunction<T> color, Predicate<T> selected) {
		this.label = label;
		this.color = color;
		this.selected = selected;
	}

	void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		clamp();
	}

	void setItems(List<T> items) {
		this.items = items;
		clamp();
	}

	List<T> items() {
		return items;
	}

	void setEmptyText(String text) {
		this.emptyText = text;
	}

	void resetScroll() {
		scroll = 0;
	}

	int visibleRows() {
		return Math.max(1, h / ROW);
	}

	private void clamp() {
		scroll = Math.max(0, Math.min(scroll, items.size() - visibleRows()));
	}

	void ensureVisible(T item) {
		int i = items.indexOf(item);
		if (i < 0) {
			return;
		}
		if (i < scroll) {
			scroll = i;
		} else if (i >= scroll + visibleRows()) {
			scroll = i - visibleRows() + 1;
		}
		clamp();
	}

	boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** The item under the mouse, or null. */
	T itemAt(double mx, double my) {
		if (!contains(mx, my)) {
			return null;
		}
		int i = scroll + (int) ((my - y) / ROW);
		return i >= 0 && i < items.size() && (int) ((my - y) / ROW) < visibleRows() ? items.get(i) : null;
	}

	boolean scroll(double mx, double my, double amount) {
		if (!contains(mx, my)) {
			return false;
		}
		scroll -= (int) Math.signum(amount) * 3;
		clamp();
		return true;
	}

	void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		g.fill(x, y, x + w, y + h, 0xA0000000);
		border(g, x - 1, y - 1, x + w + 1, y + h + 1, 0xFF404040);
		int rows = visibleRows();
		boolean bar = items.size() > rows;
		int textW = w - 8 - (bar ? BAR + 2 : 0);
		if (items.isEmpty() && !emptyText.isEmpty()) {
			int ty = y + 6;
			for (String line : wrap(font, emptyText, w - 12)) {
				g.text(font, line, x + 6, ty, 0xFF9A9A9A, false);
				ty += font.lineHeight + 2;
			}
		}
		T hover = itemAt(mouseX, mouseY);
		for (int r = 0; r < rows && scroll + r < items.size(); r++) {
			T item = items.get(scroll + r);
			int ry = y + r * ROW;
			if (selected.test(item)) {
				g.fill(x, ry, x + w - (bar ? BAR + 1 : 0), ry + ROW, 0x70FFFFFF);
			} else if (item == hover) {
				g.fill(x, ry, x + w - (bar ? BAR + 1 : 0), ry + ROW, 0x30FFFFFF);
			}
			g.text(font, Ui.fit(font, label.apply(item), textW), x + 4, ry + 2, color.applyAsInt(item), false);
		}
		if (bar) {
			int bx = x + w - BAR;
			g.fill(bx, y, x + w, y + h, 0x60000000);
			int thumbH = Math.max(8, h * rows / items.size());
			int maxScroll = items.size() - rows;
			int ty = y + (int) ((long) (h - thumbH) * scroll / Math.max(1, maxScroll));
			g.fill(bx, ty, x + w, ty + thumbH, 0xFFA0A0A0);
		}
	}

	static void border(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1, y1, x2, y1 + 1, color);
		g.fill(x1, y2 - 1, x2, y2, color);
		g.fill(x1, y1, x1 + 1, y2, color);
		g.fill(x2 - 1, y1, x2, y2, color);
	}

	static List<String> wrap(Font font, String text, int width) {
		java.util.ArrayList<String> lines = new java.util.ArrayList<>();
		for (String para : text.split("\n")) {
			StringBuilder line = new StringBuilder();
			for (String word : para.split(" ")) {
				String test = line.isEmpty() ? word : line + " " + word;
				if (font.width(test) > width && !line.isEmpty()) {
					lines.add(line.toString());
					line = new StringBuilder(word);
				} else {
					line = new StringBuilder(test);
				}
			}
			lines.add(line.toString());
		}
		return lines;
	}
}
