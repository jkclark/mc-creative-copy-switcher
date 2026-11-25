package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.ChangeTracker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;

/** Player actions, commands and edit tools all run outside the level's own tick, so what happens in it is the world's doing. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;)V")
	private void creativecopy$tickIsNatural(final BooleanSupplier haveTime, final Operation<Void> original) {
		ChangeTracker.runAs(true, () -> original.call(haveTime));
	}

	@WrapMethod(method = "tickNonPassenger")
	private void creativecopy$someEntitiesArent(final Entity entity, final Operation<Void> original) {
		if (entity instanceof FallingBlockEntity || entity instanceof PrimedTnt || entity instanceof ServerPlayer) {
			ChangeTracker.runAs(false, () -> original.call(entity));
		} else {
			original.call(entity);
		}
	}
}
