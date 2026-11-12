package org.asymetrik.modular.report.designapp.core;

import org.asymetrik.modular.report.designapp.marks.Audited;

/** core's own error. */
@Audited
public class CoreException extends RuntimeException {
	public CoreException(String message) {
		super(message);
	}
}
