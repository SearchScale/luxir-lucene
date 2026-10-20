// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.tests.index.BaseDocValuesFormatTestCase;
import org.luxir.lucene.CleanerThreadFilter;

/** Lucene's randomized doc-values conformance suite, run against the Luxir codec. */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirDocValuesFormat extends BaseDocValuesFormatTestCase {
  private final Codec codec = new LuxirCodec();

  @Override
  protected Codec getCodec() {
    return codec;
  }

  /**
   * Field order changes the file size by a few bytes: Lucene flushes doc values in field-hash
   * order but merges them in field-number order, and the Luxir segment pads its blocks for
   * alignment, so where padding falls depends on what precedes each block.
   */
  @Override
  protected boolean mergeIsStable() {
    return false;
  }
}
