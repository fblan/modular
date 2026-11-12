package org.asymetrik.modular.verification.testedmodules.traits.provider;

/** Static members only. */
public final class Helpers {
	public static final String PREFIX = "x";

	private Helpers() {
	}

	public static String prefixed(String name) {
		return PREFIX + name;
	}
}
