package io.github.thedeadbolt.honeyfactory.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.BlockLayout;
import io.github.thedeadbolt.honeyfactory.contract.LayoutHasher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Conversion between Minecraft block states and the contract's canonical block-state
 * text, plus {@code layout-v1} hashing of what a level actually contains. Works on any
 * {@link BlockGetter}, so the server and the client read layouts the same way.
 */
public final class BlockStates {
	private BlockStates() {
	}

	/** {@code namespace:path[name=value,...]} with properties sorted by name. */
	public static String canonical(BlockState state) {
		Map<String, String> properties = new HashMap<>();
		state.getValues().forEach(value -> properties.put(value.property().getName(), name(value)));
		return BlockLayout.canonicalState(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
	}

	public static BlockState parse(String canonical, HolderLookup<Block> blocks) {
		try {
			return BlockStateParser.parseForBlock(blocks, canonical, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new IllegalArgumentException("Invalid block state '" + canonical + "': " + e.getMessage(), e);
		}
	}

	/** Every non-air block inside {@code box}, in canonical form. */
	public static List<BlockLayout.Block> read(BlockGetter level, BlockBox box) {
		List<BlockLayout.Block> blocks = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int x = box.minX(); x <= box.maxX(); x++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					if (!state.isAir()) {
						blocks.add(new BlockLayout.Block(x, y, z, canonical(state)));
					}
				}
			}
		}

		return blocks;
	}

	/** {@code layout-v1} hash of {@code box}, streamed; equal to hashing {@link #read}. */
	public static String layoutSha256(BlockGetter level, BlockBox box) {
		LayoutHasher hasher = new LayoutHasher(box.min(), box.max());
		Map<BlockState, String> names = new IdentityHashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int x = box.minX(); x <= box.maxX(); x++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					if (!state.isAir()) {
						hasher.block(x, y, z, names.computeIfAbsent(state, BlockStates::canonical));
					}
				}
			}
		}

		return hasher.finish();
	}

	private static <T extends Comparable<T>> String name(Property.Value<T> value) {
		return value.property().getName(value.value());
	}
}
