package io.github.thedeadbolt.honeyfactory;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (client + server) entrypoint for the Honey Factory dataset generator.
 *
 * <p>Planned: scenario generation that places honey blocks in varied, partially hidden
 * situations and records ground-truth annotations for each captured frame.
 */
public class HoneyFactory implements ModInitializer {
	public static final String MOD_ID = "honey_factory";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Honey Factory loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
