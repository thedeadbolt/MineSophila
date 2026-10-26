package io.github.thedeadbolt.honeyfactory;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import io.github.thedeadbolt.honeyfactory.contract.BlockLayout;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SchemaVersion;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.dataset.SampleDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds small, internally consistent contract examples from a resolved configuration,
 * the way the generator will: resolve, plan slots, derive descriptors, then fill records.
 */
public final class ContractExamples {
	public static final Instant CREATED = Instant.parse("2026-10-23T09:00:00Z");
	public static final Instant COMPLETED = Instant.parse("2026-10-23T09:30:00Z");
	public static final int SIZE = 64;

	/** Four scenes, half with a target, two viewpoints each, 64x64 perspective, train/validation only. */
	public static final String CONFIG = """
		{
		  "dataset": {"name": "honey-l1-fixture", "seed": 42, "difficulty": 1},
		  "target": {"present_fraction": 0.5},
		  "viewpoints": {"count": 2},
		  "capture": {"width": 64, "height": 64},
		  "quotas": {"accepted_scenes": 4},
		  "splits": {"train": 0.5, "validation": 0.5, "test": 0.0},
		  "output": {"root": "/srv/honey-factory/datasets"}
		}
		""";

	private static final int[] ARENA_MIN = {0, 64, 0};
	private static final int[] ARENA_MAX = {31, 79, 31};
	private static final int[] TARGET = {16, 65, 16};

	/** {@code images} maps each dataset-relative image path to its PNG bytes. */
	public record Dataset(DatasetManifest manifest, List<SceneRecord> scenes, List<SampleRecord> samples, Map<String, byte[]> images) {
		public byte[] scenesJsonl() {
			return Jsonl.write(this.scenes.stream().map(SceneRecord::toJson).toList());
		}

		public byte[] samplesJsonl() {
			return Jsonl.write(this.samples.stream().map(SampleRecord::toJson).toList());
		}
	}

	private ContractExamples() {
	}

	public static ResolvedConfig config() {
		return ConfigResolver.resolve(CONFIG);
	}

	/**
	 * A complete dataset with real image files; present scenes show the target fully
	 * visible as a different square in each view.
	 */
	public static Dataset completeDataset() {
		ResolvedConfig config = config();
		List<SceneRecord> scenes = new ArrayList<>();
		List<SampleRecord> samples = new ArrayList<>();
		Map<String, byte[]> images = new LinkedHashMap<>();
		Map<Split, int[]> totals = new EnumMap<>(Split.class);
		for (Split split : Split.values()) {
			totals.put(split, new int[2]);
		}

		for (SceneSlots.Slot slot : SceneSlots.plan(config)) {
			SceneDescriptor scene = SceneDescriptor.of(config, slot, 0);
			scenes.add(scene(scene, config.viewpoints().count()));
			totals.get(slot.split())[0]++;
			for (int viewpoint = 0; viewpoint < config.viewpoints().count(); viewpoint++) {
				SampleDescriptor descriptor = scene.sample(viewpoint);
				byte[] mask = slot.targetPresent() ? (viewpoint == 0 ? square(24, 20, 16, 16) : square(30, 24, 10, 12)) : new byte[SIZE * SIZE];
				MaskStatistics visible = MaskStatistics.of(mask, SIZE, SIZE);
				SampleRecord.Target target = slot.targetPresent() ? SampleRecord.Target.of(true, true, visible, visible.positivePixels(), 8.0 - viewpoint) : absent();
				byte[] rgb = StoredPng.rgb(SIZE, SIZE, render(mask));
				byte[] maskPng = StoredPng.gray(SIZE, SIZE, mask);
				images.put(descriptor.rgbPath(), rgb);
				images.put(descriptor.maskPath(), maskPng);
				samples.add(sample(descriptor, target, imageFile(descriptor.rgbPath(), rgb), imageFile(descriptor.maskPath(), maskPng)));
				totals.get(slot.split())[1]++;
			}
		}

		Dataset generating = new Dataset(DatasetManifest.generating(config.toJson(), CREATED, software(), render(), provenance()), scenes, samples, images);
		Map<Split, DatasetManifest.SplitCount> splitCounts = new EnumMap<>(Split.class);
		totals.forEach((split, count) -> splitCounts.put(split, new DatasetManifest.SplitCount(count[0], count[1])));
		DatasetManifest base = generating.manifest();
		DatasetManifest complete = new DatasetManifest(
			base.schemaVersion(),
			base.datasetId(),
			base.contentVersion(),
			DatasetManifest.Lifecycle.COMPLETE,
			CREATED,
			COMPLETED,
			base.resolvedConfigSha256(),
			base.configuration(),
			splitCounts,
			base.software(),
			base.renderEnvironment(),
			new DatasetManifest.Counts(scenes.size(), scenes.size() + 1, scenes.size(), 1, 0, samples.size(), Map.of("visibility", 1)),
			base.provenance(),
			new DatasetManifest.Integrity(digest(generating.scenesJsonl()), digest(generating.samplesJsonl()), true, 0),
			base.licence(),
			base.intendedUse(),
			null
		);
		return new Dataset(complete, scenes, samples, images);
	}

	/** Grey wherever the mask is empty, honey-coloured where it is set. */
	private static byte[] render(byte[] mask) {
		byte[] rgb = new byte[mask.length * 3];
		for (int p = 0; p < mask.length; p++) {
			boolean honey = mask[p] != 0;
			rgb[p * 3] = (byte) (honey ? 230 : 140);
			rgb[p * 3 + 1] = (byte) (honey ? 160 : 140);
			rgb[p * 3 + 2] = (byte) (honey ? 30 : 140);
		}

		return rgb;
	}

	private static SampleRecord.ImageFile imageFile(String path, byte[] png) {
		return new SampleRecord.ImageFile(path, SampleRecord.MEDIA_TYPE, SIZE, SIZE, png.length, Sha256.hex(png));
	}

	public static SceneRecord scene(SceneDescriptor descriptor, int acceptedSamples) {
		boolean present = descriptor.slot().targetPresent();
		List<BlockLayout.Block> blocks = new ArrayList<>();
		blocks.add(new BlockLayout.Block(TARGET[0], TARGET[1] - 1, TARGET[2], "minecraft:white_concrete"));
		if (present) {
			blocks.add(new BlockLayout.Block(TARGET[0], TARGET[1], TARGET[2], "minecraft:honey_block"));
		}

		JsonObject gamerules = new JsonObject();
		gamerules.addProperty("advance_time", false);
		gamerules.addProperty("advance_weather", false);
		gamerules.addProperty("spawn_mobs", false);
		return new SceneRecord(
			SchemaVersion.CURRENT,
			descriptor.sceneId(),
			descriptor.split(),
			1,
			new SceneRecord.Generation(descriptor.slot().sceneIndex(), descriptor.attemptIndex(), descriptor.seeds(), "seed-v1",
				new SceneRecord.Parameters(TARGET.clone(), new int[acceptedSamples], new JsonObject())),
			new SceneRecord.Template("level-1-platform", 1, Sha256.hex("level-1-platform template v1".getBytes(StandardCharsets.UTF_8))),
			new SceneRecord.Arena("honey_factory:arena", ARENA_MIN.clone(), ARENA_MIN.clone(), ARENA_MAX.clone(), 32, 32, 16, 4),
			null,
			new SceneRecord.Target(present, "minecraft:honey_block", present ? "minecraft:honey_block" : null, present ? TARGET.clone() : null),
			new SceneRecord.Environment(6000, Weather.CLEAR, gamerules),
			BlockLayout.sha256(ARENA_MIN, ARENA_MAX, blocks),
			acceptedSamples
		);
	}

	public static SampleRecord sample(SampleDescriptor descriptor, SampleRecord.Target target, SampleRecord.ImageFile rgb, SampleRecord.ImageFile mask) {
		String id = descriptor.sampleId();
		return new SampleRecord(
			SchemaVersion.CURRENT,
			id,
			descriptor.sceneId(),
			descriptor.split(),
			descriptor.viewpointIndex(),
			rgb,
			mask,
			SampleRecord.Camera.of(new double[] {16.5, 65.0, 24.5 - descriptor.viewpointIndex()}, ConfigResolver.EYE_HEIGHT_BLOCKS, 180.0, 10.0),
			CaptureMode.PERSPECTIVE_90,
			new JsonObject(),
			new SampleRecord.CaptureInfo("op-" + id, 1000 + descriptor.viewpointIndex(), 1.0, true, 20),
			target,
			new SampleRecord.Conditions(6000, Weather.CLEAR, "minecraft:the_void", 15, 0, 0.5),
			null
		);
	}

	public static SampleRecord.Target fullyVisible() {
		MaskStatistics visible = MaskStatistics.of(square(24, 20, 16, 16), SIZE, SIZE);
		return SampleRecord.Target.of(true, true, visible, visible.positivePixels(), 8.0);
	}

	public static SampleRecord.Target partiallyVisible() {
		MaskStatistics visible = MaskStatistics.of(square(32, 20, 8, 16), SIZE, SIZE);
		return SampleRecord.Target.of(true, true, visible, 256, 7.0);
	}

	public static SampleRecord.Target fullyOccluded() {
		return SampleRecord.Target.of(true, true, MaskStatistics.of(new byte[SIZE * SIZE], SIZE, SIZE), 256, 8.0);
	}

	public static SampleRecord.Target subpixel() {
		return SampleRecord.Target.of(true, true, MaskStatistics.of(new byte[SIZE * SIZE], SIZE, SIZE), 0, 400.0);
	}

	public static SampleRecord.Target outOfView() {
		return SampleRecord.Target.of(true, false, MaskStatistics.of(new byte[SIZE * SIZE], SIZE, SIZE), 0, 8.0);
	}

	public static SampleRecord.Target absent() {
		return SampleRecord.Target.of(false, false, MaskStatistics.of(new byte[SIZE * SIZE], SIZE, SIZE), 0, null);
	}

	public static byte[] square(int x, int y, int width, int height) {
		byte[] mask = new byte[SIZE * SIZE];
		for (int row = y; row < y + height; row++) {
			for (int column = x; column < x + width; column++) {
				mask[row * SIZE + column] = (byte) MaskStatistics.POSITIVE;
			}
		}

		return mask;
	}

	public static DatasetManifest.Software software() {
		return new DatasetManifest.Software("26.3", "0.19.5", "0.163.0+26.3", "0.0.1", "25.0.1", "0123456789abcdef0123456789abcdef01234567", false);
	}

	public static DatasetManifest.RenderEnvironment render() {
		return new DatasetManifest.RenderEnvironment("OpenGL", "AMD Radeon RX 7600", "Mesa 26.2.4", true, 12, 0.5, "off", null, List.of(new DatasetManifest.ResourcePack("vanilla", null)), renderSettings());
	}

	public static JsonObject renderSettings() {
		JsonObject settings = new JsonObject();
		settings.addProperty("graphics_preset", "fancy");
		settings.addProperty("cutout_leaves", true);
		settings.addProperty("mipmap_levels", 4);
		settings.addProperty("texture_filtering", "none");
		return settings;
	}

	public static DatasetManifest.Provenance provenance() {
		return new DatasetManifest.Provenance("honey-factory 0.0.1", List.of(), List.of());
	}

	private static DatasetManifest.IndexDigest digest(byte[] bytes) {
		return new DatasetManifest.IndexDigest(bytes.length, Sha256.hex(bytes));
	}
}
