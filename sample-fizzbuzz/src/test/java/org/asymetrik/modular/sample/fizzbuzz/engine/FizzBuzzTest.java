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
package org.asymetrik.modular.sample.fizzbuzz.engine;

import java.util.List;

import org.asymetrik.modular.sample.fizzbuzz.rules.Rules;
import org.asymetrik.modular.specification.TestReq;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FizzBuzzTest {

    private final FizzBuzz fizzBuzz = new FizzBuzz(List.of(Rules.fizz(), Rules.buzz()));

    @Test
    @TestReq("ENG-1")
    void saysTheNumberWhenNoRuleMatches() {
        assertThat(fizzBuzz.say(7)).isEqualTo("7");
    }

    @Test
    @TestReq("ENG-2")
    void concatenatesTheWordsInRuleOrder() {
        assertThat(fizzBuzz.say(15)).isEqualTo("FizzBuzz");
        assertThat(new FizzBuzz(List.of(Rules.buzz(), Rules.fizz())).say(15)).isEqualTo("BuzzFizz");
    }

    @Test
    @TestReq("ENG-3")
    void saysNothingBelow1() {
        assertThat(fizzBuzz.sayUpTo(0)).isEmpty();
    }
}
