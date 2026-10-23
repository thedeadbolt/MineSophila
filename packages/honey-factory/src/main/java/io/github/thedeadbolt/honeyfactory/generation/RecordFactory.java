package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.SchemaVersion;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import io.github.thedeadbolt.honeyfactory.dataset.SampleDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.Template;
import java.util.List;

/** Builds the contract records of one dataset from generation results; it touches no files. */
final class RecordFactory {
	private final ResolvedConfig config;
	private final ArenaLayout arena;
	private final CaptureMode mode;

	RecordFactory(ResolvedConfig config, ArenaLayout arena, CaptureMode mode) {
		this.config = config;
		this.arena = arena;
		this.mode = mode;
	}

	SampleRecord sample(
		SampleDescriptor sample,
		DatasetWriter.SampleFiles files,
		GenerationEnvironment.ViewReady view,
		GenerationEnvironment.Observation observation,
		SampleRecord.Target target,
		ScenePlan plan
	) {
		CameraPose pose = view.pose();
		return new SampleRecord(
			SchemaVersion.CURRENT,
			sample.sampleId(),
			sample.sceneId(),
			sample.split(),
			sample.viewpointIndex(),
			files.rgb(),
			files.mask(),
			SampleRecord.Camera.of(new double[] {pose.x(), pose.y(), pose.z()}, this.config.viewpoints().eyeHeightBlocks(), pose.yawDegrees(), pose.pitchDegrees()),
			this.mode,
			new JsonObject(),
			new SampleRecord.CaptureInfo(observation.operationId(), observation.worldTick(), observation.partialTick(), true, (int) view.settledTicks()),
			target,
			new SampleRecord.Conditions(
				plan.timeOfDayTicks(),
				plan.weather(),
				observation.biome(),
				observation.skyLight(),
				observation.blockLight(),
				observation.images().meanLuminance()
			),
			null
		);
	}

	/** @param draws the accepted draw of each viewpoint, in order */
	SceneRecord scene(SceneDescriptor descriptor, GeneratedScene generated, List<Integer> draws, GenerationEnvironment.SceneReady ready, int acceptedSamples) {
		ResolvedConfig.Arena size = this.config.arena();
		ScenePlan plan = generated.plan();
		Template template = plan.template();
		int[] target = plan.targetPosition(this.arena);
		int[] aim = generated.aimBlock();
		BlockBox core = this.arena.core();
		SceneRecord.Parameters parameters = new SceneRecord.Parameters(
			new int[] {core.minX() + aim[0], core.minY() + aim[1], core.minZ() + aim[2]},
			draws.stream().mapToInt(Integer::intValue).toArray(),
			generated.layoutParameters()
		);
		return new SceneRecord(
			SchemaVersion.CURRENT,
			descriptor.sceneId(),
			descriptor.split(),
			this.config.difficulty(),
			new SceneRecord.Generation(descriptor.slot().sceneIndex(), descriptor.attemptIndex(), descriptor.seeds(), SceneSeeds.ALGORITHM, parameters),
			new SceneRecord.Template(template.id(), template.version(), template.sha256()),
			new SceneRecord.Arena(
				ArenaLayout.DIMENSION_ID,
				this.arena.core().min(),
				this.arena.resetVolume().min(),
				this.arena.resetVolume().max(),
				size.width(),
				size.length(),
				size.height(),
				size.margin()
			),
			null,
			new SceneRecord.Target(target != null, this.config.target().blockId(), plan.target() == null ? null : plan.target().state(), target),
			new SceneRecord.Environment(plan.timeOfDayTicks(), plan.weather(), ready.gamerules()),
			ready.layoutSha256(),
			acceptedSamples
		);
	}
}
