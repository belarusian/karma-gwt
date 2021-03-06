/*
 * Copyright 2012-2016 JetBrains s.r.o
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.datapad.js.tests.client;

import com.google.gwt.user.client.Timer;

import java.util.ArrayList;

class AsyncTestSuite extends KarmaTestSuite {
  @Override
  protected void registerTests(ArrayList<KarmaTest> tests) {
    tests.add(new KarmaTest("async test") {
      @Override
      protected void run() throws Throwable {
        makeAsync();
        Timer t = new Timer() {
          @Override
          public void run() {
            succeed();
          }
        };
        t.schedule(500);
      }
    });
    tests.add(new KarmaTest("async error test", RuntimeException.class) {
      @Override
      protected void run() throws Throwable {
        makeAsync();
        Timer t = new Timer() {
          @Override
          public void run() {
            throw new RuntimeException("oops");
          }
        };
        t.schedule(500);
      }
    });
  }
}
