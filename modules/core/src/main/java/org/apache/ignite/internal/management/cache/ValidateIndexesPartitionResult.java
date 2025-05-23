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

package org.apache.ignite.internal.management.cache;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;

/**
 * Encapsulates intermediate results of validation of SQL index.
 */
public class ValidateIndexesPartitionResult extends IgniteDataTransferObject {
    /** */
    private static final long serialVersionUID = 0L;

    /** Max issues per result. */
    private static final int MAX_ISSUES = 10;

    /** Issues. */
    @GridToStringExclude
    private List<IndexValidationIssue> issues = new ArrayList<>(MAX_ISSUES);

    /**
     *
     */
    public ValidateIndexesPartitionResult() {
        // Empty constructor required for Externalizable.
    }

    /**
     *
     */
    public List<IndexValidationIssue> issues() {
        return issues;
    }

    /**
     * @param t Issue.
     * @return True if there are already enough issues.
     */
    public boolean reportIssue(IndexValidationIssue t) {
        if (issues.size() == MAX_ISSUES)
            return true;

        issues.add(t);

        return false;
    }

    /** {@inheritDoc} */
    @Override protected void writeExternalData(ObjectOutput out) throws IOException {
        U.writeCollection(out, issues);
    }

    /** {@inheritDoc} */
    @Override protected void readExternalData(ObjectInput in) throws IOException, ClassNotFoundException {
        issues = U.readList(in);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(ValidateIndexesPartitionResult.class, this);
    }
}
