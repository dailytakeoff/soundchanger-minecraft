package io.github.dailytakeoff.soundchanger;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants. */
public final class SoundChanger {
	public static final String MOD_ID = "soundchanger";
	public static final Logger LOGGER = LoggerFactory.getLogger("Sound Changer");

	private SoundChanger() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
