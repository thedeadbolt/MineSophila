package io.github.thedeadbolt.honeyfactory.environment;

/**
 * Settings of an environment run: accepted action ranges, and whether movement uses
 * Minecraft's own auto-jump. The user's auto-jump option is set only while a step runs
 * and restored when it ends, however it ends.
 */
public record EnvironmentSettings(ActionLimits limits, boolean autoJump) {
	/** Default limits, with auto-jump assisting movement. */
	public static final EnvironmentSettings DEFAULT = new EnvironmentSettings(ActionLimits.DEFAULT, true);

	public EnvironmentSettings withAutoJump(boolean autoJump) {
		return new EnvironmentSettings(this.limits, autoJump);
	}

	public EnvironmentSettings withLimits(ActionLimits limits) {
		return new EnvironmentSettings(limits, this.autoJump);
	}
}
