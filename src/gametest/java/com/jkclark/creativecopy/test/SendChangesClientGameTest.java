package com.jkclark.creativecopy.test;

import com.jkclark.creativecopy.CreativeCopies;
import com.jkclark.creativecopy.CreativeCopyScreen;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Builds in a creative copy, sends the changes to survival, and checks what Litematica shows there. */
public class SendChangesClientGameTest implements FabricClientGameTest {
	private static final int TIMEOUT = 20 * 60;

	@Override
	public void runTest(final ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setName("Sync world")).create()) {
			singleplayer.getConnection().waitForChunksRender();
			String survivalId = context.computeOnClient(CreativeCopies::currentLevelId);
			run(context, "setblock 4 100 4 gold_block", "setblock 5 100 4 iron_block", "setblock 4 99 8 stone", "gamerule random_tick_speed 0");

			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.clickScreenButton("Open in new copy");
			waitForOtherWorld(context, survivalId);

			// Nothing changed yet.
			openPauseMenu(context);
			context.takeScreenshot("creativecopy-pause-menu-send");
			context.clickScreenButton("Send Changes to Survival");
			context.waitForScreen(AlertScreen.class);
			context.setScreen(() -> null);

			// Something built in survival after the copy was made must not show up as a difference.
			openPauseMenu(context);
			context.clickScreenButton("Return to Survival");
			waitForWorld(context, survivalId);
			run(context, "setblock 6 101 5 diamond_block");
			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.clickScreenButton("Open in most recent copy");
			waitForOtherWorld(context, survivalId);

			run(
				context,
				"setblock 4 100 4 air",
				"fill 7 100 4 8 102 6 oak_planks",
				"setblock 4 102 6 oak_stairs[facing=west,half=top]",
				"setblock 4 103 8 sand",
				"setblock 5 103 4 stone",
				"setblock 5 103 4 air",
				"setblock 8 103 5 water",
				"setblock 7 103 4 chest"
			);
			context.waitTicks(60);
			send(context, survivalId);
			context.runOnClient(mc -> {
				check("placements", 1, placements().size());
				check("origin", new BlockPos(4, 100, 4), placements().getFirst().getOrigin());
				check("selected", placements().getFirst(), DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement());
				check("removed block", Blocks.AIR, schematic(4, 100, 4));
				check("placed block", Blocks.OAK_PLANKS, schematic(8, 102, 6));
				check("placed stairs", Blocks.OAK_STAIRS, schematic(4, 102, 6));
				check("sand where it landed", Blocks.SAND, schematic(4, 100, 8));
				check("sand not where it was placed", Blocks.AIR, schematic(4, 103, 8));
				check("placed and removed again", Blocks.AIR, schematic(5, 103, 4));
				check("water source", Blocks.WATER, schematic(8, 103, 5));
				check("chest", Blocks.CHEST, schematic(7, 103, 4));
				check("unchanged block", Blocks.IRON_BLOCK, schematic(5, 100, 4));
				check("later survival block", Blocks.DIAMOND_BLOCK, schematic(6, 101, 5));
				check("survival world untouched", Blocks.GOLD_BLOCK, mc.level.getBlockState(new BlockPos(4, 100, 4)).getBlock());
				check("survival world untouched", Blocks.AIR, mc.level.getBlockState(new BlockPos(8, 102, 6)).getBlock());
			});
			context.waitTicks(20);
			context.takeScreenshot("creativecopy-sent-changes");

			// Sending again after reopening the copy replaces the placement and still knows the earlier changes.
			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.clickScreenButton("Open in most recent copy");
			waitForOtherWorld(context, survivalId);
			run(context, "setblock 2 100 4 emerald_block", "setblock 4 100 4 gold_block");
			send(context, survivalId);
			context.runOnClient(mc -> {
				check("placements", 1, placements().size());
				check("new change", Blocks.EMERALD_BLOCK, schematic(2, 100, 4));
				check("earlier change", Blocks.OAK_PLANKS, schematic(8, 102, 6));
				check("undone change", Blocks.GOLD_BLOCK, schematic(4, 100, 4));
			});
		}
	}

	private static List<SchematicPlacement> placements() {
		return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
	}

	private static Block schematic(final int x, final int y, final int z) {
		return SchematicWorldHandler.getSchematicWorld().getBlockState(new BlockPos(x, y, z)).getBlock();
	}

	private static void send(final ClientGameTestContext context, final String survivalId) {
		openPauseMenu(context);
		context.clickScreenButton("Send Changes to Survival");
		waitForWorld(context, survivalId);
		context.waitTicks(40);
	}

	/** Runs commands on whichever world is open now (the test's own server context stays with the first one). */
	private static void run(final ClientGameTestContext context, final String... commands) {
		context.runOnClient(mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				for (String command : commands) {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
				}
			});
		});
		context.waitTicks(10);
	}

	private static void openPauseMenu(final ClientGameTestContext context) {
		context.setScreen(() -> new PauseScreen(true));
		context.waitForScreen(PauseScreen.class);
	}

	private static void waitForOtherWorld(final ClientGameTestContext context, final String levelId) {
		context.waitFor(mc -> isPlaying(mc) && !levelId.equals(CreativeCopies.currentLevelId(mc)), TIMEOUT);
		context.waitTicks(40);
	}

	private static void waitForWorld(final ClientGameTestContext context, final String levelId) {
		context.waitFor(mc -> isPlaying(mc) && levelId.equals(CreativeCopies.currentLevelId(mc)), TIMEOUT);
		context.waitTicks(40);
	}

	private static boolean isPlaying(final Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		return mc.player != null && mc.level != null && mc.gui.screen() == null && server != null && !server.getPlayerList().getPlayers().isEmpty();
	}

	private static void check(final String what, final Object expected, final Object actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}
}
