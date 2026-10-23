package io.github.thedeadbolt.honeyfactory.world;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The dataset world's void arena dimension. It exists only in worlds created with the
 * {@code honey_factory:dataset_world} world preset, so personal saves never contain it.
 */
public final class ArenaDimension {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, HoneyFactory.id("arena"));
	public static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, HoneyFactory.id("dataset_world"));

	private ArenaDimension() {
	}
}
