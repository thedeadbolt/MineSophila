package io.github.thedeadbolt.honeyfactory.contract;

/** A record, configuration or dataset violates the honey-factory data contract. */
public class ContractException extends RuntimeException {
	private final String path;

	public ContractException(String path, String message) {
		super(path + ": " + message);
		this.path = path;
	}

	/** JSON-pointer-like location of the first violation, for example {@code /target/visible_pixels}. */
	public String path() {
		return this.path;
	}
}
