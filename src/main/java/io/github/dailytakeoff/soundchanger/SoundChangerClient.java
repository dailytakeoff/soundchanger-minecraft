package io.github.dailytakeoff.soundchanger;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.dailytakeoff.soundchanger.gui.PacksScreen;
import io.github.dailytakeoff.soundchanger.pack.PackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLScancode;

/**
 * Client-only entry point: reads the packs and registers the "open the sound pack menu" key
 * (J by default, changeable under Options > Controls > Key Binds > Sound Changer).
 */
public final class SoundChangerClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(SoundChanger.id("main"));

	public static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		PackManager.reload();
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.soundchanger.open", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_J, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) {
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(new PacksScreen(null));
				}
			}
		});
	}
}
