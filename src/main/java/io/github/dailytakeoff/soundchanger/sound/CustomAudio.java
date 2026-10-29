package io.github.dailytakeoff.soundchanger.sound;

import com.mojang.blaze3d.audio.SoundBuffer;
import io.github.dailytakeoff.soundchanger.SoundChanger;
import io.github.dailytakeoff.soundchanger.audio.Decoders;
import io.github.dailytakeoff.soundchanger.audio.Pcm;
import io.github.dailytakeoff.soundchanger.audio.PcmAudioStream;
import io.github.dailytakeoff.soundchanger.audio.PcmSource;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Our sounds have locations in the soundchanger namespace. When Minecraft asks its buffer library for one,
 * the mixin sends the request here and the file is decoded with our decoders instead of the vanilla OGG one.
 */
public final class CustomAudio {
	/** Non-streamed sounds longer than this are cut off (they should stream anyway). */
	private static final int MAX_BUFFER_SECONDS = 15 * 60;

	/** What a sound location stands for. */
	public record Ref(Path file, boolean mono, boolean stream) {
	}

	/** Decoded buffers are shared between sounds that use the same file in the same way. */
	private record BufferKey(Path file, long modified, long size, boolean mono) {
	}

	private static final Map<Identifier, Ref> REFS = new ConcurrentHashMap<>();
	private static final Map<BufferKey, CompletableFuture<SoundBuffer>> BUFFERS = new ConcurrentHashMap<>();
	private static final Map<Path, String> ERRORS = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT = new AtomicInteger();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "SoundChanger-Decoder");
		t.setDaemon(true);
		return t;
	});

	private CustomAudio() {
	}

	public static boolean owns(Identifier path) {
		return SoundChanger.MOD_ID.equals(path.getNamespace());
	}

	/** Makes a Minecraft Sound that plays the given file. */
	public static Sound newSound(Path file, float volume, float pitch, int weight, boolean stream, boolean mono, int attenuationDistance) {
		Identifier location = SoundChanger.id("custom/" + NEXT.incrementAndGet());
		Sound sound = new Sound(location, ConstantFloat.of(volume), ConstantFloat.of(pitch), Math.max(1, weight), Sound.Type.FILE,
				stream, false, attenuationDistance);
		REFS.put(sound.getPath(), new Ref(file, mono, stream));
		return sound;
	}

	/** The last decode error for a file, or null (shown in the editor). */
	public static String error(Path file) {
		return ERRORS.get(file.toAbsolutePath().normalize());
	}

	public static CompletableFuture<SoundBuffer> completeBuffer(Identifier path) {
		Ref ref = REFS.get(path);
		if (ref == null) {
			return CompletableFuture.failedFuture(new IOException("unknown sound " + path));
		}
		BufferKey key;
		try {
			key = new BufferKey(ref.file().toAbsolutePath().normalize(), Files.getLastModifiedTime(ref.file()).toMillis(),
					Files.size(ref.file()), ref.mono());
		} catch (IOException e) {
			fail(ref.file(), e);
			return CompletableFuture.failedFuture(e);
		}
		return BUFFERS.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
			long start = System.nanoTime();
			try (PcmSource src = Decoders.open(k.file(), k.mono())) {
				Pcm.Decoded d = Pcm.readAll(src, k.mono(), MAX_BUFFER_SECONDS);
				if (d.length() == 0) {
					throw new IOException("the file has no audio in it");
				}
				ERRORS.remove(k.file());
				SoundChanger.LOGGER.debug("Decoded {} ({} s, {} Hz, {} ch) in {} ms", k.file().getFileName(),
						String.format(java.util.Locale.ROOT, "%.1f", d.seconds()), d.sampleRate(), d.channels(),
						(System.nanoTime() - start) / 1_000_000);
				return new SoundBuffer(d.toBuffer(), Pcm.format(d.sampleRate(), d.channels()));
			} catch (IOException | RuntimeException | LinkageError e) {
				fail(k.file(), e);
				throw new CompletionException(e);
			}
		}, POOL));
	}

	public static CompletableFuture<AudioStream> stream(Identifier path, boolean looping) {
		Ref ref = REFS.get(path);
		if (ref == null) {
			return CompletableFuture.failedFuture(new IOException("unknown sound " + path));
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				AudioStream s = new PcmAudioStream(() -> Decoders.open(ref.file(), ref.mono()), looping, ref.mono());
				ERRORS.remove(ref.file().toAbsolutePath().normalize());
				return s;
			} catch (IOException | RuntimeException | LinkageError e) {
				fail(ref.file(), e);
				throw new CompletionException(e);
			}
		}, POOL);
	}

	private static void fail(Path file, Throwable e) {
		Path key = file.toAbsolutePath().normalize();
		String msg = e.getMessage() != null ? e.getMessage() : e.toString();
		if (!msg.equals(ERRORS.put(key, msg))) {
			SoundChanger.LOGGER.warn("Can't play {}: {}", file, msg);
		}
	}

	/** Called when Minecraft empties its own sound cache (resource reload, sound device change). */
	public static void clearBuffers() {
		for (CompletableFuture<SoundBuffer> f : BUFFERS.values()) {
			f.thenAccept(SoundBuffer::discardAlBuffer);
		}
		BUFFERS.clear();
		ERRORS.clear();
	}
}
