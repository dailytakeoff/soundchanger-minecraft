package io.github.dailytakeoff.soundchanger.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Plays a sound in the menu (no position, master volume) for the editor's play buttons. */
public final class PreviewSound extends SimpleSoundInstance {
	private static @Nullable PreviewSound playing;

	private final Supplier<@Nullable WeighedSoundEvents> resolver;

	private PreviewSound(Identifier event, Supplier<@Nullable WeighedSoundEvents> resolver) {
		super(event, SoundSource.MASTER, 1F, 1F, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
				0.0, 0.0, 0.0, true);
		this.resolver = resolver;
	}

	@Override
	public @Nullable WeighedSoundEvents getOrResolve(SoundManager manager) {
		WeighedSoundEvents w = resolver.get();
		this.soundEvent = w;
		this.sound = w == null ? SoundManager.EMPTY_SOUND : w.getSound(this.random);
		return w;
	}

	/** The sound as Minecraft (and normal resource packs) has it. */
	public static void original(Identifier event) {
		play(new PreviewSound(event, () -> SoundInjector.vanilla(Minecraft.getInstance().getSoundManager(), event)));
	}

	/** The sound with every enabled pack applied (a random variant each time). */
	public static void withPacks(Identifier event) {
		play(new PreviewSound(event, () -> Minecraft.getInstance().getSoundManager().getSoundEvent(event)));
	}

	/** Just this one file. */
	public static void file(Identifier event, Path file) {
		play(new PreviewSound(event, () -> {
			WeighedSoundEvents w = new WeighedSoundEvents(event, null);
			boolean stream;
			try {
				stream = Files.size(file) > 2 * 1024 * 1024;
			} catch (java.io.IOException e) {
				stream = false;
			}
			w.addSound(CustomAudio.newSound(file, 1F, 1F, 1, stream, false, 16));
			return w;
		}));
	}

	public static void stop() {
		if (playing != null) {
			Minecraft.getInstance().getSoundManager().stop(playing);
			playing = null;
		}
	}

	private static void play(PreviewSound s) {
		stop();
		playing = s;
		Minecraft.getInstance().getSoundManager().play(s);
	}
}
