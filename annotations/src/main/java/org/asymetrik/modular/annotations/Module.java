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
import java.lang.annotation.Target;

/**
 * Annotation to define a module in a modular application.
 */
@Target(ElementType.PACKAGE)
public @interface Module {
	String name();
	String[] exports();

	/**
	 * Names of the modules this module may use. A class of this module may only depend on classes of
	 * the modules listed here (and only on their exported packages). Not transitive: if A requires B
	 * and B requires C, A must also require C to use it.
	 */
	String[] requires() default {};
}
