package org.asymetrik.modular.verification.testedmodules.traits.consumer;

import org.asymetrik.modular.verification.testedmodules.traits.provider.ProviderException;

/** Creates the provider's exception. */
public class Thrower {
	public void fail() {
		throw new ProviderException("failed");
	}
}
