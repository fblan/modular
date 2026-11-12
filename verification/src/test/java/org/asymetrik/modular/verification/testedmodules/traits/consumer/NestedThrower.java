package org.asymetrik.modular.verification.testedmodules.traits.consumer;

import org.asymetrik.modular.verification.testedmodules.traits.provider.ProviderException;

/** Its nested class creates the provider's exception. */
public class NestedThrower {
	public void handle(String value) {
		new Check().run(value);
	}

	static class Check {
		void run(String value) {
			if (value.isEmpty()) {
				throw new ProviderException("empty");
			}
		}
	}
}
