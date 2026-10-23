package io.github.thedeadbolt.honeyfactory.client.m0;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.m0.M0Fixture;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Temporary M0 capture fixture: RGB, a visible target mask and an internal unoccluded
 * target mask from one fixed perspective camera, repeated without advancing the world.
 *
 * <p>Follows vanilla's panorama screenshot sequence: resize the main render target, use
 * panoramic camera mode for a fixed 90-degree vertical FOV without the hand, then
 * synchronously update, extract and render the level between client frames.
 */
public final class M0Capture {
	public static final String OUTPUT_DIRECTORY = "honey-factory-m0";

	public static final String IMPROVED_TRANSPARENCY_REQUIRED = "improved transparency must be enabled";

	private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,64}");

	private static @Nullable Request pending;
	private static @Nullable CompletableFuture<Result> pendingResult;
	private static @Nullable Result lastResult;

	/** {@code target} is the block whose masks are rendered; an air block yields empty masks. */
	public record Request(String label, int width, int height, int repeats, BlockPos target) {
	}

	/** Outcome of one request; {@code failure} is null on success. */
	public record Result(Request request, Path directory, @Nullable String failure, JsonObject summary) {
	}

	private record Frame(byte[] rgb, byte[] visible, byte[] unoccluded, long renderNanos, long readbackNanos) {
	}

	private M0Capture() {
	}

	public static void initialize() {
		TargetMaskRenderer.initialize();
		ClientTickEvents.END_CLIENT_TICK.register(M0Capture::onEndTick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			literal("honeyfactory_capture").then(argument("label", StringArgumentType.word())
				.then(argument("width", IntegerArgumentType.integer(16, 4096))
					.then(argument("height", IntegerArgumentType.integer(16, 4096))
						.then(argument("repeats", IntegerArgumentType.integer(1, 100)).executes(context -> {
							Request request = new Request(
								StringArgumentType.getString(context, "label"),
								IntegerArgumentType.getInteger(context, "width"),
								IntegerArgumentType.getInteger(context, "height"),
								IntegerArgumentType.getInteger(context, "repeats"),
								M0Fixture.TARGET
							);
							submit(request);
							context.getSource().sendFeedback(Component.literal("Queued M0 capture " + request.label()));
							return 1;
						})))))
		));
	}

	/** Queues a capture for the end of the next client tick; the future completes on the client thread. */
	public static CompletableFuture<Result> submit(Request request) {
		if (!LABEL.matcher(request.label()).matches()) {
			throw new IllegalArgumentException("Capture label must match " + LABEL.pattern());
		}

		if (pending != null) {
			throw new IllegalStateException("A capture is already pending");
		}

		lastResult = null;
		pending = request;
		pendingResult = new CompletableFuture<>();
		return pendingResult;
	}

	public static @Nullable Result lastResult() {
		return lastResult;
	}

	private static void onEndTick(Minecraft minecraft) {
		Request request = pending;
		if (request == null) {
			return;
		}

		pending = null;
		Path directory = minecraft.gameDirectory.toPath().resolve(OUTPUT_DIRECTORY).resolve(request.label());
		JsonObject summary = new JsonObject();
		String failure;
		try {
			failure = capture(minecraft, request, directory, summary);
		} catch (IOException | RuntimeException e) {
			HoneyFactory.LOGGER.error("M0 capture {} failed", request.label(), e);
			failure = e.toString();
		}

		summary.addProperty("failure", failure);
		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			HoneyFactory.LOGGER.error("Could not write M0 capture summary", e);
			failure = failure == null ? e.toString() : failure;
		}

		lastResult = new Result(request, directory, failure, summary);
		CompletableFuture<Result> future = pendingResult;
		pendingResult = null;
		future.complete(lastResult);
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.literal(failure == null ? "M0 capture written to " + directory : "M0 capture failed: " + failure));
		}
	}

	private static @Nullable String capture(Minecraft minecraft, Request request, Path directory, JsonObject summary) throws IOException {
		if (minecraft.level == null || minecraft.player == null) {
			return "no client level";
		}

		int width = request.width();
		int height = request.height();
		Window window = minecraft.getWindow();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		Camera camera = minecraft.gameRenderer.mainCamera();
		int originalWidth = window.getWidth();
		int originalHeight = window.getHeight();
		boolean bobView = minecraft.options.bobView().get();
		List<Frame> frames = new ArrayList<>();
		String failure = null;
		describe(minecraft, request, summary);
		if (!minecraft.gameRenderer.useImprovedTransparency()) {
			// Classic transparency writes translucent depth, including the target's (ADR 0005).
			return IMPROVED_TRANSPARENCY_REQUIRED;
		}

		try {
			minecraft.options.bobView().set(false);
			minecraft.gameRenderer.setRenderBlockOutline(false);
			camera.enablePanoramicMode();
			window.setWidth(width);
			window.setHeight(height);
			main.resize(width, height);

			for (int i = 0; i < request.repeats() && failure == null; i++) {
				long start = System.nanoTime();
				TargetMaskRenderer.arm(request.target());
				minecraft.gameRenderer.update(DeltaTracker.ONE);
				minecraft.gameRenderer.extract(DeltaTracker.ONE, true);
				minecraft.gameRenderer.renderLevel();
				failure = TargetMaskRenderer.finish();
				long rendered = System.nanoTime();
				if (failure == null) {
					byte[][] pixels = readBack(main.getColorTexture(), TargetMaskRenderer.visibleTexture(), TargetMaskRenderer.unoccludedTexture());
					frames.add(new Frame(pixels[0], pixels[1], pixels[2], rendered - start, System.nanoTime() - rendered));
				}
			}

			summary.addProperty("estimated_capture_gpu_bytes", estimatedGpuBytes(minecraft, width, height));
		} finally {
			camera.disablePanoramicMode();
			minecraft.gameRenderer.setRenderBlockOutline(true);
			minecraft.options.bobView().set(bobView);
			window.setWidth(originalWidth);
			window.setHeight(originalHeight);
			main.resize(originalWidth, originalHeight);
			TargetMaskRenderer.close();
		}

		if (failure != null) {
			return failure;
		}

		return analyse(frames, width, height, directory, summary);
	}

	private static void describe(Minecraft minecraft, Request request, JsonObject summary) {
		summary.addProperty("label", request.label());
		summary.addProperty("width", request.width());
		summary.addProperty("height", request.height());
		summary.addProperty("repeats", request.repeats());
		summary.addProperty("vertical_fov_degrees", 90.0);
		summary.addProperty("improved_transparency", minecraft.gameRenderer.useImprovedTransparency());
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		summary.addProperty("graphics_backend", device.backendName());
		summary.addProperty("graphics_device", device.name());
		summary.addProperty("graphics_driver", device.driverInfo());
		summary.addProperty("depth_zero_to_one", device.isZZeroToOne());
		summary.addProperty("target_block", minecraft.level.getBlockState(request.target()).toString());
		JsonArray target = new JsonArray();
		target.add(request.target().getX());
		target.add(request.target().getY());
		target.add(request.target().getZ());
		summary.add("target_position", target);
		JsonArray eye = new JsonArray();
		eye.add(minecraft.player.getX());
		eye.add(minecraft.player.getEyeY());
		eye.add(minecraft.player.getZ());
		summary.add("eye_position", eye);
		summary.addProperty("yaw_degrees", minecraft.player.getYRot());
		summary.addProperty("pitch_degrees", minecraft.player.getXRot());
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

	/** Returns RGBA8 rows ordered from the image top, as vanilla screenshots flip them. */
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

	private static @Nullable String analyse(List<Frame> frames, int width, int height, Path directory, JsonObject summary) throws IOException {
		JsonArray repeats = new JsonArray();
		String firstRgb = null;
		String firstVisible = null;
		String firstUnoccluded = null;
		boolean rgbDeterministic = true;
		boolean masksDeterministic = true;
		String failure = null;
		for (int i = 0; i < frames.size(); i++) {
			Frame frame = frames.get(i);
			byte[] rgb = channels(frame.rgb(), 3);
			byte[] visible = channels(frame.visible(), 1);
			byte[] unoccluded = channels(frame.unoccluded(), 1);
			int visiblePixels = 0;
			int unoccludedPixels = 0;
			int visibleOutside = 0;
			int nonBinary = 0;
			for (int p = 0; p < visible.length; p++) {
				int v = visible[p] & 0xFF;
				int u = unoccluded[p] & 0xFF;
				nonBinary += (v != 0 && v != 255 ? 1 : 0) + (u != 0 && u != 255 ? 1 : 0);
				visiblePixels += v == 255 ? 1 : 0;
				unoccludedPixels += u == 255 ? 1 : 0;
				visibleOutside += v == 255 && u != 255 ? 1 : 0;
			}

			String rgbHash = sha256(rgb);
			String visibleHash = sha256(visible);
			String unoccludedHash = sha256(unoccluded);
			if (i == 0) {
				firstRgb = rgbHash;
				firstVisible = visibleHash;
				firstUnoccluded = unoccludedHash;
				writePngs(directory, width, height, rgb, visible, unoccluded);
			} else {
				rgbDeterministic &= rgbHash.equals(firstRgb);
				masksDeterministic &= visibleHash.equals(firstVisible) && unoccludedHash.equals(firstUnoccluded);
			}

			if (nonBinary > 0 || visibleOutside > 0) {
				failure = "repeat " + i + " has " + nonBinary + " non-binary and " + visibleOutside + " visible-outside-unoccluded pixels";
			}

			JsonObject repeat = new JsonObject();
			repeat.addProperty("visible_pixels", visiblePixels);
			repeat.addProperty("unoccluded_pixels", unoccludedPixels);
			repeat.addProperty("visible_outside_unoccluded_pixels", visibleOutside);
			repeat.addProperty("non_binary_mask_pixels", nonBinary);
			repeat.addProperty("target_status", status(visiblePixels, unoccludedPixels));
			repeat.addProperty("visibility_ratio", unoccludedPixels == 0 ? null : (double) visiblePixels / unoccludedPixels);
			repeat.addProperty("rgb_sha256", rgbHash);
			repeat.addProperty("visible_mask_sha256", visibleHash);
			repeat.addProperty("unoccluded_mask_sha256", unoccludedHash);
			repeat.addProperty("render_ms", frame.renderNanos() / 1.0e6);
			repeat.addProperty("readback_ms", frame.readbackNanos() / 1.0e6);
			repeats.add(repeat);
		}

		summary.add("captures", repeats);
		summary.addProperty("masks_deterministic", masksDeterministic);
		summary.addProperty("rgb_deterministic", rgbDeterministic);
		if (failure == null && !masksDeterministic) {
			failure = "masks differ between repeats";
		}

		return failure;
	}

	/** Target status for an in-view target, following the specification's derivation order. */
	private static String status(int visible, int unoccluded) {
		if (unoccluded == 0) {
			return "subpixel_or_out_of_view";
		}

		if (visible == 0) {
			return "fully_occluded";
		}

		return visible == unoccluded ? "fully_visible" : "partially_visible";
	}

	private static long estimatedGpuBytes(Minecraft minecraft, int width, int height) {
		long pixels = (long) width * height;
		// Main color RGBA8 + main depth D32 and, with OIT, depth bounds (2x RGBA32F),
		// transmittance (2x RGBA16F), accumulation (RGBA16F) and two extra D32 depths.
		long frame = pixels * (4 + 4) + (minecraft.gameRenderer.useImprovedTransparency() ? pixels * (16 + 16 + 8 + 8 + 8 + 4 + 4) : 0);
		return frame + TargetMaskRenderer.allocatedBytes() + pixels * 4 * 3;
	}

	private static byte[] channels(byte[] rgba, int count) {
		byte[] result = new byte[rgba.length / 4 * count];
		for (int p = 0, q = 0; p < rgba.length; p += 4) {
			for (int c = 0; c < count; c++) {
				result[q++] = rgba[p + c];
			}
		}

		return result;
	}

	private static void writePngs(Path directory, int width, int height, byte[] rgb, byte[] visible, byte[] unoccluded) throws IOException {
		Files.createDirectories(directory);
		BufferedImage rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		BufferedImage visibleImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		BufferedImage unoccludedImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = y * width + x;
				int r = rgb[p * 3] & 0xFF;
				int g = rgb[p * 3 + 1] & 0xFF;
				int b = rgb[p * 3 + 2] & 0xFF;
				rgbImage.setRGB(x, y, r << 16 | g << 8 | b);
				boolean positive = visible[p] != 0;
				boolean hidden = !positive && unoccluded[p] != 0;
				overlay.setRGB(x, y, positive ? (r / 2 + 127) << 16 | g / 2 << 8 | b / 2 : hidden ? r / 2 << 16 | g / 2 << 8 | (b / 2 + 127) : r << 16 | g << 8 | b);
				visibleImage.getRaster().setSample(x, y, 0, visible[p] & 0xFF);
				unoccludedImage.getRaster().setSample(x, y, 0, unoccluded[p] & 0xFF);
			}
		}

		ImageIO.write(rgbImage, "png", directory.resolve("rgb.png").toFile());
		ImageIO.write(visibleImage, "png", directory.resolve("visible_mask.png").toFile());
		ImageIO.write(unoccludedImage, "png", directory.resolve("unoccluded_mask.png").toFile());
		ImageIO.write(overlay, "png", directory.resolve("overlay.png").toFile());
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
