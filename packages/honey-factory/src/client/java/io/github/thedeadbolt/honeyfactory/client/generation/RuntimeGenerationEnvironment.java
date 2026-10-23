package io.github.thedeadbolt.honeyfactory.client.generation;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import io.github.thedeadbolt.honeyfactory.BuildInfo;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.world.Baseline;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Dataset generation through the client environment runtime of the running game. */
public final class RuntimeGenerationEnvironment implements GenerationEnvironment {
	private final EnvironmentRuntime runtime;

	public RuntimeGenerationEnvironment(EnvironmentRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public CompletableFuture<SceneReady> reset(ArenaLayout arena, ScenePlan plan, CameraPose firstViewpoint) {
		return this.runtime.reset(arena, plan, firstViewpoint)
			.thenApply(scene -> new SceneReady(scene.operationId(), scene.layoutSha256(), scene.settledTicks(), Baseline.ruleValues()));
	}

	@Override
	public CompletableFuture<ViewReady> setViewpoint(CameraPose pose) {
		return this.runtime.setViewpoint(pose).thenApply(view -> new ViewReady(view.operationId(), view.pose(), view.settledTicks()));
	}

	@Override
	public CompletableFuture<Observation> observe(CaptureProjection projection, int @Nullable [] target) {
		int[] sceneTarget = this.runtime.scene() == null ? null : this.runtime.scene().targetPosition(this.runtime.arena());
		if (!Arrays.equals(target, sceneTarget)) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH,
				"requested target " + Arrays.toString(target) + " is not the current scene's " + Arrays.toString(sceneTarget)));
		}

		return this.runtime.observe(projection).thenApply(observation -> new Observation(
			observation.operationId(),
			observation.capture().worldTick(),
			observation.capture().partialTick(),
			observation.capture().images(),
			observation.biome(),
			observation.skyLight(),
			observation.blockLight()
		));
	}

	@Override
	public DatasetManifest.Software software() {
		BuildInfo build = BuildInfo.load();
		return new DatasetManifest.Software(
			version("minecraft"),
			version("fabricloader"),
			version("fabric-api"),
			version(HoneyFactory.MOD_ID),
			Runtime.version().toString(),
			build.gitCommit(),
			build.gitDirty()
		);
	}

	@Override
	public DatasetManifest.RenderEnvironment renderEnvironment() {
		Minecraft minecraft = Minecraft.getInstance();
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		List<DatasetManifest.ResourcePack> packs = minecraft.getResourcePackRepository().getSelectedIds().stream()
			.map(id -> new DatasetManifest.ResourcePack(id, null))
			.toList();
		return new DatasetManifest.RenderEnvironment(
			device.backendName(),
			device.name(),
			device.driverInfo(),
			minecraft.options.improvedTransparency().get(),
			minecraft.options.renderDistance().get(),
			minecraft.options.gamma().get(),
			minecraft.options.cloudStatus().get().name().toLowerCase(Locale.ROOT),
			null,
			packs
		);
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
