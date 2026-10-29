package io.github.dailytakeoff.soundchanger.pack;

import java.nio.file.Path;

/**
 * One of your audio files assigned to a sound event.
 *
 * @param file   path inside the pack folder, with '/' separators (e.g. "files/boom.mp3")
 * @param path   the resolved file on disk
 * @param volume 1 = as recorded
 * @param pitch  1 = as recorded
 * @param weight how often it is picked compared to the others (vanilla variants count 1 each)
 * @param stream null = decide automatically (long files and music stream from disk)
 * @param mono   null = decide automatically (mono for sounds that come from a place in the world)
 * @param fromFolder true if it came from the replace/ or add/ folders rather than the editor
 */
public record PackFile(String file, Path path, float volume, float pitch, int weight, Boolean stream, Boolean mono,
		boolean fromFolder) {
	public String fileName() {
		return path.getFileName().toString();
	}

	public boolean isDefault() {
		return volume == 1F && pitch == 1F && weight == 1 && stream == null && mono == null;
	}
}
