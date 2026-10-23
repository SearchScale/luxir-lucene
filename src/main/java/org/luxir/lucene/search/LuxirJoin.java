// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.LxlNative;

/**
 * A join over single-valued string doc values, run natively: the docs whose {@code toField} value is
 * the {@code fromField} value of some doc in a given set. The from docs' values are read from their
 * segments' columns and looked up in each segment's sorted dictionary, then each segment's column is
 * scanned once; no terms or postings are iterated.
 */
public final class LuxirJoin {
  private LuxirJoin() {}

  /**
   * The docs (global ids) whose {@code toField} value is the {@code fromField} value of a doc in
   * {@code fromDocs}, or null if either field is not a single-valued sorted doc-values column in
   * Luxir's format in every segment that has it.
   */
  public static FixedBitSet join(IndexReader top, String fromField, String toField, FixedBitSet fromDocs)
      throws java.io.IOException {
    List<LeafReaderContext> leaves = top.leaves();
    int n = leaves.size();
    FixedBitSet result = new FixedBitSet(Math.max(1, top.maxDoc()));
    if (n == 0) return result;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment stores = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment maxDocs = arena.allocate(ValueLayout.JAVA_INT, n);
      MemorySegment fromCols = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment fromDicts = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment fromMatches = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment fromCounts = arena.allocate(ValueLayout.JAVA_INT, n);
      MemorySegment toCols = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment toDicts = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment toLive = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment[] outBits = new MemorySegment[n];
      for (int i = 0; i < n; i++) {
        LeafReaderContext ctx = leaves.get(i);
        LeafReader leaf = ctx.reader();
        int maxDoc = leaf.maxDoc();
        maxDocs.setAtIndex(ValueLayout.JAVA_INT, i, maxDoc);
        NativeLeaf.NativeColumn from = column(leaf, fromField);
        NativeLeaf.NativeColumn to = column(leaf, toField);
        if (from == null || to == null) return null;
        MemorySegment store = from != NativeLeaf.NO_COLUMN ? from.store() : to.store();
        stores.setAtIndex(ValueLayout.ADDRESS, i, store);
        if (from != NativeLeaf.NO_COLUMN) {
          fromCols.setAtIndex(ValueLayout.ADDRESS, i, name(arena, from.column(), ""));
          fromDicts.setAtIndex(ValueLayout.ADDRESS, i, name(arena, from.column(), "t"));
          NativeLeaf.Words w = NativeLeaf.liveAndFilter(null, fromDocs, ctx.docBase, maxDoc, arena);
          fromMatches.setAtIndex(ValueLayout.ADDRESS, i, w.words());
          fromCounts.setAtIndex(ValueLayout.JAVA_INT, i, w.count());
        }
        if (to != NativeLeaf.NO_COLUMN) {
          toCols.setAtIndex(ValueLayout.ADDRESS, i, name(arena, to.column(), ""));
          toDicts.setAtIndex(ValueLayout.ADDRESS, i, name(arena, to.column(), "t"));
          NativeLeaf.Words live = NativeLeaf.liveAndFilter(leaf.getLiveDocs(), null, ctx.docBase, maxDoc, arena);
          toLive.setAtIndex(ValueLayout.ADDRESS, i, live == null ? MemorySegment.NULL : live.words());
          outBits[i] = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, FixedBitSet.bits2words(maxDoc)));
          out.setAtIndex(ValueLayout.ADDRESS, i, outBits[i]);
        }
      }
      MemorySegment total = arena.allocate(ValueLayout.JAVA_LONG);
      try {
        check((int) LxlNative.JOIN_SORTED.invokeExact(n, stores, maxDocs, fromCols, fromDicts, fromMatches, fromCounts,
            toCols, toDicts, toLive, out, total));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      for (int i = 0; i < n; i++) {
        if (outBits[i] != null) LuxirSearch.orInto(result, outBits[i], leaves.get(i).docBase, leaves.get(i).reader().maxDoc());
      }
    }
    return result;
  }

  /**
   * The leaf's native single-valued sorted column for {@code field}; {@link NativeLeaf#NO_COLUMN}
   * if the leaf has no values for it; null if its values are not such a column.
   */
  private static NativeLeaf.NativeColumn column(LeafReader leaf, String field) {
    FieldInfo fi = leaf.getFieldInfos().fieldInfo(field);
    if (fi == null || fi.getDocValuesType() == DocValuesType.NONE) return NativeLeaf.NO_COLUMN;
    if (fi.getDocValuesType() != DocValuesType.SORTED) return null;
    NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(leaf, field);
    if (col == null || col.multi()) return null;
    if (col != NativeLeaf.NO_COLUMN && col.store().equals(MemorySegment.NULL)) return NativeLeaf.NO_COLUMN;
    return col;
  }

  /** A NUL-terminated native name: {@code prefix} then the column's name ("t" names its dictionary). */
  private static MemorySegment name(Arena arena, byte[] column, String prefix) {
    byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
    byte[] all = new byte[p.length + column.length + 1];
    System.arraycopy(p, 0, all, 0, p.length);
    System.arraycopy(column, 0, all, p.length, column.length);
    return arena.allocateFrom(ValueLayout.JAVA_BYTE, all);
  }
}
