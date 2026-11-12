package org.asymetrik.modular.report.designapp.client;

import org.asymetrik.modular.report.designapp.core.CoreException;
import org.asymetrik.modular.report.designapp.core.CoreView;
import org.asymetrik.modular.report.designapp.core.Texts;

/** Receives core's view without requiring base, throws core's exception, and uses core's helper. */
public class Client {
	public String nameOf(CoreView view) {
		if (view.name().isEmpty()) {
			throw new CoreException("No name");
		}
		return Texts.trimmed(view.name());
	}
}
