package io.github.thedeadbolt.honeyfactory.environment;

/** One accepted request, identified for ordering and for tying results to captures. */
public record Operation(String id, OperationKind kind, long startTick) {
}
