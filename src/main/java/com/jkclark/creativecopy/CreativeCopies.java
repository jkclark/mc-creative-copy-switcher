package com.jkclark.creativecopy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds, creates and opens creative copies of single-player worlds.
 *
 * <p>Each copy gets a small marker file in its world folder recording the folder of the world it was
 * copied from and its copy number. Copies are found through that marker rather than by display name,
 * so renaming a world (or having two worlds with the same name) doesn't break anything.
 */
public final class CreativeCopies {
	private static final Logger LOGGER = LoggerFactory.getLogger("creativecopy");
	private static final String MARKER_FILE = "creativecopy.json";
	private static final Set<String> NOT_COPIED = Set.of("session.lock", MARKER_FILE, ChangeTracker.JOURNAL_FILE);
	private static final long WORLD_CLOSE_TIMEOUT_MILLIS = 120_000;

	/** The id tells copies apart that reuse the number of a deleted one. */
	public record Marker(String sourceLevelId, int number, String id) {}

	public record Copy(String levelId, String name, int number) {}

	private CreativeCopies() {}

	/** Folder name of the world currently open in single-player, or null when not in a (non-LAN) single-player world. */
	public static @Nullable String currentLevelId(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || server.isPublished()) {
			return null;
		}

		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
	}

	public static Optional<Marker> readMarker(final Path worldDir) {
		Path markerFile = worldDir.resolve(MARKER_FILE);
		if (!Files.isRegularFile(markerFile)) {
			return Optional.empty();
		}

		try {
			JsonObject json = JsonParser.parseString(Files.readString(markerFile)).getAsJsonObject();
			String id = json.has("id") ? json.get("id").getAsString() : "";
			return Optional.of(new Marker(json.get("source").getAsString(), json.get("number").getAsInt(), id));
		} catch (Exception e) {
			LOGGER.warn("Ignoring unreadable marker file {}", markerFile, e);
			return Optional.empty();
		}
	}

	private static void writeMarker(final Path worldDir, final Marker marker) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("source", marker.sourceLevelId());
		json.addProperty("number", marker.number());
		json.addProperty("id", marker.id());
		Files.writeString(worldDir.resolve(MARKER_FILE), json.toString());
	}

	/** All creative copies of the given world, sorted by copy number. */
	public static List<Copy> findCopies(final LevelStorageSource levelSource, final String sourceLevelId) {
		List<Copy> copies = new ArrayList<>();
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(levelSource.getBaseDir(), Files::isDirectory)) {
			for (Path dir : dirs) {
				readMarker(dir)
					.filter(marker -> marker.sourceLevelId().equals(sourceLevelId))
					.ifPresent(marker -> copies.add(new Copy(dir.getFileName().toString(), readLevelName(dir), marker.number())));
			}
		} catch (IOException e) {
			LOGGER.error("Failed to list worlds in {}", levelSource.getBaseDir(), e);
		}

		copies.sort(Comparator.comparingInt(Copy::number));
		return copies;
	}

	public static Optional<Copy> mostRecentCopy(final LevelStorageSource levelSource, final String sourceLevelId) {
		List<Copy> copies = findCopies(levelSource, sourceLevelId);
		return copies.isEmpty() ? Optional.empty() : Optional.of(copies.getLast());
	}

	private static String readLevelName(final Path worldDir) {
		try {
			CompoundTag root = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
			return root.getCompoundOrEmpty("Data").getStringOr("LevelName", worldDir.getFileName().toString());
		} catch (IOException e) {
			return worldDir.getFileName().toString();
		}
	}

	/** Saves and leaves the current world, copies it, makes the copy creative and opens it. */
	public static void openInNewCopy(final Minecraft minecraft) {
		String sourceLevelId = currentLevelId(minecraft);
		if (sourceLevelId == null) {
			return;
		}

		String sourceName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
		LevelStorageSource levelSource = minecraft.getLevelSource();
		saveAndLeaveWorld(minecraft);
		minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("creativecopy.status.creating")));
		CompletableFuture.supplyAsync(() -> {
			waitForWorldToClose(levelSource, sourceLevelId);
			return createCopy(levelSource, sourceLevelId, sourceName);
		}, Util.ioPool()).whenCompleteAsync((copyLevelId, error) -> {
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				LOGGER.error("Failed to create creative copy of {}", sourceLevelId, cause);
				String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
				showError(minecraft, Component.translatable("creativecopy.error.copy_failed", reason));
			} else {
				openWorld(minecraft, copyLevelId);
			}
		}, minecraft);
	}

	/** Saves and leaves the current world, then opens the given world. */
	public static void switchToWorld(final Minecraft minecraft, final String levelId) {
		String currentLevelId = currentLevelId(minecraft);
		LevelStorageSource levelSource = minecraft.getLevelSource();
		saveAndLeaveWorld(minecraft);
		CompletableFuture.runAsync(() -> {
			if (currentLevelId != null) {
				waitForWorldToClose(levelSource, currentLevelId);
			}
		}, Util.ioPool()).whenCompleteAsync((ignored, error) -> {
			if (error == null && levelSource.levelExists(levelId)) {
				openWorld(minecraft, levelId);
			} else {
				if (error != null) {
					LOGGER.error("Gave up waiting for world {} to close", currentLevelId, error);
				}
				showError(minecraft, Component.translatable("creativecopy.error.source_missing"));
			}
		}, minecraft);
	}

	/**
	 * Blocks until the integrated server has finished saving the world and released its session lock.
	 *
	 * <p>In normal play {@link Minecraft#disconnectWithSavingScreen()} already waits for this, but we don't
	 * rely on it: disconnecting can be deferred (the test harness does this), and copying a world that's
	 * still open would produce a half-written copy.
	 */
	private static void waitForWorldToClose(final LevelStorageSource levelSource, final String levelId) {
		long deadline = System.currentTimeMillis() + WORLD_CLOSE_TIMEOUT_MILLIS;
		while (true) {
			try (LevelStorageSource.LevelStorageAccess ignored = levelSource.createAccess(levelId)) {
				return;
			} catch (IOException | OverlappingFileLockException e) {
				if (System.currentTimeMillis() > deadline) {
					throw new IllegalStateException("World " + levelId + " is still in use", e);
				}
			}

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
	}

	private static void saveAndLeaveWorld(final Minecraft minecraft) {
		if (minecraft.level != null) {
			minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
		}

		minecraft.disconnectWithSavingScreen();
	}

	private static void openWorld(final Minecraft minecraft, final String levelId) {
		minecraft.createWorldOpenFlows().openWorld(levelId, () -> minecraft.gui.setScreen(new SelectWorldScreen(new TitleScreen())));
	}

	private static void showError(final Minecraft minecraft, final Component message) {
		minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(new TitleScreen()), Component.translatable("creativecopy.error.title"), message));
	}

	/** Copies the world folder and returns the new copy's folder name. Runs off the main thread. */
	private static String createCopy(final LevelStorageSource levelSource, final String sourceLevelId, final String sourceName) {
		int number = findCopies(levelSource, sourceLevelId).stream().mapToInt(Copy::number).max().orElse(0) + 1;
		String copyName = sourceName + " - creative #" + number;

		try {
			String copyLevelId = FileUtil.findAvailableName(levelSource.getBaseDir(), copyName, "");
			// Holding both session locks guarantees nothing else is using either world while we copy.
			try (LevelStorageSource.LevelStorageAccess sourceAccess = levelSource.createAccess(sourceLevelId);
				LevelStorageSource.LevelStorageAccess copyAccess = levelSource.createAccess(copyLevelId)) {
				Path copyDir = levelSource.getLevelPath(copyLevelId);
				try {
					copyWorldFiles(levelSource.getLevelPath(sourceLevelId), copyDir);
					makeCreative(copyDir, copyName);
					ChangeTracker.createJournal(copyDir);
					writeMarker(copyDir, new Marker(sourceLevelId, number, UUID.randomUUID().toString().substring(0, 8)));
				} catch (IOException | RuntimeException e) {
					try {
						copyAccess.deleteLevel();
					} catch (IOException deleteError) {
						e.addSuppressed(deleteError);
					}

					throw e;
				}
			}

			LOGGER.info("Created creative copy \"{}\" of world {} in folder {}", copyName, sourceLevelId, copyLevelId);
			return copyLevelId;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void copyWorldFiles(final Path from, final Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
				Path relative = from.relativize(file);
				if (!NOT_COPIED.contains(relative.toString())) {
					Files.copy(file, to.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}

				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Renames the copy and switches both the world default and the saved player(s) to creative, with cheats on and hardcore off. */
	private static void makeCreative(final Path worldDir, final String name) throws IOException {
		for (String levelDataFile : List.of("level.dat", "level.dat_old")) {
			editNbt(worldDir.resolve(levelDataFile), root -> {
				CompoundTag data = root.getCompoundOrEmpty("Data");
				data.putString("LevelName", name);
				data.putInt("GameType", GameType.CREATIVE.getId());
				data.putBoolean("allowCommands", true);
				data.getCompound("difficulty_settings").ifPresent(difficulty -> difficulty.putBoolean("hardcore", false));
				root.put("Data", data);
			});
		}

		Path playerDataDir = worldDir.resolve("players/data");
		if (Files.isDirectory(playerDataDir)) {
			try (DirectoryStream<Path> playerFiles = Files.newDirectoryStream(playerDataDir, "*.{dat,dat_old}")) {
				for (Path playerFile : playerFiles) {
					editNbt(playerFile, player -> player.putInt("playerGameType", GameType.CREATIVE.getId()));
				}
			}
		}
	}

	private interface NbtEditor {
		void edit(CompoundTag tag);
	}

	private static void editNbt(final Path file, final NbtEditor editor) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}

		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		editor.edit(root);
		NbtIo.writeCompressed(root, file);
	}
}
