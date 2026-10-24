package io.github.thedeadbolt.honeyfactory.environment;

import java.util.Locale;

/**
 * One movement action of {@code step}. Degrees and game ticks are the environment
 * contract; keyboard press durations are not.
 *
 * <p>Conventions follow Minecraft's: a positive {@link Turn} increases yaw, turning right
 * (clockwise seen from above), and a positive {@link Look} increases pitch, looking down.
 * Yaw is kept within [-180, 180) and pitch is clamped to [-90, 90], so a look may complete
 * less than it requested. Ticks are player ticks: {@link MoveForward} holds the forward
 * input for exactly that many, and {@link Wait} commands no movement for that many.
 */
public sealed interface Action {
	/** Turns in place by {@code degrees} of yaw. */
	record Turn(float degrees) implements Action {
		/** The yaw after this turn from {@code yaw}, within [-180, 180). */
		public float resultingYaw(float yaw) {
			return wrapDegrees(yaw + this.degrees);
		}

		@Override
		public double requested() {
			return this.degrees;
		}
	}

	/** Changes pitch by {@code degrees}, within Minecraft's limits. */
	record Look(float degrees) implements Action {
		/** The pitch after this look from {@code pitch}, clamped to [-90, 90]. */
		public float resultingPitch(float pitch) {
			return Math.clamp(pitch + this.degrees, -90.0F, 90.0F);
		}

		@Override
		public double requested() {
			return this.degrees;
		}
	}

	/** Holds the forward input for {@code ticks} player ticks. */
	record MoveForward(int ticks) implements Action {
		@Override
		public double requested() {
			return this.ticks;
		}
	}

	/** Advances {@code ticks} player ticks without commanded movement. */
	record Wait(int ticks) implements Action {
		@Override
		public double requested() {
			return this.ticks;
		}
	}

	/** Releases every movement input without advancing; momentum decays in later ticks. */
	record Stop() implements Action {
		@Override
		public double requested() {
			return 0.0;
		}
	}

	/** Mining is out of scope; every mine request is rejected as unsupported. */
	record Mine(int ticks) implements Action {
		@Override
		public double requested() {
			return this.ticks;
		}
	}

	/** The requested value: degrees for turn and look, ticks otherwise. */
	double requested();

	/** Player ticks this action holds inputs for: its ticks for move, wait and mine, else 0. */
	default int ticks() {
		return switch (this) {
			case MoveForward move -> move.ticks();
			case Wait wait -> wait.ticks();
			case Mine mine -> mine.ticks();
			case Turn turn -> 0;
			case Look look -> 0;
			case Stop stop -> 0;
		};
	}

	/** The specification's name: TURN, LOOK, MOVE_FORWARD, WAIT, STOP or MINE. */
	default String type() {
		return switch (this) {
			case Turn turn -> "TURN";
			case Look look -> "LOOK";
			case MoveForward move -> "MOVE_FORWARD";
			case Wait wait -> "WAIT";
			case Stop stop -> "STOP";
			case Mine mine -> "MINE";
		};
	}

	/** {@code degrees} or {@code ticks}. */
	default String unit() {
		return this instanceof Turn || this instanceof Look ? "degrees" : "ticks";
	}

	/**
	 * Parses a type name, case-insensitive, and its value: degrees for turn and look, whole
	 * ticks for move_forward, wait and mine, none for stop.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#INVALID_REQUEST} for an unknown type or a bad value
	 */
	static Action parse(String type, double value) {
		String name = type.toUpperCase(Locale.ROOT);
		if (!Double.isFinite(value)) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, name + " needs a finite value");
		}

		return switch (name) {
			case "TURN" -> new Turn((float) value);
			case "LOOK" -> new Look((float) value);
			case "MOVE_FORWARD" -> new MoveForward(wholeTicks(name, value));
			case "WAIT" -> new Wait(wholeTicks(name, value));
			case "STOP" -> new Stop();
			case "MINE" -> new Mine(wholeTicks(name, value));
			default -> throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "unknown action " + type);
		};
	}

	private static int wholeTicks(String name, double value) {
		if (value != Math.rint(value) || value < 0 || value > Integer.MAX_VALUE) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, name + " needs a whole number of ticks, not " + value);
		}

		return (int) value;
	}

	/** {@code degrees} within [-180, 180). */
	static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) {
			wrapped -= 360.0F;
		}

		if (wrapped < -180.0F) {
			wrapped += 360.0F;
		}

		return wrapped;
	}
}
