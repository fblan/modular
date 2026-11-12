package org.asymetrik.modular.verification.testedmodules.traits.consumer;

import org.asymetrik.modular.verification.testedmodules.traits.provider.ProviderException;

/** Extends the provider's exception: its call to super(...) creates none. */
public class OwnException extends ProviderException {
	public OwnException() {
		super("own");
	}
}
