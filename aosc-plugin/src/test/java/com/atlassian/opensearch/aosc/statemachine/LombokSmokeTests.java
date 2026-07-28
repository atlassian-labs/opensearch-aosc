/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.statemachine;

import lombok.Builder;
import lombok.Value;

import org.opensearch.test.OpenSearchTestCase;

/**
 * Smoke test to verify Lombok annotation processing works in this build.
 */
public class LombokSmokeTests extends OpenSearchTestCase {

    @Value
    @Builder
    static class SampleConfig {
        String name;
        int retries;
        boolean enabled;
    }

    public void testLombokValueAndBuilder() {
        SampleConfig config = SampleConfig.builder().name("test").retries(3).enabled(true).build();

        assertEquals("test", config.getName());
        assertEquals(3, config.getRetries());
        assertTrue(config.isEnabled());

        // @Value generates equals/hashCode
        SampleConfig same = SampleConfig.builder().name("test").retries(3).enabled(true).build();
        assertEquals(config, same);
        assertEquals(config.hashCode(), same.hashCode());

        // @Value generates toString
        assertNotNull(config.toString());
        assertTrue(config.toString().contains("test"));

        // Immutability — no setters available (compile-time guarantee from @Value)
    }
}
