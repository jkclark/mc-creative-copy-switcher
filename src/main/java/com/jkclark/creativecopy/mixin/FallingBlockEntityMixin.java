package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.ChangeTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
	/** The landing is tracked, so the block leaving its old place has to be too. */
	@WrapOperation(
		method = "fall",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z")
	)
	private static boolean creativecopy$trackFall(final Level level, final BlockPos pos, final BlockState state, final Operation<Boolean> original) {
		boolean[] result = new boolean[1];
		ChangeTracker.runAs(false, () -> result[0] = original.call(level, pos, state));
		return result[0];
	}
}
