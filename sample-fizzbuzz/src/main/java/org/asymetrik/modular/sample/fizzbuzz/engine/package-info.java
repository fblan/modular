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
/**
 * Turns numbers into FizzBuzz words, using the exported API of the rules module.
 */
@Module(name = "engine", exports = {"."}, requires = {"rules"})
@Req(id = "ENG-1", value = "When no rule matches a number, the engine shall say the number itself.")
@Req(id = "ENG-2", value = "When several rules match a number, the engine shall concatenate their words in rule order.")
@Req(id = "ENG-3", value = "While the upper bound is below 1, the engine shall say nothing.")
package org.asymetrik.modular.sample.fizzbuzz.engine;

import org.asymetrik.modular.annotations.Module;
import org.asymetrik.modular.specification.Req;
