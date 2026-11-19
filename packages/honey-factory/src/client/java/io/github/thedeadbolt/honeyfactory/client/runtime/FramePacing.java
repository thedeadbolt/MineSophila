package io.github.thedeadbolt.honeyfactory.client.runtime;

import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;

/**
 * Lifts the frame-rate limits while the mod generates or controls the player, and gives the
 * user's options back afterwards. The game ticks once per frame during client game tests, and
 * readiness waits count frames everywhere, so the frame cap, vsync and the inactivity limit
 * (vanilla drops to 30 and then 10 frames per second when nobody touches the keyboard or
 * mouse, which is always the case for an unattended run) pace the whole generator.
 *
 * <p>Each user holds a {@link Lease}: the first lease saves the user's options and lifts
 * them, closing the last open lease puts them back. Leaving the world gives them back at
 * once ({@link #restoreAll}); leases taken before that close without effect. Call everything
 * on the client thread.
 */
public final class FramePacing {
	private static int users;
	/** Counts {@link #restoreAll} calls, so leases from before one cannot release later users. */
	private static int generation;
	private static @Nullable Saved saved;

	private record Saved(int framerateLimit, boolean vsync, InactivityFpsLimit inactivity) {
	}

	/** One user's hold on the uncapped frame rate; closing it more than once has no effect. */
	public static final class Lease {
		private final int generation;
		private boolean closed;

		private Lease(int generation) {
			this.generation = generation;
		}

		/** Ends this hold; the last open one gives the user's frame pacing back. */
		public void close(Minecraft minecraft) {
			if (this.closed) {
				return;
			}

			this.closed = true;
			if (this.generation == FramePacing.generation && users > 0 && --users == 0) {
				giveBack(minecraft);
			}
		}
	}

	private FramePacing() {
	}

	/**
	 * Saves the user's frame pacing on first use, then runs unlimited, without vsync or an
	 * inactivity limit. With {@code -Dhoneyfactory.uncapFrames=false} the user's pacing is
	 * left alone, as before M12, for comparisons.
	 */
	public static Lease uncap(Minecraft minecraft) {
		if (!Boolean.parseBoolean(System.getProperty("honeyfactory.uncapFrames", "true"))) {
			Lease lease = new Lease(generation);
			lease.closed = true;
			return lease;
		}

		if (users++ == 0) {
			Options options = minecraft.options;
			saved = new Saved(options.framerateLimit().get(), options.enableVsync().get(), options.inactivityFpsLimit().get());
			options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
			options.enableVsync().set(false);
			options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		}

		return new Lease(generation);
	}

	/** Ends every hold at once and gives the user's frame pacing back, as when the world closes. */
	public static void restoreAll(Minecraft minecraft) {
		generation++;
		if (users > 0) {
			users = 0;
			giveBack(minecraft);
		}
	}

	private static void giveBack(Minecraft minecraft) {
		Saved user = saved;
		saved = null;
		if (user != null) {
			minecraft.options.framerateLimit().set(user.framerateLimit());
			minecraft.options.enableVsync().set(user.vsync());
			minecraft.options.inactivityFpsLimit().set(user.inactivity());
		}
	}
}
