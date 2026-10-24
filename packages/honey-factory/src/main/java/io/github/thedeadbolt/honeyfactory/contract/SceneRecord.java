package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/** One world arrangement shared by all of its viewpoints: a line of {@code scenes.jsonl}. */
public record SceneRecord(
	String schemaVersion,
	String sceneId,
	Split split,
	int difficulty,
	Generation generation,
	Template template,
	Arena arena,
	@Nullable SourcePatch sourcePatch,
	Target target,
	Environment environment,
	String layoutSha256,
	int acceptedSampleCount
) {
	/** {@code parameters} is null in records written before generators reported them. */
	public record Generation(int sceneIndex, int attemptIndex, SceneSeeds seeds, String algorithm, @Nullable Parameters parameters) {
	}

	/**
	 * What the scene generator drew: the absolute block its viewpoints aim at, the accepted
	 * draw (attempt index) of each viewpoint in order, and generator-specific layout details.
	 */
	public record Parameters(int[] aimBlock, int[] viewpointDraws, JsonObject layout) {
		public Parameters {
			layout = layout.deepCopy();
		}

		@Override
		public JsonObject layout() {
			return this.layout.deepCopy();
		}
	}

	public record Template(String id, int version, String sha256) {
	}

	/** Integer block bounds are inclusive; core size and margin are the configured values. */
	public record Arena(String dimension, int[] origin, int[] boundsMin, int[] boundsMax, int coreWidth, int coreLength, int coreHeight, int margin) {
	}

	public record SourcePatch(long worldSeed, String dimension, int[] boundsMin, int[] boundsMax, String generatorVersion, String patchId) {
	}

	/** Block state and position are null exactly when the target is absent. */
	public record Target(boolean present, String blockId, @Nullable String blockState, int @Nullable [] position) {
	}

	/** Scene-wide world settings; {@code gamerules} maps rule names to their values. */
	public record Environment(int timeOfDayTicks, Weather weather, JsonObject gamerules) {
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", this.schemaVersion);
		json.addProperty("scene_id", this.sceneId);
		json.addProperty("split", this.split.jsonName());
		json.addProperty("difficulty", this.difficulty);
		JsonObject generation = new JsonObject();
		generation.addProperty("scene_index", this.generation.sceneIndex());
		generation.addProperty("attempt_index", this.generation.attemptIndex());
		JsonObject seeds = new JsonObject();
		seeds.addProperty("layout", this.generation.seeds().layout());
		seeds.addProperty("target", this.generation.seeds().target());
		seeds.addProperty("viewpoint", this.generation.seeds().viewpoint());
		generation.add("seeds", seeds);
		generation.addProperty("algorithm", this.generation.algorithm());
		Parameters parameters = this.generation.parameters();
		if (parameters != null) {
			JsonObject drawn = new JsonObject();
			drawn.add("aim_block", JsonWrite.array(parameters.aimBlock()));
			drawn.add("viewpoint_draws", JsonWrite.array(parameters.viewpointDraws()));
			drawn.add("layout", parameters.layout());
			generation.add("parameters", drawn);
		}

		json.add("generation", generation);
		JsonObject template = new JsonObject();
		template.addProperty("id", this.template.id());
		template.addProperty("version", this.template.version());
		template.addProperty("sha256", this.template.sha256());
		json.add("template", template);
		JsonObject arena = new JsonObject();
		arena.addProperty("dimension", this.arena.dimension());
		arena.add("origin", JsonWrite.array(this.arena.origin()));
		JsonObject bounds = new JsonObject();
		bounds.add("min", JsonWrite.array(this.arena.boundsMin()));
		bounds.add("max", JsonWrite.array(this.arena.boundsMax()));
		arena.add("bounds", bounds);
		JsonObject core = new JsonObject();
		core.addProperty("width", this.arena.coreWidth());
		core.addProperty("length", this.arena.coreLength());
		core.addProperty("height", this.arena.coreHeight());
		arena.add("core_size", core);
		arena.addProperty("margin", this.arena.margin());
		json.add("arena", arena);
		if (this.sourcePatch != null) {
			JsonObject patch = new JsonObject();
			patch.addProperty("world_seed", this.sourcePatch.worldSeed());
			patch.addProperty("dimension", this.sourcePatch.dimension());
			JsonObject patchBounds = new JsonObject();
			patchBounds.add("min", JsonWrite.array(this.sourcePatch.boundsMin()));
			patchBounds.add("max", JsonWrite.array(this.sourcePatch.boundsMax()));
			patch.add("bounds", patchBounds);
			patch.addProperty("generator_version", this.sourcePatch.generatorVersion());
			patch.addProperty("patch_id", this.sourcePatch.patchId());
			json.add("source_patch", patch);
		}

		JsonObject target = new JsonObject();
		target.addProperty("present", this.target.present());
		target.addProperty("block_id", this.target.blockId());
		if (this.target.present()) {
			target.addProperty("block_state", this.target.blockState());
			target.add("position", JsonWrite.array(this.target.position()));
		}

		json.add("target", target);
		JsonObject environment = new JsonObject();
		environment.addProperty("time_of_day_ticks", this.environment.timeOfDayTicks());
		environment.addProperty("weather", this.environment.weather().jsonName());
		environment.add("gamerules", this.environment.gamerules().deepCopy());
		json.add("environment", environment);
		json.addProperty("layout_sha256", this.layoutSha256);
		json.addProperty("accepted_sample_count", this.acceptedSampleCount);
		return json;
	}

	/** Parses and validates every single-record invariant of the contract. */
	public static SceneRecord fromJson(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		String version = SchemaVersion.require(fields);
		String sceneId = fields.string("scene_id");
		Split split = fields.enumValue("split", Split::fromJson);
		int difficulty = fields.integer32("difficulty", 1, 5);

		JsonFields generation = fields.object("generation");
		int sceneIndex = generation.integer32("scene_index", 0, Ids.MAX_SCENES - 1);
		if (!sceneId.equals(Ids.sceneId(sceneIndex))) {
			throw fields.error("scene_id", "must be '" + Ids.sceneId(sceneIndex) + "' for scene_index " + sceneIndex);
		}

		JsonFields seeds = generation.object("seeds");
		Generation generationRecord = new Generation(
			sceneIndex,
			generation.integer32("attempt_index", 0, Integer.MAX_VALUE),
			new SceneSeeds(seeds.integer("layout", 0, SceneSeeds.MAX_SEED), seeds.integer("target", 0, SceneSeeds.MAX_SEED), seeds.integer("viewpoint", 0, SceneSeeds.MAX_SEED)),
			generation.string("algorithm"),
			generation.has("parameters") ? parameters(generation.object("parameters")) : null
		);
		if (!SceneSeeds.ALGORITHM.equals(generationRecord.algorithm())) {
			throw generation.error("algorithm", "unsupported seed algorithm; expected " + SceneSeeds.ALGORITHM);
		}

		JsonFields template = fields.object("template");
		Template templateRecord = new Template(template.string("id"), template.integer32("version", 0, Integer.MAX_VALUE), template.sha256("sha256"));
		if (templateRecord.id().isEmpty()) {
			throw template.error("id", "must not be empty");
		}

		JsonFields arena = fields.object("arena");
		JsonFields bounds = arena.object("bounds");
		int[] min = bounds.integers("min", 3);
		int[] max = bounds.integers("max", 3);
		requireOrdered(bounds, min, max);
		JsonFields core = arena.object("core_size");
		Arena arenaRecord = new Arena(
			arena.namespacedId("dimension"),
			arena.integers("origin", 3),
			min,
			max,
			core.integer32("width", 1, 4096),
			core.integer32("length", 1, 4096),
			core.integer32("height", 1, 4096),
			arena.integer32("margin", 0, 4096)
		);

		SourcePatch patch = null;
		if (fields.has("source_patch")) {
			JsonFields source = fields.object("source_patch");
			JsonFields patchBounds = source.object("bounds");
			int[] patchMin = patchBounds.integers("min", 3);
			int[] patchMax = patchBounds.integers("max", 3);
			requireOrdered(patchBounds, patchMin, patchMax);
			patch = new SourcePatch(
				source.integer("world_seed", Long.MIN_VALUE, Long.MAX_VALUE),
				source.namespacedId("dimension"),
				patchMin,
				patchMax,
				source.string("generator_version"),
				source.string("patch_id")
			);
		}

		Target target = target(fields.object("target"), min, max);
		JsonFields environment = fields.object("environment");
		JsonObject gamerules = environment.object("gamerules").raw();
		for (String rule : gamerules.keySet()) {
			var value = gamerules.get(rule);
			boolean valid = value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean()
				|| value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0);
			if (!valid) {
				throw environment.object("gamerules").error(rule, "must be a boolean or an integer");
			}
		}

		Environment environmentRecord = new Environment(
			environment.integer32("time_of_day_ticks", 0, 23_999),
			environment.enumValue("weather", Weather::fromJson),
			gamerules
		);

		String layout = fields.sha256("layout_sha256");
		int accepted = fields.integer32("accepted_sample_count", 0, Ids.MAX_VIEWPOINTS);
		Parameters parameters = generationRecord.parameters();
		if (parameters != null) {
			JsonFields drawn = generation.object("parameters");
			if (parameters.viewpointDraws().length != accepted) {
				throw drawn.error("viewpoint_draws", "must list one draw per accepted sample (" + accepted + ")");
			}

			for (int i = 0; i < 3; i++) {
				if (parameters.aimBlock()[i] < min[i] || parameters.aimBlock()[i] > max[i]) {
					throw drawn.error("aim_block", "must lie inside the arena bounds");
				}
			}
		}

		return new SceneRecord(version, sceneId, split, difficulty, generationRecord, templateRecord, arenaRecord, patch, target, environmentRecord, layout, accepted);
	}

	private static Parameters parameters(JsonFields parameters) {
		int[] draws = parameters.integers("viewpoint_draws", parameters.array("viewpoint_draws").size());
		for (int i = 0; i < draws.length; i++) {
			if (draws[i] < 0) {
				throw new ContractException(parameters.path("viewpoint_draws") + "/" + i, "must not be negative");
			}
		}

		return new Parameters(parameters.integers("aim_block", 3), draws, parameters.object("layout").raw());
	}

	private static Target target(JsonFields target, int[] min, int[] max) {
		boolean present = target.bool("present");
		String blockId = target.namespacedId("block_id");
		if (!present) {
			if (target.has("block_state") || target.has("position")) {
				throw target.error(target.has("block_state") ? "block_state" : "position", "must be omitted when the target is absent");
			}

			return new Target(false, blockId, null, null);
		}

		String state = target.string("block_state");
		if (!state.equals(blockId) && !state.startsWith(blockId + "[")) {
			throw target.error("block_state", "is not a state of " + blockId);
		}

		int[] position = target.integers("position", 3);
		for (int i = 0; i < 3; i++) {
			if (position[i] < min[i] || position[i] > max[i]) {
				throw target.error("position", "must lie inside the arena bounds");
			}
		}

		return new Target(true, blockId, state, position);
	}

	private static void requireOrdered(JsonFields bounds, int[] min, int[] max) {
		for (int i = 0; i < 3; i++) {
			if (min[i] > max[i]) {
				throw bounds.error("min", "must not exceed max on any axis");
			}
		}
	}
}
