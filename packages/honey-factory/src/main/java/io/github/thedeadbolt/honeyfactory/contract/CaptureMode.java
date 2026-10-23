package io.github.thedeadbolt.honeyfactory.contract;

/** Capture projection selected per dataset; the JSON name is the constant name. */
public enum CaptureMode {
	PERSPECTIVE_90,
	PANORAMA_310;

	public static CaptureMode fromJson(String name) {
		for (CaptureMode mode : values()) {
			if (mode.name().equals(name)) {
				return mode;
			}
		}

		throw new IllegalArgumentException("unknown capture mode '" + name + "'; expected PERSPECTIVE_90 or PANORAMA_310");
	}
}
