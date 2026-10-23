package io.github.thedeadbolt.honeyfactory.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Render the known target block's baked model into two off-screen categorical masks at
 * the end of the main level pass, using the frame's camera matrices and viewport.
 *
 * <ul>
 *   <li>Visible: depth-tested, without writing, against the main depth attachment.</li>
 *   <li>Unoccluded: depth-tested against a freshly cleared private depth texture.</li>
 * </ul>
 *
 * <p>The main depth holds only opaque/cutout geometry when improved transparency (OIT) is
 * enabled, because every OIT translucent pipeline has depth writes disabled. With classic
 * transparency, translucent terrain (including the target) also writes main depth.
 */
public final class TargetMaskRenderer {
	public static final RenderPipeline VISIBLE = RenderPipelines.register(pipeline("target_mask_visible", false));
	public static final RenderPipeline UNOCCLUDED = RenderPipelines.register(pipeline("target_mask_unoccluded", true));

	private static final Vector4f CLEAR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	private static @Nullable BlockPos armedTarget;
	private static boolean drawn;
	private static @Nullable String failure;
	private static @Nullable GpuTexture visible;
	private static @Nullable GpuTexture unoccluded;
	private static @Nullable GpuTexture unoccludedDepth;
	private static @Nullable GpuTextureView visibleView;
	private static @Nullable GpuTextureView unoccludedView;
	private static @Nullable GpuTextureView unoccludedDepthView;

	private TargetMaskRenderer() {
	}

	/** Registers the pipelines before resources load and hooks the end of the main pass. */
	public static void initialize() {
		LevelRenderEvents.END_MAIN.register(TargetMaskRenderer::draw);
	}

	private static RenderPipeline pipeline(String name, boolean writeDepth) {
		return RenderPipeline.builder()
			.withLocation(HoneyFactory.id("pipeline/" + name))
			.withVertexShader(HoneyFactory.id("core/target_mask"))
			.withFragmentShader(HoneyFactory.id("core/target_mask"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexBinding(0, DefaultVertexFormat.BLOCK)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
			.build();
	}

	/** Requests masks for the next level render. */
	static void arm(BlockPos target) {
		armedTarget = target.immutable();
		drawn = false;
		failure = null;
	}

	/** Returns the failure reason, or null when both masks were drawn for the armed render. */
	static @Nullable String finish() {
		BlockPos target = armedTarget;
		armedTarget = null;
		if (failure != null) {
			return failure;
		}

		return drawn ? null : "end of main pass was not reached for target " + target;
	}

	static GpuTexture visibleTexture() {
		return require(visible);
	}

	static GpuTexture unoccludedTexture() {
		return require(unoccluded);
	}

	/** Bytes held by the mask textures at their current size. */
	static long allocatedBytes() {
		long total = 0;
		for (GpuTexture texture : new GpuTexture[] {visible, unoccluded, unoccludedDepth}) {
			if (texture != null) {
				total += (long) texture.getWidth(0) * texture.getHeight(0) * texture.getFormat().blockSize();
			}
		}

		return total;
	}

	static void close() {
		for (GpuTextureView view : new GpuTextureView[] {visibleView, unoccludedView, unoccludedDepthView}) {
			if (view != null) {
				view.close();
			}
		}

		for (GpuTexture texture : new GpuTexture[] {visible, unoccluded, unoccludedDepth}) {
			if (texture != null) {
				texture.close();
			}
		}

		visible = null;
		unoccluded = null;
		unoccludedDepth = null;
		visibleView = null;
		unoccludedView = null;
		unoccludedDepthView = null;
	}

	private static GpuTexture require(@Nullable GpuTexture texture) {
		if (texture == null) {
			throw new IllegalStateException("Target masks have not been rendered");
		}

		return texture;
	}

	private static void draw(LevelRenderContext context) {
		BlockPos target = armedTarget;
		if (target == null || drawn) {
			return;
		}

		try {
			drawMasks(context, target);
			drawn = true;
		} catch (RuntimeException e) {
			failure = e.toString();
			HoneyFactory.LOGGER.error("Target mask render failed", e);
		}
	}

	private static void drawMasks(LevelRenderContext context, BlockPos target) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			throw new IllegalStateException("No client level");
		}

		RenderTarget main = context.gameRenderer().mainRenderTarget();
		GpuTextureView sceneDepth = main.getDepthTextureView();
		GpuTexture sceneDepthTexture = main.getDepthTexture();
		if (sceneDepth == null || sceneDepthTexture == null) {
			throw new IllegalStateException("Main render target has no depth attachment");
		}

		ensureTextures(main.width, main.height, sceneDepthTexture.getFormat());
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.clearColorTexture(visible, CLEAR);
		encoder.clearColorTexture(unoccluded, CLEAR);
		encoder.clearDepthTexture(unoccludedDepth, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);

		BlockState state = level.getBlockState(target);
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.BLOCK.getVertexSize() * 64)) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
			new ModelBlockRenderer(false, true, minecraft.getBlockColors()).tesselateBlock(
				builder::putBlockBakedQuad,
				0.0F,
				0.0F,
				0.0F,
				level,
				target,
				state,
				minecraft.getModelManager().getBlockStateModelSet().get(state),
				state.getSeed(target)
			);

			try (MeshData mesh = builder.build()) {
				if (mesh == null) {
					// No exposed target faces: both masks stay empty.
					return;
				}

				try (GpuBuffer vertices = device.createBuffer(() -> "Honey Factory target vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())) {
					int indexCount = mesh.drawState().indexCount();
					RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
					GpuBuffer indexBuffer = indices.getBuffer(indexCount);
					BlockPos cameraBlock = BlockPos.containing(context.levelState().cameraRenderState.pos);
					GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
						RenderSystem.getModelViewMatrixCopy(),
						new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
						new Vector3f(target.getX() - cameraBlock.getX(), target.getY() - cameraBlock.getY(), target.getZ() - cameraBlock.getZ()),
						new Matrix4f()
					);
					GpuTextureView atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();

					drawPass(encoder, "Honey Factory visible target mask", VISIBLE, visibleView, sceneDepth, transforms, atlas, vertices, indexBuffer, indices, indexCount);
					drawPass(encoder, "Honey Factory unoccluded target mask", UNOCCLUDED, unoccludedView, unoccludedDepthView, transforms, atlas, vertices, indexBuffer, indices, indexCount);
				}
			}
		}
	}

	private static void drawPass(
		CommandEncoder encoder,
		String label,
		RenderPipeline pipeline,
		GpuTextureView color,
		GpuTextureView depth,
		GpuBufferSlice transforms,
		GpuTextureView atlas,
		GpuBuffer vertices,
		GpuBuffer indexBuffer,
		RenderSystem.AutoStorageIndexBuffer indices,
		int indexCount
	) {
		try (RenderPass pass = encoder.createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			pass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform());
			pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
			pass.setUniform("DynamicTransforms", transforms);
			pass.setUniform("Sampler0", atlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setVertexBuffer(0, vertices.slice());
			pass.setIndexBuffer(indexBuffer, indices.type());
			pass.drawIndexed(indexCount, 1, 0, 0, 0);
		}
	}

	private static void ensureTextures(int width, int height, GpuFormat depthFormat) {
		if (visible != null && visible.getWidth(0) == width && visible.getHeight(0) == height && unoccludedDepth.getFormat() == depthFormat) {
			return;
		}

		close();
		GpuDevice device = RenderSystem.getDevice();
		int colorUsage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
		visible = device.createTexture(() -> "Honey Factory visible mask", colorUsage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		unoccluded = device.createTexture(() -> "Honey Factory unoccluded mask", colorUsage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		unoccludedDepth = device.createTexture(() -> "Honey Factory unoccluded depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, width, height, 1, 1);
		visibleView = device.createTextureView(visible);
		unoccludedView = device.createTextureView(unoccluded);
		unoccludedDepthView = device.createTextureView(unoccludedDepth);
	}
}
