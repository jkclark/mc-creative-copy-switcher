package com.jkclark.creativecopy;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The "Open in Creative" submenu, opened from the pause menu. */
public class CreativeCopyScreen extends Screen {
	private static final int BUTTON_WIDTH = 204;
	private static final Component OPEN_IN_CREATIVE = Component.translatable("creativecopy.button.open_in_creative");
	private static final Component RETURN_TO_SURVIVAL = Component.translatable("creativecopy.button.return_to_survival");

	private final Screen parent;
	private final String sourceLevelId;

	public CreativeCopyScreen(final Screen parent, final String sourceLevelId) {
		super(Component.translatable("creativecopy.screen.title"));
		this.parent = parent;
		this.sourceLevelId = sourceLevelId;
	}

	/**
	 * The button added to the pause menu: "Open in Creative" in a normal world, "Return to Survival" in a
	 * creative copy, or nothing when not in single-player.
	 */
	public static @Nullable Button pauseMenuButton(final Minecraft minecraft, final Screen pauseScreen) {
		String levelId = CreativeCopies.currentLevelId(minecraft);
		if (levelId == null) {
			return null;
		}

		Optional<CreativeCopies.Marker> marker = CreativeCopies.readMarker(minecraft.getLevelSource().getLevelPath(levelId));
		if (marker.isPresent()) {
			String sourceLevelId = marker.get().sourceLevelId();
			Button button = Button.builder(RETURN_TO_SURVIVAL, b -> CreativeCopies.switchToWorld(minecraft, sourceLevelId)).width(BUTTON_WIDTH).build();
			if (!minecraft.getLevelSource().levelExists(sourceLevelId)) {
				button.active = false;
				button.setTooltip(Tooltip.create(Component.translatable("creativecopy.error.source_missing")));
			}

			return button;
		}

		return Button.builder(OPEN_IN_CREATIVE, b -> minecraft.gui.setScreen(new CreativeCopyScreen(pauseScreen, levelId))).width(BUTTON_WIDTH).build();
	}

	@Override
	protected void init() {
		LinearLayout layout = LinearLayout.vertical().spacing(4);
		layout.addChild(new StringWidget(this.title, this.font), settings -> settings.alignHorizontallyCenter().paddingBottom(8));
		layout.addChild(
			Button.builder(Component.translatable("creativecopy.button.new_copy"), b -> CreativeCopies.openInNewCopy(this.minecraft)).width(BUTTON_WIDTH).build()
		);

		Optional<CreativeCopies.Copy> mostRecent = CreativeCopies.mostRecentCopy(this.minecraft.getLevelSource(), this.sourceLevelId);
		Button recentButton = layout.addChild(
			Button.builder(
					Component.translatable("creativecopy.button.recent_copy"),
					b -> mostRecent.ifPresent(copy -> CreativeCopies.switchToWorld(this.minecraft, copy.levelId()))
				)
				.width(BUTTON_WIDTH)
				.build()
		);
		recentButton.active = mostRecent.isPresent();
		recentButton.setTooltip(
			Tooltip.create(
				mostRecent.map(copy -> Component.translatable("creativecopy.button.recent_copy.tooltip", copy.name()))
					.orElse(Component.translatable("creativecopy.button.recent_copy.none"))
			)
		);

		layout.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose()).width(BUTTON_WIDTH).build(), settings -> settings.paddingTop(8));
		layout.arrangeElements();
		FrameLayout.alignInRectangle(layout, 0, 0, this.width, this.height, 0.5F, 0.25F);
		layout.visitWidgets(this::addRenderableWidget);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
