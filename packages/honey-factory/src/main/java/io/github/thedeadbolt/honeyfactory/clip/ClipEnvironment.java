package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * What clip generation needs from the running game (M12-2): the control loop's reset and
 * step, every frame captured from the controlled body, plus source patches and a
 * description of the software and render setup. The client adapts its environment runtime
 * to this; tests substitute a scripted body.
 *
 * <p>Futures complete on the thread that may issue the next request; {@link #executor()}
 * runs work on that thread.
 */
public interface ClipEnvironment {
	/** One captured frame: the camera's pose, its partial tick and the images. */
	record Frame(CameraPose pose, double partialTick, CapturedImages images) {
	}

	/** One control step's record and its frames, the last at the tick's end. */
	record Step(ControlStep step, List<Frame> frames) {
	}

	/** A clip's start: step 0 with its single frame, and the scene as built. */
	record Reset(Step step, GenerationEnvironment.SceneReady ready) {
	}

	/**
	 * Builds {@code plan}, places the body at {@code start} and captures step 0; later steps
	 * capture {@code framesPerTick} frames each.
	 */
	CompletableFuture<Reset> reset(ArenaLayout arena, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed, int framesPerTick);

	/** Runs one tick with {@code executed} and records {@code teacher} beside it. */
	CompletableFuture<Step> step(MotorCommand executed, MotorCommand teacher);

	/** Ends the control session; the world ticks again. */
	void end();

	CompletableFuture<PreparedPatch> preparePatch(GenerationEnvironment.PatchRequest request);

	DatasetManifest.Software software();

	DatasetManifest.RenderEnvironment renderEnvironment();

	/** Runs work on the thread that may issue the next request. */
	Executor executor();
}
