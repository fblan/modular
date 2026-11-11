package org.asymetrik.modular.verification.testedmodules.broken.consumer;

import org.asymetrik.modular.verification.testedmodules.broken.provider.Api;

/** Uses broken-provider twice without requiring it: one violation per used class, not per use. */
public class ApiUser {
	private Api first;
	private Api second;
}
