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
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous capture of RGB, the visible-target mask and the internal unoccluded-target
 * mask from the current camera, all from one frozen frame.
 *
 * <p>Follows vanilla's panorama screenshot sequence: resize the main render target, use
 * panoramic camera mode (no hand, no FOV effects), then update, extract and render the
 * level between client frames. View bobbing, the block outline and section fade-in are
 * switched off for the capture and restored afterwards. The projection's vertical field of view replaces
 * panoramic mode's fixed 90 degrees through {@code CameraMixin}, so non-square
 * perspective outputs keep their exact 90-degree horizontal field of view.
 *
 * <p>Call {@link #capture} on the client thread at the end of a client tick.
 */
public final class CaptureService {
	public static final String IMPROVED_TRANSPARENCY_REQUIRED = "improved transparency must be enabled (ADR 0005)";
	/** Every capture renders with {@code DeltaTracker.ONE}: the state at the end of the current tick. */
	public static final double PARTIAL_TICK = 1.0;

	private static @Nullable String injectedFailure;

	/** One capture; times are CPU wall time for render submission and for readback. */
	public record Capture(CapturedImages images, long worldTick, double partialTick, long renderNanos, long readbackNanos) {
	}

	private CaptureService() {
	}

	public static void initialize() {
		TargetMaskRenderer.initialize();
	}

	/** The vertical field of view the camera must use while a capture renders, or null. */
	public static @Nullable Float verticalFovOverride() {
		return CaptureSetup.activeVerticalFov();
	}

	/** Makes the next capture fail with {@code reason}, for failure-handling tests. */
	public static void failNextCapture(String reason) {
		injectedFailure = reason;
	}

	/**
	 * Renders one capture through {@code projection}; {@code target} is the block whose masks
	 * are drawn, or null for a scene without a target, whose masks are empty.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#CAPTURE_FAILED} when any
	 *     render or readback step fails; no partial images are returned
	 */
	public static Capture capture(Minecraft minecraft, CaptureProjection projection, @Nullable BlockPos target) {
		CaptureProjection.View view = supportedView(minecraft, projection);
		long worldTick = minecraft.level.getGameTime();
		try (CaptureSetup ignored = CaptureSetup.apply(minecraft, view)) {
			long start = System.nanoTime();
			renderFrame(minecraft, target);
			long rendered = System.nanoTime();
			CapturedImages images = readImages(minecraft.gameRenderer.mainRenderTarget(), view, target != null);
			return new Capture(images, worldTick, PARTIAL_TICK, rendered - start, System.nanoTime() - rendered);
		} catch (EnvironmentException | UnsupportedOperationException e) {
			throw e;
		} catch (RuntimeException e) {
			throw failure(e.toString());
		}
	}

	/** The single unrotated view a capture can render, after checking the client can capture at all. */
	private static CaptureProjection.View supportedView(Minecraft minecraft, CaptureProjection projection) {
		if (minecraft.level == null || minecraft.player == null) {
			throw failure("no client level");
		}

		if (!minecraft.gameRenderer.useImprovedTransparency()) {
			// Classic transparency writes translucent depth, including the target's (ADR 0005).
			throw failure(IMPROVED_TRANSPARENCY_REQUIRED);
		}

		if (injectedFailure != null) {
			String reason = injectedFailure;
			injectedFailure = null;
			throw failure("injected: " + reason);
		}

		List<CaptureProjection.View> views = projection.views();
		if (views.size() != 1 || views.getFirst().yawOffsetDegrees() != 0.0 || views.getFirst().pitchOffsetDegrees() != 0.0) {
			throw new UnsupportedOperationException("Multi-view and rotated captures arrive with PANORAMA_310 in M5");
		}

		return views.getFirst();
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

	private static CapturedImages readImages(RenderTarget main, CaptureProjection.View view, boolean withMasks) {
		if (!withMasks) {
			return CapturedImages.withoutTarget(view.width(), view.height(), channels(readBack(main.getColorTexture())[0], 3));
		}

		byte[][] pixels = readBack(main.getColorTexture(), TargetMaskRenderer.visibleTexture(), TargetMaskRenderer.unoccludedTexture());
		return new CapturedImages(view.width(), view.height(), channels(pixels[0], 3), channels(pixels[1], 1), channels(pixels[2], 1));
	}

	private static EnvironmentException failure(String reason) {
		return new EnvironmentException(EnvironmentException.Reason.CAPTURE_FAILED, reason);
	}

	/** Copies the textures into mappable buffers and waits for the GPU on a fence. */
	private static byte[][] readBack(@Nullable GpuTexture... textures) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuBuffer[] buffers = new GpuBuffer[textures.length];
		try {
			for (int i = 0; i < textures.length; i++) {
				GpuTexture texture = textures[i];
				if (texture == null || texture.getFormat().blockSize() != 4) {
					throw new IllegalStateException("Readback needs a 4-byte-per-pixel color texture");
				}

				buffers[i] = device.createBuffer(
					() -> "Honey Factory readback",
					GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
					(long) texture.getWidth(0) * texture.getHeight(0) * 4
				);
				encoder.copyTextureToBuffer(texture, buffers[i], 0L, () -> {
				}, 0);
			}

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
			byte[][] result = new byte[textures.length][];
			for (int i = 0; i < textures.length; i++) {
				result[i] = topDown(buffers[i], textures[i].getWidth(0), textures[i].getHeight(0));
			}

			return result;
		} finally {
			for (GpuBuffer buffer : buffers) {
				if (buffer != null) {
					buffer.close();
				}
			}
		}
	}

	/** Returns RGBA8 rows ordered from the image top, flipping OpenGL's bottom-up rows as vanilla screenshots do. */
	private static byte[] topDown(GpuBuffer buffer, int width, int height) {
		byte[] pixels = new byte[width * height * 4];
		int stride = width * 4;
		try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
			ByteBuffer data = view.data();
			for (int y = 0; y < height; y++) {
				data.get((height - 1 - y) * stride, pixels, y * stride, stride);
			}
		}

		return pixels;
	}

	/** The first {@code count} channels of each RGBA8 pixel. */
	private static byte[] channels(byte[] rgba, int count) {
		byte[] result = new byte[rgba.length / 4 * count];
		for (int p = 0, q = 0; p < rgba.length; p += 4) {
			for (int c = 0; c < count; c++) {
				result[q++] = rgba[p + c];
			}
		}

		return result;
	}
}
