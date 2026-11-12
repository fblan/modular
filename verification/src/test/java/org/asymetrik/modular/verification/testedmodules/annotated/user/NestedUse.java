package org.asymetrik.modular.verification.testedmodules.annotated.user;

import org.asymetrik.modular.verification.testedmodules.annotated.marks.Level;
import org.asymetrik.modular.verification.testedmodules.annotated.marks.Tagged;

/** Level is only in an annotation here, but its nested class has a field of it: not only in annotations. */
@Tagged(Level.HIGH)
public class NestedUse {

	static class Holder {
		Level level;
	}
}
