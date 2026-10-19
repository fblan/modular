/*
 * Copyright 2026 Asymetryk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asymetrik.modular.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Target;

/**
 * Declares a virtual module: a named group of packages that are not part of any {@link Module},
 * typically from an external library. A module may only use these packages if it lists the virtual
 * module's name in {@link Module#requires()}. Everything in a virtual module is exported.
 * <p>
 * Can be placed, and repeated, on any {@code package-info.java}. Only {@code java.*} packages can be
 * used without being part of a module or a virtual module.
 */
@Target(ElementType.PACKAGE)
@Repeatable(ExternalModules.class)
public @interface ExternalModule {
	String name();

	/**
	 * Packages of this virtual module. Each package also covers its sub-packages:
	 * {@code "org.slf4j"} covers {@code org.slf4j} and {@code org.slf4j.event}.
	 */
	String[] packages();
}
