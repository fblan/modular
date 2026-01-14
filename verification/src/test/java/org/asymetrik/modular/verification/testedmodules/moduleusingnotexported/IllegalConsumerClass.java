package org.asymetrik.modular.verification.testedmodules.moduleusingnotexported;

import org.asymetrik.modular.verification.testedmodules.modulea.ClassNotExported;

public class IllegalConsumerClass {
	public String useNotExportedClass() {
		ClassNotExported notExported = new ClassNotExported();
		return notExported.getSecret();
	}
}