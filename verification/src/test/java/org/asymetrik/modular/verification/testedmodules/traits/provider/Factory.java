package org.asymetrik.modular.verification.testedmodules.traits.provider;

/** Static members only, but it returns the module's own types: a factory, not a helper. */
public final class Factory {
	private Factory() {
	}

	public static Point origin() {
		return new Point(0);
	}

	public static String describe() {
		return "factory";
	}
}
