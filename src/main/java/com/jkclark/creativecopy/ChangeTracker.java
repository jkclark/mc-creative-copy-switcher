package com.jkclark.creativecopy;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers what each block the player changed in a creative copy originally was.
 *
 * <p>The first time a block is changed, its original state is appended to a journal file in the copy's
 * world folder. Being append-only and flushed every tick, the journal survives a crash of the game.
 * Changes the world makes by itself (growth, fluids, mobs, machines) don't start tracking a block.
 *
 * <p>Everything except {@link #open}/{@link #close} runs on the server thread only.
 */
public final class ChangeTracker {
	public static final String JOURNAL_FILE = "creativecopy_changes.bin";
	private static final Logger LOGGER = LoggerFactory.getLogger("creativecopy");
	private static final int MAGIC = 0x43435357;
	private static final int DEFINE_DIMENSION = 1;
	private static final int DEFINE_STATE = 2;
	private static final int CHANGE = 3;

	private static volatile @Nullable ChangeTracker active;
	private static boolean natural;

	private final MinecraftServer server;
	private final Map<ResourceKey<Level>, Long2ObjectLinkedOpenHashMap<BlockState>> originals = new HashMap<>();
	private final Map<ResourceKey<Level>, Integer> dimensionIds = new HashMap<>();
	private final Map<BlockState, Integer> stateIds = new HashMap<>();
	private @Nullable DataOutputStream out;
	private boolean dirty;

	private ChangeTracker(final MinecraftServer server) {
		this.server = server;
	}

	/** Creates the empty journal of a new copy. */
	public static void createJournal(final Path worldDir) throws IOException {
		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(worldDir.resolve(JOURNAL_FILE)))) {
			out.writeInt(MAGIC);
		}
	}

	/** Starts tracking if the server's world is a creative copy with a journal. */
	public static void open(final MinecraftServer server) {
		close();
		natural = false;
		Path worldDir = server.getWorldPath(LevelResource.ROOT);
		Path journal = worldDir.resolve(JOURNAL_FILE);
		if (CreativeCopies.readMarker(worldDir).isEmpty() || !Files.isRegularFile(journal)) {
			return;
		}

		try {
			ChangeTracker tracker = new ChangeTracker(server);
			tracker.load(journal);
			tracker.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(journal, StandardOpenOption.APPEND), 1 << 16));
			active = tracker;
		} catch (IOException e) {
			LOGGER.error("Can't track changes: failed to open {}", journal, e);
		}
	}

	public static void close() {
		ChangeTracker tracker = active;
		active = null;
		if (tracker != null && tracker.out != null) {
			try {
				tracker.out.close();
			} catch (IOException e) {
				LOGGER.error("Failed to close the change journal", e);
			}
		}
	}

	/** The tracker for the given server, or null if its world isn't tracked (or tracking failed). */
	public static @Nullable ChangeTracker get(final @Nullable MinecraftServer server) {
		ChangeTracker tracker = active;
		return tracker != null && tracker.server == server && tracker.out != null ? tracker : null;
	}

	/** Runs something whose block changes are the world's own doing, or (inside that) the player's after all. */
	public static void runAs(final boolean naturalChanges, final Runnable action) {
		boolean previous = natural;
		natural = naturalChanges;
		try {
			action.run();
		} finally {
			natural = previous;
		}
	}

	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
		ChangeTracker tracker = active;
		if (tracker != null && oldState != newState && tracker.server == level.getServer() && tracker.server.isSameThread() && !natural) {
			tracker.record(level.dimension(), pos.asLong(), oldState);
		}
	}

	public Map<ResourceKey<Level>, Long2ObjectLinkedOpenHashMap<BlockState>> originals() {
		return this.originals;
	}

	public static void flush(final MinecraftServer server) {
		ChangeTracker tracker = get(server);
		if (tracker != null && tracker.dirty) {
			tracker.dirty = false;
			try {
				tracker.out.flush();
			} catch (IOException e) {
				tracker.fail(e);
			}
		}
	}

	private void record(final ResourceKey<Level> dimension, final long pos, final BlockState original) {
		Long2ObjectLinkedOpenHashMap<BlockState> map = this.originals.computeIfAbsent(dimension, k -> new Long2ObjectLinkedOpenHashMap<>());
		if (this.out == null || map.putIfAbsent(pos, original) != null) {
			return;
		}

		try {
			Integer dimensionId = this.dimensionIds.get(dimension);
			if (dimensionId == null) {
				dimensionId = this.dimensionIds.size();
				this.dimensionIds.put(dimension, dimensionId);
				this.out.writeByte(DEFINE_DIMENSION);
				this.out.writeUTF(dimension.identifier().toString());
			}

			Integer stateId = this.stateIds.get(original);
			if (stateId == null) {
				stateId = this.stateIds.size();
				this.stateIds.put(original, stateId);
				this.out.writeByte(DEFINE_STATE);
				this.out.writeUTF(BlockStateParser.serialize(original));
			}

			this.out.writeByte(CHANGE);
			this.out.writeInt(dimensionId);
			this.out.writeInt(stateId);
			this.out.writeLong(pos);
			this.dirty = true;
		} catch (IOException e) {
			this.fail(e);
		}
	}

	/** Stops tracking rather than carry on with a journal that has holes in it. */
	private void fail(final IOException e) {
		LOGGER.error("Failed to write the change journal, changes are no longer tracked", e);
		this.out = null;
	}

	private void load(final Path journal) throws IOException {
		List<ResourceKey<Level>> dimensions = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		long validLength = Integer.BYTES;

		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal), 1 << 16))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a change journal");
			}

			try {
				for (int type = in.read(); type != -1; type = in.read()) {
					switch (type) {
						case DEFINE_DIMENSION -> {
							String id = in.readUTF();
							ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
							this.dimensionIds.put(key, dimensions.size());
							dimensions.add(key);
							validLength += 3 + id.length();
						}
						case DEFINE_STATE -> {
							String id = in.readUTF();
							BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, id, false).blockState();
							this.stateIds.put(state, states.size());
							states.add(state);
							validLength += 3 + id.length();
						}
						case CHANGE -> {
							ResourceKey<Level> dimension = dimensions.get(in.readInt());
							BlockState state = states.get(in.readInt());
							this.originals.computeIfAbsent(dimension, k -> new Long2ObjectLinkedOpenHashMap<>()).putIfAbsent(in.readLong(), state);
							validLength += 17;
						}
						default -> throw new IOException("Corrupt change journal: unknown record " + type);
					}
				}
			} catch (EOFException e) {
				LOGGER.warn("The change journal ends in a partial record (the game probably crashed), dropping that record");
			} catch (CommandSyntaxException | IndexOutOfBoundsException e) {
				throw new IOException("Corrupt change journal", e);
			}
		}

		try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
			if (file.length() > validLength) {
				file.setLength(validLength);
			}
		}
	}
}
