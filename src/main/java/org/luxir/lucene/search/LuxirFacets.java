// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.LxlNative;

/**
 * Facet counting by Luxir: one native scan of a field's doc-values column over a segment's
 * matching documents, instead of a doc values lookup per document in Java. Matches are given as
 * an index-wide bitset (global doc ids, live docs only).
 */
public final class LuxirFacets {
  private LuxirFacets() {}

  /** Whether every segment that has {@code field} keeps its doc values of {@code type} natively. */
  public static boolean nativeField(IndexSearcher searcher, String field, DocValuesType type) {
    for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
      FieldInfo fi = ctx.reader().getFieldInfos().fieldInfo(field);
      if (fi == null) continue;
      if (fi.getDocValuesType() != type || NativeLeaf.nativeColumn(ctx.reader(), field) == null) return false;
    }
    return true;
  }

  /** A segment's matches as native words, and how many there are. */
  private static NativeLeaf.Words words(LeafReaderContext ctx, FixedBitSet matches, Arena arena) {
    return NativeLeaf.liveAndFilter(null, matches, ctx.docBase, ctx.reader().maxDoc(), arena);
  }

  /** The ordinals of a segment's matches that have any (ascending), with counts. */
  public record OrdCounts(int[] ords, int[] counts, int missing) {}

  /**
   * Per-ordinal counts of a SORTED or SORTED_SET field's values among one segment's matches, as
   * the touched ordinals only (and the matches without a value); null if not native.
   */
  public static OrdCounts sparseOrdCounts(LeafReaderContext ctx, String field, FixedBitSet matches, int numOrds) {
    NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), field);
    if (col == null) return null;
    try (Arena arena = Arena.ofConfined()) {
      NativeLeaf.Words m = words(ctx, matches, arena);
      if (m.count() == 0) return new OrdCounts(new int[0], new int[0], 0);
      if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL) || numOrds == 0) {
        return new OrdCounts(new int[0], new int[0], m.count());
      }
      MemorySegment name = arena.allocateFrom(ValueLayout.JAVA_BYTE, col.column());
      MemorySegment missing = arena.allocate(ValueLayout.JAVA_INT);
      int cap = Math.min(numOrds, 4096);
      while (true) {
        MemorySegment ords = arena.allocate(ValueLayout.JAVA_INT, cap);
        MemorySegment counts = arena.allocate(ValueLayout.JAVA_INT, cap);
        int n;
        try {
          n = (int) LxlNative.COLUMN_ORD_COUNTS_SPARSE.invokeExact(col.store(), name, col.column().length, m.words(),
              m.count(), ctx.reader().maxDoc(), (long) numOrds, ords, counts, cap, missing);
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
        check(n);
        if (n > cap) {
          cap = n;
          continue;
        }
        int[] o = new int[n], c = new int[n];
        MemorySegment.copy(ords, ValueLayout.JAVA_INT, 0, o, 0, n);
        MemorySegment.copy(counts, ValueLayout.JAVA_INT, 0, c, 0, n);
        return new OrdCounts(o, c, missing.get(ValueLayout.JAVA_INT, 0));
      }
    }
  }

  /**
   * Raw doc values of a segment's matches with counts and a first (global) doc each; a value can
   * repeat (see {@link #valueCounts(LeafReaderContext, String, FixedBitSet, int, ValueSink)}).
   */
  public record ValueCounts(long[] values, int[] counts, int[] firstDocs, int missing) {}

  /** Receives a segment's value counts. */
  @FunctionalInterface
  public interface ValueSink {
    /** Called first, with how many values follow. */
    default void expect(int n) {}

    void accept(long value, int count, int firstDoc) throws IOException;
  }

  /** Docs with a value sampled before {@link #valueCounts} judges the values mostly distinct. */
  public static final int NEAR_UNIQUE_SAMPLE = 65536;

  /** Whether {@link #valueCounts} can count the field natively in the segment. */
  public static boolean nativeValues(LeafReaderContext ctx, String field) {
    return NativeLeaf.nativeColumn(ctx.reader(), field) != null;
  }

  /**
   * Value counts of a NUMERIC or SORTED_NUMERIC field in one segment; null if not native; sample
   * <= 0 always hashes. See {@link #valueCounts(LeafReaderContext, String, FixedBitSet, int,
   * ValueSink)}.
   */
  public static ValueCounts valueCounts(LeafReaderContext ctx, String field, FixedBitSet matches, int sample) {
    class Lists implements ValueSink {
      long[] v = new long[0];
      int[] c = new int[0], f = new int[0];
      int n;

      @Override
      public void expect(int size) {
        v = new long[size];
        c = new int[size];
        f = new int[size];
      }

      @Override
      public void accept(long value, int count, int firstDoc) {
        v[n] = value;
        c[n] = count;
        f[n++] = firstDoc;
      }
    }
    Lists l = new Lists();
    int missing;
    try {
      missing = valueCounts(ctx, field, matches, sample, l);
    } catch (IOException e) {
      throw new UncheckedIOException(e); // Lists does not throw
    }
    return missing < 0 ? null : new ValueCounts(l.v, l.c, l.f, missing);
  }

  /**
   * Passes the value counts of a NUMERIC or SORTED_NUMERIC field in one segment to {@code sink}:
   * each raw doc value of the matches, the number of matches with it and its first (global) doc.
   * Returns the number of matches without a value, or -1 (with nothing passed) if not native.
   * Each value is passed once, except when more than half of the first {@code sample} matches with
   * a value (sample <= 0: never) add a new one: the values then come in doc order, counted rather
   * than hashed, and a value that recurs after others repeats (the caller adds up its counts).
   */
  public static int valueCounts(LeafReaderContext ctx, String field, FixedBitSet matches, int sample, ValueSink sink)
      throws IOException {
    NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), field);
    if (col == null) return -1;
    try (Arena arena = Arena.ofConfined()) {
      NativeLeaf.Words m = words(ctx, matches, arena);
      if (m.count() == 0) return 0;
      if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL)) return m.count();
      MemorySegment name = arena.allocateFrom(ValueLayout.JAVA_BYTE, col.column());
      MemorySegment missing = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
      int n;
      try {
        n = (int) LxlNative.COLUMN_VALUE_COUNTS.invokeExact(col.store(), name, col.column().length, m.words(),
            m.count(), ctx.reader().maxDoc(), sample, out, missing);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      check(n);
      try {
        // n entries of {long value; int count; int firstDoc}, this thread's until its next call
        MemorySegment entries = out.get(ValueLayout.ADDRESS, 0).reinterpret(16L * n);
        sink.expect(n);
        for (int i = 0; i < n; i++) {
          long off = 16L * i;
          sink.accept(entries.get(ValueLayout.JAVA_LONG_UNALIGNED, off),
              entries.get(ValueLayout.JAVA_INT_UNALIGNED, off + 8),
              entries.get(ValueLayout.JAVA_INT_UNALIGNED, off + 12) + ctx.docBase);
        }
        return missing.get(ValueLayout.JAVA_INT, 0);
      } finally {
        if (n > 1 << 16) {
          try {
            LxlNative.VALUE_COUNTS_RELEASE.invokeExact();
          } catch (Throwable t) {
            throw LxlNative.rethrow(t);
          }
        }
      }
    }
  }

  /**
   * For each range i, the matches with a value whose sort key (see {@link LxlNative#SORT_LONG},
   * float/double bits in Float.compare/Double.compare order) is in [los[i], his[i]]; a doc counts
   * once per range. Null if a segment's doc values are not native.
   */
  public static int[] rangeCounts(IndexSearcher searcher, String field, int kind, FixedBitSet matches, long[] los,
      long[] his) {
    int n = los.length;
    int[] total = new int[n];
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment lo = arena.allocateFrom(ValueLayout.JAVA_LONG, los);
      MemorySegment hi = arena.allocateFrom(ValueLayout.JAVA_LONG, his);
      MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
      for (LeafReaderContext ctx : leaves) {
        NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), field);
        if (col == null) return null;
        if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL)) continue;
        NativeLeaf.Words m = words(ctx, matches, arena);
        if (m.count() == 0) continue;
        out.fill((byte) 0);
        MemorySegment name = arena.allocateFrom(ValueLayout.JAVA_BYTE, col.column());
        int rc;
        try {
          rc = (int) LxlNative.COLUMN_RANGE_COUNTS.invokeExact(col.store(), name, col.column().length, kind,
              m.words(), m.count(), ctx.reader().maxDoc(), n, lo, hi, out);
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
        check(rc);
        for (int i = 0; i < n; i++) total[i] += out.getAtIndex(ValueLayout.JAVA_INT, i);
      }
    }
    return total;
  }
}
