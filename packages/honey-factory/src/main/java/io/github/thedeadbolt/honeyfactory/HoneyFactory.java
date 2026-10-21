package io.github.thedeadbolt.honeyfactory;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log mod startup on the client and server and provide namespaced identifiers.
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
