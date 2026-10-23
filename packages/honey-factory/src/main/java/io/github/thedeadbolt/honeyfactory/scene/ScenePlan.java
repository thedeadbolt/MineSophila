package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.BlockLayout;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An immutable scene: a template restored first, then an ordered block delta applied
 * block by block, plus scene-wide environment settings. Positions are arena-relative.
 *
 * <p>Generators return plans; they never touch the world. Applying the same plan to a
 * fresh arena must reproduce {@link #layoutSha256(ArenaLayout)}.
 */
public record ScenePlan(Template template, List<Placement> delta, @Nullable Placement target, int timeOfDayTicks, Weather weather) {
	public ScenePlan {
		delta = List.copyOf(delta);
		for (Placement placement : delta) {
			requireInside(template, placement);
		}

		if (target != null) {
			requireInside(template, target);
			if (!delta.contains(target)) {
				throw new IllegalArgumentException("The target must be placed by the delta: " + target);
			}
		}
	}

	private static void requireInside(Template template, Placement placement) {
		if (placement.x() < 0 || placement.y() < 0 || placement.z() < 0
			|| placement.x() >= template.width() || placement.y() >= template.height() || placement.z() >= template.length()) {
			throw new IllegalArgumentException("Placement outside the arena core: " + placement);
		}
	}

	/** Absolute non-air blocks after restoring the template and applying the delta in order. */
	public List<BlockLayout.Block> layoutBlocks(ArenaLayout arena) {
		Map<List<Integer>, String> blocks = new LinkedHashMap<>();
		for (Placement placement : this.template.blocks()) {
			blocks.put(List.of(placement.x(), placement.y(), placement.z()), placement.state());
		}

		for (Placement placement : this.delta) {
			blocks.put(List.of(placement.x(), placement.y(), placement.z()), placement.state());
		}

		BlockBox core = arena.core();
		List<BlockLayout.Block> result = new ArrayList<>();
		blocks.forEach((position, state) -> {
			if (!state.equals(Placement.AIR)) {
				result.add(new BlockLayout.Block(core.minX() + position.get(0), core.minY() + position.get(1), core.minZ() + position.get(2), state));
			}
		});
		return result;
	}

	/** {@code layout-v1} hash of the whole reset volume once this plan is applied. */
	public String layoutSha256(ArenaLayout arena) {
		return BlockLayout.sha256(arena.resetVolume().min(), arena.resetVolume().max(), layoutBlocks(arena));
	}

	/** Absolute target position, or null for a target-absent scene. */
	public int @Nullable [] targetPosition(ArenaLayout arena) {
		return this.target == null ? null : new int[] {
			arena.core().minX() + this.target.x(), arena.core().minY() + this.target.y(), arena.core().minZ() + this.target.z()
		};
	}
}
