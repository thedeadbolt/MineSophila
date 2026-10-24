package io.github.thedeadbolt.honeyfactory.client.replay;

import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetRuns;
import io.github.thedeadbolt.honeyfactory.client.generation.RuntimeGenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.replay.ReplayComparison;
import io.github.thedeadbolt.honeyfactory.replay.ReplayException;
import io.github.thedeadbolt.honeyfactory.replay.ReplayScene;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

/**
 * Replays recorded dataset scenes through the client environment runtime: one replay at a
 * time, which stays loaded until the next. Loading regenerates and checks the scene (see
 * {@link ReplayScene}), builds it with the runtime's reset, so only a verified
 * dataset-owned world accepts it, and compares the world's layout hash with the record.
 * Captures are compared with the stored images and written, with the stored dataset left
 * untouched, under {@value #DIRECTORY} in the game directory. A level-5 scene needs a
 * dataset world created with the seed its source patch was copied from.
 *
 * <p>A replay is exact when the layout hash matches and every viewpoint's fresh mask
 * equals the stored one. Render settings that differ from the dataset's recorded setup are
 * reported beside the result.
 */
public final class ReplayRuns {
	public static final String DIRECTORY = "honey-factory-replay";

	/** A built replay scene; {@code viewpoint} indexes the scene's samples. */
	public record Loaded(ReplayScene scene, ScenePlan plan, String layoutSha256, int viewpoint, List<String> renderDifferences) {
		public boolean layoutMatches() {
			return this.layoutSha256.equals(this.scene.scene().layoutSha256());
		}

		public SampleRecord sample() {
			return this.scene.samples().get(this.viewpoint);
		}
	}

	/** Fresh captures of viewpoints compared with the stored ones. */
	public record Verification(Loaded loaded, List<ReplayComparison.Result> results, Path freshDirectory) {
		public boolean exact() {
			return this.loaded.layoutMatches() && this.results.stream().allMatch(ReplayComparison.Result::maskMatches);
		}
	}

	private static @Nullable Loaded current;
	private static boolean busy;

	private ReplayRuns() {
	}

	/** The loaded replay, or null. */
	public static @Nullable Loaded current() {
		return current;
	}

	/**
	 * Loads scene or sample {@code id} of the dataset in {@code directory} and builds it at
	 * the sample's viewpoint, or the scene's first.
	 *
	 * @throws ReplayException NOT_FOUND, INVALID_DATASET, UNAVAILABLE or MISMATCH, before the world changes
	 * @throws IllegalStateException while a replay request or dataset generation runs, or without improved transparency
	 */
	public static CompletableFuture<Loaded> load(Path directory, String id) {
		if (DatasetRuns.running() != null) {
			throw new IllegalStateException("a dataset generation is running");
		}

		if (!Minecraft.getInstance().options.improvedTransparency().get()) {
			throw new IllegalStateException(CaptureService.IMPROVED_TRANSPARENCY_REQUIRED);
		}

		ReplayScene scene = ReplayScene.load(directory, id);
		SceneRecord.SourcePatch patch = scene.scene().sourcePatch();
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (patch != null && server != null && server.overworld().getSeed() != patch.worldSeed()) {
			throw new ReplayException(ReplayException.Reason.UNAVAILABLE, "scene " + scene.scene().sceneId() + " was copied from a world with seed " + patch.worldSeed()
				+ "; replay it in a dataset world created with that seed, not " + server.overworld().getSeed());
		}

		begin();
		current = null;
		EnvironmentRuntime runtime = EnvironmentRuntime.get();
		RuntimeGenerationEnvironment environment = new RuntimeGenerationEnvironment(runtime);
		GenerationEnvironment.PatchRequest request = scene.patchRequest();
		CompletableFuture<GeneratedScene> generated = request == null
			? CompletableFuture.supplyAsync(() -> scene.regenerate(null), Minecraft.getInstance())
			: environment.preparePatch(request).thenApply(scene::regenerate);
		CameraPose pose = scene.poses().get(scene.focus());
		return finish(generated
			.thenCompose(regenerated -> runtime.reset(scene.arena(), regenerated.plan(), pose).thenApply(state -> {
				List<String> differences = ReplayComparison.renderDifferences(scene.manifest().renderEnvironment(), environment.renderEnvironment());
				current = new Loaded(scene, regenerated.plan(), state.layoutSha256(), scene.focus(), differences);
				return current;
			})));
	}

	/** Moves the camera to viewpoint {@code index} of the loaded scene. */
	public static CompletableFuture<Loaded> view(int index) {
		Loaded loaded = requireLoaded();
		if (index < 0 || index >= loaded.scene().samples().size()) {
			throw new IllegalArgumentException("viewpoint " + index + " is not one of the scene's 0-" + (loaded.scene().samples().size() - 1));
		}

		begin();
		return finish(EnvironmentRuntime.get().setViewpoint(loaded.scene().poses().get(index)).thenApply(state -> {
			current = new Loaded(loaded.scene(), loaded.plan(), loaded.layoutSha256(), index, loaded.renderDifferences());
			return current;
		}));
	}

	/** Captures the current viewpoint and compares it with its stored sample. */
	public static CompletableFuture<Verification> capture() {
		Loaded loaded = requireLoaded();
		begin();
		return finish(EnvironmentRuntime.get().observe(loaded.scene().projection())
			.thenApply(observation -> verification(loaded, List.of(loaded.sample()), List.of(observation))));
	}

	/**
	 * Captures every viewpoint of the loaded scene in one operation and compares each, then
	 * returns the camera to the current viewpoint.
	 */
	public static CompletableFuture<Verification> verify() {
		Loaded loaded = requireLoaded();
		begin();
		EnvironmentRuntime runtime = EnvironmentRuntime.get();
		return finish(runtime.observeAll(loaded.scene().projection(), loaded.scene().poses())
			.thenApply(observations -> verification(loaded, loaded.scene().samples(), observations))
			.thenCompose(verification -> runtime.setViewpoint(loaded.scene().poses().get(loaded.viewpoint())).thenApply(state -> verification)));
	}

	private static Verification verification(Loaded loaded, List<SampleRecord> samples, List<EnvironmentRuntime.Observation> observations) {
		Path fresh = Minecraft.getInstance().gameDirectory.toPath().resolve(DIRECTORY).resolve(loaded.scene().manifest().datasetId()).resolve(loaded.scene().scene().sceneId());
		if (fresh.toAbsolutePath().normalize().startsWith(loaded.scene().directory().toAbsolutePath().normalize())) {
			throw new ReplayException(ReplayException.Reason.INVALID_DATASET, "fresh captures would be written inside the dataset directory");
		}

		List<ReplayComparison.Result> results = new ArrayList<>();
		for (int i = 0; i < samples.size(); i++) {
			SampleRecord sample = samples.get(i);
			EnvironmentRuntime.Observation observation = observations.get(i);
			results.add(ReplayComparison.compare(loaded.scene(), sample, observation.capture().images()));
			try {
				ReplayComparison.writeFresh(fresh, sample.sampleId(), observation.capture().images());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		return new Verification(loaded, List.copyOf(results), fresh);
	}

	private static Loaded requireLoaded() {
		if (current == null) {
			throw new IllegalStateException("no replay is loaded; use load first");
		}

		if (EnvironmentRuntime.get().scene() != current.plan()) {
			current = null;
			throw new IllegalStateException("the world has been reset since the replay was loaded; load it again");
		}

		return current;
	}

	private static void begin() {
		if (busy) {
			throw new IllegalStateException("a replay request is still running");
		}

		busy = true;
	}

	private static <T> CompletableFuture<T> finish(CompletableFuture<T> request) {
		return request.whenComplete((value, error) -> busy = false);
	}
}
