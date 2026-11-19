package io.github.thedeadbolt.honeyfactory.contract;

/** Still images per viewpoint, or motion clips (M12-2). A manifest without {@code kind} holds stills. */
public enum DatasetKind implements JsonEnum {
	STILLS,
	CLIPS;

	public static DatasetKind fromJson(String name) {
		return JsonEnum.parse(DatasetKind.class, name);
	}
}
