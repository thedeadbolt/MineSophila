package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;

/**
 * One component of a scene recipe's layout, such as clutter, a hiding structure or a built
 * setting. A part is an immutable value holding its settings; {@link #build} draws and
 * places its blocks. Register new kinds in {@link LayoutParts}.
 */
public interface LayoutPart {
	/** The registered type name, such as {@code clutter}. */
	String type();

	/**
	 * Places this part's blocks. Draws come only from {@code context.random()}, a stream
	 * owned by this part, so other parts never shift them. Whatever the part drew is
	 * recorded in {@code context.drawn()}.
	 */
	void build(Context context);

	/** The settings, without {@code type}; {@link LayoutParts#read} accepts them back. */
	JsonObject settingsJson();

	/** What a part builds with: the shared layout, the aim cell, its own stream and its record of draws. */
	record Context(LayoutBuilder layout, int[] aim, SeededRandom random, JsonObject drawn) {
	}
}
