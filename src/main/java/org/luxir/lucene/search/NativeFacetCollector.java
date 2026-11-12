// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.LxlNative;

/**
 * JSON-facet style collection by Luxir: per segment, one native pass maps each matching document
 * to slots (a single slot, its ordinals of a SORTED/SORTED_SET field, or the numeric ranges holding
 * one of its values), counts it per slot and updates per-slot aggregations, with the exact update
 * rules of Solr's slot accumulators and in doc order, so results are identical to collecting in
 * Java. The caller's accumulator arrays are copied in on creation and written back by {@link
 * #finish()}, after every segment succeeded.
 */
public final class NativeFacetCollector implements AutoCloseable {

  /** An aggregation's per-doc update (see lxl_facet_collect). */
  public enum Op {
    /** d0 += value (0 when missing). */
    SUM,
    /** d0 += value * value (0 when missing). */
    SUMSQ,
    /** if present: d0 += value, i0 += 1. */
    AVG,
    /** if present: d0 = Double.compare-min (NaN: unset). */
    MIN_DOUBLE,
    /** if present: d0 = Double.compare-max (NaN: unset). */
    MAX_DOUBLE,
    /** if present: l0 = min (unset: 0 and not in bits; sets the bit). */
    MIN_LONG,
    /** if present: l0 = max (unset: 0 and not in bits; sets the bit). */
    MAX_LONG,
    /** if present: l0 = min (unset: Long.MIN_VALUE). */
    MIN_DATE,
    /** if present: l0 = max (unset: Long.MIN_VALUE). */
    MAX_DATE,
    /** if present: l0 += 1. */
    COUNT,
    /** if missing: l0 += 1. */
    MISSING,
    /** if present: i0 += 1, d0 += value * value, d1 += value. */
    VARIANCE
  }

  /** How a raw numeric doc value reads, as Solr's numeric field sources read it. */
  public enum ValueType {
    INT,
    LONG,
    FLOAT,
    DOUBLE
  }

  /**
   * One aggregation over a single-valued numeric doc values field: its update and the per-slot
   * arrays it updates (those the op does not use may be null).
   */
  public record Agg(Op op, String field, ValueType type, double[] d0, double[] d1, long[] l0, int[] i0,
      long[] bits) {}

  private static final int GROUP_SLOT = 0, GROUP_ORDS = 1, GROUP_RANGES = 2;
  private static final byte[] NO_FIELD = {'~'};

  private final Arena arena = Arena.ofConfined();
  private final List<Agg> aggs;
  private final int onlySlot;
  private final MemorySegment counts;
  private final int numCounts;
  private final MemorySegment ops, types, d0, d1, l0, i0, bits;
  private final MemorySegment[][] arrays; // per agg: d0, d1, l0, i0, bits (native copies or null)

  /**
   * A collection updating {@code aggs}' arrays. With {@code onlySlot} >= 0, only that slot is
   * collected (and copied); otherwise all. {@code numCounts}: slots to count (0: no counts).
   */
  public NativeFacetCollector(List<Agg> aggs, int onlySlot, int numCounts) {
    this.aggs = aggs;
    this.onlySlot = onlySlot;
    this.numCounts = numCounts;
    counts = numCounts > 0 ? arena.allocate(ValueLayout.JAVA_LONG, numCounts) : MemorySegment.NULL;
    int n = aggs.size();
    ops = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
    types = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
    d0 = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    d1 = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    l0 = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    i0 = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    bits = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    arrays = new MemorySegment[n][];
    for (int a = 0; a < n; a++) {
      Agg g = aggs.get(a);
      ops.setAtIndex(ValueLayout.JAVA_INT, a, g.op().ordinal());
      types.setAtIndex(ValueLayout.JAVA_INT, a, g.type().ordinal());
      MemorySegment[] m = arrays[a] = new MemorySegment[] {copyIn(g.d0()), copyIn(g.d1()), copyIn(g.l0()),
          copyIn(g.i0()), copyInBits(g.bits())};
      d0.setAtIndex(ValueLayout.ADDRESS, a, m[0]);
      d1.setAtIndex(ValueLayout.ADDRESS, a, m[1]);
      l0.setAtIndex(ValueLayout.ADDRESS, a, m[2]);
      i0.setAtIndex(ValueLayout.ADDRESS, a, m[3]);
      bits.setAtIndex(ValueLayout.ADDRESS, a, m[4]);
    }
  }

  /** Collects the segment's matches (an index-wide bitset) to the one slot. */
  public void collectSlot(LeafReaderContext ctx, FixedBitSet matches, int slot) {
    collectSlot(ctx, DocMatches.of(matches), slot);
  }

  /** Collects the segment's matches (index-wide) to the one slot. */
  public void collectSlot(LeafReaderContext ctx, DocMatches matches, int slot) {
    collect(ctx, matches, GROUP_SLOT, null, onlySlot >= 0 ? 0 : slot, null, 0, null, null);
  }

  /**
   * Collects the segment's matches by their ordinals of {@code field}: an ordinal o goes to slot
   * {@code ordSlots[o]}, or nowhere if that is negative.
   */
  public void collectOrds(LeafReaderContext ctx, FixedBitSet matches, String field, int[] ordSlots) {
    collectOrds(ctx, DocMatches.of(matches), field, ordSlots);
  }

  /** As {@link #collectOrds(LeafReaderContext, FixedBitSet, String, int[])}. */
  public void collectOrds(LeafReaderContext ctx, DocMatches matches, String field, int[] ordSlots) {
    collect(ctx, matches, GROUP_ORDS, field, 0, ordSlots, 0, null, null);
  }

  /**
   * Collects the segment's matches to each range i (slot i) whose [los[i], his[i]] holds the sort
   * key (kind as for {@link LuxirFacets#rangeCounts}) of one of its values of {@code field}.
   */
  public void collectRanges(LeafReaderContext ctx, FixedBitSet matches, String field, int kind, long[] los,
      long[] his) {
    collectRanges(ctx, DocMatches.of(matches), field, kind, los, his);
  }

  /** As {@link #collectRanges(LeafReaderContext, FixedBitSet, String, int, long[], long[])}. */
  public void collectRanges(LeafReaderContext ctx, DocMatches matches, String field, int kind, long[] los,
      long[] his) {
    collect(ctx, matches, GROUP_RANGES, field, 0, null, kind, los, his);
  }

  private void collect(LeafReaderContext ctx, DocMatches matches, int group, String groupField, int slot,
      int[] ordSlots, int kind, long[] los, long[] his) {
    NativeLeaf.NativeColumn gcol = groupField == null ? null : NativeLeaf.nativeColumn(ctx.reader(), groupField);
    if (groupField != null && (gcol == null || gcol == NativeLeaf.NO_COLUMN)) {
      if (gcol == null) throw new IllegalStateException("not native: " + groupField);
      return; // no values to group by: nothing to collect
    }
    MemorySegment store = gcol == null ? MemorySegment.NULL : gcol.store();
    ByteArrayOutputStream names = new ByteArrayOutputStream();
    int[] offs = new int[aggs.size() + 1];
    for (int a = 0; a < aggs.size(); a++) {
      NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), aggs.get(a).field());
      if (col == null) throw new IllegalStateException("not native: " + aggs.get(a).field());
      byte[] name = col == NativeLeaf.NO_COLUMN ? NO_FIELD : col.column();
      if (store.equals(MemorySegment.NULL) && col != NativeLeaf.NO_COLUMN) store = col.store();
      names.writeBytes(name);
      offs[a + 1] = names.size();
    }
    try (Arena call = Arena.ofConfined()) {
      DocMatches.Leaf m = matches.leaf(ctx, call);
      if (m.count() == 0) return;
      if (store.equals(MemorySegment.NULL)) {
        missingOnly(slot, m.count()); // a single slot whose fields have no values here
        return;
      }
      // invokeExact needs each argument's static type to be MemorySegment
      MemorySegment gname =
          gcol == null ? MemorySegment.NULL : call.allocateFrom(ValueLayout.JAVA_BYTE, gcol.column());
      MemorySegment slots =
          ordSlots == null ? MemorySegment.NULL : call.allocateFrom(ValueLayout.JAVA_INT, ordSlots);
      MemorySegment lo = los == null ? MemorySegment.NULL : call.allocateFrom(ValueLayout.JAVA_LONG, los);
      MemorySegment hi = his == null ? MemorySegment.NULL : call.allocateFrom(ValueLayout.JAVA_LONG, his);
      MemorySegment nameBytes = call.allocateFrom(ValueLayout.JAVA_BYTE, names.toByteArray());
      MemorySegment nameOffs = call.allocateFrom(ValueLayout.JAVA_INT, offs);
      int rc;
      try {
        rc = (int) LxlNative.FACET_COLLECT.invokeExact(store, m.words(), m.docs(), m.count(), ctx.reader().maxDoc(),
            group, gname, gcol == null ? 0 : gcol.column().length, slot, slots,
            ordSlots == null ? 0L : (long) ordSlots.length, kind, los == null ? 0 : los.length, lo, hi, counts,
            aggs.size(), nameBytes, nameOffs, ops, types, d0, d1, l0, i0, bits);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      check(rc);
    }
  }

  // n docs without values collected to one slot, as the native updates would do
  private void missingOnly(int slot, int n) {
    if (!counts.equals(MemorySegment.NULL)) {
      counts.setAtIndex(ValueLayout.JAVA_LONG, slot, counts.getAtIndex(ValueLayout.JAVA_LONG, slot) + n);
    }
    for (int a = 0; a < aggs.size(); a++) {
      MemorySegment[] m = arrays[a];
      switch (aggs.get(a).op()) {
        case SUM, SUMSQ -> m[0].setAtIndex(ValueLayout.JAVA_DOUBLE, slot,
            m[0].getAtIndex(ValueLayout.JAVA_DOUBLE, slot) + 0.0);
        case MISSING -> m[2].setAtIndex(ValueLayout.JAVA_LONG, slot, m[2].getAtIndex(ValueLayout.JAVA_LONG, slot) + n);
        default -> {}
      }
    }
  }

  /** Writes the aggregations back to their arrays; returns the counts (null if none were kept). */
  public long[] finish() {
    for (int a = 0; a < aggs.size(); a++) {
      Agg g = aggs.get(a);
      MemorySegment[] m = arrays[a];
      if (g.d0() != null) copyOut(m[0], g.d0());
      if (g.d1() != null) copyOut(m[1], g.d1());
      if (g.l0() != null) copyOut(m[2], g.l0());
      if (g.i0() != null) copyOut(m[3], g.i0());
      if (g.bits() != null) copyOutBits(m[4], g.bits());
    }
    return numCounts > 0 ? counts.toArray(ValueLayout.JAVA_LONG) : null;
  }

  @Override
  public void close() {
    arena.close();
  }

  private MemorySegment copyIn(double[] a) {
    if (a == null) return MemorySegment.NULL;
    return onlySlot >= 0 ? arena.allocateFrom(ValueLayout.JAVA_DOUBLE, a[onlySlot])
        : arena.allocateFrom(ValueLayout.JAVA_DOUBLE, a);
  }

  private MemorySegment copyIn(long[] a) {
    if (a == null) return MemorySegment.NULL;
    return onlySlot >= 0 ? arena.allocateFrom(ValueLayout.JAVA_LONG, a[onlySlot])
        : arena.allocateFrom(ValueLayout.JAVA_LONG, a);
  }

  private MemorySegment copyIn(int[] a) {
    if (a == null) return MemorySegment.NULL;
    return onlySlot >= 0 ? arena.allocateFrom(ValueLayout.JAVA_INT, a[onlySlot])
        : arena.allocateFrom(ValueLayout.JAVA_INT, a);
  }

  // slot bits (FixedBitSet words); with onlySlot, its bit as bit 0
  private MemorySegment copyInBits(long[] words) {
    if (words == null) return MemorySegment.NULL;
    if (onlySlot < 0) return arena.allocateFrom(ValueLayout.JAVA_LONG, words);
    long bit = (words[onlySlot >> 6] >>> (onlySlot & 63)) & 1L;
    return arena.allocateFrom(ValueLayout.JAVA_LONG, bit);
  }

  private void copyOut(MemorySegment m, double[] a) {
    if (onlySlot >= 0) a[onlySlot] = m.getAtIndex(ValueLayout.JAVA_DOUBLE, 0);
    else MemorySegment.copy(m, ValueLayout.JAVA_DOUBLE, 0, a, 0, a.length);
  }

  private void copyOut(MemorySegment m, long[] a) {
    if (onlySlot >= 0) a[onlySlot] = m.getAtIndex(ValueLayout.JAVA_LONG, 0);
    else MemorySegment.copy(m, ValueLayout.JAVA_LONG, 0, a, 0, a.length);
  }

  private void copyOut(MemorySegment m, int[] a) {
    if (onlySlot >= 0) a[onlySlot] = m.getAtIndex(ValueLayout.JAVA_INT, 0);
    else MemorySegment.copy(m, ValueLayout.JAVA_INT, 0, a, 0, a.length);
  }

  private void copyOutBits(MemorySegment m, long[] words) {
    if (onlySlot < 0) {
      MemorySegment.copy(m, ValueLayout.JAVA_LONG, 0, words, 0, words.length);
    } else if ((m.getAtIndex(ValueLayout.JAVA_LONG, 0) & 1L) != 0) {
      words[onlySlot >> 6] |= 1L << (onlySlot & 63);
    }
  }
}
