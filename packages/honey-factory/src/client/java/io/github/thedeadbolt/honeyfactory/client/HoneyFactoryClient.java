package io.github.thedeadbolt.honeyfactory.client;

import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.control.ControlServer;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetCommands;
import io.github.thedeadbolt.honeyfactory.client.replay.ReplayCommands;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentCommands;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.client.runtime.TickStepper;
import net.fabricmc.api.ClientModInitializer;

/**
 * Register the capture service, the client environment runtime and the debug, dataset
 * and replay commands.
 */
public class HoneyFactoryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CaptureService.initialize();
		EnvironmentRuntime.initialize();
		TickStepper.initialize();
		ControlServer.initialize();
		EnvironmentCommands.initialize();
		DatasetCommands.initialize();
		ReplayCommands.initialize();
	}
}
