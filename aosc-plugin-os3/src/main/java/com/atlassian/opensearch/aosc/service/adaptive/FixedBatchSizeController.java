/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.service.adaptive;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Fixed batch size controller — legacy mode, no adaptation.
 *
 * <p>Returns the current value from the supplier on every call, so dynamic
 * cluster setting updates are reflected immediately. {@link #observe(BulkOutcome)}
 * uses the default no-op from {@link BatchSizeController}.</p>
 */
public class FixedBatchSizeController implements BatchSizeController {

    private final IntSupplier batchSizeSupplier;

    public FixedBatchSizeController(IntSupplier batchSizeSupplier) {
        this.batchSizeSupplier = Objects.requireNonNull(batchSizeSupplier, "batchSizeSupplier");
    }

    @Override
    public int nextBatchSize() {
        return batchSizeSupplier.getAsInt();
    }

}
