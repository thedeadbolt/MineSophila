package io.github.thedeadbolt.honeyfactory.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import io.github.thedeadbolt.honeyfactory.contract.CanonicalJson;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, validated generation and capture configuration with every default applied.
 * Create it with {@link ConfigResolver}.
 *
 * <p>{@link #toJson()} is the canonical resolved configuration: the manifest's
 * {@code difficulty}, {@code target}, {@code generator}, {@code capture} and
 * {@code splits} members, without final split counts. Its JCS SHA-256 is the
 * resolved-configuration hash. The machine-specific output settings are excluded.
 */
public record ResolvedConfig(
	String datasetName,
	int difficulty,
	long seed,
	Target target,
	Arena arena,
	@Nullable NaturalRegion naturalRegion,
	Viewpoints viewpoints,
	Lighting lighting,
	Visibility visibility,
	Capture capture,
	Quotas quotas,
	Splits splits,
	Output output
) {
	public record Target(String blockId, List<String> allowedBlockStates, double presentFraction) {
	}

	public record Arena(int width, int length, int height, int margin) {
	}

	/** Level-5 sampled region; no defaults until M6 benchmarks them. */
	public record NaturalRegion(int coreWidth, int coreLength, int height, int contextPadding) {
	}

	public record Viewpoints(
		int count,
		double distanceMinBlocks,
		double distanceMaxBlocks,
		double yawJitterDegrees,
		double pitchJitterDegrees,
		int attemptsPerViewpoint,
		double eyeHeightBlocks
	) {
	}

	public record Lighting(int timeOfDayTicks, Weather weather) {
	}

	/** The selected level's visibility rule; ratio bounds are inclusive and null where unbounded. */
	public record Visibility(VisibilityRule rule, @Nullable Double minRatio, @Nullable Double maxRatio) {
	}

	/** {@code cubeFaceSize} is set only for {@link CaptureMode#PANORAMA_310}. */
	public record Capture(CaptureMode mode, int width, int height, @Nullable Integer cubeFaceSize) {
	}

	public record Quotas(int acceptedScenes, int targetPresentScenes, int targetAbsentScenes, int sceneAttemptLimit) {
	}

	public record Splits(double train, double validation, double test, SplitGrouping grouping) {
	}

	/**
	 * Where datasets are written and the free space a run keeps there; neither affects the
	 * generated data, so neither enters the resolved configuration.
	 */
	public record Output(Path root, long freeSpaceReserveBytes) {
	}

	public Path outputRoot() {
		return this.output.root();
	}

	public String sha256() {
		return CanonicalJson.sha256(toJson());
	}

	public String datasetId() {
		return Ids.datasetId(this.datasetName, sha256());
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("difficulty", this.difficulty);
		json.add("target", targetJson());
		json.add("generator", generatorJson());
		json.add("capture", captureJson());
		json.add("splits", splitsJson());
		return json;
	}

	private JsonObject targetJson() {
		JsonObject target = new JsonObject();
		target.addProperty("block_id", this.target.blockId());
		JsonArray states = new JsonArray();
		this.target.allowedBlockStates().forEach(states::add);
		target.add("allowed_block_states", states);
		target.addProperty("present_fraction", this.target.presentFraction());
		target.addProperty("target_present_scenes", this.quotas.targetPresentScenes());
		target.addProperty("target_absent_scenes", this.quotas.targetAbsentScenes());
		return target;
	}

	private JsonObject generatorJson() {
		JsonObject generator = new JsonObject();
		generator.addProperty("dataset_name", this.datasetName);
		generator.addProperty("seed", this.seed);
		generator.addProperty("accepted_scenes", this.quotas.acceptedScenes());
		generator.addProperty("scene_attempt_limit", this.quotas.sceneAttemptLimit());
		JsonObject arena = new JsonObject();
		arena.addProperty("width", this.arena.width());
		arena.addProperty("length", this.arena.length());
		arena.addProperty("height", this.arena.height());
		arena.addProperty("margin", this.arena.margin());
		generator.add("arena", arena);
		if (this.naturalRegion != null) {
			JsonObject region = new JsonObject();
			region.addProperty("core_width", this.naturalRegion.coreWidth());
			region.addProperty("core_length", this.naturalRegion.coreLength());
			region.addProperty("height", this.naturalRegion.height());
			region.addProperty("context_padding", this.naturalRegion.contextPadding());
			generator.add("natural_region", region);
		}

		JsonObject lighting = new JsonObject();
		lighting.addProperty("time_of_day_ticks", this.lighting.timeOfDayTicks());
		lighting.addProperty("weather", this.lighting.weather().jsonName());
		generator.add("lighting", lighting);
		JsonObject visibility = new JsonObject();
		visibility.addProperty("rule", this.visibility.rule().jsonName());
		visibility.add("min_ratio", this.visibility.minRatio() == null ? JsonNull.INSTANCE : new JsonPrimitive(this.visibility.minRatio()));
		visibility.add("max_ratio", this.visibility.maxRatio() == null ? JsonNull.INSTANCE : new JsonPrimitive(this.visibility.maxRatio()));
		generator.add("visibility", visibility);
		JsonObject algorithms = new JsonObject();
		algorithms.addProperty("config_hash", Algorithms.CONFIG_HASH);
		algorithms.addProperty("seeds", Algorithms.SEEDS);
		algorithms.addProperty("ids", Algorithms.IDS);
		algorithms.addProperty("scene_slots", Algorithms.SCENE_SLOTS);
		algorithms.addProperty("layout", Algorithms.LAYOUT);
		String sceneGenerator = Algorithms.sceneGenerator(this.difficulty);
		if (sceneGenerator != null) {
			algorithms.addProperty("scene_generator", sceneGenerator);
		}
		generator.add("algorithms", algorithms);
		return generator;
	}

	private JsonObject captureJson() {
		JsonObject capture = new JsonObject();
		capture.addProperty("mode", this.capture.mode().name());
		capture.addProperty("width", this.capture.width());
		capture.addProperty("height", this.capture.height());
		capture.add("projection", projectionJson());
		JsonObject mask = new JsonObject();
		mask.addProperty("encoding", MaskStatistics.ENCODING);
		mask.addProperty("meaning", MaskStatistics.MEANING);
		mask.addProperty("positive_value", 255);
		mask.addProperty("negative_value", 0);
		capture.add("mask", mask);
		JsonObject viewpoints = new JsonObject();
		viewpoints.addProperty("count", this.viewpoints.count());
		viewpoints.addProperty("distance_min_blocks", this.viewpoints.distanceMinBlocks());
		viewpoints.addProperty("distance_max_blocks", this.viewpoints.distanceMaxBlocks());
		viewpoints.addProperty("yaw_jitter_degrees", this.viewpoints.yawJitterDegrees());
		viewpoints.addProperty("pitch_jitter_degrees", this.viewpoints.pitchJitterDegrees());
		viewpoints.addProperty("attempts_per_viewpoint", this.viewpoints.attemptsPerViewpoint());
		viewpoints.addProperty("eye_height_blocks", this.viewpoints.eyeHeightBlocks());
		capture.add("viewpoints", viewpoints);
		capture.add("augmentations", new JsonArray());
		return capture;
	}

	private JsonObject projectionJson() {
		JsonObject projection = CaptureProjection.of(this.capture.mode(), this.capture.width(), this.capture.height(), this.capture.cubeFaceSize()).metadata();
		projection.addProperty("pixel_centre", "half-pixel");
		return projection;
	}

	private JsonObject splitsJson() {
		JsonObject splits = new JsonObject();
		JsonObject ratios = new JsonObject();
		ratios.addProperty("train", this.splits.train());
		ratios.addProperty("validation", this.splits.validation());
		ratios.addProperty("test", this.splits.test());
		splits.add("ratios", ratios);
		splits.addProperty("grouping", this.splits.grouping().jsonName());
		splits.addProperty("algorithm", Algorithms.SCENE_SLOTS);
		return splits;
	}
}
