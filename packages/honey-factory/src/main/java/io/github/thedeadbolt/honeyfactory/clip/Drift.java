package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/**
 * Temporally correlated departures from the teacher's command (DART-style): the body is
 * nudged off course and the teacher, recorded beside it, recovers. Episodes start at a rate
 * that makes about {@code rate} of ticks drift and last a geometric number of ticks with mean
 * {@code meanTicks}. Each adds a half-sine bump to the turn rate and the forward speed, up to
 * the configured amplitudes, so it rises and falls smoothly; the result stays within the
 * motor limits. Call {@link #apply} once per tick, in order.
 *
 * <p>With a hazard within reach, a share {@code hazard_fraction} of episodes turn towards it
 * and push forward (SAFE-GIL style): the body is taken close to ledges and walls, and the
 * teacher's recoveries from there enter the data. The {@link EdgeGuard} keeps it from going
 * over.
 */
public final class Drift {
	private final SplittableRandom random;
	private final ClipSettings.Drift settings;
	private final MotorLimits limits;
	private final Hazards hazards;
	/** The chance an episode starts on a tick without one. */
	private final double startChance;
	private int length;
	private int step;
	private double turn;
	private double forward;
	private boolean active;
	private boolean towardsHazard;

	/** Where the nearest hazard lies from a pose. */
	@FunctionalInterface
	public interface Hazards {
		/** Degrees to turn right to face the nearest ledge or wall within reach, or NaN when none is. */
		double bearing(CameraPose pose);
	}

	public Drift(long seed, ClipSettings.Drift settings, MotorLimits limits) {
		this(seed, settings, limits, pose -> Double.NaN);
	}

	public Drift(long seed, ClipSettings.Drift settings, MotorLimits limits, Hazards hazards) {
		this.random = new SplittableRandom(seed);
		this.settings = settings;
		this.limits = limits;
		this.hazards = hazards;
		double rate = settings.rate();
		// A share r of ticks drifting, with episodes of mean L and gaps of mean 1/p: r = L / (L + 1/p).
		this.startChance = rate <= 0.0 ? 0.0 : Math.min(1.0, rate / (settings.meanTicks() * (1.0 - rate)));
	}

	/** {@link #apply(MotorCommand, CameraPose)} with no hazard in reach. */
	public MotorCommand apply(MotorCommand teacher) {
		return apply(teacher, null);
	}

	/**
	 * The command to execute this tick: {@code teacher}, plus the drift when an episode runs.
	 *
	 * @param pose where the body stands, to find hazards when an episode starts; null for none
	 */
	public MotorCommand apply(MotorCommand teacher, @Nullable CameraPose pose) {
		if (this.step >= this.length && this.random.nextDouble() < this.startChance) {
			double bearing = pose == null || this.settings.hazardFraction() <= 0.0 ? Double.NaN : this.hazards.bearing(pose);
			// The hazard draw happens only with a hazard in reach, so without one the bumps are drawn as before.
			this.towardsHazard = !Double.isNaN(bearing) && this.random.nextDouble() < this.settings.hazardFraction();
			this.length = geometric(this.settings.meanTicks());
			this.step = 0;
			this.turn = this.random.nextDouble(-1.0, 1.0) * this.settings.maxTurnRate();
			this.forward = this.random.nextDouble(-1.0, 1.0) * this.settings.maxForward();
			if (this.towardsHazard) {
				this.turn = Math.copySign(this.turn, bearing);
				this.forward = Math.abs(this.forward);
			}
		}

		this.active = this.step < this.length;
		if (!this.active) {
			return teacher;
		}

		double bump = Math.sin(Math.PI * (this.step + 0.5) / this.length);
		this.step++;
		return new MotorCommand(
			Math.clamp(teacher.forward() + this.forward * bump, -1.0, 1.0),
			teacher.strafe(),
			Math.clamp(teacher.turnRate() + this.turn * bump, -this.limits.maxTurnRate(), this.limits.maxTurnRate()),
			teacher.pitchRate(),
			teacher.jump(),
			teacher.delayTicks()
		);
	}

	/** Whether the last {@link #apply} drifted. */
	public boolean active() {
		return this.active;
	}

	/** Whether the last {@link #apply} drifted in an episode pushing towards a hazard. */
	public boolean towardsHazard() {
		return this.active && this.towardsHazard;
	}

	/** A length of at least one tick, geometric with the given mean. */
	private int geometric(double mean) {
		double continueChance = 1.0 - 1.0 / mean;
		int ticks = 1;
		while (this.random.nextDouble() < continueChance) {
			ticks++;
		}

		return ticks;
	}
}
