package io.github.thedeadbolt.honeyfactory.environment;

import java.util.concurrent.CompletionException;

/** Helpers for the futures environment operations return. */
public final class Futures {
	private Futures() {
	}

	/** The failure behind {@code error}, without the {@link CompletionException} that wraps it in dependent stages. */
	public static Throwable cause(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}
}
