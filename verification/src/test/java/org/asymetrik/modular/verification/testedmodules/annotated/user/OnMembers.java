package org.asymetrik.modular.verification.testedmodules.annotated.user;

import org.asymetrik.modular.verification.testedmodules.annotated.marks.Marker;

/** Annotated on a field, a method and a parameter. */
public class OnMembers {

	@Marker
	private int count;

	@Marker
	public int count() {
		return count;
	}

	public void setCount(@Marker int count) {
		this.count = count;
	}
}
