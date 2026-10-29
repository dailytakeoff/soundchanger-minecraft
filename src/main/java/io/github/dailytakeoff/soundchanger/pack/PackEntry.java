package io.github.dailytakeoff.soundchanger.pack;

import java.util.ArrayList;
import java.util.List;

/** What one pack does to one sound event. */
public final class PackEntry {
	public enum Mode {
		/** Your files instead of the vanilla ones (no files at all = muted). */
		REPLACE,
		/** Your files play as extra variants next to the vanilla ones. */
		ADD
	}

	public Mode mode;
	public final List<PackFile> files = new ArrayList<>();

	public PackEntry(Mode mode) {
		this.mode = mode;
	}

	public boolean isMute() {
		return mode == Mode.REPLACE && files.isEmpty();
	}

	public PackEntry copy() {
		PackEntry e = new PackEntry(mode);
		e.files.addAll(files);
		return e;
	}
}
