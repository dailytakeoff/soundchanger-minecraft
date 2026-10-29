package io.github.dailytakeoff.soundchanger.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.nio.file.Path;

/** Small shared helpers for the screens. */
final class Ui {
	static final int WHITE = 0xFFFFFFFF;
	static final int GRAY = 0xFFA0A0A0;
	static final int DARK_GRAY = 0xFF707070;
	static final int RED = 0xFFFF6B6B;
	static final int GREEN = 0xFF7CE07C;
	static final int YELLOW = 0xFFFFD866;
	static final int BLUE = 0xFF8EC5FF;

	private Ui() {
	}

	/** Cuts text to fit, with "…" at the end. */
	static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		String ell = "…";
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ell))) + ell;
	}

	/** Cuts text to fit, keeping the end (for file paths). */
	static String fitEnd(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		String ell = "…";
		int start = 0;
		while (start < text.length() && font.width(ell + text.substring(start)) > width) {
			start++;
		}
		return ell + text.substring(start);
	}

	static Button button(String text, int x, int y, int w, int h, Button.OnPress onPress) {
		return Button.builder(Component.literal(text), onPress).bounds(x, y, w, h).build();
	}

	static Button button(String text, String tooltip, int x, int y, int w, int h, Button.OnPress onPress) {
		return Button.builder(Component.literal(text), onPress).bounds(x, y, w, h).tooltip(Tooltip.create(Component.literal(tooltip))).build();
	}

	static void openFolder(Path dir) {
		try {
			java.nio.file.Files.createDirectories(dir);
		} catch (java.io.IOException ignored) {
		}
		Util.getPlatform().openPath(dir);
	}

	static String size(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return (bytes / 1024) + " KB";
		}
		return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
