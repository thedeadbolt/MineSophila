package io.github.thedeadbolt.honeyfactory.contract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Canonical block-layout text hashed as a scene's {@code layout_sha256} ({@value #ALGORITHM}).
 *
 * <pre>
 * honey-factory/layout-v1
 * bounds &lt;min x&gt; &lt;min y&gt; &lt;min z&gt; &lt;max x&gt; &lt;max y&gt; &lt;max z&gt;
 * &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;block state&gt;
 * ...
 * </pre>
 *
 * <p>Bounds are inclusive. Every non-air block inside them appears once, ordered by y,
 * then z, then x; air is omitted. Lines end with {@code \n} and the text is UTF-8.
 * Block states are canonical: {@code namespace:path}, then properties in name order as
 * {@code [name=value,...]}, omitted when there are none.
 */
public final class BlockLayout {
	public static final String ALGORITHM = "layout-v1";

	private static final Pattern STATE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?");

	public record Block(int x, int y, int z, String state) {
	}

	private BlockLayout() {
	}

	/** Formats a block state with its properties sorted by name. */
	public static String canonicalState(String blockId, Map<String, String> properties) {
		if (properties.isEmpty()) {
			return blockId;
		}

		StringBuilder out = new StringBuilder(blockId).append('[');
		new TreeMap<>(properties).forEach((name, value) -> out.append(name).append('=').append(value).append(','));
		out.setCharAt(out.length() - 1, ']');
		return requireCanonical(out.toString());
	}

	public static String serialize(int[] boundsMin, int[] boundsMax, List<Block> blocks) {
		List<Block> ordered = new ArrayList<>(blocks);
		ordered.sort(Comparator.comparingInt(Block::y).thenComparingInt(Block::z).thenComparingInt(Block::x));
		StringBuilder out = new StringBuilder("honey-factory/").append(ALGORITHM).append('\n');
		out.append("bounds ").append(boundsMin[0]).append(' ').append(boundsMin[1]).append(' ').append(boundsMin[2]).append(' ')
			.append(boundsMax[0]).append(' ').append(boundsMax[1]).append(' ').append(boundsMax[2]).append('\n');
		Set<List<Integer>> seen = new HashSet<>();
		for (Block block : ordered) {
			if (block.x() < boundsMin[0] || block.y() < boundsMin[1] || block.z() < boundsMin[2]
				|| block.x() > boundsMax[0] || block.y() > boundsMax[1] || block.z() > boundsMax[2]) {
				throw new IllegalArgumentException("Block outside layout bounds: " + block);
			}

			if (!seen.add(List.of(block.x(), block.y(), block.z()))) {
				throw new IllegalArgumentException("Duplicate block position: " + block);
			}

			if (block.state().equals("minecraft:air")) {
				throw new IllegalArgumentException("Air is implicit and must be omitted: " + block);
			}

			out.append(block.x()).append(' ').append(block.y()).append(' ').append(block.z()).append(' ').append(requireCanonical(block.state())).append('\n');
		}

		return out.toString();
	}

	public static String sha256(int[] boundsMin, int[] boundsMax, List<Block> blocks) {
		return Sha256.hex(serialize(boundsMin, boundsMax, blocks).getBytes(StandardCharsets.UTF_8));
	}

	private static String requireCanonical(String state) {
		if (!STATE.matcher(state).matches()) {
			throw new IllegalArgumentException("Not a canonical block state: " + state);
		}

		int open = state.indexOf('[');
		if (open >= 0) {
			String previous = "";
			for (String property : state.substring(open + 1, state.length() - 1).split(",")) {
				String name = property.substring(0, property.indexOf('='));
				if (name.compareTo(previous) <= 0) {
					throw new IllegalArgumentException("Block state properties must be unique and sorted by name: " + state);
				}

				previous = name;
			}
		}

		return state;
	}
}
