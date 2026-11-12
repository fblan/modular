package org.asymetrik.modular.verification.testedmodules.annotated.user;

import org.asymetrik.modular.verification.testedmodules.annotated.marks.Level;
import org.asymetrik.modular.verification.testedmodules.annotated.marks.Tagged;

/** Level is in an annotation's value and in a signature: not only in annotations. */
@Tagged(Level.LOW)
public class AlsoInSignature {

	public Level level() {
		return Level.LOW;
	}
}
