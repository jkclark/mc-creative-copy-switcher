package com.jkclark.creativecopy.test;

import com.jkclark.creativecopy.CreativeCopies;
import com.jkclark.creativecopy.CreativeCopyScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * Drives the real pause menu: survival (hardcore) world -> new copy #1 -> back to survival -> most recent
 * copy (#1) -> back -> new copy #2 -> back -> most recent copy (#2).
 */
public class CreativeCopyClientGameTest implements FabricClientGameTest {
	private static final BlockPos MARKER_POS = new BlockPos(0, 100, 0);

	@Override
	public void runTest(final ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
			.adjustSettings(settings -> {
				settings.setName("Forever world");
				settings.setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE);
			})
			.create()) {
			singleplayer.getConnection().waitForChunksRender();
			singleplayer.getServer().runCommand("setblock 0 100 0 minecraft:gold_block");
			context.waitTicks(5);
			String survivalId = context.computeOnClient(CreativeCopies::currentLevelId);
			assertWorld(context, "Forever world", GameType.SURVIVAL, true);

			openPauseMenu(context);
			context.takeScreenshot("creativecopy-pause-menu-survival");
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.takeScreenshot("creativecopy-submenu-no-copies");
			assertButtonActive(context, "Open in most recent copy", false);
			context.clickScreenButton("Open in new copy");
			String copy1 = waitForWorldOtherThan(context, survivalId);
			assertWorld(context, "Forever world - creative #1", GameType.CREATIVE, false);
			context.takeScreenshot("creativecopy-in-copy-1");

			openPauseMenu(context);
			context.takeScreenshot("creativecopy-pause-menu-copy");
			context.clickScreenButton("Return to Survival");
			waitForWorld(context, survivalId);
			assertWorld(context, "Forever world", GameType.SURVIVAL, true);

			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.takeScreenshot("creativecopy-submenu-with-copy");
			context.clickScreenButton("Open in most recent copy");
			waitForWorld(context, copy1);
			assertWorld(context, "Forever world - creative #1", GameType.CREATIVE, false);

			openPauseMenu(context);
			context.clickScreenButton("Return to Survival");
			waitForWorld(context, survivalId);

			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.clickScreenButton("Open in new copy");
			String copy2 = waitForWorldOtherThan(context, survivalId);
			if (copy2.equals(copy1)) {
				throw new AssertionError("Second copy reused the first copy's folder");
			}
			assertWorld(context, "Forever world - creative #2", GameType.CREATIVE, false);

			openPauseMenu(context);
			context.clickScreenButton("Return to Survival");
			waitForWorld(context, survivalId);
			openPauseMenu(context);
			context.clickScreenButton("Open in Creative");
			context.waitForScreen(CreativeCopyScreen.class);
			context.clickScreenButton("Open in most recent copy");
			waitForWorld(context, copy2);
			assertWorld(context, "Forever world - creative #2", GameType.CREATIVE, false);

			// Finish in the original world so the test harness can close it normally.
			openPauseMenu(context);
			context.clickScreenButton("Return to Survival");
			waitForWorld(context, survivalId);
		}
	}

	private static void openPauseMenu(final ClientGameTestContext context) {
		context.setScreen(() -> new PauseScreen(true));
		context.waitForScreen(PauseScreen.class);
	}

	private static String waitForWorldOtherThan(final ClientGameTestContext context, final String levelId) {
		context.waitFor(mc -> isPlaying(mc) && !levelId.equals(CreativeCopies.currentLevelId(mc)), 20 * 120);
		context.waitTicks(20);
		return context.computeOnClient(CreativeCopies::currentLevelId);
	}

	private static void waitForWorld(final ClientGameTestContext context, final String levelId) {
		context.waitFor(mc -> isPlaying(mc) && levelId.equals(CreativeCopies.currentLevelId(mc)), 20 * 120);
		context.waitTicks(20);
	}

	private static boolean isPlaying(final Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		return mc.player != null && mc.level != null && mc.gui.screen() == null && server != null && !server.getPlayerList().getPlayers().isEmpty();
	}

	private static void assertWorld(final ClientGameTestContext context, final String name, final GameType playerMode, final boolean hardcore) {
		context.runOnClient(mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			check("world name", name, server.getWorldData().getLevelName());
			check("player game mode", playerMode, player.gameMode.getGameModeForPlayer());
			check("hardcore", hardcore, server.getWorldData().isHardcore());
			if (playerMode == GameType.CREATIVE) {
				check("default game mode", GameType.CREATIVE, server.getWorldData().getGameType());
				check("cheats", true, server.getWorldData().isAllowCommands());
			}
			// Read from the client's level: the server thread is paused while test code runs, so querying
			// the server's level from here would deadlock.
			check("marker block copied", true, mc.level.getBlockState(MARKER_POS).is(Blocks.GOLD_BLOCK));
		});
	}

	private static void assertButtonActive(final ClientGameTestContext context, final String label, final boolean active) {
		context.runOnClient(mc -> {
			boolean found = mc.gui.screen().children().stream()
				.anyMatch(child -> child instanceof net.minecraft.client.gui.components.Button button
					&& button.getMessage().getString().equals(label)
					&& button.active == active);
			if (!found) {
				throw new AssertionError("Expected button '" + label + "' with active=" + active);
			}
		});
	}

	private static void check(final String what, final Object expected, final Object actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}
}
