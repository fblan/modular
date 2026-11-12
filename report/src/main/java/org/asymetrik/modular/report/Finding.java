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
package org.asymetrik.modular.report;

import java.util.List;

/**
 * Something the report asks its reader to look at.
 *
 * @param level how serious it is
 * @param kind what was found
 * @param title one sentence saying what was found, and where
 * @param details the lines behind the title: the classes, the uses, the suggestion
 * @param modules the names of the modules it is about, the ones to look at: empty when it is about no module
 */
public record Finding(Level level, Kind kind, String title, List<String> details, List<String> modules) {

	/** How serious a finding is. */
	public enum Level {
		/** A module rule is broken: {@code ModularVerifier} rejects these classes. */
		ERROR,
		/** No rule is broken, but the modules are probably not what was intended. */
		WARNING,
		/** Worth a look. It can be a false alarm, since the report only reads bytecode. */
		HINT
	}

	/** What a finding is about. */
	public enum Kind {
		/** A violation of the module rules. */
		VIOLATION,
		/** Modules that depend on each other, directly or not. */
		CYCLE,
		/** Scanned classes that are in no module. */
		CLASSES_OUTSIDE_MODULES,
		/**
		 * A module whose class files are more than {@link Findings#LARGE_MODULE_FACTOR} times the median module's, with
		 * at least {@link Findings#LARGE_MODULE_MIN_TYPES} types: it probably holds several parts of the domain.
		 */
		LARGE_MODULE,
		/** A name in {@code requires} that the module's bytecode does not use. */
		REQUIRES_NOT_USED,
		/** An exported package that no other module uses. */
		EXPORT_NOT_USED,
		/** A type with more methods than {@link Findings#MAX_METHODS}: it probably does several jobs. */
		LARGE_TYPE,
		/**
		 * Exported types that use another module, given to a module that does not require it: it cannot use what it
		 * receives without a new {@code requires}. A use only in annotations does not count.
		 */
		LEAKY_API,
		/** A module that creates exceptions of another module: its errors depend on how that module handles them. */
		BORROWED_EXCEPTION,
		/** An exported class of static helpers that another module uses: shared code that belongs to neither. */
		EXPORTED_HELPER
	}

	/** Copies the details and the modules, so that a finding cannot change. */
	public Finding {
		details = List.copyOf(details);
		modules = List.copyOf(modules);
	}

	/** A finding about no module. */
	public Finding(Level level, Kind kind, String title, List<String> details) {
		this(level, kind, title, details, List.of());
	}
}
