package io.github.thedeadbolt.honeyfactory.contract;

/** Capture projection selected per dataset; the JSON name is the constant name. */
public enum CaptureMode implements JsonEnum {
	PERSPECTIVE_90,
	PANORAMA_310;

	@Override
	public String jsonName() {
		return name();
	}

	public static CaptureMode fromJson(String name) {
		return JsonEnum.parse(CaptureMode.class, name);
	}
}
