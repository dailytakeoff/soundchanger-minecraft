package io.github.dailytakeoff.soundchanger;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.dailytakeoff.soundchanger.gui.PacksScreen;

/** The settings button in Mod Menu opens the pack list (only loaded when Mod Menu is installed). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PacksScreen::new;
	}
}
