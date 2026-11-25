package com.jkclark.creativecopy.mixin;

import com.jkclark.creativecopy.CreativeCopyScreen;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(final Component title) {
		super(title);
	}

	/** Adds our full-width buttons to the pause menu grid, just above "Save and Quit to Title". */
	@Inject(
		method = "createPauseMenu",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/CommonComponents;disconnectButtonLabel(Z)Lnet/minecraft/network/chat/Component;")
	)
	private void creativecopy$addButtons(final CallbackInfo ci, @Local final GridLayout.RowHelper helper) {
		for (Button button : CreativeCopyScreen.pauseMenuButtons(this.minecraft, this)) {
			helper.addChild(button, 2);
		}
	}
}
