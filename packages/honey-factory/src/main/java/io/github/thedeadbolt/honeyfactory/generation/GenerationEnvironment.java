package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What dataset generation needs from the running game: the environment API's
 * {@code reset}, {@code setViewpoint} and {@code observe}, plus a description of the
 * software and render setup. The client adapts its environment runtime to this; tests
 * substitute a scripted environment.
 *
 * <p>Futures complete on the thread that may issue the next request.
 */
public interface GenerationEnvironment {
	/** A built and verified scene, with the player at the first viewpoint and rendering settled. */
	record SceneReady(String operationId, String layoutSha256, long settledTicks, JsonObject gamerules) {
	}

	/** The camera at a resolved pose, rendering settled. */
	record ViewReady(String operationId, CameraPose pose, long settledTicks) {
	}

	/** One capture at the current pose; light levels and biome are at the camera's eye. */
	record Observation(
		String operationId,
		long worldTick,
		double partialTick,
		CapturedImages images,
		@Nullable String biome,
		@Nullable Integer skyLight,
		@Nullable Integer blockLight
	) {
	}

	CompletableFuture<SceneReady> reset(ArenaLayout arena, ScenePlan plan, CameraPose firstViewpoint);

	CompletableFuture<ViewReady> setViewpoint(CameraPose pose);

	/** Captures from the current pose; {@code target} is the absolute target block, or null when absent. */
	CompletableFuture<Observation> observe(CaptureProjection projection, int @Nullable [] target);

	DatasetManifest.Software software();

	DatasetManifest.RenderEnvironment renderEnvironment();
}
