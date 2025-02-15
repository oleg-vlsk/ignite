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

package org.apache.ignite.internal.processors.query;

import org.apache.ignite.Ignite;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.util.typedef.F;

import static org.apache.ignite.internal.IgniteApplicationAttributesAware.ReservedApplicationAttributes.QUERY_LABEL;

/**
 * Test class for verifying that the labels of queries executed from client nodes are correctly displayed in the
 * SQL query history.
 */
public class SqlQueryHistoryFromClientWithLablesTest extends SqlQueryHistorySelfTest {
    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        isLblChecked = true;
    }

    /** {@inheritDoc} */
    @Override protected void startTestGrid() throws Exception {
        startGrids(2);
        startClientGrid(2);
    }

    /** {@inheritDoc} */
    @Override protected Ignite queryNode() {
        return grid(2).withApplicationAttributes(F.asMap(QUERY_LABEL, LABEL));
    }

    /** {@inheritDoc} */
    @Override protected IgniteEx checkNode() {
        IgniteEx node = grid(2);

        assertTrue(node.context().clientNode());

        return node;
    }
}
