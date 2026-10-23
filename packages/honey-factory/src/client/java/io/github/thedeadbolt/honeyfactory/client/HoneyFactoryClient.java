package io.github.thedeadbolt.honeyfactory.client;

import io.github.thedeadbolt.honeyfactory.client.m0.M0Capture;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentCommands;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import net.fabricmc.api.ClientModInitializer;

/**
 * Register the client environment runtime, its debug commands and the temporary M0
 * capture fixture; production frame capture is described in docs/architecture.md.
 */
public class HoneyFactoryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		M0Capture.initialize();
		EnvironmentRuntime.initialize();
		EnvironmentCommands.initialize();
	}
}
