package com.jkclark.creativecopy;

import com.mojang.serialization.Codec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * Reads blocks from a world that isn't open. Region files are parsed here instead of with vanilla's
 * RegionFile because that opens them for writing, and the survival world must never be modified.
 */
final class SurvivalChunks {
	private static final int SECTOR = 4096;

	private final Path regionDir;
	private final BlockState air;
	private final Codec<PalettedContainer<BlockState>> codec;
	private final Map<Long, Map<Integer, PalettedContainer<BlockState>>> chunks = new HashMap<>();

	SurvivalChunks(final Path worldDir, final ResourceKey<Level> dimension, final RegistryAccess registries) {
		PalettedContainerFactory factory = PalettedContainerFactory.create(registries);
		this.regionDir = DimensionType.getStorageFolder(dimension, worldDir).resolve("region");
		this.air = factory.defaultBlockState();
		this.codec = factory.blockStatesContainerCodec();
	}

	/** The block at the position, or null if its chunk isn't saved or was saved by another version of the game. */
	@Nullable BlockState get(final BlockPos pos) throws IOException {
		int chunkX = SectionPos.blockToSectionCoord(pos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
		long key = ChunkPos.pack(chunkX, chunkZ);
		if (!this.chunks.containsKey(key)) {
			this.chunks.put(key, this.load(chunkX, chunkZ));
		}

		Map<Integer, PalettedContainer<BlockState>> sections = this.chunks.get(key);
		if (sections == null) {
			return null;
		}

		PalettedContainer<BlockState> section = sections.get(SectionPos.blockToSectionCoord(pos.getY()));
		return section == null ? this.air : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}

	private @Nullable Map<Integer, PalettedContainer<BlockState>> load(final int chunkX, final int chunkZ) throws IOException {
		CompoundTag chunk = this.readChunk(chunkX, chunkZ);
		if (chunk == null
			|| chunk.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()
			|| !chunk.getStringOr("Status", "").equals("minecraft:full")) {
			return null;
		}

		Map<Integer, PalettedContainer<BlockState>> sections = new HashMap<>();
		for (CompoundTag section : chunk.getListOrEmpty("sections").compoundStream().toList()) {
			CompoundTag states = section.getCompound("block_states").orElse(null);
			if (states != null) {
				PalettedContainer<BlockState> container = this.codec
					.parse(NbtOps.INSTANCE, states)
					.getOrThrow(message -> new IOException("Unreadable section in chunk " + chunkX + ", " + chunkZ + ": " + message));
				sections.put((int)section.getByteOr("Y", (byte)0), container);
			}
		}

		return sections;
	}

	private @Nullable CompoundTag readChunk(final int chunkX, final int chunkZ) throws IOException {
		Path path = this.regionDir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
		if (!Files.isRegularFile(path)) {
			return null;
		}

		byte[] data;
		int compression;
		try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
			if (file.length() < 2 * SECTOR) {
				return null;
			}

			file.seek(4L * ((chunkX & 31) + (chunkZ & 31) * 32));
			int location = file.readInt();
			if (location == 0) {
				return null;
			}

			file.seek((long)(location >>> 8) * SECTOR);
			int length = file.readInt();
			compression = file.readUnsignedByte();
			if (length < 1 || length > file.length()) {
				throw new IOException("Corrupt chunk " + chunkX + ", " + chunkZ + " in " + path);
			}

			data = new byte[length - 1];
			file.readFully(data);
		}

		if ((compression & 128) != 0) {
			// Oversized chunks are stored in a file of their own.
			data = Files.readAllBytes(this.regionDir.resolve("c." + chunkX + "." + chunkZ + ".mcc"));
			compression &= 127;
		}

		RegionFileVersion version = RegionFileVersion.fromId(compression);
		if (version == null) {
			throw new IOException("Unknown compression " + compression + " in " + path);
		}

		try (InputStream in = version.wrap(new ByteArrayInputStream(data))) {
			return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
		}
	}
}
