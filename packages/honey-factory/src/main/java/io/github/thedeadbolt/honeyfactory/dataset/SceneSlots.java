package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
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
 * <p>Hashes compare as lowercase hex, ties by index. Source-region grouping uses
 * {@link #planPatches} instead.
 */
public final class SceneSlots {
	public static final String ALGORITHM = Algorithms.SCENE_SLOTS;

	/** {@code group} is the scene index under scene grouping and the source patch index under {@value #PATCH_ALGORITHM}. */
	public record Slot(int sceneIndex, String sceneId, boolean targetPresent, Split split, int group) {
	}

	public static final String PATCH_ALGORITHM = Algorithms.PATCH_SLOTS;

	private SceneSlots() {
	}

	public static List<Slot> plan(ResolvedConfig config) {
		ResolvedConfig.Splits splits = config.splits();
		double[] ratios = {splits.train(), splits.validation(), splits.test()};
		if (splits.grouping() == SplitGrouping.SCENE) {
			return plan(config.seed(), config.quotas().acceptedScenes(), config.quotas().targetPresentScenes(), ratios);
		}

		if (splits.grouping() != SplitGrouping.SOURCE_REGION || !(config.scene().base() instanceof SceneBase.NaturalTerrain base)) {
			throw new UnsupportedOperationException("Split grouping " + splits.grouping().jsonName() + " needs datasets drawn from several saves");
		}

		return planPatches(config.seed(), config.quotas().acceptedScenes(), config.quotas().targetPresentScenes(), base.scenesPerPatch(), ratios);
	}

	/**
	 * {@value #PATCH_ALGORITHM}: classes as in {@value #ALGORITHM} step 1; slot {@code i}
	 * belongs to patch {@code ⌊i / scenesPerPatch⌋}; the patches are apportioned to the
	 * splits by the ratios with the largest remainder method, then ordered by
	 * {@code SHA-256(".../patch-slots-v1/split/<seed>/<patch>")} and assigned train, then
	 * validation, then test. Every slot takes its patch's split, so a patch's terrain never
	 * reaches two splits.
	 */
	public static List<Slot> planPatches(long seed, int scenes, int presentScenes, int scenesPerPatch, double[] ratios) {
		List<Integer> byClassKey = ordered(ALGORITHM, seed, "class", range(scenes));
		boolean[] present = new boolean[scenes];
		for (int i = 0; i < presentScenes; i++) {
			present[byClassKey.get(i)] = true;
		}

		int patches = Math.ceilDiv(scenes, scenesPerPatch);
		int[] counts = splitCounts(patches, ratios);
		List<Integer> bySplitKey = ordered(PATCH_ALGORITHM, seed, "split", range(patches));
		Split[] patchSplits = new Split[patches];
		int next = 0;
		for (Split split : Split.values()) {
			for (int c = 0; c < counts[split.ordinal()]; c++) {
				patchSplits[bySplitKey.get(next++)] = split;
			}
		}

		List<Slot> slots = new ArrayList<>(scenes);
		for (int i = 0; i < scenes; i++) {
			slots.add(new Slot(i, Ids.sceneId(i), present[i], patchSplits[i / scenesPerPatch], i / scenesPerPatch));
		}

		return List.copyOf(slots);
	}

	/**
	 * The slots of {@code scenes} accepted scenes, {@code presentScenes} of them with a target,
	 * split by the train, validation and test {@code ratios}.
	 */
	public static List<Slot> plan(long seed, int scenes, int presentScenes, double[] ratios) {
		List<Integer> byClassKey = ordered(ALGORITHM, seed, "class", range(scenes));
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
			List<Integer> bySplitKey = ordered(ALGORITHM, seed, "split", members);
			int next = 0;
			for (Split split : Split.values()) {
				for (int c = 0; c < counts[split.ordinal()]; c++) {
					splits[bySplitKey.get(next++)] = split;
				}
			}
		}

		List<Slot> slots = new ArrayList<>(scenes);
		for (int i = 0; i < scenes; i++) {
			slots.add(new Slot(i, Ids.sceneId(i), present[i], splits[i], i));
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

	private static List<Integer> ordered(String algorithm, long seed, String purpose, List<Integer> indices) {
		List<String> keys = new ArrayList<>();
		for (int index : indices) {
			keys.add(Sha256.hex(("honey-factory/" + algorithm + "/" + purpose + "/" + seed + "/" + index).getBytes(StandardCharsets.UTF_8)));
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
