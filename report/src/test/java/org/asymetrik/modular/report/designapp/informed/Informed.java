package org.asymetrik.modular.report.designapp.informed;

import org.asymetrik.modular.report.designapp.base.BaseId;
import org.asymetrik.modular.report.designapp.core.CoreException;
import org.asymetrik.modular.report.designapp.core.CoreView;

/** Requires base, so receiving core's view is fine; catching core's exception is fine. */
public class Informed {
	public BaseId idOf(CoreView view, Runnable check) {
		try {
			check.run();
		} catch (CoreException failed) {
			return null;
		}
		return view.id();
	}
}
