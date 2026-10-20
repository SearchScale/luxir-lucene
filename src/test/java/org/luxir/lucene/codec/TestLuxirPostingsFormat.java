// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.tests.index.BasePostingsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.luxir.lucene.CleanerThreadFilter;

/** Lucene's randomized postings-format conformance suite, run against the Luxir format. */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirPostingsFormat extends BasePostingsFormatTestCase {
  private final Codec codec = TestUtil.alwaysPostingsFormat(new LuxirPostingsFormat());

  @Override
  protected Codec getCodec() {
    return codec;
  }
}
