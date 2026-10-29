package io.github.dailytakeoff.soundchanger.sound;

import io.github.dailytakeoff.soundchanger.pack.PackFile;
import io.github.dailytakeoff.soundchanger.pack.PackManager;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sits behind SoundManager.getSoundEvent: when a pack changes an event, the game gets a combined event
 * instead of the vanilla one: the vanilla sounds (unless replaced) plus the pack's files as extra variants.
 */
public final class SoundInjector {
	private static final int STREAM_ABOVE_BYTES = 2 * 1024 * 1024;
	private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
	private static final Map<Identifier, Cached> CACHE = new ConcurrentHashMap<>();

	private record Cached(@Nullable WeighedSoundEvents base, PackManager.EventPlan plan, WeighedSoundEvents merged) {
	}

	private SoundInjector() {
	}

	/** Called from the mixin with what vanilla found (null for an unknown event). */
	public static @Nullable WeighedSoundEvents apply(Identifier event, @Nullable WeighedSoundEvents base) {
		if (BYPASS.get()) {
			return base;
		}
		PackManager.EventPlan plan = PackManager.plan(event);
		if (plan == null) {
			return base;
		}
		Cached c = CACHE.get(event);
		if (c != null && c.base() == base && c.plan() == plan) {
			return c.merged();
		}
		WeighedSoundEvents merged = build(event, base, plan);
		CACHE.put(event, new Cached(base, plan, merged));
		return merged;
	}

	/** The event as vanilla (and normal resource packs) would have it, ignoring Sound Changer. */
	public static @Nullable WeighedSoundEvents vanilla(SoundManager manager, Identifier event) {
		BYPASS.set(true);
		try {
			return manager.getSoundEvent(event);
		} finally {
			BYPASS.set(false);
		}
	}

	public static void invalidate() {
		CACHE.clear();
	}

	private static WeighedSoundEvents build(Identifier event, @Nullable WeighedSoundEvents base, PackManager.EventPlan plan) {
		WeighedSoundEvents w = new WeighedSoundEvents(event, subtitleKey(base));
		if (plan.keepVanilla() && base != null) {
			w.addSound(base); // all vanilla variants, with their combined weight
		}
		Sound sample = sample(base);
		int attenuation = sample != null ? sample.getAttenuationDistance() : 16;
		boolean vanillaStreams = sample != null && sample.shouldStream();
		for (PackFile f : plan.files()) {
			boolean stream = f.stream() != null ? f.stream() : autoStream(event, f, vanillaStreams);
			boolean mono = f.mono() != null ? f.mono() : autoMono(event);
			w.addSound(CustomAudio.newSound(f.path(), f.volume(), f.pitch(), f.weight(), stream, mono, attenuation));
		}
		if (plan.muted()) {
			w.addSound(SoundManager.INTENTIONALLY_EMPTY_SOUND);
		}
		return w;
	}

	/** Keeps the vanilla subtitle ("Creeper hisses") for replaced sounds. */
	private static @Nullable String subtitleKey(@Nullable WeighedSoundEvents base) {
		if (base == null) {
			return null;
		}
		Component subtitle = base.getSubtitle();
		return subtitle != null && subtitle.getContents() instanceof TranslatableContents t ? t.getKey() : null;
	}

	/** One vanilla sound of the event, to copy its hearing range and streaming setting. */
	private static @Nullable Sound sample(@Nullable WeighedSoundEvents base) {
		if (base == null || base.getWeight() == 0) {
			return null;
		}
		try {
			Sound s = base.getSound(RandomSource.create(0L));
			return s == SoundManager.EMPTY_SOUND || s == SoundManager.INTENTIONALLY_EMPTY_SOUND ? null : s;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * OpenAL only places mono sounds in the world, so everything is mixed down to mono except music and menu sounds
	 * (which play "in your head" and keep their stereo).
	 */
	public static boolean autoMono(Identifier event) {
		String p = event.getPath();
		return !(p.startsWith("music.") || p.startsWith("ui."));
	}

	private static boolean autoStream(Identifier event, PackFile f, boolean vanillaStreams) {
		if (vanillaStreams || event.getPath().startsWith("music")) {
			return true;
		}
		try {
			return Files.size(f.path()) > STREAM_ABOVE_BYTES;
		} catch (java.io.IOException e) {
			return false;
		}
	}
}
