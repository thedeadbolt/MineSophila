package io.github.thedeadbolt.honeyfactory.client;

import io.github.thedeadbolt.honeyfactory.client.m0.M0Capture;
import net.fabricmc.api.ClientModInitializer;

/**
 * Register the temporary M0 rendering-feasibility capture; production frame capture is
 * described in docs/architecture.md.
 */
public class HoneyFactoryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		M0Capture.initialize();
	}
}
