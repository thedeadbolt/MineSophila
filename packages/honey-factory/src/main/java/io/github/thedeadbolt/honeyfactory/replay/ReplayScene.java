package io.github.thedeadbolt.honeyfactory.replay;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerator;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerators;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One recorded scene of a dataset, read and checked for replay. Loading only reads files:
 * the dataset is never written. It re-resolves the manifest's configuration with this
 * build and requires the recorded configuration hash, so a changed default, recipe or
 * algorithm version is reported as unavailable rather than approximated; it then derives
 * the scene's slot and seeds again and requires the recorded ones.
 *
 * <p>{@link #regenerate} rebuilds the scene plan and checks it against the record: the
 * template or source patch, target, aim block, layout parameters, every recorded viewpoint
 * pose and, on a platform base, the layout hash. Only the world can confirm a natural
 * scene's layout hash, after its reset. A natural scene's source patch must be read from
 * the world that generated it: Minecraft's chunk decoration depends on the order chunks
 * generate in, so another world with the same seed decorates the same patch differently.
 */
public final class ReplayScene {
	private static final double POSE_TOLERANCE = 1e-9;
	private static final String OTHER_WORLD = "; Minecraft decorates chunks in an order-dependent way, so a level-5 scene replays exactly"
		+ " only in the dataset world that generated it, or a copy of it";

	private final Path directory;
	private final DatasetManifest manifest;
	private final ResolvedConfig config;
	private final SceneRecord scene;
	private final List<SampleRecord> samples;
	private final int focus;
	private final SceneDescriptor descriptor;
	private final SceneGenerator generator;
	private final ArenaLayout arena;

	private ReplayScene(Path directory, DatasetManifest manifest, ResolvedConfig config, SceneRecord scene, List<SampleRecord> samples, int focus) {
		this.directory = directory;
		this.manifest = manifest;
		this.config = config;
		this.scene = scene;
		this.samples = List.copyOf(samples);
		this.focus = focus;
		this.generator = SceneGenerators.forConfig(config);
		this.arena = ArenaLayout.of(config.scene().base(), config.arena());
		SceneRecord.Generation generation = scene.generation();
		List<SceneSlots.Slot> slots = SceneSlots.plan(config);
		if (generation.sceneIndex() >= slots.size()) {
			throw new ReplayException(ReplayException.Reason.MISMATCH, "scene index " + generation.sceneIndex() + " exceeds the dataset's " + slots.size() + " scenes");
		}

		SceneSlots.Slot slot = slots.get(generation.sceneIndex());
		if (!slot.sceneId().equals(scene.sceneId()) || slot.split() != scene.split() || slot.targetPresent() != scene.target().present()) {
			throw new ReplayException(ReplayException.Reason.MISMATCH, "scene " + scene.sceneId() + " is not the slot its scene index assigns: " + slot);
		}

		this.descriptor = SceneDescriptor.of(config, slot, generation.attemptIndex());
		if (!this.descriptor.seeds().equals(generation.seeds())) {
			throw new ReplayException(ReplayException.Reason.MISMATCH, "scene " + scene.sceneId() + " records seeds that do not derive from its dataset seed, scene index and attempt index");
		}
	}

	/**
	 * Reads scene or sample {@code id} of the dataset in {@code directory}; for a sample,
	 * its scene is loaded and the sample's viewpoint is the focus.
	 *
	 * @throws ReplayException NOT_FOUND, INVALID_DATASET, UNAVAILABLE or MISMATCH
	 */
	public static ReplayScene load(Path directory, String id) {
		boolean sampleId = Ids.SAMPLE_ID.matcher(id).matches();
		if (!sampleId && !Ids.SCENE_ID.matcher(id).matches()) {
			throw new ReplayException(ReplayException.Reason.NOT_FOUND, id + " is neither a scene ID (s000000) nor a sample ID (s000000-v00)");
		}

		DatasetManifest manifest;
		List<JsonObject> sceneLines;
		List<JsonObject> sampleLines;
		try {
			manifest = DatasetManifest.fromJson(JsonFields.parseObject(new String(read(directory, "dataset.json"), StandardCharsets.UTF_8)));
			sceneLines = Jsonl.read(read(directory, "scenes.jsonl"), "scenes.jsonl");
			sampleLines = Jsonl.read(read(directory, "samples.jsonl"), "samples.jsonl");
		} catch (ContractException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, e.getMessage());
		}

		String sceneId = sampleId ? id.substring(0, id.indexOf('-')) : id;
		SceneRecord scene = null;
		try {
			for (JsonObject line : sceneLines) {
				if (sceneId.equals(line.get("scene_id").getAsString())) {
					scene = SceneRecord.fromJson(line);
					break;
				}
			}

			if (scene == null) {
				throw new ReplayException(ReplayException.Reason.NOT_FOUND, "dataset " + manifest.datasetId() + " has no scene " + sceneId);
			}

			List<SampleRecord> samples = new ArrayList<>();
			for (JsonObject line : sampleLines) {
				if (sceneId.equals(line.get("scene_id").getAsString()) && !line.has("augmentation")) {
					samples.add(SampleRecord.fromJson(line));
				}
			}

			samples.sort(Comparator.comparingInt(SampleRecord::viewpointIndex));
			if (samples.isEmpty()) {
				throw new ReplayException(ReplayException.Reason.NOT_FOUND, "scene " + sceneId + " has no samples");
			}

			int focus = 0;
			if (sampleId) {
				focus = samples.stream().map(SampleRecord::sampleId).toList().indexOf(id);
				if (focus < 0) {
					throw new ReplayException(ReplayException.Reason.NOT_FOUND, "dataset " + manifest.datasetId() + " has no sample " + id);
				}
			}

			return new ReplayScene(directory, manifest, resolve(manifest, directory), scene, samples, focus);
		} catch (ContractException | NullPointerException | IllegalStateException | UnsupportedOperationException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "cannot read the records of " + sceneId + ": " + e.getMessage());
		}
	}

	private static byte[] read(Path directory, String name) {
		try {
			return Files.readAllBytes(directory.resolve(name));
		} catch (NoSuchFileException e) {
			throw new ReplayException(ReplayException.Reason.NOT_FOUND, directory.resolve(name) + " does not exist");
		} catch (IOException e) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "cannot read " + directory.resolve(name) + ": " + e.getMessage());
		}
	}

	/** The configuration this build resolves from the manifest, when it has the recorded hash. */
	private static ResolvedConfig resolve(DatasetManifest manifest, Path directory) {
		ResolvedConfig config;
		try {
			config = ConfigResolver.fromManifest(manifest.configuration(), directory.toAbsolutePath().getParent());
		} catch (ContractException e) {
			throw new ReplayException(ReplayException.Reason.UNAVAILABLE, "this build cannot resolve the recorded configuration: " + e.getMessage());
		}

		if (!config.sha256().equals(manifest.resolvedConfigSha256())) {
			throw new ReplayException(ReplayException.Reason.UNAVAILABLE, "this build resolves the recorded configuration to " + config.sha256() + ", not the recorded "
				+ manifest.resolvedConfigSha256() + "; a setting or algorithm version differs from mod " + manifest.software().mod() + " at commit " + manifest.software().gitCommit());
		}

		return config;
	}

	/** The source patch the scene stands on, to prepare before {@link #regenerate}; null on a platform base. */
	public GenerationEnvironment.@Nullable PatchRequest patchRequest() {
		if (!(this.generator instanceof NaturalGenerator natural)) {
			return null;
		}

		return new GenerationEnvironment.PatchRequest(this.config.seed(), natural.base(), this.descriptor.slot().group(), this.config.target().blockId());
	}

	/**
	 * Generates the scene again and checks it against the record; see the class description.
	 *
	 * @param patch the prepared {@link #patchRequest()}, or null on a platform base
	 * @throws ReplayException UNAVAILABLE for a missing template version, MISMATCH for any other difference
	 */
	public GeneratedScene regenerate(@Nullable PreparedPatch patch) {
		GeneratedScene generated;
		if (this.generator instanceof NaturalGenerator natural) {
			if (patch == null) {
				throw new IllegalArgumentException("a natural scene needs its prepared source patch");
			}

			generated = natural.scene(this.descriptor.seeds(), this.scene.target().present(), patch);
		} else {
			generated = this.generator.scene(this.descriptor.seeds(), this.scene.target().present());
		}

		ScenePlan plan = generated.plan();
		SceneRecord.Template recorded = this.scene.template();
		String sha = plan.patch() == null ? plan.template().sha256() : plan.patch().contentSha256();
		if (!recorded.id().equals(plan.template().id()) || recorded.version() != plan.template().version()) {
			throw new ReplayException(ReplayException.Reason.UNAVAILABLE, "template " + recorded.id() + " version " + recorded.version() + " is not in this build, which has "
				+ plan.template().id() + " version " + plan.template().version());
		}

		if (this.scene.sourcePatch() != null && (plan.patch() == null || !this.scene.sourcePatch().patchId().equals(plan.patch().patchId()))) {
			throw mismatch("source patch " + (plan.patch() == null ? "none" : plan.patch().patchId()) + " differs from the recorded " + this.scene.sourcePatch().patchId() + OTHER_WORLD);
		}

		if (!recorded.sha256().equals(sha)) {
			throw mismatch((plan.patch() == null ? "template content " : "source patch terrain ") + sha + " differs from the recorded " + recorded.sha256()
				+ (plan.patch() == null ? "" : OTHER_WORLD));
		}

		if (!Arrays.equals(plan.targetPosition(this.arena), this.scene.target().position())) {
			throw mismatch("target " + Arrays.toString(plan.targetPosition(this.arena)) + " differs from the recorded " + Arrays.toString(this.scene.target().position()));
		}

		SceneRecord.Parameters parameters = this.scene.generation().parameters();
		if (parameters != null) {
			int[] min = this.arena.core().min();
			int[] aim = generated.aimBlock();
			if (!Arrays.equals(new int[] {min[0] + aim[0], min[1] + aim[1], min[2] + aim[2]}, parameters.aimBlock())) {
				throw mismatch("aim block differs from the recorded " + Arrays.toString(parameters.aimBlock()));
			}

			if (!generated.layoutParameters().equals(parameters.layout())) {
				throw mismatch("layout parameters " + generated.layoutParameters() + " differ from the recorded " + parameters.layout());
			}

			for (SampleRecord sample : this.samples) {
				checkViewpoint(generated, sample, parameters.viewpointDraws());
			}
		}

		if (plan.patch() == null && !plan.layoutSha256(this.arena).equals(this.scene.layoutSha256())) {
			throw mismatch("layout hash " + plan.layoutSha256(this.arena) + " differs from the recorded " + this.scene.layoutSha256());
		}

		return generated;
	}

	private void checkViewpoint(GeneratedScene generated, SampleRecord sample, int[] draws) {
		int index = sample.viewpointIndex();
		if (index >= draws.length) {
			throw mismatch("viewpoint " + index + " has no recorded draw");
		}

		if (!(this.generator.viewpoint(generated, index, draws[index]) instanceof ViewpointCandidate.Placeable placeable)) {
			throw mismatch("draw " + draws[index] + " of viewpoint " + index + " is no longer placeable");
		}

		CameraPose expected = placeable.plan().pose(this.arena);
		CameraPose recorded = pose(sample);
		if (Math.abs(expected.x() - recorded.x()) > POSE_TOLERANCE || Math.abs(expected.y() - recorded.y()) > POSE_TOLERANCE
			|| Math.abs(expected.z() - recorded.z()) > POSE_TOLERANCE || expected.yawDegrees() != recorded.yawDegrees() || expected.pitchDegrees() != recorded.pitchDegrees()) {
			throw mismatch("viewpoint " + index + " regenerates at " + expected + ", not the recorded " + recorded);
		}
	}

	private ReplayException mismatch(String detail) {
		return new ReplayException(ReplayException.Reason.MISMATCH, "scene " + this.scene.sceneId() + ": " + detail);
	}

	/** The recorded camera pose of {@code sample}. */
	public static CameraPose pose(SampleRecord sample) {
		double[] feet = sample.camera().playerPosition();
		return new CameraPose(feet[0], feet[1], feet[2], (float) sample.camera().yawDegrees(), (float) sample.camera().pitchDegrees());
	}

	/** Recorded camera poses in viewpoint order. */
	public List<CameraPose> poses() {
		return this.samples.stream().map(ReplayScene::pose).toList();
	}

	public Path directory() {
		return this.directory;
	}

	public DatasetManifest manifest() {
		return this.manifest;
	}

	public ResolvedConfig config() {
		return this.config;
	}

	/**
	 * The world that generated this scene, as a dataset merged from slices records it, or
	 * null for a single run's dataset, whose world is the one copied beside it.
	 */
	public @Nullable String world() {
		return this.manifest.worldOf(this.scene.generation().sceneIndex());
	}

	public SceneRecord scene() {
		return this.scene;
	}

	/** The scene's base samples in viewpoint order. */
	public List<SampleRecord> samples() {
		return this.samples;
	}

	/** Index into {@link #samples()} of the sample asked for, or 0 for a scene. */
	public int focus() {
		return this.focus;
	}

	public ArenaLayout arena() {
		return this.arena;
	}

	public CaptureProjection projection() {
		ResolvedConfig.Capture capture = this.config.capture();
		return CaptureProjection.of(capture.mode(), capture.width(), capture.height(), capture.cubeFaceSize());
	}

	/** Where the dataset's root-relative {@code path} is. */
	public Path file(String path) {
		return this.directory.resolve(path);
	}
}
