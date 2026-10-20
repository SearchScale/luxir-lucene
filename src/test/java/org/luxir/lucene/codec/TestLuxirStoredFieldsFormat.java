// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.tests.index.BaseStoredFieldsFormatTestCase;
import org.luxir.lucene.CleanerThreadFilter;

/** Lucene's randomized stored-fields conformance suite, run against the Luxir codec. */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirStoredFieldsFormat extends BaseStoredFieldsFormatTestCase {
  private final Codec codec = new LuxirCodec();

  @Override
  protected Codec getCodec() {
    return codec;
  }
}
