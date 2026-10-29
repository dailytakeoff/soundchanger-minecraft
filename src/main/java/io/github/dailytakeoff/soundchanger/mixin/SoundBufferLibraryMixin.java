package io.github.dailytakeoff.soundchanger.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import io.github.dailytakeoff.soundchanger.sound.CustomAudio;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Sounds in the soundchanger namespace are files on disk in any format: decode them ourselves. */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void soundchanger$buffer(Identifier path, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (CustomAudio.owns(path)) {
			cir.setReturnValue(CustomAudio.completeBuffer(path));
		}
	}

	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void soundchanger$stream(Identifier path, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (CustomAudio.owns(path)) {
			cir.setReturnValue(CustomAudio.stream(path, looping));
		}
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void soundchanger$clear(CallbackInfo ci) {
		CustomAudio.clearBuffers();
	}
}
