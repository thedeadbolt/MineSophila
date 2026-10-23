package io.github.thedeadbolt.honeyfactory.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rendered geometry of the block states generators place, as boxes in block-local
 * sixteenths, for sight-line estimates. The table describes what is drawn, not what
 * collides: a fence is its post and two rails per connection, as in the block model.
 *
 * <ul>
 *   <li>Slabs ({@code type=bottom|top|double}) and straight stairs ({@code half},
 *       {@code facing}) are exact.</li>
 *   <li>Fences ({@code *_fence}) are the post and the rails of each connected side.</li>
 *   <li>Leaves and plants are {@linkplain #seeThrough see-through}: their cutout textures
 *       have holes no box describes, so they never block an estimated sight line.</li>
 *   <li>Every other state, including stairs with a corner {@code shape}, is a full cube.</li>
 * </ul>
 *
 * <p>Levels 1–3 place only full cubes, so the table never changes their estimates.
 */
public final class BlockShapes {
	/** A box in sixteenths, minimum corner inclusive and maximum exclusive. */
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	}

	public static final List<Box> FULL = List.of(new Box(0, 0, 0, 16, 16, 16));

	/** Plants drawn as crossed cutout quads. */
	private static final Set<String> PLANTS = Set.of(
		"minecraft:short_grass",
		"minecraft:fern",
		"minecraft:bush",
		"minecraft:poppy",
		"minecraft:dandelion",
		"minecraft:cornflower",
		"minecraft:oxeye_daisy"
	);

	private BlockShapes() {
	}

	/** Whether the state's cutout texture lets sight lines through; such states have no boxes. */
	public static boolean seeThrough(String state) {
		String id = blockId(state);
		return id.endsWith("_leaves") || PLANTS.contains(id);
	}

	/** Whether the state is drawn as a whole opaque cube. */
	public static boolean fullCube(String state) {
		return !seeThrough(state) && boxes(state) == FULL;
	}

	/** The state's rendered boxes; empty for a see-through state. */
	public static List<Box> boxes(String state) {
		if (seeThrough(state)) {
			return List.of();
		}

		String id = blockId(state);
		if (id.endsWith("_slab")) {
			return switch (String.valueOf(property(state, "type"))) {
				case "bottom" -> List.of(new Box(0, 0, 0, 16, 8, 16));
				case "top" -> List.of(new Box(0, 8, 0, 16, 16, 16));
				default -> FULL;
			};
		}

		if (id.endsWith("_stairs") && "straight".equals(property(state, "shape"))) {
			boolean top = "top".equals(property(state, "half"));
			Box base = top ? new Box(0, 8, 0, 16, 16, 16) : new Box(0, 0, 0, 16, 8, 16);
			int y0 = top ? 0 : 8;
			int y1 = top ? 8 : 16;
			@Nullable Box step = switch (String.valueOf(property(state, "facing"))) {
				case "north" -> new Box(0, y0, 0, 16, y1, 8);
				case "south" -> new Box(0, y0, 8, 16, y1, 16);
				case "west" -> new Box(0, y0, 0, 8, y1, 16);
				case "east" -> new Box(8, y0, 0, 16, y1, 16);
				default -> null;
			};
			return step == null ? FULL : List.of(base, step);
		}

		if (id.endsWith("_fence")) {
			List<Box> boxes = new ArrayList<>(List.of(new Box(6, 0, 6, 10, 16, 10)));
			for (int[] rail : new int[][] {{6, 9}, {12, 15}}) {
				if ("true".equals(property(state, "north"))) {
					boxes.add(new Box(7, rail[0], 0, 9, rail[1], 6));
				}

				if ("true".equals(property(state, "south"))) {
					boxes.add(new Box(7, rail[0], 10, 9, rail[1], 16));
				}

				if ("true".equals(property(state, "west"))) {
					boxes.add(new Box(0, rail[0], 7, 6, rail[1], 9));
				}

				if ("true".equals(property(state, "east"))) {
					boxes.add(new Box(10, rail[0], 7, 16, rail[1], 9));
				}
			}

			return List.copyOf(boxes);
		}

		return FULL;
	}

	/** The namespaced block ID of a canonical state. */
	public static String blockId(String state) {
		int open = state.indexOf('[');
		return open < 0 ? state : state.substring(0, open);
	}

	/** A property's value in a canonical state, or null when it has none. */
	static @Nullable String property(String state, String name) {
		int open = state.indexOf('[');
		if (open < 0) {
			return null;
		}

		for (String entry : state.substring(open + 1, state.length() - 1).split(",")) {
			int equals = entry.indexOf('=');
			if (entry.substring(0, equals).equals(name)) {
				return entry.substring(equals + 1);
			}
		}

		return null;
	}
}
