/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.bulk;

import org.opensearch.test.OpenSearchTestCase;

public class WriteDecisionTests extends OpenSearchTestCase {

    public void testSuccessDefaults() {
        WriteDecision d = WriteDecision.success();
        assertEquals(WriteDecision.Action.SUCCESS, d.action());
        assertEquals(0L, d.pauseMillis());
        assertNull(d.reason());
    }

    public void testPauseAndRetry() {
        WriteDecision d = WriteDecision.pauseAndRetry(500L);
        assertEquals(WriteDecision.Action.PAUSE_AND_RETRY, d.action());
        assertEquals(500L, d.pauseMillis());
        assertNull(d.reason());
    }

    public void testFatal() {
        WriteDecision d = WriteDecision.fatal("too many errors");
        assertEquals(WriteDecision.Action.FATAL, d.action());
        assertEquals(0L, d.pauseMillis());
        assertEquals("too many errors", d.reason());
    }
}
