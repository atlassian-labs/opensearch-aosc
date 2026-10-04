/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.compat;

import org.opensearch.Version;
import org.opensearch.index.translog.Translog;
import org.opensearch.test.OpenSearchTestCase;

/** Runs on every OpenSearch line: catches a missing java-39plus overlay or a missing default. */
public class TranslogDeleteOpTests extends OpenSearchTestCase {

    public void testRoutingMatchesOpenSearchVersion() {
        TranslogDeleteOp delete = new TranslogDeleteOp(new Translog.Delete("doc", 1, 1));
        if (Version.CURRENT.onOrAfter(Version.fromString("3.9.0"))) { // not V_3_9_0: absent before 3.9
            assertNull(delete.routing());
        } else {
            expectThrows(UnsupportedOperationException.class, delete::routing);
        }
    }
}
