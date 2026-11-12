package org.asymetrik.modular.verification.testedmodules.traits.provider;

/** A static method and an instance method. */
public final class Mixed {
	private Mixed() {
	}

	public static Mixed create() {
		return new Mixed();
	}

	public String name() {
		return "mixed";
	}
}
