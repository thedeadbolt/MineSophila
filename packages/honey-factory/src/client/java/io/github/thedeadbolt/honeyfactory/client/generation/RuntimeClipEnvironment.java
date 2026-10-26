package io.github.thedeadbolt.honeyfactory.client.generation;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.clip.ClipEnvironment;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.world.Baseline;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;

/**
 * Clip generation through the control loop of the running game (M12-2): every frame is a
 * capture of the controlled body's camera, at the end of a tick or at a point it passed
 * during it. The clip's executed command runs in the control step's student slot and the
 * teacher's command is recorded beside it.
 */
public final class RuntimeClipEnvironment implements ClipEnvironment {
	private final EnvironmentRuntime runtime;
	private final RuntimeGenerationEnvironment stills;

	public RuntimeClipEnvironment(EnvironmentRuntime runtime) {
		this.runtime = runtime;
		this.stills = new RuntimeGenerationEnvironment(runtime);
	}

	@Override
	public CompletableFuture<Reset> reset(ArenaLayout arena, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed, int framesPerTick) {
		return this.runtime.controlReset(arena, plan, start, projection, seed, framesPerTick).thenApply(result -> new Reset(
			step(result),
			new GenerationEnvironment.SceneReady("control-session-" + result.session(), this.runtime.layoutSha256(), this.runtime.timing().resetSettleTicks(), Baseline.ruleValues())
		));
	}

	@Override
	public CompletableFuture<Step> step(MotorCommand executed, MotorCommand teacher) {
		return this.runtime.controlStep(executed, teacher, ControlStep.Executed.STUDENT).thenApply(RuntimeClipEnvironment::step);
	}

	/** The step's frames in order; the last is the tick's end, at the body's recorded pose. */
	private static Step step(EnvironmentRuntime.ControlResult result) {
		List<CaptureService.Capture> captures = result.frames();
		List<Frame> frames = new ArrayList<>(captures.size());
		for (int i = 0; i < captures.size(); i++) {
			CaptureService.Capture capture = captures.get(i);
			boolean end = i == captures.size() - 1;
			frames.add(new Frame(end ? result.step().truth().pose() : capture.pose(), end ? 1.0 : capture.partialTick(), capture.images()));
		}

		return new Step(result.step(), List.copyOf(frames));
	}

	@Override
	public void end() {
		Minecraft.getInstance().execute(this.runtime::controlEnd);
	}

	@Override
	public CompletableFuture<PreparedPatch> preparePatch(GenerationEnvironment.PatchRequest request) {
		return this.stills.preparePatch(request);
	}

	@Override
	public DatasetManifest.Software software() {
		return this.stills.software();
	}

	@Override
	public DatasetManifest.RenderEnvironment renderEnvironment() {
		return this.stills.renderEnvironment();
	}

	@Override
	public Executor executor() {
		return Minecraft.getInstance();
	}
}
