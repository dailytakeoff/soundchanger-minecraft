package io.github.dailytakeoff.soundchanger.test;

import io.github.dailytakeoff.soundchanger.SoundChanger;
import io.github.dailytakeoff.soundchanger.audio.Decoders;
import io.github.dailytakeoff.soundchanger.audio.Pcm;
import io.github.dailytakeoff.soundchanger.audio.PcmSource;
import io.github.dailytakeoff.soundchanger.gui.FileBrowserScreen;
import io.github.dailytakeoff.soundchanger.gui.PackEditorScreen;
import io.github.dailytakeoff.soundchanger.gui.PacksScreen;
import io.github.dailytakeoff.soundchanger.pack.PackManager;
import io.github.dailytakeoff.soundchanger.pack.SoundPack;
import io.github.dailytakeoff.soundchanger.sound.CustomAudio;
import io.github.dailytakeoff.soundchanger.sound.SoundInjector;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a sound pack with every supported format, checks that each decodes and that the game picks the
 * changed sounds up, then photographs the menus (run/clientGameTest/screenshots).
 * Results are logged as "CHECK OK"/"CHECK FAIL" lines.
 */
public class SoundChangerGameTest implements FabricClientGameTest {
	private static final Identifier HARP = Identifier.withDefaultNamespace("block.note_block.harp");
	private static final Identifier CHICKEN = Identifier.withDefaultNamespace("entity.chicken.ambient");
	private static final Identifier COW = Identifier.withDefaultNamespace("entity.cow.ambient");
	private static final Identifier CREEPER = Identifier.withDefaultNamespace("entity.creeper.primed");
	private static final Identifier MUSIC = Identifier.withDefaultNamespace("music.menu");

	private int failures;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1280, 720);
		Path pack = PackManager.root().resolve("CI Test");
		try {
			buildPack(pack);
		} catch (IOException e) {
			check("build test pack", false, e.toString());
		}
		ctx.runOnClient(mc -> PackManager.reload());
		ctx.waitTicks(2);

		decodeAll(pack);
		ctx.runOnClient(this::checkEvents);
		ctx.runOnClient(this::checkPlayback);

		// menus
		ctx.runOnClient(mc -> mc.gui.setScreen(new PacksScreen(null)));
		ctx.waitTicks(10);
		ctx.takeScreenshot("01_packs");
		ctx.runOnClient(mc -> {
			SoundPack p = PackManager.find("CI Test");
			PackEditorScreen editor = new PackEditorScreen(new PacksScreen(null), p);
			mc.gui.setScreen(editor);
			editor.selectSound(CHICKEN);
		});
		ctx.waitTicks(10);
		ctx.takeScreenshot("02_editor_chicken");
		ctx.runOnClient(mc -> {
			if (mc.gui.screen() instanceof PackEditorScreen e) {
				e.selectSound(CREEPER);
			}
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("03_editor_creeper");
		ctx.runOnClient(mc -> {
			SoundPack p = PackManager.find("CI Test");
			PackEditorScreen editor = new PackEditorScreen(new PacksScreen(null), p);
			mc.gui.setScreen(editor);
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("04_editor_nothing_selected");
		ctx.runOnClient(mc -> mc.gui.setScreen(new FileBrowserScreen(new PacksScreen(null), path -> {
		})));
		ctx.waitTicks(5);
		ctx.takeScreenshot("05_file_browser");
		// smaller window = bigger GUI scale: does everything still fit?
		ctx.getInput().resizeWindow(854, 480);
		ctx.runOnClient(mc -> {
			SoundPack p = PackManager.find("CI Test");
			PackEditorScreen editor = new PackEditorScreen(new PacksScreen(null), p);
			mc.gui.setScreen(editor);
			editor.selectSound(CHICKEN);
		});
		ctx.waitTicks(10);
		ctx.takeScreenshot("06_editor_small_window");
		ctx.runOnClient(mc -> mc.gui.setScreen(new PacksScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("07_packs_small_window");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));

		SoundChanger.LOGGER.info("CHECK SUMMARY: {}", failures == 0 ? "all passed" : failures + " failed");
	}

	// ---------------------------------------------------------------- test pack

	private void buildPack(Path pack) throws IOException {
		Path files = pack.resolve("files");
		Path replace = pack.resolve("replace");
		Path add = pack.resolve("add");
		Files.createDirectories(files);
		Files.createDirectories(replace);
		Files.createDirectories(add);
		Path wav = files.resolve("tone.wav");
		writeSineWav(wav, 44100, 2, 1.0);
		ffmpeg(wav, files.resolve("tone.mp3"), "-c:a", "libmp3lame", "-b:a", "128k");
		ffmpeg(wav, files.resolve("tone_mono22k.mp3"), "-ac", "1", "-ar", "22050", "-c:a", "libmp3lame");
		ffmpeg(wav, files.resolve("tone.flac"), "-c:a", "flac");
		ffmpeg(wav, files.resolve("tone24.flac"), "-c:a", "flac", "-sample_fmt", "s32");
		ffmpeg(wav, files.resolve("tone.ogg"), "-c:a", "libvorbis");
		ffmpeg(wav, files.resolve("tone.opus"), "-c:a", "libopus");
		ffmpeg(wav, files.resolve("tone.m4a"), "-c:a", "aac");
		ffmpeg(wav, files.resolve("tone.aiff"), "-c:a", "pcm_s16be");
		ffmpeg(wav, files.resolve("tone24.wav"), "-c:a", "pcm_s24le");
		ffmpeg(wav, files.resolve("tonef32.wav"), "-c:a", "pcm_f32le");
		ffmpeg(wav, files.resolve("tone8mono.wav"), "-ac", "1", "-c:a", "pcm_u8");

		// drop-in folders
		Files.copy(files.resolve("tone.mp3"), replace.resolve("block.note_block.harp.mp3"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Path chicken = add.resolve("entity.chicken.ambient");
		Files.createDirectories(chicken);
		Files.copy(files.resolve("tone.flac"), chicken.resolve("a.flac"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(files.resolve("tone.ogg"), chicken.resolve("b.ogg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.createDirectories(replace.resolve("entity.cow.ambient")); // empty = mute
		Files.writeString(replace.resolve("Not A Sound!.wav"), "x"); // should be reported as a problem

		// editor-style entries
		Files.writeString(pack.resolve("pack.json"), """
				{
				  "description": "Made by the automated test",
				  "sounds": {
				    "minecraft:entity.creeper.primed": { "mode": "replace", "files": ["files/tone.wav", "files/tone.m4a", {"file": "files/tone.opus", "volume": 0.5}] },
				    "minecraft:music.menu": { "mode": "replace", "files": ["files/tone.mp3"] }
				  }
				}
				""");
	}

	private static void writeSineWav(Path file, int rate, int channels, double seconds) throws IOException {
		int frames = (int) (rate * seconds);
		ByteBuffer data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < frames; i++) {
			short s = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12000);
			for (int c = 0; c < channels; c++) {
				data.putShort(s);
			}
		}
		ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		h.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
		h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
				.putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
		h.put("data".getBytes()).putInt(data.capacity());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(h.array());
		out.write(data.array());
		Files.write(file, out.toByteArray());
	}

	private void ffmpeg(Path in, Path out, String... args) {
		List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-i", in.toString()));
		cmd.addAll(List.of(args));
		cmd.add(out.toString());
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String log = new String(p.getInputStream().readAllBytes());
			if (p.waitFor() != 0) {
				check("make " + out.getFileName(), false, log.trim());
			}
		} catch (IOException e) {
			check("make " + out.getFileName(), false, e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- checks

	private void decodeAll(Path pack) {
		Path files = pack.resolve("files");
		try (var s = Files.list(files)) {
			for (Path f : s.sorted().toList()) {
				for (boolean mono : new boolean[] {false, true}) {
					try (PcmSource src = Decoders.open(f, mono)) {
						Decoders.Format format = Decoders.detect(f);
						Pcm.Decoded d = Pcm.readAll(src, mono, 60);
						boolean ok = d.seconds() > 0.9 && d.seconds() < 1.2 && d.channels() == (mono ? 1 : Pcm.outChannels(src.channels(), false))
								&& peak(d) > 8000;
						check(String.format(Locale.ROOT, "decode %-18s %-16s %s: %.3f s, %d Hz, %d ch, peak %d", f.getFileName(), format.label,
								mono ? "mono" : "orig", d.seconds(), d.sampleRate(), d.channels(), peak(d)), ok, "");
					} catch (IOException | RuntimeException e) {
						check("decode " + f.getFileName() + (mono ? " mono" : ""), false, e.toString());
					}
				}
			}
		} catch (IOException e) {
			check("list test files", false, e.toString());
		}
	}

	private static int peak(Pcm.Decoded d) {
		int peak = 0;
		for (int i = 0; i < d.length(); i++) {
			peak = Math.max(peak, Math.abs(d.samples()[i]));
		}
		return peak;
	}

	private void checkEvents(Minecraft mc) {
		SoundManager sm = mc.getSoundManager();
		SoundPack p = PackManager.find("CI Test");
		check("pack found", p != null, "");
		if (p == null) {
			return;
		}
		check("problem reported for bad name", p.problems().stream().anyMatch(s -> s.contains("Not A Sound!")), p.problems().toString());
		check("game has sounds", sm.getAvailableSounds().size() > 500, "only " + sm.getAvailableSounds().size());

		WeighedSoundEvents harp = sm.getSoundEvent(HARP);
		Sound h = harp == null ? null : harp.getSound(RandomSource.create());
		check("harp replaced by our file", h != null && CustomAudio.owns(h.getLocation()), String.valueOf(h));
		check("harp is mono (placed in world)", SoundInjector.autoMono(HARP), "");

		WeighedSoundEvents vanillaChicken = SoundInjector.vanilla(sm, CHICKEN);
		WeighedSoundEvents chicken = sm.getSoundEvent(CHICKEN);
		check("chicken keeps vanilla + 2", vanillaChicken != null && chicken != null && chicken.getWeight() == vanillaChicken.getWeight() + 2,
				(vanillaChicken == null ? "?" : vanillaChicken.getWeight()) + " -> " + (chicken == null ? "?" : chicken.getWeight()));
		int ours = 0;
		RandomSource r = RandomSource.create(1);
		for (int i = 0; i < 400; i++) {
			if (CustomAudio.owns(chicken.getSound(r).getLocation())) {
				ours++;
			}
		}
		check("chicken picks ours sometimes and vanilla sometimes", ours > 0 && ours < 400, ours + "/400 were ours");
		check("chicken subtitle kept", chicken.getSubtitle() != null && vanillaChicken.getSubtitle() != null
				&& chicken.getSubtitle().getString().equals(vanillaChicken.getSubtitle().getString()), String.valueOf(chicken.getSubtitle()));

		WeighedSoundEvents cow = sm.getSoundEvent(COW);
		check("cow muted", cow != null && cow.getSound(RandomSource.create()) == SoundManager.INTENTIONALLY_EMPTY_SOUND, "");

		WeighedSoundEvents creeper = sm.getSoundEvent(CREEPER);
		check("creeper has 3 of ours", creeper != null && creeper.getWeight() == 3, creeper == null ? "null" : "weight " + creeper.getWeight());

		WeighedSoundEvents music = sm.getSoundEvent(MUSIC);
		Sound m = music == null ? null : music.getSound(RandomSource.create());
		check("menu music streams and stays stereo", m != null && m.shouldStream() && !SoundInjector.autoMono(MUSIC), String.valueOf(m));

		// switching the pack off brings vanilla back
		io.github.dailytakeoff.soundchanger.pack.PackManager.setEnabled(p, false);
		WeighedSoundEvents off = sm.getSoundEvent(HARP);
		Sound o = off == null ? null : off.getSound(RandomSource.create());
		check("pack off -> vanilla harp", o != null && !CustomAudio.owns(o.getLocation()), String.valueOf(o));
		io.github.dailytakeoff.soundchanger.pack.PackManager.setEnabled(p, true);
	}

	private void checkPlayback(Minecraft mc) {
		SoundManager sm = mc.getSoundManager();
		WeighedSoundEvents creeper = sm.getSoundEvent(CREEPER);
		RandomSource r = RandomSource.create(7);
		java.util.Set<Identifier> seen = new java.util.HashSet<>();
		for (int i = 0; i < 60 && creeper != null; i++) {
			Sound s = creeper.getSound(r);
			if (!seen.add(s.getPath())) {
				continue;
			}
			try {
				if (s.shouldStream()) {
					AudioStream st = CustomAudio.stream(s.getPath(), false).get(20, java.util.concurrent.TimeUnit.SECONDS);
					ByteBuffer b = st.read(44100);
					check("stream " + s.getPath(), b != null && b.remaining() > 0, "");
					st.close();
				} else {
					var buf = CustomAudio.completeBuffer(s.getPath()).get(20, java.util.concurrent.TimeUnit.SECONDS);
					check("buffer " + s.getPath() + " (" + buf.size() + " bytes, " + buf.format() + ")", buf.size() > 40000, "");
				}
			} catch (Exception e) {
				check("load " + s.getPath(), false, e.toString());
			}
		}
		check("creeper variants loaded", seen.size() == 3, seen.size() + " seen");

		// looping stream restarts the file
		WeighedSoundEvents music = sm.getSoundEvent(MUSIC);
		Sound m = music == null ? null : music.getSound(RandomSource.create());
		if (m != null) {
			try {
				AudioStream st = CustomAudio.stream(m.getPath(), true).get(20, java.util.concurrent.TimeUnit.SECONDS);
				long total = 0;
				for (int i = 0; i < 6; i++) {
					ByteBuffer b = st.read(48000 * 4 / 2);
					total += b == null ? 0 : b.remaining();
				}
				st.close();
				// 1 s file, 3 s asked for: looping must keep going
				check("looping stream keeps going (" + total + " bytes)", total >= 48000 * 4 * 2, "");
			} catch (Exception e) {
				check("looping stream", false, e.toString());
			}
		}
	}

	private void check(String what, boolean ok, String detail) {
		if (!ok) {
			failures++;
		}
		SoundChanger.LOGGER.info("CHECK {} {}{}", ok ? "OK  " : "FAIL", what, detail.isEmpty() ? "" : " — " + detail);
	}
}
