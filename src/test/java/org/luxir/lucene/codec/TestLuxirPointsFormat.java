// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.tests.index.BasePointsFormatTestCase;
import org.luxir.lucene.CleanerThreadFilter;

/** Lucene's randomized points conformance suite, run against the Luxir codec. */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirPointsFormat extends BasePointsFormatTestCase {
  private final Codec codec = new LuxirCodec();

  @Override
  protected Codec getCodec() {
    return codec;
  }
}
