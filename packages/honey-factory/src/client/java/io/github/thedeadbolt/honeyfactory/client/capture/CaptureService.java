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
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
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
	 * One capture: the output images and the rendered views they were assembled from. Times
	 * are CPU wall time for render submission and readback, summed over views, and for
	 * assembly.
	 */
	public record Capture(CapturedImages images, List<CapturedImages> views, long worldTick, double partialTick, long renderNanos, long readbackNanos, long assembleNanos) {
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
		GpuReprojection.initialize();
	}

	/** Makes the next GPU reprojection fail, for fallback tests. */
	public static void failNextGpuReprojection(String reason) {
		GpuReprojection.failNext(reason);
	}

	/** Whether GPU reprojection may run: it has not failed this session. */
	public static boolean gpuReprojectionUsable() {
		return GpuReprojection.usable();
	}

	/** Lets GPU reprojection run again after a failure, for tests. */
	public static void enableGpuReprojectionAgain() {
		GpuReprojection.enableAgain();
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

		if (projection instanceof PanoramicProjection panorama && EnvironmentRuntime.get().settings().gpuReprojection() && GpuReprojection.usable()) {
			try {
				return render(minecraft, panorama, views, target, true);
			} catch (GpuReprojection.Failure e) {
				GpuReprojection.disable(e);
			}
		}

		return render(minecraft, projection, views, target, false);
	}

	/**
	 * Renders every view and assembles the output: on the CPU from the read-back views, or with
	 * {@code gpu} on the GPU from atlases of the views, reading back only the output. A GPU
	 * step's own failure is a {@link GpuReprojection.Failure}, for the caller to fall back.
	 */
	private static Capture render(Minecraft minecraft, CaptureProjection projection, List<CaptureProjection.View> views, @Nullable BlockPos target, boolean gpu) {
		LocalPlayer player = minecraft.player;
		CaptureProjection.View first = views.getFirst();
		long worldTick = minecraft.level.getGameTime();
		Rotation original = Rotation.of(player);
		try (CaptureSetup ignored = CaptureSetup.apply(minecraft, first)) {
			List<Readback> pending = new ArrayList<>(views.size());
			long renderNanos = 0;
			long readbackNanos = 0;
			long assembleNanos = 0;
			CapturedImages images;
			List<CapturedImages> rendered;
			try {
				if (gpu) {
					GpuReprojection.begin((PanoramicProjection) projection);
				}

				for (int i = 0; i < views.size(); i++) {
					failIfInjected(i);
					CaptureProjection.View view = views.get(i);
					Rotation.turn(player, view.yawDegrees(), view.pitchDegrees());
					long start = System.nanoTime();
					renderFrame(minecraft, target);
					long renderedAt = System.nanoTime();
					RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
					if (gpu) {
						GpuReprojection.addFace(i, main.getColorTexture(), target == null ? null : TargetMaskRenderer.visibleTexture(), target == null ? null : TargetMaskRenderer.unoccludedTexture());
					} else {
						pending.add(target == null
							? Readback.start(view.width(), view.height(), new GpuTexture[] {main.getColorTexture()}, Readback.RGB)
							: Readback.start(view.width(), view.height(), new GpuTexture[] {main.getColorTexture(), TargetMaskRenderer.visibleTexture(), TargetMaskRenderer.unoccludedTexture()}, Readback.RGB_AND_MASKS));
					}

					renderNanos += renderedAt - start;
					readbackNanos += System.nanoTime() - renderedAt;
				}

				if (gpu) {
					long assembleStart = System.nanoTime();
					pending.add(GpuReprojection.assemble());
					assembleNanos = System.nanoTime() - assembleStart;
				}

				long finishStart = System.nanoTime();
				rendered = Readback.finish(pending);
				readbackNanos += System.nanoTime() - finishStart;
			} finally {
				pending.forEach(Readback::close);
			}

			if (gpu) {
				CapturedImages output = rendered.getFirst();
				images = target == null ? CapturedImages.withoutTarget(output.width(), output.height(), output.rgb()) : output;
				return new Capture(images, List.of(), worldTick, PARTIAL_TICK, renderNanos, readbackNanos, assembleNanos);
			}

			long assembleStart = System.nanoTime();
			images = projection.assemble(rendered);
			return new Capture(images, List.copyOf(rendered), worldTick, PARTIAL_TICK, renderNanos, readbackNanos, System.nanoTime() - assembleStart);
		} catch (EnvironmentException | UnsupportedOperationException | GpuReprojection.Failure e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure(e.toString());
		} finally {
			original.restore(player);
		}
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
	 * Images on their way back from the GPU. {@link #start} copies textures into mappable
	 * buffers and submits the copies without waiting, so the next view renders while they
	 * run; the GPU runs commands in order, so a copy reads its view before the next view
	 * draws over the same texture. {@link #finish} waits once, on a fence after the last
	 * copy, then converts every buffer on worker threads into the images' channels.
	 */
	static final class Readback implements AutoCloseable {
		/** Where an image's channels come from: a texture of the readback, its first channel and how many. */
		record Part(int texture, int firstChannel, int channels) {
		}

		/** One RGB image from one texture. */
		static final List<Part> RGB = List.of(new Part(0, 0, 3));
		/** RGB, then the visible and unoccluded masks, from three textures. */
		static final List<Part> RGB_AND_MASKS = List.of(new Part(0, 0, 3), new Part(1, 0, 1), new Part(2, 0, 1));
		/** A GPU reprojection's outputs: RGB and the visible mask in one texture, the unoccluded mask in another. */
		static final List<Part> PANORAMA_OUTPUTS = List.of(new Part(0, 0, 3), new Part(0, 3, 1), new Part(1, 0, 1));

		/** Worker threads for converting read-back pixels: all but one processor, at most 8. */
		private static final ExecutorService CONVERTERS = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, 8),
			Thread.ofPlatform().daemon().name("honey-factory-readback-", 0).factory());

		private final int width;
		private final int height;
		private final GpuBuffer[] buffers;
		private final List<Part> parts;

		private Readback(int width, int height, GpuBuffer[] buffers, List<Part> parts) {
			this.width = width;
			this.height = height;
			this.buffers = buffers;
			this.parts = parts;
		}

		/** Starts copying {@code textures}, each {@code width} by {@code height} RGBA8, for an image made of {@code parts}. */
		static Readback start(int width, int height, GpuTexture[] textures, List<Part> parts) {
			GpuDevice device = RenderSystem.getDevice();
			CommandEncoder encoder = device.createCommandEncoder();
			GpuBuffer[] buffers = new GpuBuffer[textures.length];
			Readback readback = new Readback(width, height, buffers, parts);
			try {
				for (int i = 0; i < textures.length; i++) {
					GpuTexture texture = textures[i];
					if (texture == null || texture.getFormat().blockSize() != 4 || texture.getWidth(0) != width || texture.getHeight(0) != height) {
						throw new IllegalStateException("Readback needs a 4-byte-per-pixel color texture of " + width + "x" + height);
					}

					buffers[i] = device.createBuffer(() -> "Honey Factory readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
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

		/**
		 * Waits for every pending copy, then returns one image per readback, in order: RGB
		 * only, or RGB and both masks.
		 */
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
				List<List<Future<byte[]>>> conversions = new ArrayList<>();
				for (Readback readback : pending) {
					ByteBuffer[] data = new ByteBuffer[readback.buffers.length];
					for (int i = 0; i < readback.buffers.length; i++) {
						GpuBufferSlice.MappedView view = readback.buffers[i].map(true, false);
						mapped.add(view);
						data[i] = view.data();
					}

					List<Future<byte[]>> parts = new ArrayList<>();
					for (Part part : readback.parts) {
						parts.add(CONVERTERS.submit(() -> topDownChannels(data[part.texture()], readback.width, readback.height, part.firstChannel(), part.channels())));
					}

					conversions.add(parts);
				}

				List<CapturedImages> images = new ArrayList<>(pending.size());
				for (int r = 0; r < pending.size(); r++) {
					Readback readback = pending.get(r);
					List<Future<byte[]>> parts = conversions.get(r);
					images.add(parts.size() == 1
						? CapturedImages.withoutTarget(readback.width, readback.height, join(parts.get(0)))
						: new CapturedImages(readback.width, readback.height, join(parts.get(0)), join(parts.get(1)), join(parts.get(2))));
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
	 * {@code channels} channels of each RGBA8 pixel from {@code firstChannel} on, in rows
	 * ordered from the image top, flipping OpenGL's bottom-up rows as vanilla screenshots do.
	 */
	private static byte[] topDownChannels(ByteBuffer rgba, int width, int height, int firstChannel, int channels) {
		byte[] result = new byte[width * height * channels];
		byte[] row = new byte[width * 4];
		int stride = width * 4;
		for (int y = 0; y < height; y++) {
			rgba.get((height - 1 - y) * stride, row, 0, stride);
			int q = y * width * channels;
			for (int p = 0; p < stride; p += 4) {
				for (int c = 0; c < channels; c++) {
					result[q++] = row[p + firstChannel + c];
				}
			}
		}

		return result;
	}
}
