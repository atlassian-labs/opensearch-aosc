/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package com.atlassian.opensearch.aosc.transform;

import com.atlassian.opensearch.aosc.model.transform.InlineTransformScript;
import com.atlassian.opensearch.aosc.model.transform.StoredTransformScript;
import com.atlassian.opensearch.aosc.model.transform.TransformScript;

import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class TransformFactoryTests extends OpenSearchTestCase {

    private TransformFactory factory() {
        return new TransformFactory(null);
    }

    private static IndexMetadata dummyMeta(String name) {
        return IndexMetadata.builder(name)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            )
            .build();
    }

    // ---- Identity cases (null TransformScript or no body) ----

    public void testNullReturnsIdentity() {
        assertTrue(factory().create(null, dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction);
    }

    public void testInlineWithNullSourceReturnsIdentity() {
        assertTrue(
            factory().create(new InlineTransformScript(null, null), dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction
        );
    }

    public void testInlineWithBlankSourceReturnsIdentity() {
        assertTrue(
            factory().create(new InlineTransformScript("  ", null), dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction
        );
    }

    public void testInlineWithIdentitySourceReturnsIdentity() {
        assertTrue(
            factory().create(
                new InlineTransformScript("identity", null),
                dummyMeta("src"),
                dummyMeta("tgt")
            ) instanceof IdentityTransformFunction
        );
    }

    public void testStoredWithNullIdReturnsIdentity() {
        assertTrue(
            factory().create(new StoredTransformScript(null, null), dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction
        );
    }

    public void testStoredWithBlankIdReturnsIdentity() {
        assertTrue(
            factory().create(new StoredTransformScript("  ", null), dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction
        );
    }

    // ---- Update context (compilation attempts) ----

    public void testInlineScriptWithSourceAttemptsCompilation() {
        TransformFactory f = factory();
        // ScriptService is null → compile() throws NPE
        Exception e = expectThrows(
            Exception.class,
            () -> f.create(new InlineTransformScript("ctx._source.x = 1", null), dummyMeta("src"), dummyMeta("tgt"))
        );
        assertTrue(e instanceof NullPointerException || e instanceof IllegalArgumentException);
    }

    public void testStoredScriptWithIdAttemptsCompilation() {
        TransformFactory f = factory();
        Exception e = expectThrows(
            Exception.class,
            () -> f.create(new StoredTransformScript("my-stored-script", null), dummyMeta("src"), dummyMeta("tgt"))
        );
        assertTrue(e instanceof NullPointerException || e instanceof IllegalArgumentException);
    }

    // ---- Unknown script_context ----

    public void testUnknownScriptContextThrows() {
        TransformFactory f = factory();
        InlineTransformScript script = new InlineTransformScript("source", null);
        script.setScriptContext("unknown_context");
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> f.create(script, dummyMeta("src"), dummyMeta("tgt"))
        );
        assertTrue(e.getMessage().contains("Unknown transform script_context"));
        assertTrue(e.getMessage().contains("unknown_context"));
    }

    // ---- Extension via subclassing ----

    public void testSubclassCanHandleCustomScriptContext() {
        TransformFunction customFn = doc -> List.of(doc);
        TransformFactory custom = new TransformFactory(null) {
            @Override
            public TransformFunction create(TransformScript script, IndexMetadata src, IndexMetadata tgt) {
                if (script != null && "custom".equals(script.getScriptContext())) {
                    return customFn;
                }
                return super.create(script, src, tgt);
            }
        };

        InlineTransformScript script = new InlineTransformScript("source", null);
        script.setScriptContext("custom");

        assertSame(customFn, custom.create(script, dummyMeta("src"), dummyMeta("tgt")));
        // Base behaviour is preserved
        assertTrue(custom.create(null, dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction);
    }

    public void testUpdateContextWithIdentitySourceReturnsIdentity() {
        TransformFactory f = factory();
        InlineTransformScript script = new InlineTransformScript("identity", null);
        script.setScriptContext("update");
        assertTrue(f.create(script, dummyMeta("src"), dummyMeta("tgt")) instanceof IdentityTransformFunction);
    }

}
