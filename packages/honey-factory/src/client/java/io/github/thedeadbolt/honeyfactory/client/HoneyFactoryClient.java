package io.github.thedeadbolt.honeyfactory.client;

import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetRuns;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentCommands;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import net.fabricmc.api.ClientModInitializer;

/**
 * Register the capture service, the client environment runtime and the debug and
 * dataset commands.
 */
public class HoneyFactoryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CaptureService.initialize();
		EnvironmentRuntime.initialize();
		EnvironmentCommands.initialize();
		DatasetRuns.initialize();
	}
}
