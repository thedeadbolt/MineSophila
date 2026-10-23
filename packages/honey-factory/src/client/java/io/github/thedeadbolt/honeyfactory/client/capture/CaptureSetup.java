package io.github.thedeadbolt.honeyfactory.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The client state one capture renders with, restored on close: output-sized window and
 * main render target, panoramic camera mode with the view's vertical field of view, and
 * view bobbing, the block outline and section fade-in switched off.
 */
final class CaptureSetup implements AutoCloseable {
	private static @Nullable Float activeVerticalFov;

	private final Minecraft minecraft;
	private final int windowWidth;
	private final int windowHeight;
	private final boolean bobView;
	private final double chunkFade;

	private CaptureSetup(Minecraft minecraft) {
		this.minecraft = minecraft;
		Window window = minecraft.getWindow();
		this.windowWidth = window.getWidth();
		this.windowHeight = window.getHeight();
		this.bobView = minecraft.options.bobView().get();
		this.chunkFade = minecraft.options.chunkSectionFadeInTime().get();
	}

	/** The vertical field of view of the capture being rendered, or null outside one. */
	static @Nullable Float activeVerticalFov() {
		return activeVerticalFov;
	}

	static CaptureSetup apply(Minecraft minecraft, CaptureProjection.View view) {
		CaptureSetup setup = new CaptureSetup(minecraft);
		minecraft.options.bobView().set(false);
		// Section fade-in follows wall-clock time, which would make RGB depend on timing.
		minecraft.options.chunkSectionFadeInTime().set(0.0);
		minecraft.gameRenderer.setRenderBlockOutline(false);
		minecraft.gameRenderer.mainCamera().enablePanoramicMode();
		activeVerticalFov = (float) view.verticalFovDegrees();
		resize(minecraft, view.width(), view.height());
		return setup;
	}

	@Override
	public void close() {
		activeVerticalFov = null;
		Camera camera = this.minecraft.gameRenderer.mainCamera();
		camera.disablePanoramicMode();
		this.minecraft.gameRenderer.setRenderBlockOutline(true);
		this.minecraft.options.bobView().set(this.bobView);
		this.minecraft.options.chunkSectionFadeInTime().set(this.chunkFade);
		resize(this.minecraft, this.windowWidth, this.windowHeight);
		TargetMaskRenderer.close();
	}

	private static void resize(Minecraft minecraft, int width, int height) {
		Window window = minecraft.getWindow();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		window.setWidth(width);
		window.setHeight(height);
		main.resize(width, height);
	}
}
