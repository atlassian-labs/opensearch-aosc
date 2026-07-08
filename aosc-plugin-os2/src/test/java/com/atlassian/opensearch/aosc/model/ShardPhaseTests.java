/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.model;

import com.atlassian.opensearch.aosc.model.phase.ShardPhase;

import org.opensearch.test.OpenSearchTestCase;

import java.util.EnumSet;
import java.util.Set;

public class ShardPhaseTests extends OpenSearchTestCase {

    private static final Set<ShardPhase> TERMINAL_PHASES = EnumSet.of(ShardPhase.COMPLETED, ShardPhase.CANCELLED, ShardPhase.FAILED);

    public void testIsTerminalUnchanged() {
        for (ShardPhase phase : TERMINAL_PHASES) {
            assertTrue(phase + " should be terminal", phase.isTerminal());
        }
        for (ShardPhase phase : ShardPhase.values()) {
            if (!TERMINAL_PHASES.contains(phase)) {
                assertFalse(phase + " should not be terminal", phase.isTerminal());
            }
        }
    }
}
