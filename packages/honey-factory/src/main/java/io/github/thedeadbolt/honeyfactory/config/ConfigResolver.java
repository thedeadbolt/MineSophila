package io.github.thedeadbolt.honeyfactory.config;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePresets;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatches;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validates generation input and applies the specification's defaults. Resolution reads
 * only the given JSON, so invalid input never touches the world or the filesystem; the one
 * exception is a missing seed, which is drawn at random and recorded in the result.
 *
 * <p>Input members, all optional unless noted:
 *
 * <pre>
 * dataset        name (required), difficulty (required, 1-5), seed (drawn at random when absent)
 * target         block_id, allowed_block_states, present_fraction
 * arena          width, length, height, margin (platform bases only)
 * viewpoints     count, distance_min_blocks, distance_max_blocks, yaw_jitter_degrees,
 *                pitch_jitter_degrees, attempts_per_viewpoint
 * scene          preset (default level&lt;difficulty&gt;), base, parts, sight, lighting; see
 *                {@link SceneSpec#read}. A natural_patch base groups splits by source region.
 * visibility     min_ratio, max_ratio (levels 3-5; defaults 0.2-0.8 for level 3, 0.1 minimum for 4-5)
 * capture        mode, width, height, cube_face_size (PANORAMA_310 only)
 * quotas         accepted_scenes, scene_attempts_per_accepted_scene
 * splits         train, validation, test
 * output         root (required, absolute), free_space_reserve_bytes
 * </pre>
 */
public final class ConfigResolver {
	/** First-person standing eye height used for every viewpoint, in blocks. */
	public static final double EYE_HEIGHT_BLOCKS = 1.62;

	private static final Set<String> SECTIONS = Set.of(
		"dataset", "target", "arena", "viewpoints", "scene", "visibility", "capture", "quotas", "splits", "output"
	);
	private static final double SPLIT_SUM_TOLERANCE = 1e-9;
	private static final int MAX_ARENA_HEIGHT_WITH_MARGIN = 256;
	/** Default visibility-ratio bounds; see the M3 findings for the evidence. */
	private static final Double LEVEL3_MIN_RATIO = 0.2;
	private static final Double LEVEL3_MAX_RATIO = 0.8;
	private static final Double LEVEL4_5_MIN_RATIO = 0.1;
	/** Free space a run leaves on the output file system by default: 10 GiB. */
	public static final long DEFAULT_FREE_SPACE_RESERVE_BYTES = 10L << 30;

	private ConfigResolver() {
	}

	public static ResolvedConfig resolve(String json) {
		return resolve(JsonFields.parseObject(json));
	}

	/** @throws ContractException naming the first invalid setting */
	public static ResolvedConfig resolve(JsonObject input) {
		JsonFields root = JsonFields.of(input);
		root.rejectUnknown(SECTIONS);

		JsonFields dataset = root.object("dataset");
		dataset.rejectUnknown(Set.of("name", "seed", "difficulty"));
		String name = dataset.string("name");
		if (!Ids.isDatasetName(name)) {
			throw dataset.error("name", "must match " + Ids.DATASET_NAME.pattern());
		}

		long seed = dataset.has("seed") ? dataset.integer("seed", 0, SceneSeeds.MAX_SEED) : randomSeed();
		if (dataset.has("difficulty") && dataset.integer("difficulty", 0, Integer.MAX_VALUE) == 6) {
			throw dataset.error("difficulty", "level 6 (active search) is a separate extension and is not supported");
		}

		int difficulty = dataset.integer32("difficulty", 1, 5);

		ResolvedConfig.Target target = target(root.objectOrEmpty("target"));
		ResolvedConfig.Arena arena = arena(root.objectOrEmpty("arena"));
		ResolvedConfig.Viewpoints viewpoints = viewpoints(root.objectOrEmpty("viewpoints"));
		SceneSpec scene = SceneSpec.read(root.objectOrEmpty("scene"), ScenePresets.forLevel(difficulty));
		boolean natural = scene.base() instanceof SceneBase.NaturalTerrain;
		if (natural && root.has("arena")) {
			throw root.error("arena", "applies to platform bases; a natural_patch base sets its own region");
		}
		ResolvedConfig.Visibility visibility = visibility(root.objectOrEmpty("visibility"), difficulty);
		ResolvedConfig.Capture capture = capture(root.objectOrEmpty("capture"));
		ResolvedConfig.Quotas quotas = quotas(root.objectOrEmpty("quotas"), target.presentFraction());
		ResolvedConfig.Splits splits = splits(root.objectOrEmpty("splits"), natural);
		if (scene.base() instanceof SceneBase.NaturalTerrain base && Math.ceilDiv(quotas.acceptedScenes(), base.scenesPerPatch()) > SourcePatches.maxPatches()) {
			throw root.error("scene", "needs more than " + SourcePatches.maxPatches() + " source patches; raise scenes_per_patch");
		}
		ResolvedConfig.Output output = output(root.object("output"));

		return new ResolvedConfig(name, difficulty, seed, target, arena, viewpoints, scene, visibility, capture, quotas, splits, output);
	}

	private static ResolvedConfig.Target target(JsonFields target) {
		target.rejectUnknown(Set.of("block_id", "allowed_block_states", "present_fraction"));
		String blockId = target.string("block_id", "minecraft:honey_block");
		if (!Ids.NAMESPACED_ID.matcher(blockId).matches()) {
			throw target.error("block_id", "must be a namespaced block ID such as minecraft:honey_block");
		}

		List<String> states = target.has("allowed_block_states") ? target.strings("allowed_block_states") : List.of(blockId);
		if (states.isEmpty()) {
			throw target.error("allowed_block_states", "must list at least one block state");
		}

		for (String state : states) {
			if (!state.equals(blockId) && !state.startsWith(blockId + "[")) {
				throw target.error("allowed_block_states", "state '" + state + "' is not a state of " + blockId);
			}
		}

		return new ResolvedConfig.Target(blockId, List.copyOf(states), target.decimal("present_fraction", 0.0, 1.0, 0.8));
	}

	private static ResolvedConfig.Arena arena(JsonFields arena) {
		arena.rejectUnknown(Set.of("width", "length", "height", "margin"));
		// At least two layers: the floor and the target standing on it.
		ResolvedConfig.Arena resolved = new ResolvedConfig.Arena(
			arena.integer32("width", 1, 256, 32),
			arena.integer32("length", 1, 256, 32),
			arena.integer32("height", 2, 256, 16),
			arena.integer32("margin", 0, 64, 4)
		);
		// The margin extends above the build height, and the owned region is 256 blocks tall.
		if (resolved.height() + resolved.margin() > MAX_ARENA_HEIGHT_WITH_MARGIN) {
			throw arena.error("height", "height plus margin must not exceed " + MAX_ARENA_HEIGHT_WITH_MARGIN + " blocks");
		}

		return resolved;
	}

	private static ResolvedConfig.Viewpoints viewpoints(JsonFields viewpoints) {
		viewpoints.rejectUnknown(Set.of(
			"count", "distance_min_blocks", "distance_max_blocks", "yaw_jitter_degrees", "pitch_jitter_degrees", "attempts_per_viewpoint"
		));
		double min = viewpoints.decimal("distance_min_blocks", 0.5, 512.0, 3.0);
		double max = viewpoints.decimal("distance_max_blocks", 0.5, 512.0, 16.0);
		if (min > max) {
			throw viewpoints.error("distance_min_blocks", "must not exceed distance_max_blocks (" + max + ")");
		}

		return new ResolvedConfig.Viewpoints(
			viewpoints.integer32("count", 1, Ids.MAX_VIEWPOINTS, 8),
			min,
			max,
			viewpoints.decimal("yaw_jitter_degrees", 0.0, 180.0, 15.0),
			viewpoints.decimal("pitch_jitter_degrees", 0.0, 90.0, 10.0),
			viewpoints.integer32("attempts_per_viewpoint", 1, 10_000, 50),
			EYE_HEIGHT_BLOCKS
		);
	}

	/** A seed for a configuration that names none; the resolved configuration records it. */
	private static long randomSeed() {
		return new SecureRandom().nextLong(SceneSeeds.MAX_SEED + 1);
	}

	private static ResolvedConfig.Visibility visibility(JsonFields visibility, int difficulty) {
		visibility.rejectUnknown(Set.of("min_ratio", "max_ratio"));
		if (difficulty <= 2) {
			if (visibility.has("min_ratio") || visibility.has("max_ratio")) {
				throw visibility.error(visibility.has("min_ratio") ? "min_ratio" : "max_ratio", "levels 1-2 require fully visible targets; ratio bounds do not apply");
			}

			return new ResolvedConfig.Visibility(VisibilityRule.FULLY_VISIBLE, null, null);
		}

		// Initial defaults set from the M3 mask-gate evidence; null removes a bound.
		Double min = visibility.has("min_ratio") ? visibility.nullableDecimal("min_ratio", 0.0, 1.0) : difficulty == 3 ? LEVEL3_MIN_RATIO : LEVEL4_5_MIN_RATIO;
		Double max = visibility.has("max_ratio") ? visibility.nullableDecimal("max_ratio", 0.0, 1.0) : difficulty == 3 ? LEVEL3_MAX_RATIO : null;
		if (difficulty == 3) {
			// Partial visibility: both bounds are exclusive of 0 and 1.
			if (min != null && (min <= 0.0 || min >= 1.0)) {
				throw visibility.error("min_ratio", "level 3 bounds must lie strictly between 0 and 1");
			}

			if (max != null && (max <= 0.0 || max >= 1.0)) {
				throw visibility.error("max_ratio", "level 3 bounds must lie strictly between 0 and 1");
			}
		} else if (min != null && min <= 0.0) {
			throw visibility.error("min_ratio", "levels 4-5 require a visible target; use a bound above 0");
		}

		if (min != null && max != null && min >= max) {
			throw visibility.error("min_ratio", "must be below max_ratio (" + max + ")");
		}

		return new ResolvedConfig.Visibility(difficulty == 3 ? VisibilityRule.PARTIALLY_VISIBLE : VisibilityRule.AT_LEAST_PARTLY_VISIBLE, min, max);
	}

	private static ResolvedConfig.Capture capture(JsonFields capture) {
		capture.rejectUnknown(Set.of("mode", "width", "height", "cube_face_size"));
		CaptureMode mode = capture.has("mode") ? capture.enumValue("mode", CaptureMode::fromJson) : CaptureMode.PERSPECTIVE_90;
		boolean panorama = mode == CaptureMode.PANORAMA_310;
		int width = capture.integer32("width", 16, 16_384, panorama ? 2480 : 1280);
		int height = capture.integer32("height", 16, 16_384, 1280);
		Integer face = null;
		if (panorama) {
			face = capture.integer32("cube_face_size", PanoramicProjection.MIN_SIZE, PanoramicProjection.MAX_FACE_SIZE, 1024);
			if ((long) width * height > PanoramicProjection.MAX_PIXELS) {
				throw capture.error("width", "a panorama may have at most " + PanoramicProjection.MAX_PIXELS + " pixels, got " + width + "x" + height);
			}
		} else if (capture.has("cube_face_size")) {
			throw capture.error("cube_face_size", "applies only to PANORAMA_310");
		}

		return new ResolvedConfig.Capture(mode, width, height, face);
	}

	private static ResolvedConfig.Quotas quotas(JsonFields quotas, double presentFraction) {
		quotas.rejectUnknown(Set.of("accepted_scenes", "scene_attempts_per_accepted_scene"));
		int accepted = quotas.integer32("accepted_scenes", 1, Ids.MAX_SCENES, 1000);
		int attemptsPerScene = quotas.integer32("scene_attempts_per_accepted_scene", 1, 1000, 10);
		long attemptLimit = (long) accepted * attemptsPerScene;
		if (attemptLimit > Integer.MAX_VALUE) {
			throw quotas.error("scene_attempts_per_accepted_scene", "gives more than " + Integer.MAX_VALUE + " scene attempts");
		}

		// Exact quotas before generation: round half to even on the decimal fraction.
		int present = BigDecimal.valueOf(presentFraction).multiply(BigDecimal.valueOf(accepted)).setScale(0, RoundingMode.HALF_EVEN).intValueExact();
		return new ResolvedConfig.Quotas(accepted, present, accepted - present, (int) attemptLimit);
	}

	private static ResolvedConfig.Splits splits(JsonFields splits, boolean natural) {
		splits.rejectUnknown(Set.of("train", "validation", "test"));
		double train = splits.decimal("train", 0.0, 1.0, 0.8);
		double validation = splits.decimal("validation", 0.0, 1.0, 0.1);
		double test = splits.decimal("test", 0.0, 1.0, 0.1);
		if (Math.abs(train + validation + test - 1.0) > SPLIT_SUM_TOLERANCE) {
			throw splits.error("train", "train, validation and test must sum to 1, got " + (train + validation + test));
		}

		if (train <= 0.0) {
			throw splits.error("train", "must be above 0");
		}

		return new ResolvedConfig.Splits(train, validation, test, natural ? SplitGrouping.SOURCE_REGION : SplitGrouping.SCENE);
	}

	private static ResolvedConfig.Output output(JsonFields output) {
		output.rejectUnknown(Set.of("root", "free_space_reserve_bytes"));
		String root = output.string("root");
		Path path;
		try {
			path = Path.of(root);
		} catch (InvalidPathException e) {
			throw output.error("root", "is not a valid path: " + e.getMessage());
		}

		if (!path.isAbsolute()) {
			throw output.error("root", "must be an absolute path");
		}

		return new ResolvedConfig.Output(path.normalize(), output.integer("free_space_reserve_bytes", 0, Long.MAX_VALUE, DEFAULT_FREE_SPACE_RESERVE_BYTES));
	}
}
