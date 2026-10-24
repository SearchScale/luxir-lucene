// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.FixedBitSet;

/**
 * Matching docs of a whole index (global doc ids) for the native facet paths: bits, or an
 * ascending list when there are few (as Solr keeps a small doc set). A list spares building and
 * scanning words for every doc of every segment, which for a few thousand matches costs more
 * memory traffic than the facet itself.
 */
public final class DocMatches {
  private final FixedBitSet bits;
  private final int[] docs;
  private final int size;

  private DocMatches(FixedBitSet bits, int[] docs, int size) {
    this.bits = bits;
    this.docs = docs;
    this.size = size;
  }

  /** The set bits of {@code bits}. */
  public static DocMatches of(FixedBitSet bits) {
    return new DocMatches(bits, null, 0);
  }

  /** The first {@code size} entries of {@code docs}, ascending. */
  public static DocMatches of(int[] docs, int size) {
    return new DocMatches(null, docs, size);
  }

  /**
   * A segment's matches in native memory from {@code arena}: as words (docs NULL) or as ascending
   * segment doc ids (words NULL); both NULL when there are none.
   */
  record Leaf(MemorySegment words, MemorySegment docs, int count) {}

  Leaf leaf(LeafReaderContext ctx, Arena arena) {
    int maxDoc = ctx.reader().maxDoc();
    if (bits != null) {
      NativeLeaf.Words w = NativeLeaf.liveAndFilter(null, bits, ctx.docBase, maxDoc, arena);
      return new Leaf(w.words(), MemorySegment.NULL, w.count());
    }
    int lo = lowerBound(ctx.docBase);
    int n = lowerBound(ctx.docBase + maxDoc) - lo;
    if (n == 0) return new Leaf(MemorySegment.NULL, MemorySegment.NULL, 0);
    int[] local = new int[n];
    for (int i = 0; i < n; i++) local[i] = docs[lo + i] - ctx.docBase;
    return new Leaf(MemorySegment.NULL, arena.allocateFrom(ValueLayout.JAVA_INT, local), n);
  }

  // index of the first listed doc >= doc
  private int lowerBound(int doc) {
    int i = Arrays.binarySearch(docs, 0, size, doc);
    return i >= 0 ? i : -i - 1;
  }
}
