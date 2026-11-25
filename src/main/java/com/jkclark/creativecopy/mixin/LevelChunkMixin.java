package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.ChangeTracker;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	@ModifyExpressionValue(
		method = "setBlockState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"
		)
	)
	private BlockState creativecopy$trackChange(
		final BlockState oldState, @Local(argsOnly = true) final BlockPos pos, @Local(argsOnly = true) final BlockState newState
	) {
		if (this.level instanceof ServerLevel serverLevel) {
			ChangeTracker.onBlockChanged(serverLevel, pos, oldState, newState);
		}

		return oldState;
	}

	@WrapMethod(method = "postProcessGeneration")
	private void creativecopy$generationIsNatural(final ServerLevel level, final Operation<Void> original) {
		ChangeTracker.runAs(true, () -> original.call(level));
	}
}
