package io.github.dailytakeoff.soundchanger.mixin;

import io.github.dailytakeoff.soundchanger.pack.PackManager;
import io.github.dailytakeoff.soundchanger.sound.SoundInjector;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	/** Every sound the game plays is looked up here: hand out the pack version when a pack changes it. */
	@Inject(method = "getSoundEvent", at = @At("RETURN"), cancellable = true)
	private void soundchanger$override(Identifier event, CallbackInfoReturnable<WeighedSoundEvents> cir) {
		if (!PackManager.hasPlans()) {
			return;
		}
		WeighedSoundEvents vanilla = cir.getReturnValue();
		WeighedSoundEvents changed = SoundInjector.apply(event, vanilla);
		if (changed != vanilla) {
			cir.setReturnValue(changed);
		}
	}

	/** F3+T / resource pack changes: read the sound packs from disk again too. */
	@Inject(method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
			at = @At("HEAD"))
	private void soundchanger$reloadPacks(CallbackInfo ci) {
		PackManager.reload();
	}
}
