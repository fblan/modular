package org.asymetryk.modulyth.testedmodules.moduleusingnotexported;

import org.asymetryk.modulyth.testedmodules.modulea.ClassNotExported;

public class IllegalConsumerClass {
	public String useNotExportedClass() {
		ClassNotExported notExported = new ClassNotExported();
		return notExported.getSecret();
	}
}