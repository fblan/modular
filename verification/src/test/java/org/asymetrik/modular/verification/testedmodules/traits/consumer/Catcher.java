package org.asymetrik.modular.verification.testedmodules.traits.consumer;

import org.asymetrik.modular.verification.testedmodules.traits.provider.Helpers;
import org.asymetrik.modular.verification.testedmodules.traits.provider.ProviderException;

/** Catches the provider's exception, and uses its helper. */
public class Catcher {
	public String run(Runnable action) {
		try {
			action.run();
			return Helpers.prefixed("ok");
		} catch (ProviderException failed) {
			return failed.getMessage();
		}
	}
}
