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

package org.apache.ignite.internal.management.snapshot;

import java.util.function.Consumer;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotWarningException;
import org.apache.ignite.internal.util.typedef.X;

/** */
public class SnapshotCreateCommand extends AbstractSnapshotCommand<SnapshotCreateCommandArg, String> {
    /** {@inheritDoc} */
    @Override public String description() {
        return "Create cluster snapshot";
    }

    /** {@inheritDoc} */
    @Override public Class<SnapshotCreateCommandArg> argClass() {
        return SnapshotCreateCommandArg.class;
    }

    /** {@inheritDoc} */
    @Override public Class<SnapshotCreateTask> taskClass() {
        return SnapshotCreateTask.class;
    }

    /** {@inheritDoc} */
    @Override public String handleException(Exception e, Consumer<String> printer) throws Exception {
        if (X.hasCause(e, SnapshotWarningException.class))
            return e.getMessage();
        else
            throw e;
    }
}
