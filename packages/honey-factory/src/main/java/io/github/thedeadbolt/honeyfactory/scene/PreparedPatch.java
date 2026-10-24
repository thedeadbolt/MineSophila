package io.github.thedeadbolt.honeyfactory.scene;

/** An accepted source patch with the terrain of its core, ready for level-5 scenes. */
public record PreparedPatch(SourcePatch patch, PatchTerrain terrain) {
}
