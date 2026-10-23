package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Target class and split for every accepted-scene slot, fixed before generation
 * ({@value #ALGORITHM}).
 *
 * <ol>
 *   <li>Order slot indices by {@code SHA-256("honey-factory/scene-slots-v1/class/<seed>/<index>")};
 *       the first {@code target_present_scenes} slots contain a target.</li>
 *   <li>Within each class, split counts are the requested ratios applied by the largest
 *       remainder method, ties broken in train, validation, test order.</li>
 *   <li>Order each class by {@code SHA-256(".../split/<seed>/<index>")} and assign train,
 *       then validation, then test.</li>
 * </ol>
 *
 * <p>Hashes compare as lowercase hex, ties by index. Level-5 source-world grouping is
 * not available until M6.
 */
public final class SceneSlots {
	public static final String ALGORITHM = Algorithms.SCENE_SLOTS;

	public record Slot(int sceneIndex, String sceneId, boolean targetPresent, Split split) {
	}

	private SceneSlots() {
	}

	public static List<Slot> plan(ResolvedConfig config) {
		if (config.splits().grouping() != SplitGrouping.SCENE) {
			throw new UnsupportedOperationException("Split grouping by source world arrives with level 5 in M6");
		}

		ResolvedConfig.Splits splits = config.splits();
		double[] ratios = {splits.train(), splits.validation(), splits.test()};
		return plan(config.seed(), config.quotas().acceptedScenes(), config.quotas().targetPresentScenes(), ratios);
	}

	/**
	 * The slots of {@code scenes} accepted scenes, {@code presentScenes} of them with a target,
	 * split by the train, validation and test {@code ratios}.
	 */
	public static List<Slot> plan(long seed, int scenes, int presentScenes, double[] ratios) {
		List<Integer> byClassKey = ordered(seed, "class", range(scenes));
		boolean[] present = new boolean[scenes];
		for (int i = 0; i < presentScenes; i++) {
			present[byClassKey.get(i)] = true;
		}

		Split[] splits = new Split[scenes];
		for (boolean targetClass : new boolean[] {true, false}) {
			List<Integer> members = new ArrayList<>();
			for (int i = 0; i < scenes; i++) {
				if (present[i] == targetClass) {
					members.add(i);
				}
			}

			int[] counts = splitCounts(members.size(), ratios);
			List<Integer> bySplitKey = ordered(seed, "split", members);
			int next = 0;
			for (Split split : Split.values()) {
				for (int c = 0; c < counts[split.ordinal()]; c++) {
					splits[bySplitKey.get(next++)] = split;
				}
			}
		}

		List<Slot> slots = new ArrayList<>(scenes);
		for (int i = 0; i < scenes; i++) {
			slots.add(new Slot(i, Ids.sceneId(i), present[i], splits[i]));
		}

		return List.copyOf(slots);
	}

	/** Largest-remainder apportionment of {@code total} scenes by the split ratios. */
	static int[] splitCounts(int total, double[] ratios) {
		BigDecimal[] exact = new BigDecimal[3];
		for (int i = 0; i < 3; i++) {
			exact[i] = BigDecimal.valueOf(ratios[i]).multiply(BigDecimal.valueOf(total));
		}

		int[] counts = new int[3];
		int assigned = 0;
		for (int i = 0; i < 3; i++) {
			counts[i] = exact[i].setScale(0, RoundingMode.FLOOR).intValueExact();
			assigned += counts[i];
		}

		List<Integer> order = new ArrayList<>(List.of(0, 1, 2));
		order.sort(Comparator.comparing((Integer i) -> exact[i].subtract(BigDecimal.valueOf(counts[i]))).reversed().thenComparing(i -> i));
		for (int r = 0; assigned < total; r++, assigned++) {
			counts[order.get(r % 3)]++;
		}

		return counts;
	}

	private static List<Integer> ordered(long seed, String purpose, List<Integer> indices) {
		List<String> keys = new ArrayList<>();
		for (int index : indices) {
			keys.add(Sha256.hex(("honey-factory/" + ALGORITHM + "/" + purpose + "/" + seed + "/" + index).getBytes(StandardCharsets.UTF_8)));
		}

		List<Integer> positions = range(indices.size());
		positions.sort(Comparator.comparing(keys::get).thenComparing(indices::get));
		return positions.stream().map(indices::get).toList();
	}

	private static List<Integer> range(int size) {
		List<Integer> result = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			result.add(i);
		}

		return result;
	}
}
