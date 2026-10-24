package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code dataset.json} manifest.
 *
 * <p>{@code configuration} holds the resolved-configuration members verbatim
 * ({@code difficulty}, {@code target}, {@code generator}, {@code capture} and
 * {@code splits} without {@code counts}); they are written at the manifest's top level,
 * and {@code resolved_config_sha256} is their JCS SHA-256.
 */
public record DatasetManifest(
	String schemaVersion,
	String datasetId,
	String contentVersion,
	Lifecycle lifecycle,
	Instant createdAtUtc,
	@Nullable Instant completedAtUtc,
	String resolvedConfigSha256,
	JsonObject configuration,
	@Nullable Map<Split, SplitCount> splitCounts,
	Software software,
	RenderEnvironment renderEnvironment,
	Counts counts,
	Provenance provenance,
	@Nullable Integrity integrity,
	String licence,
	String intendedUse
) {
	public static final String UNASSIGNED = "unassigned";
	public static final String RGB_ENCODING = "png-rgb8";

	public enum Lifecycle implements JsonEnum {
		GENERATING,
		COMPLETE,
		FAILED;

		public static Lifecycle fromJson(String name) {
			return JsonEnum.parse(Lifecycle.class, name);
		}
	}

	public record SplitCount(int scenes, int samples) {
	}

	/** {@code gitCommit} is null outside a Git checkout. */
	public record Software(String minecraft, String fabricLoader, String fabricApi, String mod, String java, @Nullable String gitCommit, boolean gitDirty) {
	}

	public record ResourcePack(String id, @Nullable String sha256) {
	}

	/**
	 * The client's rendering setup. {@code settings} records every other client option that
	 * can change captured pixels, by option name, as booleans, numbers or strings.
	 */
	public record RenderEnvironment(
		String graphicsBackend,
		String gpu,
		String driver,
		boolean improvedTransparency,
		int renderDistanceChunks,
		double gamma,
		String cloudStatus,
		@Nullable String shaderPack,
		List<ResourcePack> resourcePacks,
		JsonObject settings
	) {
		public RenderEnvironment {
			settings = settings.deepCopy();
		}

		@Override
		public JsonObject settings() {
			return this.settings.deepCopy();
		}
	}

	public record Counts(
		int requestedScenes,
		int attemptedScenes,
		int acceptedScenes,
		int rejectedScenes,
		int failedScenes,
		int acceptedSamples,
		Map<String, Integer> rejectionReasons
	) {
		public static Counts empty(int requestedScenes) {
			return new Counts(requestedScenes, 0, 0, 0, 0, 0, Map.of());
		}
	}

	public record Provenance(String tool, List<String> parentDatasetIds, List<String> derivations) {
	}

	public record IndexDigest(long bytes, String sha256) {
	}

	public record Integrity(IndexDigest scenes, IndexDigest samples, boolean validationPassed, int validationErrors) {
	}

	/**
	 * A new manifest in the {@code generating} state for a canonical resolved configuration,
	 * as produced by {@code ResolvedConfig.toJson()}.
	 */
	public static DatasetManifest generating(JsonObject configuration, Instant createdAt, Software software, RenderEnvironment renderEnvironment, Provenance provenance) {
		String sha256 = CanonicalJson.sha256(configuration);
		JsonObject generator = configuration.getAsJsonObject("generator");
		return new DatasetManifest(
			SchemaVersion.CURRENT,
			Ids.datasetId(generator.get("dataset_name").getAsString(), sha256),
			"1",
			Lifecycle.GENERATING,
			createdAt,
			null,
			sha256,
			configuration.deepCopy(),
			null,
			software,
			renderEnvironment,
			Counts.empty(generator.get("accepted_scenes").getAsInt()),
			provenance,
			null,
			UNASSIGNED,
			UNASSIGNED
		);
	}

	/** This manifest in a terminal or updated state; identity, configuration and environment are unchanged. */
	public DatasetManifest withState(
		Lifecycle lifecycle,
		@Nullable Instant completedAt,
		@Nullable Map<Split, SplitCount> splitCounts,
		Counts counts,
		@Nullable Integrity integrity
	) {
		return new DatasetManifest(
			this.schemaVersion,
			this.datasetId,
			this.contentVersion,
			lifecycle,
			this.createdAtUtc,
			completedAt,
			this.resolvedConfigSha256,
			this.configuration,
			splitCounts,
			this.software,
			this.renderEnvironment,
			counts,
			this.provenance,
			integrity,
			this.licence,
			this.intendedUse
		);
	}

	public int difficulty() {
		return this.configuration.get("difficulty").getAsInt();
	}

	public long seed() {
		return generator().get("seed").getAsLong();
	}

	public int acceptedScenesRequested() {
		return generator().get("accepted_scenes").getAsInt();
	}

	public int targetPresentScenes() {
		return this.configuration.getAsJsonObject("target").get("target_present_scenes").getAsInt();
	}

	public int targetAbsentScenes() {
		return this.configuration.getAsJsonObject("target").get("target_absent_scenes").getAsInt();
	}

	public String targetBlockId() {
		return this.configuration.getAsJsonObject("target").get("block_id").getAsString();
	}

	public int viewpointCount() {
		return capture().getAsJsonObject("viewpoints").get("count").getAsInt();
	}

	public CaptureMode captureMode() {
		return CaptureMode.fromJson(capture().get("mode").getAsString());
	}

	public int captureWidth() {
		return capture().get("width").getAsInt();
	}

	public int captureHeight() {
		return capture().get("height").getAsInt();
	}

	/** The level's visibility rule with its inclusive ratio bounds, null where unbounded. */
	public record Visibility(VisibilityRule rule, @Nullable Double minRatio, @Nullable Double maxRatio) {
		public boolean accepts(TargetStatus status, @Nullable Double ratio) {
			return this.rule.accepts(status, ratio, this.minRatio, this.maxRatio);
		}
	}

	public Visibility visibility() {
		JsonFields visibility = JsonFields.of(generator().getAsJsonObject("visibility"));
		return new Visibility(
			visibility.enumValue("rule", VisibilityRule::fromJson),
			visibility.nullableDecimal("min_ratio", 0.0, 1.0),
			visibility.nullableDecimal("max_ratio", 0.0, 1.0)
		);
	}

	/** Requested train, validation and test ratios, in that order. */
	public double[] splitRatios() {
		JsonFields ratios = JsonFields.of(this.configuration.getAsJsonObject("splits").getAsJsonObject("ratios"));
		return new double[] {ratios.decimal("train", 0.0, 1.0), ratios.decimal("validation", 0.0, 1.0), ratios.decimal("test", 0.0, 1.0)};
	}

	/** The resolved scene recipe, {@code generator.scene}. */
	public JsonObject sceneRecipe() {
		return this.configuration.getAsJsonObject("generator").getAsJsonObject("scene").deepCopy();
	}

	public SplitGrouping splitGrouping() {
		return SplitGrouping.fromJson(this.configuration.getAsJsonObject("splits").get("grouping").getAsString());
	}

	private JsonObject generator() {
		return this.configuration.getAsJsonObject("generator");
	}

	private JsonObject capture() {
		return this.configuration.getAsJsonObject("capture");
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", this.schemaVersion);
		json.addProperty("dataset_id", this.datasetId);
		json.addProperty("content_version", this.contentVersion);
		json.addProperty("lifecycle", this.lifecycle.jsonName());
		json.addProperty("created_at_utc", this.createdAtUtc.toString());
		json.add("completed_at_utc", JsonWrite.nullable(this.completedAtUtc == null ? null : this.completedAtUtc.toString()));
		json.addProperty("resolved_config_sha256", this.resolvedConfigSha256);
		for (String member : List.of("difficulty", "target", "generator", "capture", "splits")) {
			json.add(member, this.configuration.get(member).deepCopy());
		}

		if (this.splitCounts != null) {
			JsonObject counts = new JsonObject();
			for (Split split : Split.values()) {
				SplitCount count = this.splitCounts.get(split);
				JsonObject entry = new JsonObject();
				entry.addProperty("scenes", count.scenes());
				entry.addProperty("samples", count.samples());
				counts.add(split.jsonName(), entry);
			}

			json.getAsJsonObject("splits").add("counts", counts);
		}

		JsonObject software = new JsonObject();
		software.addProperty("minecraft", this.software.minecraft());
		software.addProperty("fabric_loader", this.software.fabricLoader());
		software.addProperty("fabric_api", this.software.fabricApi());
		software.addProperty("mod", this.software.mod());
		software.addProperty("java", this.software.java());
		software.add("git_commit", JsonWrite.nullable(this.software.gitCommit()));
		software.addProperty("git_dirty", this.software.gitDirty());
		json.add("software", software);
		json.add("render_environment", renderJson());
		json.add("storage", storageJson());
		JsonObject counts = new JsonObject();
		counts.addProperty("requested_scenes", this.counts.requestedScenes());
		counts.addProperty("attempted_scenes", this.counts.attemptedScenes());
		counts.addProperty("accepted_scenes", this.counts.acceptedScenes());
		counts.addProperty("rejected_scenes", this.counts.rejectedScenes());
		counts.addProperty("failed_scenes", this.counts.failedScenes());
		counts.addProperty("accepted_samples", this.counts.acceptedSamples());
		JsonObject reasons = new JsonObject();
		this.counts.rejectionReasons().forEach(reasons::addProperty);
		counts.add("rejection_reasons", reasons);
		json.add("counts", counts);
		JsonObject provenance = new JsonObject();
		provenance.addProperty("tool", this.provenance.tool());
		provenance.add("parent_dataset_ids", JsonWrite.strings(this.provenance.parentDatasetIds()));
		provenance.add("derivations", JsonWrite.strings(this.provenance.derivations()));
		json.add("provenance", provenance);
		if (this.integrity != null) {
			JsonObject integrity = new JsonObject();
			integrity.addProperty("hash_algorithm", Sha256.ALGORITHM);
			JsonObject indexes = new JsonObject();
			indexes.add("scenes", digestJson(this.integrity.scenes()));
			indexes.add("samples", digestJson(this.integrity.samples()));
			integrity.add("indexes", indexes);
			JsonObject validation = new JsonObject();
			validation.addProperty("passed", this.integrity.validationPassed());
			validation.addProperty("error_count", this.integrity.validationErrors());
			integrity.add("validation", validation);
			json.add("integrity", integrity);
		}

		json.addProperty("licence", this.licence);
		json.addProperty("intended_use", this.intendedUse);
		return json;
	}

	private JsonObject renderJson() {
		JsonObject render = new JsonObject();
		render.addProperty("graphics_backend", this.renderEnvironment.graphicsBackend());
		render.addProperty("gpu", this.renderEnvironment.gpu());
		render.addProperty("driver", this.renderEnvironment.driver());
		render.addProperty("improved_transparency", this.renderEnvironment.improvedTransparency());
		render.addProperty("render_distance_chunks", this.renderEnvironment.renderDistanceChunks());
		render.addProperty("gamma", this.renderEnvironment.gamma());
		render.addProperty("cloud_status", this.renderEnvironment.cloudStatus());
		render.add("shader_pack", JsonWrite.nullable(this.renderEnvironment.shaderPack()));
		JsonArray packs = new JsonArray();
		for (ResourcePack pack : this.renderEnvironment.resourcePacks()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("id", pack.id());
			entry.add("sha256", JsonWrite.nullable(pack.sha256()));
			packs.add(entry);
		}

		render.add("resource_packs", packs);
		render.add("settings", this.renderEnvironment.settings());
		return render;
	}

	/** The fixed layout of contract 1.x; readers derive paths from it, never from host paths. */
	static JsonObject storageJson() {
		JsonObject storage = new JsonObject();
		JsonObject indexes = new JsonObject();
		indexes.addProperty("scenes", "scenes.jsonl");
		indexes.addProperty("samples", "samples.jsonl");
		storage.add("indexes", indexes);
		JsonObject roots = new JsonObject();
		roots.addProperty("rgb", "rgb");
		roots.addProperty("mask", "masks");
		storage.add("image_roots", roots);
		JsonObject encodings = new JsonObject();
		encodings.addProperty("rgb", RGB_ENCODING);
		encodings.addProperty("mask", MaskStatistics.ENCODING);
		storage.add("encodings", encodings);
		storage.addProperty("sharding", Algorithms.IDS);
		storage.add("channels", JsonWrite.strings(List.of("rgb", "mask")));
		return storage;
	}

	private static JsonObject digestJson(IndexDigest digest) {
		JsonObject json = new JsonObject();
		json.addProperty("bytes", digest.bytes());
		json.addProperty("sha256", digest.sha256());
		return json;
	}

	/** Parses and validates every single-record invariant of the contract. */
	public static DatasetManifest fromJson(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		String version = SchemaVersion.require(fields);
		String datasetId = fields.string("dataset_id");
		String contentVersion = fields.string("content_version");
		if (contentVersion.isEmpty()) {
			throw fields.error("content_version", "must not be empty");
		}

		Lifecycle lifecycle = fields.enumValue("lifecycle", Lifecycle::fromJson);
		Instant created = timestamp(fields, "created_at_utc");
		Instant completed = fields.isNull("completed_at_utc") ? null : timestamp(fields, "completed_at_utc");

		JsonObject configuration = configuration(json, fields);
		String configSha = fields.string("resolved_config_sha256");
		String actualSha = CanonicalJson.sha256(configuration);
		if (!configSha.equals(actualSha)) {
			throw fields.error("resolved_config_sha256", "does not match the configuration members (expected " + actualSha + ")");
		}

		String name = fields.object("generator").string("dataset_name");
		String expectedId = Ids.isDatasetName(name) ? Ids.datasetId(name, configSha) : null;
		if (!datasetId.equals(expectedId)) {
			throw fields.error("dataset_id", "must be the dataset name plus the first 12 hex digits of resolved_config_sha256 (" + expectedId + ")");
		}

		Map<Split, SplitCount> splitCounts = splitCounts(fields.object("splits"));
		Software software = software(fields.object("software"));
		RenderEnvironment render = render(fields.object("render_environment"));
		JsonFields storage = fields.object("storage");
		if (!storage.raw().equals(storageJson())) {
			throw fields.error("storage", "must describe the contract " + SchemaVersion.MAJOR + ".x layout exactly");
		}

		Counts counts = counts(fields.object("counts"));
		Provenance provenance = provenance(fields.object("provenance"));
		Integrity integrity = fields.has("integrity") ? integrity(fields.object("integrity")) : null;
		DatasetManifest manifest = new DatasetManifest(
			version,
			datasetId,
			contentVersion,
			lifecycle,
			created,
			completed,
			configSha,
			configuration,
			splitCounts,
			software,
			render,
			counts,
			provenance,
			integrity,
			fields.string("licence"),
			fields.string("intended_use")
		);
		manifest.checkLifecycle(fields);
		return manifest;
	}

	private void checkLifecycle(JsonFields fields) {
		if (this.counts.requestedScenes() != acceptedScenesRequested()) {
			throw fields.object("counts").error("requested_scenes", "must equal generator.accepted_scenes (" + acceptedScenesRequested() + ")");
		}

		if (this.lifecycle == Lifecycle.GENERATING) {
			if (this.completedAtUtc != null) {
				throw fields.error("completed_at_utc", "must be null while generating");
			}

			if (this.integrity != null) {
				throw fields.error("integrity", "is populated only at completion");
			}

			if (this.splitCounts != null) {
				throw fields.object("splits").error("counts", "are final counts and appear only when complete");
			}

			return;
		}

		if (this.completedAtUtc == null || this.completedAtUtc.isBefore(this.createdAtUtc)) {
			throw fields.error("completed_at_utc", "must be a time no earlier than created_at_utc once " + this.lifecycle.jsonName());
		}

		if (this.lifecycle == Lifecycle.COMPLETE) {
			if (this.integrity == null || !this.integrity.validationPassed() || this.integrity.validationErrors() != 0) {
				throw fields.error("integrity", "a complete dataset needs integrity data with a passed, error-free validation");
			}

			if (this.counts.acceptedScenes() != this.counts.requestedScenes()) {
				throw fields.object("counts").error("accepted_scenes", "a complete dataset must reach the requested " + this.counts.requestedScenes() + " scenes");
			}

			if (this.splitCounts == null) {
				throw fields.object("splits").error("counts", "are required for a complete dataset");
			}

			int scenes = this.splitCounts.values().stream().mapToInt(SplitCount::scenes).sum();
			int samples = this.splitCounts.values().stream().mapToInt(SplitCount::samples).sum();
			if (scenes != this.counts.acceptedScenes() || samples != this.counts.acceptedSamples()) {
				throw fields.object("splits").error("counts", "must sum to the accepted scene and sample counts");
			}
		}
	}

	private static JsonObject configuration(JsonObject json, JsonFields fields) {
		JsonObject configuration = new JsonObject();
		fields.integer32("difficulty", 1, 5);
		for (String member : List.of("difficulty", "target", "generator", "capture", "splits")) {
			if (!json.has(member)) {
				throw fields.error(member, "is required");
			}

			if (!member.equals("difficulty")) {
				fields.object(member);
			}

			configuration.add(member, json.get(member).deepCopy());
		}

		configuration.getAsJsonObject("splits").remove("counts");
		checkConfiguration(JsonFields.of(configuration));
		return configuration;
	}

	/** Validates the configuration members needed by readers and consistency checks. */
	private static void checkConfiguration(JsonFields configuration) {
		JsonFields target = configuration.object("target");
		target.string("block_id");
		target.integer32("target_present_scenes", 0, Ids.MAX_SCENES);
		target.integer32("target_absent_scenes", 0, Ids.MAX_SCENES);
		JsonFields generator = configuration.object("generator");
		generator.integer("seed", 0, SceneSeeds.MAX_SEED);
		int accepted = generator.integer32("accepted_scenes", 1, Ids.MAX_SCENES);
		if (target.integer32("target_present_scenes", 0, Ids.MAX_SCENES) + target.integer32("target_absent_scenes", 0, Ids.MAX_SCENES) != accepted) {
			throw target.error("target_present_scenes", "present and absent quotas must sum to generator.accepted_scenes (" + accepted + ")");
		}

		JsonFields capture = configuration.object("capture");
		capture.enumValue("mode", CaptureMode::fromJson);
		capture.integer32("width", 16, 16_384);
		capture.integer32("height", 16, 16_384);
		capture.object("viewpoints").integer32("count", 1, Ids.MAX_VIEWPOINTS);
		JsonFields mask = capture.object("mask");
		requireConstant(mask, "encoding", MaskStatistics.ENCODING);
		requireConstant(mask, "meaning", MaskStatistics.MEANING);
		JsonFields algorithms = generator.object("algorithms");
		requireConstant(algorithms, "config_hash", Algorithms.CONFIG_HASH);
		requireConstant(algorithms, "seeds", Algorithms.SEEDS);
		requireConstant(algorithms, "ids", Algorithms.IDS);
		requireConstant(algorithms, "scene_slots", Algorithms.SCENE_SLOTS);
		requireConstant(algorithms, "layout", Algorithms.LAYOUT);
		JsonFields visibility = generator.object("visibility");
		visibility.enumValue("rule", VisibilityRule::fromJson);
		visibility.nullableDecimal("min_ratio", 0.0, 1.0);
		visibility.nullableDecimal("max_ratio", 0.0, 1.0);
		JsonFields splits = configuration.object("splits");
		JsonFields ratios = splits.object("ratios");
		for (String split : List.of("train", "validation", "test")) {
			ratios.decimal(split, 0.0, 1.0);
		}

		boolean natural = "natural_patch".equals(generator.object("scene").object("base").string("type"));
		SplitGrouping grouping = splits.enumValue("grouping", SplitGrouping::fromJson);
		if (natural == (grouping == SplitGrouping.SCENE)) {
			throw splits.error("grouping", natural ? "a natural_patch base groups splits by source_region or source_world" : "a platform base groups splits by scene");
		}

		requireConstant(splits, "algorithm", Algorithms.slotsFor(grouping));
	}

	private static void requireConstant(JsonFields fields, String name, String expected) {
		if (!expected.equals(fields.string(name))) {
			throw fields.error(name, "must be " + expected);
		}
	}

	private static @Nullable Map<Split, SplitCount> splitCounts(JsonFields splits) {
		if (!splits.has("counts")) {
			return null;
		}

		JsonFields counts = splits.object("counts");
		Map<Split, SplitCount> result = new EnumMap<>(Split.class);
		for (Split split : Split.values()) {
			JsonFields entry = counts.object(split.jsonName());
			result.put(split, new SplitCount(entry.integer32("scenes", 0, Ids.MAX_SCENES), entry.integer32("samples", 0, Integer.MAX_VALUE)));
		}

		return result;
	}

	private static Software software(JsonFields software) {
		String commit = software.nullableString("git_commit");
		if (commit != null && !commit.matches("[0-9a-f]{40}")) {
			throw software.error("git_commit", "must be a full 40-character lowercase commit hash or null");
		}

		return new Software(
			software.string("minecraft"),
			software.string("fabric_loader"),
			software.string("fabric_api"),
			software.string("mod"),
			software.string("java"),
			commit,
			software.bool("git_dirty")
		);
	}

	private static RenderEnvironment render(JsonFields render) {
		if (!render.bool("improved_transparency")) {
			throw render.error("improved_transparency", "must be true: masks require improved transparency (ADR 0005)");
		}

		JsonObject settings = new JsonObject();
		if (render.has("settings")) {
			JsonFields fields = render.object("settings");
			settings = fields.raw();
			for (String name : settings.keySet()) {
				if (!settings.get(name).isJsonPrimitive()) {
					throw fields.error(name, "must be a boolean, number or string");
				}
			}
		}

		List<ResourcePack> packs = render.objects("resource_packs").stream()
			.map(pack -> new ResourcePack(pack.string("id"), pack.nullableString("sha256")))
			.toList();
		return new RenderEnvironment(
			render.string("graphics_backend"),
			render.string("gpu"),
			render.string("driver"),
			true,
			render.integer32("render_distance_chunks", 2, 64),
			render.decimal("gamma", 0.0, 1.0),
			render.string("cloud_status"),
			render.nullableString("shader_pack"),
			packs,
			settings
		);
	}

	private static Counts counts(JsonFields counts) {
		int requested = counts.integer32("requested_scenes", 1, Ids.MAX_SCENES);
		int attempted = counts.integer32("attempted_scenes", 0, Integer.MAX_VALUE);
		int accepted = counts.integer32("accepted_scenes", 0, Ids.MAX_SCENES);
		int rejected = counts.integer32("rejected_scenes", 0, Integer.MAX_VALUE);
		int failed = counts.integer32("failed_scenes", 0, Integer.MAX_VALUE);
		if ((long) accepted + rejected + failed != attempted) {
			throw counts.error("attempted_scenes", "must equal accepted + rejected + failed scenes");
		}

		if (accepted > requested) {
			throw counts.error("accepted_scenes", "must not exceed requested_scenes");
		}

		JsonObject reasons = counts.object("rejection_reasons").raw();
		Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
		JsonFields reasonFields = counts.object("rejection_reasons");
		long reasonTotal = 0;
		for (String reason : reasons.keySet()) {
			int count = reasonFields.integer32(reason, 0, Integer.MAX_VALUE);
			rejectionReasons.put(reason, count);
			reasonTotal += count;
		}

		if (reasonTotal != rejected) {
			throw counts.error("rejection_reasons", "must sum to rejected_scenes (" + rejected + ")");
		}

		return new Counts(requested, attempted, accepted, rejected, failed, counts.integer32("accepted_samples", 0, Integer.MAX_VALUE), Map.copyOf(rejectionReasons));
	}

	private static Provenance provenance(JsonFields provenance) {
		List<String> parents = provenance.strings("parent_dataset_ids");
		for (String parent : parents) {
			if (!Ids.DATASET_ID.matcher(parent).matches()) {
				throw provenance.error("parent_dataset_ids", "'" + parent + "' is not a dataset ID");
			}
		}

		return new Provenance(provenance.string("tool"), parents, provenance.strings("derivations"));
	}

	private static Integrity integrity(JsonFields integrity) {
		if (!Sha256.ALGORITHM.equals(integrity.string("hash_algorithm"))) {
			throw integrity.error("hash_algorithm", "must be " + Sha256.ALGORITHM);
		}

		JsonFields indexes = integrity.object("indexes");
		JsonFields validation = integrity.object("validation");
		return new Integrity(digest(indexes.object("scenes")), digest(indexes.object("samples")), validation.bool("passed"), validation.integer32("error_count", 0, Integer.MAX_VALUE));
	}

	private static IndexDigest digest(JsonFields digest) {
		String sha = digest.sha256("sha256");
		return new IndexDigest(digest.integer("bytes", 0, Long.MAX_VALUE), sha);
	}

	private static Instant timestamp(JsonFields fields, String name) {
		String value = fields.string(name);
		if (!value.endsWith("Z")) {
			throw fields.error(name, "must be an RFC 3339 UTC timestamp ending in Z");
		}

		try {
			return Instant.parse(value);
		} catch (DateTimeParseException e) {
			throw fields.error(name, "must be an RFC 3339 UTC timestamp ending in Z");
		}
	}
}
