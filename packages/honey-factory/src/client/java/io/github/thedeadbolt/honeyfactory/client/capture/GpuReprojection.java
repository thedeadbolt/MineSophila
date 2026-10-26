package io.github.thedeadbolt.honeyfactory.client.capture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CubeFace;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/**
 * Reprojects a panorama's six cube faces on the GPU (M12-1, ADR 0019), so only the output is
 * read back instead of every face. A fragment shader repeats {@code PanoramaSampling}'s
 * integer arithmetic from its own sampling table, uploaded once as a texel buffer, so the
 * output equals the CPU reprojection byte for byte.
 *
 * <p>Each rendered face is copied into one of three atlases (colour, visible mask,
 * unoccluded mask) of six faces side by side. One full-screen pass then writes RGB and the
 * visible mask into one output and the unoccluded mask into another.
 *
 * <p>Any failure of these steps is a {@link Failure}; {@link CaptureService} then falls back
 * to the CPU path and {@link #disable} turns this path off for the rest of the session.
 */
final class GpuReprojection {
	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
		.withUniform("FaceColor", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FaceVisible", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FaceUnoccluded", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("Samples", UniformType.TEXEL_BUFFER, GpuFormat.RG32_UINT)
		.withUniform("ReprojectInfo", UniformType.UNIFORM_BUFFER)
		.build();
	private static final ColorTargetState OUTPUT = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
	static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder()
		.withLocation(HoneyFactory.id("pipeline/panorama_reproject"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(HoneyFactory.id("core/panorama_reproject"))
		.withBindGroupLayout(LAYOUT)
		.withColorTargetState(0, OUTPUT)
		.withColorTargetState(1, OUTPUT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build());

	/** A GPU reprojection step failed; the capture falls back to the CPU. */
	static final class Failure extends RuntimeException {
		Failure(String message, @Nullable Throwable cause) {
			super(message, cause);
		}
	}

	private static boolean disabled;
	private static @Nullable String injectedFailure;
	private static @Nullable Resources resources;

	/** The textures and buffers for one projection size. */
	private record Resources(int width, int height, int faceSize, GpuTexture[] atlases, GpuTextureView[] atlasViews, GpuTexture[] outputs, GpuTextureView[] outputViews,
		GpuBuffer samples, GpuBuffer info) {
		void close() {
			for (GpuTextureView view : this.atlasViews) {
				view.close();
			}

			for (GpuTextureView view : this.outputViews) {
				view.close();
			}

			for (GpuTexture texture : this.atlases) {
				texture.close();
			}

			for (GpuTexture texture : this.outputs) {
				texture.close();
			}

			this.samples.close();
			this.info.close();
		}
	}

	private GpuReprojection() {
	}

	/** Loads the class, registering the pipeline before resources load. */
	static void initialize() {
	}

	/** Whether the GPU path may run: it has not failed this session. */
	static boolean usable() {
		return !disabled;
	}

	/** Turns the GPU path off for the session after {@code failure}. */
	static void disable(Failure failure) {
		disabled = true;
		HoneyFactory.LOGGER.error("GPU reprojection failed; captures use the CPU from now on", failure);
	}

	/** Lets the GPU path run again, for tests. */
	static void enableAgain() {
		disabled = false;
	}

	/** Makes the next GPU reprojection fail with {@code reason}, for fallback tests. */
	static void failNext(String reason) {
		injectedFailure = reason;
	}

	/** Prepares for a capture through {@code projection}. */
	static void begin(PanoramicProjection projection) {
		try {
			Resources current = resources;
			if (current == null || current.width() != projection.width() || current.height() != projection.height() || current.faceSize() != projection.faceSize()) {
				if (current != null) {
					current.close();
				}

				resources = null;
				resources = create(projection);
			}
		} catch (RuntimeException e) {
			throw new Failure("preparing GPU reprojection failed: " + e, e);
		}
	}

	/** Copies face {@code face}'s colour and masks into the atlases; masks are null without a target. */
	static void addFace(int face, GpuTexture color, @Nullable GpuTexture visible, @Nullable GpuTexture unoccluded) {
		Resources current = require();
		try {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			int size = current.faceSize();
			GpuTexture[] sources = {color, visible, unoccluded};
			for (int i = 0; i < sources.length; i++) {
				if (sources[i] != null) {
					encoder.copyTextureToTexture(sources[i], current.atlases()[i], 0, face * size, 0, 0, 0, size, size);
				}
			}
		} catch (RuntimeException e) {
			throw new Failure("copying face " + face + " into the atlases failed: " + e, e);
		}
	}

	/** Draws the panorama from the atlases and starts reading back the outputs. */
	static CaptureService.Readback assemble() {
		Resources current = require();
		try {
			String injected = injectedFailure;
			if (injected != null) {
				injectedFailure = null;
				throw new IllegalStateException("injected: " + injected);
			}

			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Honey Factory panorama reprojection")
				.withColorAttachment(current.outputViews()[0])
				.withColorAttachment(current.outputViews()[1])
				.build();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
				pass.setUniform("FaceColor", current.atlasViews()[0], nearest);
				pass.setUniform("FaceVisible", current.atlasViews()[1], nearest);
				pass.setUniform("FaceUnoccluded", current.atlasViews()[2], nearest);
				pass.setUniform("Samples", current.samples());
				pass.setUniform("ReprojectInfo", current.info());
				pass.draw(3, 1, 0, 0);
			}

			return CaptureService.Readback.start(current.width(), current.height(), current.outputs(), CaptureService.Readback.PANORAMA_OUTPUTS);
		} catch (RuntimeException e) {
			throw new Failure("drawing the GPU reprojection failed: " + e, e);
		}
	}

	private static Resources require() {
		Resources current = resources;
		if (current == null) {
			throw new Failure("GPU reprojection was not prepared", null);
		}

		return current;
	}

	private static Resources create(PanoramicProjection projection) {
		GpuDevice device = RenderSystem.getDevice();
		int size = projection.faceSize();
		int faces = CubeFace.values().length;
		String[] names = {"colour", "visible mask", "unoccluded mask"};
		GpuTexture[] atlases = new GpuTexture[names.length];
		GpuTextureView[] atlasViews = new GpuTextureView[names.length];
		for (int i = 0; i < names.length; i++) {
			String name = names[i];
			atlases[i] = device.createTexture(() -> "Honey Factory face atlas, " + name, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, faces * size, size, 1, 1);
			atlasViews[i] = device.createTextureView(atlases[i]);
		}

		GpuTexture[] outputs = new GpuTexture[2];
		GpuTextureView[] outputViews = new GpuTextureView[2];
		for (int i = 0; i < outputs.length; i++) {
			int index = i;
			outputs[i] = device.createTexture(() -> "Honey Factory panorama output " + index, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM,
				projection.width(), projection.height(), 1, 1);
			outputViews[i] = device.createTextureView(outputs[i]);
		}

		int[] table = projection.gpuSampleTable();
		ByteBuffer samples = ByteBuffer.allocateDirect(table.length * Integer.BYTES).order(ByteOrder.nativeOrder());
		samples.asIntBuffer().put(table);
		ByteBuffer info = ByteBuffer.allocateDirect(4 * Integer.BYTES).order(ByteOrder.nativeOrder());
		info.asIntBuffer().put(new int[] {projection.width(), projection.height(), size, 0});
		return new Resources(projection.width(), projection.height(), size, atlases, atlasViews, outputs, outputViews,
			device.createBuffer(() -> "Honey Factory panorama samples", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, samples),
			device.createBuffer(() -> "Honey Factory panorama info", GpuBuffer.USAGE_UNIFORM, info));
	}
}
