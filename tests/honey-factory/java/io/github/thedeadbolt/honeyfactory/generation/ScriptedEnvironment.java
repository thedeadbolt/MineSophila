package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PatchTerrain;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatch;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatches;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A Minecraft-free environment for generation tests. Requests complete when {@link #pump()}
 * runs them, as the real runtime completes them on later client ticks. Masks are ray cast
 * per pixel centre against the target's block cell; the scene's other blocks above the
 * floor occlude it.
 */
final class ScriptedEnvironment implements GenerationEnvironment {
	static final double EYE_HEIGHT = 1.62;

	private final Deque<Runnable> queue = new ArrayDeque<>();
	private int operations;
	private int observations;
	private @Nullable CameraPose pose;
	private List<int[]> occluders = List.of();
	/** Observations (counted from 1) that fail with a capture error. */
	IntPredicate failCapture = observation -> false;
	/** Runs as each observation (counted from 1) is captured. */
	IntConsumer onObserve = observation -> {
	};
	/** Returns empty masks, as if the target were never visible. */
	boolean hideTarget;
	/** Observations (counted from 1) whose masks are empty, as if the target were hidden from that view. */
	IntPredicate hideOn = observation -> false;

	/** Runs queued requests, including any they queue, until none remain. */
	void pump() {
		while (!this.queue.isEmpty()) {
			this.queue.removeFirst().run();
		}
	}

	/** Patches whose index this rejects outright, as if no candidate passed. */
	IntPredicate rejectPatch = patch -> false;
	/** Patch indices prepared so far, in request order. */
	final List<Integer> preparedPatches = new java.util.ArrayList<>();

	/** Flat synthetic terrain: solid ground below y = 10 and air above; every column is a target site. */
	@Override
	public CompletableFuture<PreparedPatch> preparePatch(PatchRequest request) {
		return later(() -> {
			this.preparedPatches.add(request.patchIndex());
			if (this.rejectPatch.test(request.patchIndex())) {
				throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "no acceptable source patch for patch " + request.patchIndex());
			}

			SceneBase.NaturalTerrain base = request.base();
			int[] corner = SourcePatches.corner(request.datasetSeed(), base, request.patchIndex(), 0);
			int[] min = {corner[0], 0, corner[1]};
			int[] max = {corner[0] + base.copyWidth() - 1, base.height() - 1, corner[1] + base.copyLength() - 1};
			byte[] flags = new byte[base.coreWidth() * base.height() * base.coreLength()];
			int[] surface = new int[base.coreWidth() * base.coreLength()];
			java.util.Arrays.fill(surface, 10);
			for (int y = 0; y < base.height(); y++) {
				int value = y < 10 ? PatchTerrain.OCCUPIED | PatchTerrain.OCCLUDES | PatchTerrain.COLLIDES | PatchTerrain.STURDY_TOP : PatchTerrain.REPLACEABLE;
				java.util.Arrays.fill(flags, y * base.coreWidth() * base.coreLength(), (y + 1) * base.coreWidth() * base.coreLength(), (byte) value);
			}

			String id = SourcePatch.id(12345L, "minecraft:overworld", min, max, "minecraft-test");
			return new PreparedPatch(new SourcePatch(id, 12345L, "minecraft:overworld", min, max, "minecraft-test", request.patchIndex(), 0, Sha256.hex(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.util.Map.of()),
				new PatchTerrain(base.coreWidth(), base.height(), base.coreLength(), flags, surface));
		});
	}

	@Override
	public CompletableFuture<SceneReady> reset(ArenaLayout arena, ScenePlan plan, CameraPose firstViewpoint) {
		if (plan.patch() != null) {
			return later(() -> {
				this.pose = firstViewpoint;
				this.occluders = List.of();
				JsonObject rules = new JsonObject();
				rules.addProperty("advance_time", false);
				String layout = Sha256.hex((plan.patch().contentSha256() + plan.delta()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				return new SceneReady(nextOperation(), layout, 5, rules);
			});
		}

		return later(() -> {
			this.pose = firstViewpoint;
			this.occluders = plan.layoutBlocks(arena).stream()
				.filter(block -> block.y() > arena.core().minY() && !Arrays.equals(new int[] {block.x(), block.y(), block.z()}, plan.targetPosition(arena)))
				.map(block -> new int[] {block.x(), block.y(), block.z()})
				.toList();
			JsonObject rules = new JsonObject();
			rules.addProperty("advance_time", false);
			rules.addProperty("random_tick_speed", 0);
			return new SceneReady(nextOperation(), plan.layoutSha256(arena), 5, rules);
		});
	}

	@Override
	public CompletableFuture<ViewReady> setViewpoint(CameraPose pose) {
		return later(() -> {
			this.pose = pose;
			return new ViewReady(nextOperation(), pose, 3);
		});
	}

	/** One operation; each pose counts as one observation, and a failing one fails the whole batch. */
	@Override
	public CompletableFuture<List<Observation>> observeAll(CaptureProjection projection, int @Nullable [] target, List<CameraPose> poses) {
		return later(() -> {
			String operation = nextOperation();
			List<Observation> observations = new java.util.ArrayList<>();
			for (CameraPose pose : poses) {
				this.onObserve.accept(++this.observations);
				if (this.failCapture.test(this.observations)) {
					throw new EnvironmentException(EnvironmentException.Reason.CAPTURE_FAILED, "scripted readback failure");
				}

				observations.add(new Observation(operation, 1000 + this.observations, 1.0, render(projection, target, pose), "honey_factory:arena", 15, 0));
			}

			return List.copyOf(observations);
		});
	}

	private CapturedImages render(CaptureProjection projection, int @Nullable [] target, CameraPose pose) {
		int width = projection.width();
		int height = projection.height();
		byte[] rgb = new byte[width * height * 3];
		byte[] mask = new byte[width * height];
		byte[] unoccluded = new byte[width * height];
		CameraFrame camera = CameraFrame.of(pose, EYE_HEIGHT);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = y * width + x;
				double[] direction = projection.direction(camera, x + 0.5, y + 0.5);
				double distance = target == null || this.hideTarget || this.hideOn.test(this.observations) ? Double.NaN : distance(camera.eye(), direction, target);
				boolean inView = !Double.isNaN(distance);
				boolean hit = inView && this.occluders.stream().noneMatch(block -> distance(camera.eye(), direction, block) < distance);
				unoccluded[p] = inView ? (byte) 255 : 0;
				mask[p] = hit ? (byte) 255 : 0;
				rgb[p * 3] = (byte) (hit ? 230 : 140);
				rgb[p * 3 + 1] = (byte) (hit ? 160 : 140);
				rgb[p * 3 + 2] = (byte) (hit ? 30 : 140);
			}
		}

		return new CapturedImages(width, height, rgb, mask, unoccluded);
	}

	/** Slab test of a ray against the unit block cell at {@code block}: the entry distance, or NaN on a miss. */
	private static double distance(double[] origin, double[] direction, int[] block) {
		double near = 0.0;
		double far = Double.POSITIVE_INFINITY;
		for (int axis = 0; axis < 3; axis++) {
			double min = block[axis];
			double max = block[axis] + 1.0;
			if (Math.abs(direction[axis]) < 1e-12) {
				if (origin[axis] < min || origin[axis] > max) {
					return Double.NaN;
				}

				continue;
			}

			double t1 = (min - origin[axis]) / direction[axis];
			double t2 = (max - origin[axis]) / direction[axis];
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}

		return near <= far ? near : Double.NaN;
	}

	@Override
	public DatasetManifest.Software software() {
		return new DatasetManifest.Software("26.3", "0.19.5", "0.163.0+26.3", "0.0.1", "25", null, false);
	}

	@Override
	public DatasetManifest.RenderEnvironment renderEnvironment() {
		return new DatasetManifest.RenderEnvironment("scripted", "none", "none", true, 8, 0.5, "off", null, List.of(new DatasetManifest.ResourcePack("vanilla", null)), new JsonObject());
	}

	int observations() {
		return this.observations;
	}

	private String nextOperation() {
		return String.format("op-%06d", ++this.operations);
	}

	private <T> CompletableFuture<T> later(Supplier<T> work) {
		CompletableFuture<T> future = new CompletableFuture<>();
		this.queue.addLast(() -> {
			try {
				future.complete(work.get());
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
