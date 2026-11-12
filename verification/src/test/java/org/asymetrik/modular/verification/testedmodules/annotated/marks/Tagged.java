package org.asymetrik.modular.verification.testedmodules.annotated.marks;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An annotation whose value is an enum. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Tagged {
	Level value();
}
