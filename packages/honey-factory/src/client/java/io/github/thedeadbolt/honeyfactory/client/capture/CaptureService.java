package io.github.thedeadbolt.honeyfactory.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.client.mixin.SectionOcclusionGraphAccessor;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous capture of RGB, the visible-target mask and the internal unoccluded-target
 * mask from the current camera position, all from one frozen world state.
 *
 * <p>Follows vanilla's panorama screenshot sequence: resize the main render target, use
 * panoramic camera mode (no hand, no FOV effects), then for each of the projection's views
 * turn the player to the view's yaw and pitch and update, extract and render the level
 * between client frames. Every view renders with the same tick and interpolation, so
 * nothing moves between them. The player's rotation is restored afterwards, as are view
 * bobbing, the block outline and section fade-in, which are switched off for the capture.
 * The view's vertical field of view replaces panoramic mode's fixed 90 degrees through
 * {@code CameraMixin}, so non-square perspective outputs keep their exact 90-degree
 * horizontal field of view. The projection then assembles the views into the output.
 *
 * <p>A capture is atomic: if any view fails, no images are returned.
 *
 * <p>Call {@link #capture} on the client thread at the end of a client tick.
 */
public final class CaptureService {
	public static final String IMPROVED_TRANSPARENCY_REQUIRED = "improved transparency must be enabled (ADR 0005)";
	/** Every capture renders with {@code DeltaTracker.ONE}: the state at the end of the current tick. */
	public static final double PARTIAL_TICK = 1.0;

	private static @Nullable String injectedFailure;
	private static int injectedFailureView;

	/**
	 * One capture: the output images, the rendered views they were assembled from, and the
	 * camera's pose, feet position and view rotation. Times are CPU wall time for render
	 * submission and readback, summed over views, and for assembly.
	 */
	public record Capture(CapturedImages images, List<CapturedImages> views, long worldTick, double partialTick, long renderNanos, long readbackNanos, long assembleNanos, CameraPose pose) {
		/** The same capture recorded at {@code partialTick} of its tick. */
		Capture atPartialTick(double partialTick) {
			return new Capture(this.images, this.views, this.worldTick, partialTick, this.renderNanos, this.readbackNanos, this.assembleNanos, this.pose);
		}
	}

	/** The player's rotation, saved before the views turn it. */
	private record Rotation(float yaw, float pitch, float yawO, float pitchO) {
		static Rotation of(LocalPlayer player) {
			return new Rotation(player.getYRot(), player.getXRot(), player.yRotO, player.xRotO);
		}

		static void turn(LocalPlayer player, float yaw, float pitch) {
			new Rotation(yaw, pitch, yaw, pitch).restore(player);
		}

		void restore(LocalPlayer player) {
			player.setYRot(this.yaw);
			player.setXRot(this.pitch);
			player.yRotO = this.yawO;
			player.xRotO = this.pitchO;
		}
	}

	private CaptureService() {
	}

	public static void initialize() {
		TargetMaskRenderer.initialize();
	}

	/** Whether a capture is rendering now. */
	public static boolean capturing() {
		return CaptureSetup.activeVerticalFov() != null;
	}

	/** The vertical field of view the camera must use while a capture renders, or null. */
	public static @Nullable Float verticalFovOverride() {
		return CaptureSetup.activeVerticalFov();
	}

	/** Makes the next capture fail with {@code reason}, for failure-handling tests. */
	public static void failNextCapture(String reason) {
		failNextCapture(reason, 0);
	}

	/** Makes the next capture fail with {@code reason} just before rendering view {@code viewIndex}. */
	public static void failNextCapture(String reason, int viewIndex) {
		injectedFailure = reason;
		injectedFailureView = viewIndex;
	}

	/**
	 * Renders one capture through {@code projection}; {@code target} is the block whose masks
	 * are drawn, or null for a scene without a target, whose masks are empty.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#CAPTURE_FAILED} when any
	 *     render or readback step fails; no partial images are returned
	 */
	public static Capture capture(Minecraft minecraft, CaptureProjection projection, @Nullable BlockPos target) {
		checkCanCapture(minecraft);
		LocalPlayer player = minecraft.player;
		List<CaptureProjection.View> views = projection.views(player.getYRot(), player.getXRot());
		CaptureProjection.View first = views.getFirst();
		for (CaptureProjection.View view : views) {
			if (view.width() != first.width() || view.height() != first.height() || view.verticalFovDegrees() != first.verticalFovDegrees()) {
				throw new UnsupportedOperationException("All views of one capture must share their size and field of view");
			}
		}

		long worldTick = minecraft.level.getGameTime();
		CameraPose pose = new CameraPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		Rotation original = Rotation.of(player);
		try (CaptureSetup ignored = CaptureSetup.apply(minecraft, first)) {
			List<Readback> pending = new ArrayList<>(views.size());
			long renderNanos = 0;
			long readbackNanos = 0;
			List<CapturedImages> rendered;
			try {
				for (int i = 0; i < views.size(); i++) {
					failIfInjected(i);
					CaptureProjection.View view = views.get(i);
					Rotation.turn(player, view.yawDegrees(), view.pitchDegrees());
					long start = System.nanoTime();
					renderFrame(minecraft, target);
					long renderedAt = System.nanoTime();
					pending.add(Readback.start(minecraft.gameRenderer.mainRenderTarget(), view, target != null));
					renderNanos += renderedAt - start;
					readbackNanos += System.nanoTime() - renderedAt;
				}

				long finishStart = System.nanoTime();
				rendered = Readback.finish(pending);
				readbackNanos += System.nanoTime() - finishStart;
			} finally {
				pending.forEach(Readback::close);
			}

			long assembleStart = System.nanoTime();
			CapturedImages images = projection.assemble(rendered);
			return new Capture(images, List.copyOf(rendered), worldTick, PARTIAL_TICK, renderNanos, readbackNanos, System.nanoTime() - assembleStart, pose);
		} catch (EnvironmentException | UnsupportedOperationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure(e.toString());
		} finally {
			original.restore(player);
		}
	}

	/**
	 * Captures from {@code pose}, a point the camera passes during the current tick, as the
	 * frame at {@code partialTick} of it (M12-1, several frames per control tick). The client
	 * player is moved there, the section graph brought up to date for its position, then put
	 * back exactly; the world is frozen and the player does not tick in between, so nothing
	 * else sees the move.
	 */
	public static Capture captureAt(Minecraft minecraft, CaptureProjection projection, @Nullable BlockPos target, CameraPose pose, double partialTick) {
		checkCanCapture(minecraft);
		LocalPlayer player = minecraft.player;
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		try {
			place(player, pose.x(), pose.y(), pose.z(), pose.yawDegrees(), pose.pitchDegrees());
			settleSectionGraph(minecraft);
			return capture(minecraft, projection, target).atPartialTick(partialTick);
		} finally {
			place(player, x, y, z, yaw, pitch);
		}
	}

	/** Puts the player at a pose with its interpolation settled, without wrapping or snapping its angles. */
	private static void place(LocalPlayer player, double x, double y, double z, float yaw, float pitch) {
		player.setPos(x, y, z);
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setOldPosAndRot();
	}

	/**
	 * Renders a frame from the camera's position so the section graph updates for it, and
	 * when that needs a full rebuild, as after moving into another section, waits for the
	 * rebuild and renders once more so the next render uses it.
	 */
	private static void settleSectionGraph(Minecraft minecraft) {
		for (int attempt = 0; attempt < 4; attempt++) {
			renderFrame(minecraft, null);
			SectionOcclusionGraphAccessor graph = (SectionOcclusionGraphAccessor) minecraft.levelRenderer.sectionOcclusionGraph();
			Future<?> rebuild = graph.honeyFactory$fullUpdateTask();
			if (!graph.honeyFactory$needsFullUpdate() && (rebuild == null || rebuild.isDone())) {
				return;
			}

			if (rebuild != null) {
				try {
					rebuild.get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw failure("interrupted while rebuilding the section graph");
				} catch (ExecutionException e) {
					throw failure("rebuilding the section graph failed: " + e.getCause());
				}
			}
		}

		throw failure("the section graph did not settle at the interpolated camera position");
	}

	/** Checks the client can capture at all: a level, a player and improved transparency. */
	private static void checkCanCapture(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			throw failure("no client level");
		}

		if (!minecraft.gameRenderer.useImprovedTransparency()) {
			// Classic transparency writes translucent depth, including the target's (ADR 0005).
			throw failure(IMPROVED_TRANSPARENCY_REQUIRED);
		}
	}

	private static void failIfInjected(int viewIndex) {
		if (injectedFailure != null && injectedFailureView == viewIndex) {
			String reason = injectedFailure;
			injectedFailure = null;
			throw failure("injected at view " + viewIndex + ": " + reason);
		}
	}

	/** Renders one level frame, with the target masks when there is a target. */
	private static void renderFrame(Minecraft minecraft, @Nullable BlockPos target) {
		if (target != null) {
			TargetMaskRenderer.arm(target);
		}

		minecraft.gameRenderer.update(DeltaTracker.ONE);
		minecraft.gameRenderer.extract(DeltaTracker.ONE, true);
		minecraft.gameRenderer.renderLevel();
		if (target != null) {
			String maskFailure = TargetMaskRenderer.finish();
			if (maskFailure != null) {
				throw failure(maskFailure);
			}
		}
	}

	private static EnvironmentException failure(String reason) {
		return new EnvironmentException(EnvironmentException.Reason.CAPTURE_FAILED, reason);
	}

	/**
	 * One view's images on their way back from the GPU. {@link #start} copies the textures
	 * into mappable buffers and submits the copies without waiting, so the next view renders
	 * while they run; the GPU runs commands in order, so a copy reads its view before the
	 * next view draws over the same texture. {@link #finish} waits once, on a fence after the
	 * last copy, then converts every view's buffers on worker threads.
	 */
	private static final class Readback implements AutoCloseable {
		/** Worker threads for converting read-back pixels: all but one processor, at most 8. */
		private static final ExecutorService CONVERTERS = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, 8),
			Thread.ofPlatform().daemon().name("honey-factory-readback-", 0).factory());

		private final CaptureProjection.View view;
		private final GpuBuffer[] buffers;
		private final int[] channels;

		private Readback(CaptureProjection.View view, GpuBuffer[] buffers, int[] channels) {
			this.view = view;
			this.buffers = buffers;
			this.channels = channels;
		}

		static Readback start(RenderTarget main, CaptureProjection.View view, boolean withMasks) {
			GpuTexture[] textures = withMasks
				? new GpuTexture[] {main.getColorTexture(), TargetMaskRenderer.visibleTexture(), TargetMaskRenderer.unoccludedTexture()}
				: new GpuTexture[] {main.getColorTexture()};
			int[] channels = withMasks ? new int[] {3, 1, 1} : new int[] {3};
			GpuDevice device = RenderSystem.getDevice();
			CommandEncoder encoder = device.createCommandEncoder();
			GpuBuffer[] buffers = new GpuBuffer[textures.length];
			Readback readback = new Readback(view, buffers, channels);
			try {
				for (int i = 0; i < textures.length; i++) {
					GpuTexture texture = textures[i];
					if (texture == null || texture.getFormat().blockSize() != 4 || texture.getWidth(0) != view.width() || texture.getHeight(0) != view.height()) {
						throw new IllegalStateException("Readback needs a 4-byte-per-pixel color texture of the view's size");
					}

					buffers[i] = device.createBuffer(
						() -> "Honey Factory readback",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
						(long) view.width() * view.height() * 4
					);
					encoder.copyTextureToBuffer(texture, buffers[i], 0L, () -> {
					}, 0);
				}

				encoder.submit();
				return readback;
			} catch (RuntimeException e) {
				readback.close();
				throw e;
			}
		}

		/** Waits for every pending copy, then returns each view's images in order. */
		static List<CapturedImages> finish(List<Readback> pending) {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			// A fence cannot be awaited for the current submit, so submit first.
			GpuFence fence = encoder.createFence();
			encoder.submit();
			try {
				if (!fence.awaitCompletion(GpuFence.NO_TIMEOUT)) {
					throw new IllegalStateException("Readback fence did not complete");
				}
			} finally {
				fence.close();
			}

			RenderSystem.executePendingTasks();
			List<GpuBufferSlice.MappedView> mapped = new ArrayList<>();
			try {
				List<Future<byte[]>> conversions = new ArrayList<>();
				for (Readback readback : pending) {
					for (int i = 0; i < readback.buffers.length; i++) {
						GpuBufferSlice.MappedView view = readback.buffers[i].map(true, false);
						mapped.add(view);
						ByteBuffer data = view.data();
						int channels = readback.channels[i];
						conversions.add(CONVERTERS.submit(() -> topDownChannels(data, readback.view.width(), readback.view.height(), channels)));
					}
				}

				List<CapturedImages> images = new ArrayList<>(pending.size());
				int next = 0;
				for (Readback readback : pending) {
					CaptureProjection.View view = readback.view;
					if (readback.buffers.length == 1) {
						images.add(CapturedImages.withoutTarget(view.width(), view.height(), join(conversions.get(next++))));
					} else {
						images.add(new CapturedImages(view.width(), view.height(), join(conversions.get(next++)), join(conversions.get(next++)), join(conversions.get(next++))));
					}
				}

				return images;
			} finally {
				mapped.forEach(GpuBufferSlice.MappedView::close);
			}
		}

		private static byte[] join(Future<byte[]> conversion) {
			try {
				return conversion.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while reading back a capture", e);
			} catch (ExecutionException e) {
				throw new IllegalStateException("reading back a capture failed", e.getCause());
			}
		}

		@Override
		public void close() {
			for (GpuBuffer buffer : this.buffers) {
				if (buffer != null) {
					buffer.close();
				}
			}
		}
	}

	/**
	 * The first {@code channels} channels of each RGBA8 pixel, in rows ordered from the image
	 * top, flipping OpenGL's bottom-up rows as vanilla screenshots do.
	 */
	private static byte[] topDownChannels(ByteBuffer rgba, int width, int height, int channels) {
		byte[] result = new byte[width * height * channels];
		byte[] row = new byte[width * 4];
		int stride = width * 4;
		for (int y = 0; y < height; y++) {
			rgba.get((height - 1 - y) * stride, row, 0, stride);
			int q = y * width * channels;
			for (int p = 0; p < stride; p += 4) {
				for (int c = 0; c < channels; c++) {
					result[q++] = row[p + c];
				}
			}
		}

		return result;
	}
}
