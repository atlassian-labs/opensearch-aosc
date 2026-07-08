/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.rest;

import com.atlassian.opensearch.aosc.model.phase.CoordinatorPhase;

import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

/**
 * Unit tests for {@link RestListMigrationsAction}'s input-validation helpers.
 *
 * <p>Pins down the contract around silent clamping of {@code size} and the rejection (HTTP 400 via
 * {@link IllegalArgumentException}) of malformed {@code status} values.</p>
 */
public class RestListMigrationsActionTests extends OpenSearchTestCase {

    // ---------------------------------------------------------------------
    // clampSize
    // ---------------------------------------------------------------------

    public void testClampSizeReturnsRequestedWhenWithinBounds() {
        assertEquals(1, RestListMigrationsAction.clampSize(1));
        assertEquals(50, RestListMigrationsAction.clampSize(50));
        assertEquals(499, RestListMigrationsAction.clampSize(499));
    }

    public void testClampSizeAtMaxIsAllowed() {
        assertEquals(
            RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE,
            RestListMigrationsAction.clampSize(RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE)
        );
    }

    public void testClampSizeAboveMaxIsClampedSilently() {
        assertEquals(
            RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE,
            RestListMigrationsAction.clampSize(RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE + 1)
        );
        assertEquals(RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE, RestListMigrationsAction.clampSize(10_000));
        assertEquals(RestListMigrationsAction.INTERNAL_MAX_LIST_SIZE, RestListMigrationsAction.clampSize(Integer.MAX_VALUE));
    }

    public void testClampSizeRejectsZero() {
        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.clampSize(0));
        assertTrue(ex.getMessage(), ex.getMessage().contains("must be > 0"));
    }

    public void testClampSizeRejectsNegative() {
        expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.clampSize(-1));
        expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.clampSize(Integer.MIN_VALUE));
    }

    // ---------------------------------------------------------------------
    // parseStatusFilter
    // ---------------------------------------------------------------------

    public void testParseStatusFilterAllReturnsEmpty() {
        assertEquals(List.of(), RestListMigrationsAction.parseStatusFilter("all"));
        assertEquals(List.of(), RestListMigrationsAction.parseStatusFilter("ALL"));
        assertEquals(List.of(), RestListMigrationsAction.parseStatusFilter("All"));
    }

    public void testParseStatusFilterActiveExpandsToAllNonTerminal() {
        List<CoordinatorPhase> phases = RestListMigrationsAction.parseStatusFilter("ACTIVE");
        // All 7 non-terminal phases must be present, no terminals.
        assertTrue(phases.contains(CoordinatorPhase.ACTIVE));
        assertTrue(phases.contains(CoordinatorPhase.INITIALIZING));
        assertTrue(phases.contains(CoordinatorPhase.CUTTING_OVER));
        assertTrue(phases.contains(CoordinatorPhase.CATCHING_UP));
        assertTrue(phases.contains(CoordinatorPhase.COMPLETING));
        assertTrue(phases.contains(CoordinatorPhase.CANCELLING));
        assertTrue(phases.contains(CoordinatorPhase.FAILING));
        for (CoordinatorPhase p : phases) {
            assertFalse("ACTIVE expansion must not include terminal phase " + p, p.isTerminal());
        }
    }

    public void testParseStatusFilterSingleTerminalPhase() {
        assertEquals(List.of(CoordinatorPhase.COMPLETED), RestListMigrationsAction.parseStatusFilter("COMPLETED"));
        assertEquals(List.of(CoordinatorPhase.FAILED), RestListMigrationsAction.parseStatusFilter("FAILED"));
        assertEquals(List.of(CoordinatorPhase.CANCELLED), RestListMigrationsAction.parseStatusFilter("CANCELLED"));
    }

    public void testParseStatusFilterCaseInsensitive() {
        assertEquals(List.of(CoordinatorPhase.COMPLETED), RestListMigrationsAction.parseStatusFilter("completed"));
        assertEquals(List.of(CoordinatorPhase.FAILED), RestListMigrationsAction.parseStatusFilter(" Failed "));
    }

    public void testParseStatusFilterCommaList() {
        List<CoordinatorPhase> phases = RestListMigrationsAction.parseStatusFilter("COMPLETED,FAILED");
        assertEquals(2, phases.size());
        assertTrue(phases.contains(CoordinatorPhase.COMPLETED));
        assertTrue(phases.contains(CoordinatorPhase.FAILED));
    }

    public void testParseStatusFilterRejectsBlankInput() {
        expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.parseStatusFilter(null));
        expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.parseStatusFilter(""));
        expectThrows(IllegalArgumentException.class, () -> RestListMigrationsAction.parseStatusFilter("   "));
    }

    public void testParseStatusFilterRejectsEmptyToken() {
        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> RestListMigrationsAction.parseStatusFilter("COMPLETED,,FAILED")
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("empty token"));
    }

    public void testParseStatusFilterRejectsUnknownPhase() {
        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> RestListMigrationsAction.parseStatusFilter("BOGUS")
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("unknown status"));
        assertTrue("error message should list valid options", ex.getMessage().contains("ACTIVE"));
    }
}
