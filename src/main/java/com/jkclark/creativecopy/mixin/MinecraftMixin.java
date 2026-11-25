package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.SurvivalSync;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void creativecopy$addQueuedPlacements(final CallbackInfo ci) {
		SurvivalSync.tick((Minecraft)(Object)this);
	}
}
