package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** One accepted RGB/mask pair: a line of {@code samples.jsonl}. */
public record SampleRecord(
	String schemaVersion,
	String sampleId,
	String sceneId,
	Split split,
	int viewpointIndex,
	ImageFile rgb,
	ImageFile mask,
	Camera camera,
	CaptureMode mode,
	JsonObject projectionOverrides,
	CaptureInfo capture,
	Target target,
	Conditions conditions,
	@Nullable Augmentation augmentation
) {
	public static final String MEDIA_TYPE = "image/png";

	private static final Pattern TRANSFORM = Pattern.compile("[a-z][a-z0-9_]{0,31}");
	private static final double VECTOR_TOLERANCE = 1e-9;

	public record ImageFile(String path, String mediaType, int width, int height, long bytes, String sha256) {
	}

	public record Camera(
		double[] playerPosition,
		double[] eyePosition,
		double eyeHeightBlocks,
		double yawDegrees,
		double pitchDegrees,
		double[] forward,
		double[] right,
		double[] up
	) {
		public static Camera of(double[] playerPosition, double eyeHeightBlocks, double yawDegrees, double pitchDegrees) {
			CameraBasis basis = CameraBasis.of(yawDegrees, pitchDegrees);
			double[] eye = {playerPosition[0], playerPosition[1] + eyeHeightBlocks, playerPosition[2]};
			return new Camera(playerPosition.clone(), eye, eyeHeightBlocks, yawDegrees, pitchDegrees, basis.forward(), basis.right(), basis.up());
		}
	}

	/** Readiness: whether chunks/lighting/rendering were confirmed ready and how many ticks were spent settling. */
	public record CaptureInfo(String operationId, long worldTick, double partialTick, boolean ready, int settlingTicks) {
	}

	public record Target(
		boolean targetPresent,
		boolean targetInProjection,
		int visiblePixels,
		int unoccludedPixels,
		@Nullable Double visibilityRatio,
		TargetStatus targetStatus,
		int @Nullable [] visibleBboxXywh,
		double @Nullable [] visibleCentroidXy,
		@Nullable Double distanceBlocks
	) {
		/** Builds consistent target metadata from presence, projection and the finalized masks. */
		public static Target of(boolean present, boolean inProjection, MaskStatistics visible, int unoccludedPixels, @Nullable Double distanceBlocks) {
			TargetStatus status = TargetStatus.derive(present, inProjection, visible.positivePixels(), unoccludedPixels);
			return new Target(
				present,
				inProjection,
				visible.positivePixels(),
				unoccludedPixels,
				TargetStatus.visibilityRatio(visible.positivePixels(), unoccludedPixels),
				status,
				visible.bboxXywh(),
				visible.centroidXy(),
				distanceBlocks
			);
		}
	}

	/** Lighting conditions; camera-local values are null where unavailable. */
	public record Conditions(
		int timeOfDayTicks,
		Weather weather,
		@Nullable String biome,
		@Nullable Integer skyLight,
		@Nullable Integer blockLight,
		@Nullable Double rgbMeanLuminance
	) {
	}

	public record Augmentation(String sourceSampleId, String transform, JsonObject parameters) {
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", this.schemaVersion);
		json.addProperty("sample_id", this.sampleId);
		json.addProperty("scene_id", this.sceneId);
		json.addProperty("split", this.split.jsonName());
		json.addProperty("viewpoint_index", this.viewpointIndex);
		JsonObject files = new JsonObject();
		files.add("rgb", fileJson(this.rgb));
		files.add("mask", fileJson(this.mask));
		json.add("files", files);
		JsonObject camera = new JsonObject();
		camera.add("player_position", JsonWrite.array(this.camera.playerPosition()));
		camera.add("eye_position", JsonWrite.array(this.camera.eyePosition()));
		camera.addProperty("eye_height_blocks", this.camera.eyeHeightBlocks());
		camera.addProperty("yaw_degrees", this.camera.yawDegrees());
		camera.addProperty("pitch_degrees", this.camera.pitchDegrees());
		camera.add("forward", JsonWrite.array(this.camera.forward()));
		camera.add("right", JsonWrite.array(this.camera.right()));
		camera.add("up", JsonWrite.array(this.camera.up()));
		json.add("camera", camera);
		JsonObject projection = new JsonObject();
		projection.addProperty("mode", this.mode.name());
		projection.add("overrides", this.projectionOverrides.deepCopy());
		json.add("projection", projection);
		JsonObject capture = new JsonObject();
		capture.addProperty("operation_id", this.capture.operationId());
		capture.addProperty("world_tick", this.capture.worldTick());
		capture.addProperty("partial_tick", this.capture.partialTick());
		capture.addProperty("ready", this.capture.ready());
		capture.addProperty("settling_ticks", this.capture.settlingTicks());
		json.add("capture", capture);
		json.add("target", targetJson(this.target));
		JsonObject conditions = new JsonObject();
		conditions.addProperty("time_of_day_ticks", this.conditions.timeOfDayTicks());
		conditions.addProperty("weather", this.conditions.weather().jsonName());
		conditions.add("biome", JsonWrite.nullable(this.conditions.biome()));
		conditions.add("sky_light", JsonWrite.nullable(this.conditions.skyLight()));
		conditions.add("block_light", JsonWrite.nullable(this.conditions.blockLight()));
		conditions.add("rgb_mean_luminance", JsonWrite.nullable(this.conditions.rgbMeanLuminance()));
		json.add("conditions", conditions);
		if (this.augmentation != null) {
			JsonObject augmentation = new JsonObject();
			augmentation.addProperty("source_sample_id", this.augmentation.sourceSampleId());
			augmentation.addProperty("transform", this.augmentation.transform());
			augmentation.add("parameters", this.augmentation.parameters().deepCopy());
			json.add("augmentation", augmentation);
		}

		return json;
	}

	private static JsonObject fileJson(ImageFile file) {
		JsonObject json = new JsonObject();
		json.addProperty("path", file.path());
		json.addProperty("media_type", file.mediaType());
		json.addProperty("width", file.width());
		json.addProperty("height", file.height());
		json.addProperty("bytes", file.bytes());
		json.addProperty("sha256", file.sha256());
		return json;
	}

	private static JsonObject targetJson(Target target) {
		JsonObject json = new JsonObject();
		json.addProperty("target_present", target.targetPresent());
		json.addProperty("target_in_projection", target.targetInProjection());
		json.addProperty("visible_pixels", target.visiblePixels());
		json.addProperty("unoccluded_pixels", target.unoccludedPixels());
		json.add("visibility_ratio", JsonWrite.nullable(target.visibilityRatio()));
		json.addProperty("target_status", target.targetStatus().jsonName());
		json.add("visible_bbox_xywh", JsonWrite.nullable(target.visibleBboxXywh()));
		json.add("visible_centroid_xy", JsonWrite.nullable(target.visibleCentroidXy()));
		if (target.distanceBlocks() != null) {
			json.addProperty("distance_blocks", target.distanceBlocks());
		}

		return json;
	}

	/** Parses and validates every single-record invariant of the contract. */
	public static SampleRecord fromJson(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		String version = SchemaVersion.require(fields);
		String sampleId = fields.string("sample_id");
		String sceneId = fields.string("scene_id");
		if (!Ids.SCENE_ID.matcher(sceneId).matches()) {
			throw fields.error("scene_id", "must match " + Ids.SCENE_ID.pattern());
		}

		Split split = fields.enumValue("split", Split::fromJson);
		int viewpoint = fields.integer32("viewpoint_index", 0, Ids.MAX_VIEWPOINTS - 1);
		Augmentation augmentation = fields.has("augmentation") ? augmentation(fields.object("augmentation")) : null;
		String baseId = Ids.sampleId(sceneId, viewpoint);
		String expectedId = augmentation == null ? baseId : baseId + "-" + augmentation.transform();
		if (!sampleId.equals(expectedId)) {
			throw fields.error("sample_id", "must be '" + expectedId + "' for this scene, viewpoint and augmentation");
		}

		if (augmentation != null && !augmentation.sourceSampleId().equals(baseId)) {
			throw fields.error("augmentation", "source_sample_id must be the base sample '" + baseId + "'");
		}

		JsonFields files = fields.object("files");
		ImageFile rgb = file(files.object("rgb"), Ids.rgbPath(split, sampleId));
		ImageFile mask = file(files.object("mask"), Ids.maskPath(split, sampleId));
		if (rgb.width() != mask.width() || rgb.height() != mask.height()) {
			throw files.error("mask", "dimensions " + mask.width() + "x" + mask.height() + " differ from RGB " + rgb.width() + "x" + rgb.height());
		}

		Camera camera = camera(fields.object("camera"));
		JsonFields projection = fields.object("projection");
		CaptureMode mode = projection.enumValue("mode", CaptureMode::fromJson);
		JsonObject overrides = projection.objectOrEmpty("overrides").raw();

		JsonFields capture = fields.object("capture");
		CaptureInfo captureInfo = new CaptureInfo(
			capture.string("operation_id"),
			capture.integer("world_tick", 0, Long.MAX_VALUE),
			capture.decimal("partial_tick", 0.0, 1.0),
			capture.bool("ready"),
			capture.integer32("settling_ticks", 0, Integer.MAX_VALUE)
		);
		if (captureInfo.operationId().isEmpty()) {
			throw capture.error("operation_id", "must not be empty");
		}

		Target target = target(fields.object("target"), mask);
		JsonFields conditions = fields.object("conditions");
		Conditions conditionsRecord = new Conditions(
			conditions.integer32("time_of_day_ticks", 0, 23_999),
			conditions.enumValue("weather", Weather::fromJson),
			conditions.nullableString("biome"),
			conditions.isNull("sky_light") ? null : conditions.integer32("sky_light", 0, 15),
			conditions.isNull("block_light") ? null : conditions.integer32("block_light", 0, 15),
			conditions.nullableDecimal("rgb_mean_luminance", 0.0, 1.0)
		);

		return new SampleRecord(version, sampleId, sceneId, split, viewpoint, rgb, mask, camera, mode, overrides, captureInfo, target, conditionsRecord, augmentation);
	}

	private static ImageFile file(JsonFields file, String expectedPath) {
		String path = file.string("path");
		if (!Ids.isRelativePath(path)) {
			throw file.error("path", "must be a dataset-relative path without '..' or leading '/'");
		}

		if (!path.equals(expectedPath)) {
			throw file.error("path", "must be '" + expectedPath + "' for this split, shard and sample");
		}

		String mediaType = file.string("media_type");
		if (!MEDIA_TYPE.equals(mediaType)) {
			throw file.error("media_type", "must be " + MEDIA_TYPE);
		}

		String sha256 = file.string("sha256");
		if (!Sha256.isHex(sha256)) {
			throw file.error("sha256", "must be 64 lowercase hexadecimal characters");
		}

		return new ImageFile(path, mediaType, file.integer32("width", 1, 65_536), file.integer32("height", 1, 65_536), file.integer("bytes", 1, Long.MAX_VALUE), sha256);
	}

	private static Camera camera(JsonFields camera) {
		double[] player = camera.decimals("player_position", 3);
		double[] eye = camera.decimals("eye_position", 3);
		double eyeHeight = camera.decimal("eye_height_blocks", 0.0, 4.0);
		double yaw = camera.decimal("yaw_degrees", -180.0, 180.0);
		double pitch = camera.decimal("pitch_degrees", -90.0, 90.0);
		Camera expected = Camera.of(player, eyeHeight, yaw, pitch);
		requireClose(camera, "eye_position", eye, expected.eyePosition());
		double[] forward = camera.decimals("forward", 3);
		double[] right = camera.decimals("right", 3);
		double[] up = camera.decimals("up", 3);
		requireClose(camera, "forward", forward, expected.forward());
		requireClose(camera, "right", right, expected.right());
		requireClose(camera, "up", up, expected.up());
		return new Camera(player, eye, eyeHeight, yaw, pitch, forward, right, up);
	}

	private static void requireClose(JsonFields fields, String name, double[] actual, double[] expected) {
		for (int i = 0; i < 3; i++) {
			if (Math.abs(actual[i] - expected[i]) > VECTOR_TOLERANCE) {
				throw fields.error(name, "is inconsistent with player position, eye height, yaw and pitch");
			}
		}
	}

	private static Target target(JsonFields target, ImageFile mask) {
		boolean present = target.bool("target_present");
		boolean inProjection = target.bool("target_in_projection");
		int visible = target.integer32("visible_pixels", 0, Integer.MAX_VALUE);
		int unoccluded = target.integer32("unoccluded_pixels", 0, Integer.MAX_VALUE);
		long maskPixels = (long) mask.width() * mask.height();
		if (unoccluded > maskPixels) {
			throw target.error("unoccluded_pixels", "exceeds the mask's " + maskPixels + " pixels");
		}

		TargetStatus derived;
		try {
			derived = TargetStatus.derive(present, inProjection, visible, unoccluded);
		} catch (IllegalArgumentException e) {
			throw target.error("visible_pixels", e.getMessage());
		}

		TargetStatus status = target.enumValue("target_status", TargetStatus::fromJson);
		if (status != derived) {
			throw target.error("target_status", "must be " + derived.jsonName() + " for this evidence, got " + status.jsonName());
		}

		Double ratio = target.nullableDecimal("visibility_ratio", 0.0, 1.0);
		if (!Objects.equals(ratio, TargetStatus.visibilityRatio(visible, unoccluded))) {
			throw target.error("visibility_ratio", "must be " + TargetStatus.visibilityRatio(visible, unoccluded) + " (visible_pixels / unoccluded_pixels)");
		}

		int[] bbox = target.nullableIntegers("visible_bbox_xywh", 4);
		double[] centroid = target.nullableDecimals("visible_centroid_xy", 2);
		if ((visible == 0) != (bbox == null) || (visible == 0) != (centroid == null)) {
			throw target.error("visible_bbox_xywh", "bounding box and centroid must be null exactly when visible_pixels is 0");
		}

		if (bbox != null) {
			if (bbox[0] < 0 || bbox[1] < 0 || bbox[2] < 1 || bbox[3] < 1 || bbox[0] + bbox[2] > mask.width() || bbox[1] + bbox[3] > mask.height()) {
				throw target.error("visible_bbox_xywh", "must lie inside the " + mask.width() + "x" + mask.height() + " mask");
			}

			if ((long) bbox[2] * bbox[3] < visible) {
				throw target.error("visible_bbox_xywh", "is too small to contain " + visible + " pixels");
			}

			if (centroid[0] < bbox[0] + 0.5 || centroid[0] > bbox[0] + bbox[2] - 0.5 || centroid[1] < bbox[1] + 0.5 || centroid[1] > bbox[1] + bbox[3] - 0.5) {
				throw target.error("visible_centroid_xy", "must lie within the pixel centres of the bounding box");
			}
		}

		Double distance = target.has("distance_blocks") ? target.decimal("distance_blocks", 0.0, 1.0e6) : null;
		if (present != (distance != null)) {
			throw target.error("distance_blocks", present ? "is required for a present target" : "must be omitted when the target is absent");
		}

		return new Target(present, inProjection, visible, unoccluded, ratio, status, bbox, centroid, distance);
	}

	private static Augmentation augmentation(JsonFields augmentation) {
		String transform = augmentation.string("transform");
		if (!TRANSFORM.matcher(transform).matches()) {
			throw augmentation.error("transform", "must match " + TRANSFORM.pattern());
		}

		return new Augmentation(augmentation.string("source_sample_id"), transform, augmentation.object("parameters").raw());
	}
}
