/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.testsuites;

import org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest;
import org.apache.ignite.internal.processors.query.calcite.exec.ClosableIteratorsHolderTest;
import org.apache.ignite.internal.processors.query.calcite.exec.LogicalRelImplementorTest;
import org.apache.ignite.internal.processors.query.calcite.exec.NumericTypesPrecisionsTest;
import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;
import org.apache.ignite.internal.processors.query.calcite.exec.tracker.MemoryTrackerTest;
import org.apache.ignite.internal.processors.query.calcite.message.CalciteCommunicationMessageSerializationTest;
import org.apache.ignite.internal.processors.query.calcite.sql.SqlCustomParserTest;
import org.apache.ignite.internal.processors.query.calcite.sql.SqlReservedWordsTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Calcite tests.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    PlannerTestSuite.class,
    ExecutionTestSuite.class,
    IntegrationTestSuite.class,

    ClosableIteratorsHolderTest.class,
    MemoryTrackerTest.class,
    QueryCheckerTest.class,
    SqlCustomParserTest.class,
    SqlReservedWordsTest.class,
    IgniteSqlFunctionsTest.class,
    LogicalRelImplementorTest.class,

    ScriptTestSuite.class,
    CalciteCommunicationMessageSerializationTest.class,

    NumericTypesPrecisionsTest.class,
})
public class IgniteCalciteTestSuite {
}
