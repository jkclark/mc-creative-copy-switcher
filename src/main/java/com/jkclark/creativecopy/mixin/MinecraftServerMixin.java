package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.ChangeTracker;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "loadLevel", at = @At("HEAD"))
	private void creativecopy$startTracking(final CallbackInfo ci) {
		ChangeTracker.open((MinecraftServer)(Object)this);
	}

	@Inject(method = "tickServer", at = @At("TAIL"))
	private void creativecopy$flushChanges(final CallbackInfo ci) {
		ChangeTracker.flush((MinecraftServer)(Object)this);
	}

	/** Before the session lock is released, so the journal is closed by the time another world can be opened. */
	@Inject(
		method = "stopServer",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;close()V")
	)
	private void creativecopy$stopTracking(final CallbackInfo ci) {
		ChangeTracker.close();
	}
}
