package com.jkclark.creativecopy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.FileNameUtils;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.malilib.util.data.tag.util.DataTypeUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends what was changed in a creative copy back to its survival world, as a Litematica placement.
 *
 * <p>A schematic per changed dimension is written while the copy is still open. The placements can only be
 * added once Litematica has loaded the survival world's dimension, so they're queued in a file and added by
 * {@link #tick} when the player gets there.
 */
public final class SurvivalSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("creativecopy");
	/** Properties that change by using the world rather than building in it. */
	private static final Set<String> TRANSIENT_PROPERTIES = Set.of(
		"open", "powered", "power", "lit", "triggered", "extended", "enabled", "occupied", "has_record", "unstable", "age", "stage", "moisture", "distance"
	);

	private static @Nullable ClientLevel checkedLevel;

	private record Placement(String dimension, Path file, String name, BlockPos origin, int blocks, boolean remove) {}

	private SurvivalSync() {}

	/** Writes the schematics, then switches to the survival world. */
	public static void sendChanges(final Minecraft minecraft, final Screen pauseScreen, final CreativeCopies.Marker marker) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}

		LevelStorageSource levelSource = minecraft.getLevelSource();
		Path survivalDir = levelSource.getLevelPath(marker.sourceLevelId());
		Path schematicDir = DataManager.getSchematicsBaseDirectory().resolve("creative-copies");
		String author = minecraft.getUser().getName();
		minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("creativecopy.status.sending")));
		server.submit(() -> {
			try {
				return createSchematics(server, survivalDir, schematicDir, author, marker);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).whenCompleteAsync((placements, error) -> {
			try {
				if (error != null) {
					throw error;
				}

				if (placements.stream().allMatch(Placement::remove)) {
					queue(marker.sourceLevelId(), placements);
					showMessage(minecraft, pauseScreen, Component.translatable("creativecopy.sync.no_changes"));
					return;
				}

				queue(marker.sourceLevelId(), placements);
				CreativeCopies.switchToWorld(minecraft, marker.sourceLevelId());
			} catch (Throwable e) {
				Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
				LOGGER.error("Failed to send changes to {}", marker.sourceLevelId(), cause);
				String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
				showMessage(minecraft, pauseScreen, Component.translatable("creativecopy.sync.failed", reason));
			}
		}, minecraft);
	}

	private static void showMessage(final Minecraft minecraft, final Screen returnTo, final Component message) {
		minecraft.gui.setScreen(
			new AlertScreen(() -> minecraft.gui.setScreen(returnTo), Component.translatable("creativecopy.error.title"), message)
		);
	}

	/** Runs on the (paused) server thread, so it can read the copy's blocks. */
	private static List<Placement> createSchematics(
		final IntegratedServer server, final Path survivalDir, final Path schematicDir, final String author, final CreativeCopies.Marker marker
	) throws IOException {
		ChangeTracker tracker = ChangeTracker.get(server);
		if (tracker == null) {
			throw new IOException("changes in this copy aren't being tracked");
		}

		String copyName = server.getWorldData().getLevelName();
		List<Placement> placements = new ArrayList<>();
		for (Map.Entry<ResourceKey<Level>, Long2ObjectLinkedOpenHashMap<BlockState>> entry : tracker.originals().entrySet()) {
			ServerLevel level = server.getLevel(entry.getKey());
			if (level == null) {
				continue;
			}

			String dimension = entry.getKey().identifier().toString();
			String fileName = schematicFileName(copyName + " " + entry.getKey().identifier().getPath().replace('/', '_') + " " + marker.id());
			Path file = schematicDir.resolve(fileName);
			Long2ObjectLinkedOpenHashMap<BlockState> changes = changes(level, entry.getValue());
			if (changes.isEmpty()) {
				if (Files.exists(file)) {
					placements.add(new Placement(dimension, file, copyName, BlockPos.ZERO, 0, true));
				}

				continue;
			}

			BlockPos origin = writeSchematic(level, changes, survivalDir, schematicDir, fileName, copyName, author);
			placements.add(new Placement(dimension, file, copyName, origin, changes.size(), false));
			LOGGER.info("Wrote {} changed blocks in {} to {}", changes.size(), dimension, file);
		}

		return placements;
	}

	private static String schematicFileName(final String name) {
		// The same clean-up Litematica does when writing the file.
		return FileNameUtils.generateSafeFileName(FileNameUtils.generateSimpleUnicodeSafeFileName(name) + ".litematic");
	}

	/** The current state of every tracked block that ended up different from what it was. */
	private static Long2ObjectLinkedOpenHashMap<BlockState> changes(final ServerLevel level, final Long2ObjectLinkedOpenHashMap<BlockState> originals) {
		Long2ObjectLinkedOpenHashMap<BlockState> changes = new Long2ObjectLinkedOpenHashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Long2ObjectMap.Entry<BlockState> entry : originals.long2ObjectEntrySet()) {
			BlockState current = withoutFlow(level.getBlockState(pos.set(entry.getLongKey())));
			if (isChange(withoutFlow(entry.getValue()), current)) {
				changes.put(entry.getLongKey(), current);
			}
		}

		return changes;
	}

	private static BlockState withoutFlow(final BlockState state) {
		return state.getBlock() instanceof LiquidBlock && !state.getFluidState().isSource() ? Blocks.AIR.defaultBlockState() : state;
	}

	private static boolean isChange(final BlockState original, final BlockState current) {
		if (original == current || original.isAir() && current.isAir()) {
			return false;
		}

		if (original.getBlock() != current.getBlock()) {
			return true;
		}

		for (Property<?> property : original.getProperties()) {
			if (!TRANSIENT_PROPERTIES.contains(property.getName()) && !original.getValue(property).equals(current.getValue(property))) {
				return true;
			}
		}

		return false;
	}

	private static BlockPos writeSchematic(
		final ServerLevel level,
		final Long2ObjectLinkedOpenHashMap<BlockState> changes,
		final Path survivalDir,
		final Path schematicDir,
		final String fileName,
		final String name,
		final String author
	) throws IOException {
		List<BlockPos> positions = new ArrayList<>(changes.size());
		changes.keySet().forEach(pos -> positions.add(BlockPos.of(pos)));
		List<ChangeRegions.Region> regions = ChangeRegions.group(positions);

		AreaSelection area = new AreaSelection();
		area.setName(name);
		BlockPos origin = regions.getFirst().min();
		for (int i = 0; i < regions.size(); i++) {
			ChangeRegions.Region region = regions.get(i);
			area.addSubRegionBox(new Box(region.min(), region.max(), "Changes " + (i + 1)), true);
			origin = new BlockPos(
				Math.min(origin.getX(), region.min().getX()), Math.min(origin.getY(), region.min().getY()), Math.min(origin.getZ(), region.min().getZ())
			);
		}

		area.setExplicitOrigin(origin);
		LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, author);
		if (schematic == null) {
			throw new IOException("Litematica didn't create a schematic");
		}

		SurvivalChunks survival = new SurvivalChunks(survivalDir, level.dimension(), level.registryAccess());
		int blocks = 0;
		for (int i = 0; i < regions.size(); i++) {
			ChangeRegions.Region region = regions.get(i);
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer("Changes " + (i + 1));
			Map<BlockPos, CompoundData> blockEntities = schematic.getBlockEntityMapForRegion("Changes " + (i + 1));
			for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) {
				BlockState state = changes.get(pos.asLong());
				BlockPos relative = pos.subtract(region.min());
				if (state != null) {
					BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
					if (blockEntity != null) {
						CompoundData data = DataConverterNbt.fromVanillaCompound(blockEntity.saveWithFullMetadata(level.registryAccess()));
						DataTypeUtils.writeBlockPosToTag(relative, data);
						blockEntities.put(relative, data);
					}
				} else {
					// Not part of the changes, so match whatever is in the survival world now.
					state = survival.get(pos);
					if (state == null) {
						state = level.getBlockState(pos);
					}
				}

				container.set(relative.getX(), relative.getY(), relative.getZ(), state);
				if (!state.isAir()) {
					blocks++;
				}
			}
		}

		long now = System.currentTimeMillis();
		schematic.getMetadata().setTimeCreated(now);
		schematic.getMetadata().setTimeModified(now);
		schematic.getMetadata().setTotalBlocks(blocks);
		Files.createDirectories(schematicDir);
		if (!schematic.writeToFile(schematicDir, fileName, true, false) || !Files.isRegularFile(schematicDir.resolve(fileName))) {
			throw new IOException("Litematica couldn't write " + fileName);
		}

		return origin;
	}

	private static Path queueFile(final String survivalLevelId) {
		return FabricLoader.getInstance().getConfigDir().resolve("creativecopy").resolve("pending").resolve(survivalLevelId + ".json");
	}

	private static void queue(final String survivalLevelId, final List<Placement> placements) throws IOException {
		if (placements.isEmpty()) {
			return;
		}

		List<Placement> queued = readQueue(survivalLevelId);
		Set<Path> files = new HashSet<>();
		placements.forEach(placement -> files.add(placement.file()));
		queued.removeIf(placement -> files.contains(placement.file()));
		queued.addAll(placements);
		writeQueue(survivalLevelId, queued);
	}

	private static List<Placement> readQueue(final String survivalLevelId) throws IOException {
		List<Placement> placements = new ArrayList<>();
		Path file = queueFile(survivalLevelId);
		if (Files.isRegularFile(file)) {
			for (JsonElement element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
				JsonObject json = element.getAsJsonObject();
				placements.add(
					new Placement(
						json.get("dimension").getAsString(),
						Path.of(json.get("file").getAsString()),
						json.get("name").getAsString(),
						new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()),
						json.get("blocks").getAsInt(),
						json.get("remove").getAsBoolean()
					)
				);
			}
		}

		return placements;
	}

	private static void writeQueue(final String survivalLevelId, final List<Placement> placements) throws IOException {
		Path file = queueFile(survivalLevelId);
		if (placements.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}

		JsonArray array = new JsonArray();
		for (Placement placement : placements) {
			JsonObject json = new JsonObject();
			json.addProperty("dimension", placement.dimension());
			json.addProperty("file", placement.file().toAbsolutePath().toString());
			json.addProperty("name", placement.name());
			json.addProperty("x", placement.origin().getX());
			json.addProperty("y", placement.origin().getY());
			json.addProperty("z", placement.origin().getZ());
			json.addProperty("blocks", placement.blocks());
			json.addProperty("remove", placement.remove());
			array.add(json);
		}

		Files.createDirectories(file.getParent());
		Files.writeString(file, array.toString());
	}

	/** Adds queued placements once the player is in the survival world and dimension they belong to. */
	public static void tick(final Minecraft minecraft) {
		if (minecraft.level == checkedLevel) {
			return;
		}

		if (minecraft.level == null || minecraft.player == null) {
			checkedLevel = null;
			return;
		}

		checkedLevel = minecraft.level;
		String levelId = CreativeCopies.currentLevelId(minecraft);
		if (levelId == null) {
			return;
		}

		try {
			List<Placement> queued = readQueue(levelId);
			String dimension = minecraft.level.dimension().identifier().toString();
			List<Placement> here = queued.stream().filter(placement -> placement.dimension().equals(dimension)).toList();
			if (here.isEmpty()) {
				return;
			}

			// Take them off the queue first, so one that makes Litematica fail isn't retried forever.
			queued.removeAll(here);
			writeQueue(levelId, queued);
			if (here.stream().anyMatch(placement -> !placement.remove())) {
				makeSchematicsVisible(minecraft);
			}

			here.forEach(placement -> place(minecraft, placement));
			DataManager.save(true);
			for (Placement elsewhere : queued) {
				if (!elsewhere.remove()) {
					say(minecraft, Component.translatable("creativecopy.sync.elsewhere", elsewhere.blocks(), elsewhere.dimension()));
				}
			}
		} catch (Exception e) {
			LOGGER.error("Failed to add queued placements in {}", levelId, e);
			say(minecraft, Component.translatable("creativecopy.sync.place_failed", String.valueOf(e.getMessage())));
		}
	}

	/** Turns on everything that could keep the new placement from being seen. */
	private static void makeSchematicsVisible(final Minecraft minecraft) {
		boolean changed = false;
		boolean renderingWasOff = !Configs.Visuals.ENABLE_RENDERING.getBooleanValue();
		for (ConfigBoolean setting : List.of(
			Configs.Visuals.ENABLE_RENDERING, Configs.Visuals.ENABLE_SCHEMATIC_RENDERING, Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS
		)) {
			if (!setting.getBooleanValue()) {
				setting.setBooleanValue(true);
				changed = true;
			}
		}

		if (changed) {
			Configs.saveToFile();
		}

		if (renderingWasOff) {
			// What Litematica's own hotkey does besides changing the setting.
			DataManager.getSchematicPlacementManager().onToggleMainRendering(true);
		}

		if (DataManager.getRenderLayerRange().getLayerMode() != LayerMode.ALL) {
			DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL, false);
			changed = true;
		}

		if (changed) {
			say(minecraft, Component.translatable("creativecopy.sync.made_visible"));
		}
	}

	private static void place(final Minecraft minecraft, final Placement placement) {
		// Replace what an earlier send from the same copy left behind.
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : List.copyOf(manager.getAllSchematicsPlacements())) {
			if (placement.file().equals(old.getSchematicFile())) {
				manager.removeSchematicPlacement(old);
			}
		}

		SchematicHolder holder = SchematicHolder.getInstance();
		for (LitematicaSchematic old : List.copyOf(holder.getAllSchematics())) {
			if (placement.file().equals(old.getFile())) {
				holder.removeSchematic(old);
			}
		}

		if (placement.remove()) {
			return;
		}

		LitematicaSchematic schematic = holder.getOrLoad(placement.file());
		if (schematic == null) {
			say(minecraft, Component.translatable("creativecopy.sync.place_failed", placement.file().getFileName().toString()));
			return;
		}

		SchematicPlacement added = SchematicPlacement.createFor(schematic, placement.origin(), placement.name(), true, true);
		manager.addSchematicPlacement(added, true);
		manager.setSelectedSchematicPlacement(added);
		say(minecraft, Component.translatable("creativecopy.sync.placed", placement.blocks(), placement.name()));
	}

	private static void say(final Minecraft minecraft, final Component message) {
		minecraft.gui.hud.getChat().addClientSystemMessage(message);
	}
}
