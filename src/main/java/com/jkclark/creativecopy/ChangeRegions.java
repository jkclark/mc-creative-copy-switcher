package com.jkclark.creativecopy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Groups scattered changed blocks into boxes, so far-apart builds don't end up in one giant schematic region. */
final class ChangeRegions {
	private static final int MIN_GAP = 8;
	private static final long MAX_SPARSE_VOLUME = 64 * 64 * 64;
	private static final int SPARSE_FACTOR = 10;

	record Region(BlockPos min, BlockPos max) {
		long volume() {
			return (long)(this.max.getX() - this.min.getX() + 1) * (this.max.getY() - this.min.getY() + 1) * (this.max.getZ() - this.min.getZ() + 1);
		}
	}

	private ChangeRegions() {}

	static List<Region> group(final List<BlockPos> positions) {
		List<Region> regions = new ArrayList<>();
		List<List<BlockPos>> todo = new ArrayList<>();
		if (!positions.isEmpty()) {
			todo.add(positions);
		}

		while (!todo.isEmpty()) {
			List<BlockPos> group = todo.removeLast();
			Region region = bounds(group);
			int axis = -1;
			int splitBefore = 0;
			int bestGap = MIN_GAP - 1;
			int longest = 0;

			for (int i = 0; i < 3; i++) {
				int min = coordinate(region.min, i);
				int size = coordinate(region.max, i) - min + 1;
				if (size > coordinate(region.max, longest) - coordinate(region.min, longest) + 1) {
					longest = i;
				}

				BitSet used = new BitSet(size);
				for (BlockPos pos : group) {
					used.set(coordinate(pos, i) - min);
				}

				for (int from = used.nextClearBit(0); from < size; from = used.nextClearBit(from)) {
					int to = used.nextSetBit(from);
					if (to - from > bestGap) {
						bestGap = to - from;
						axis = i;
						splitBefore = min + to;
					}

					from = to;
				}
			}

			if (axis < 0 && region.volume() > MAX_SPARSE_VOLUME && region.volume() > (long)group.size() * SPARSE_FACTOR) {
				axis = longest;
				splitBefore = (int)(((long)coordinate(region.min, longest) + coordinate(region.max, longest) + 1) >> 1);
			}

			if (axis < 0) {
				regions.add(region);
				continue;
			}

			List<BlockPos> before = new ArrayList<>();
			List<BlockPos> after = new ArrayList<>();
			for (BlockPos pos : group) {
				(coordinate(pos, axis) < splitBefore ? before : after).add(pos);
			}

			todo.add(after);
			todo.add(before);
		}

		return regions;
	}

	private static Region bounds(final List<BlockPos> positions) {
		BlockPos.MutableBlockPos min = positions.getFirst().mutable();
		BlockPos.MutableBlockPos max = positions.getFirst().mutable();
		for (BlockPos pos : positions) {
			min.set(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
			max.set(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
		}

		return new Region(min.immutable(), max.immutable());
	}

	private static int coordinate(final BlockPos pos, final int axis) {
		return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
	}
}
