package org.asymetrik.modular.report.designapp.marks;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An annotation on an exported type of another module: its users need not know it. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
}
