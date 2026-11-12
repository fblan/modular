package org.asymetrik.modular.verification.testedmodules.traits.provider;

/** A record with a static method. */
public record Point(int x) {
	public static Point origin() {
		return new Point(0);
	}
}
