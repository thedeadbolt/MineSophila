package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What dataset generation needs from the running game: the environment API's
 * {@code reset}, {@code setViewpoint} and {@code observe}, source patches for natural
 * bases, and a description of the software and render setup. The client adapts its environment runtime to this; tests
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

	/** What level-5 generation asks for: patch {@code patchIndex} of the dataset with {@code datasetSeed}. */
	record PatchRequest(long datasetSeed, SceneBase.NaturalTerrain base, int patchIndex, String targetBlockId) {
	}

	/**
	 * Chooses source patch {@code request.patchIndex()} and reads its terrain; fails when every
	 * candidate is rejected.
	 */
	CompletableFuture<PreparedPatch> preparePatch(PatchRequest request);

	CompletableFuture<SceneReady> reset(ArenaLayout arena, ScenePlan plan, CameraPose firstViewpoint);

	CompletableFuture<ViewReady> setViewpoint(CameraPose pose);

	/**
	 * Captures from each of {@code poses} in one operation, the camera moving on the client
	 * only, and returns the observations in order; {@code target} is the absolute target
	 * block, or null when absent. Poses lie in the current scene near the camera it was
	 * readied at.
	 */
	CompletableFuture<List<Observation>> observeAll(CaptureProjection projection, int @Nullable [] target, List<CameraPose> poses);

	DatasetManifest.Software software();

	DatasetManifest.RenderEnvironment renderEnvironment();
}
