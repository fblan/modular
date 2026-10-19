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
package org.asymetrik.modular.sample.fizzbuzz.rules;

import org.asymetrik.modular.specification.TestReq;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    @Test
    @TestReq("RUL-1")
    void fizzMatchesTheMultiplesOf3() {
        Rule fizz = Rules.fizz();

        assertThat(fizz.word()).isEqualTo("Fizz");
        assertThat(fizz.matches(9)).isTrue();
        assertThat(fizz.matches(10)).isFalse();
    }

    @Test
    @TestReq("RUL-2")
    void buzzMatchesTheMultiplesOf5() {
        Rule buzz = Rules.buzz();

        assertThat(buzz.word()).isEqualTo("Buzz");
        assertThat(buzz.matches(10)).isTrue();
        assertThat(buzz.matches(9)).isFalse();
    }
}
