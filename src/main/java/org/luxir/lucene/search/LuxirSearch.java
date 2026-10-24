// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.search.SortField;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.DocValuesType;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.LxlNative;

/**
 * Top-k with an exact hit count, computed by Luxir in one native call over all segments: an
 * unscored count pass, then a ranking pass that prunes with block-max scores and one competitive
 * floor shared across segments. Term statistics are summed natively. Lucene's collector API cannot express this split
 * (an exact count there means scoring every match), which is where most of Luxir's speed with
 * exact counts comes from.
 */
public final class LuxirSearch {
  private LuxirSearch() {}

  /**
   * Hits sorted by score descending then doc ascending (global doc ids), the exact number of
   * matches, and optionally the set of all matches: as bits, or, when asked for sparse matches and
   * there are few, as {@code matchDocs} (ascending global doc ids) with {@code matches} null.
   */
  public record Result(int[] docs, float[] scores, long totalHits, boolean exactCount, FixedBitSet matches,
      int[] matchDocs) {
    public Result(int[] docs, float[] scores, long totalHits, boolean exactCount, FixedBitSet matches) {
      this(docs, scores, totalHits, exactCount, matches, null);
    }

    public float maxScore() {
      return docs.length == 0 ? Float.NaN : scores[0];
    }
  }

  /**
   * Runs {@code query} natively, restricted to {@code filter} (global doc ids; null for none), or
   * returns null when the query shape or any segment cannot run natively.
   */
  public static Result topK(IndexSearcher searcher, Query query, int k, FixedBitSet filter, boolean wantMatches)
      throws IOException {
    return topK(searcher, query, k, Integer.MAX_VALUE, filter, wantMatches);
  }

  /**
   * As {@link #topK(IndexSearcher, Query, int, FixedBitSet, boolean)}, counting matches exactly
   * only up to {@code max(minExactCount, k)} (Lucene's totalHitsThreshold); beyond that the count
   * is a lower bound and {@link Result#exactCount()} is false.
   */
  public static Result topK(IndexSearcher searcher, Query query, int k, int minExactCount, FixedBitSet filter,
      boolean wantMatches) throws IOException {
    return topK(searcher, query, List.of(), k, minExactCount, filter, wantMatches);
  }

  /**
   * As above, also restricted to every query in {@code filters} (non-scoring, like Solr's fq),
   * which run natively with the query; null if the query or any filter cannot.
   */
  public static Result topK(IndexSearcher searcher, Query query, List<Query> filters, int k, int minExactCount,
      FixedBitSet filter, boolean wantMatches) throws IOException {
    return topK(searcher, query, filters, k, minExactCount, filter, false, wantMatches);
  }

  /**
   * As above; {@code filterCached}: the filter is not modified while it lives (a cached filter's
   * bits), so its native copy is made once and reused by later requests.
   */
  public static Result topK(IndexSearcher searcher, Query query, List<Query> filters, int k, int minExactCount,
      FixedBitSet filter, boolean filterCached, boolean wantMatches) throws IOException {
    return topK(searcher, query, filters, k, minExactCount, filter, filterCached, wantMatches, false);
  }

  /**
   * As above; {@code sparseMatches}: wanted matches that are fewer than one doc in 64 (Solr's
   * threshold for a sorted int doc set) come as {@link Result#matchDocs()} instead of bits.
   */
  public static Result topK(IndexSearcher searcher, Query query, List<Query> filters, int k, int minExactCount,
      FixedBitSet filter, boolean filterCached, boolean wantMatches, boolean sparseMatches) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      Run r = run(searcher, query, filters, k, minExactCount, filter, filterCached, wantMatches, arena);
      if (r == null) return null;
      return r.result(searcher, r.docs(), r.scores(), r.exact(), wantMatches, sparseMatches);
    }
  }

  /**
   * One native execution: top hits and count, and, with {@code segBits}, each native segment's
   * matches as native words allocated from {@code arena} (valid while it is open). Null if the
   * query or a filter cannot run natively.
   */
  private record Run(int[] docs, float[] scores, long total, boolean exact, NativeMulti multi,
      MemorySegment[] segBits, SegTops tops) {
    Run(int[] docs, float[] scores, long total, boolean exact, NativeMulti multi, MemorySegment[] segBits) {
      this(docs, scores, total, exact, multi, segBits, null);
    }

    /** The result with these hits and, if wanted, the matches (see {@link Result}). */
    Result result(IndexSearcher searcher, int[] hits, float[] scores, boolean exact, boolean wantMatches,
        boolean sparseMatches) {
      if (!wantMatches) return new Result(hits, scores, total, exact, null);
      if (sparseMatches && this.exact && total < (searcher.getIndexReader().maxDoc() >> 6) + 5) {
        return new Result(hits, scores, total, exact, null, matchDocs(searcher));
      }
      return new Result(hits, scores, total, exact, matches(searcher));
    }

    /** All matches as ascending index-wide doc ids (the count must be exact). */
    int[] matchDocs(IndexSearcher searcher) {
      int[] out = new int[(int) total];
      if (multi == null) return out;
      List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      Integer[] order = new Integer[segBits.length];
      for (int s = 0; s < order.length; s++) order[s] = s;
      Arrays.sort(order, Comparator.comparingInt(s -> multi.leafOfSeg[s]));
      int n = 0;
      long[] chunk = NativeLeaf.scratch(0);
      for (int s : order) {
        LeafReaderContext ctx = leaves.get(multi.leafOfSeg[s]);
        int nwords = FixedBitSet.bits2words(ctx.reader().maxDoc());
        // bulk copies in chunks: per-word segment access is slow until the JIT has compiled it
        for (int start = 0; start < nwords; start += chunk.length) {
          int len = Math.min(chunk.length, nwords - start);
          MemorySegment.copy(segBits[s], ValueLayout.JAVA_LONG, (long) start * Long.BYTES, chunk, 0, len);
          for (int i = 0; i < len; i++) {
            long word = chunk[i];
            int base = ctx.docBase + ((start + i) << 6);
            while (word != 0) {
              out[n++] = base + Long.numberOfTrailingZeros(word);
              word &= word - 1;
            }
          }
        }
      }
      if (n != out.length) throw new IllegalStateException("match count " + total + " but " + n + " matches");
      return out;
    }

    /** All matches, index-wide. */
    FixedBitSet matches(IndexSearcher searcher) {
      FixedBitSet m = new FixedBitSet(Math.max(1, searcher.getIndexReader().maxDoc()));
      if (multi == null) return m;
      List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      for (int s = 0; s < segBits.length; s++) {
        LeafReaderContext ctx = leaves.get(multi.leafOfSeg[s]);
        orInto(m, segBits[s], ctx.docBase, ctx.reader().maxDoc());
      }
      return m;
    }
  }

  /** A sort by one column for {@link #run}: each segment's top k by it, instead of hits by score. */
  private record ColumnSort(String field, int kind, boolean reverse, long missing, int k) {}

  /**
   * Per native segment s, its best docs by the sort (segment doc ids) and their keys, best first:
   * {@code counts[s]} of them from {@code s * k} on.
   */
  private record SegTops(int k, int[] counts, int[] docs, long[] keys) {}

  private static Run run(IndexSearcher searcher, Query query, List<Query> filters, int k, int minExactCount,
      FixedBitSet filter, boolean filterCached, boolean wantSegBits, Arena arena) throws IOException {
    return run(searcher, query, filters, k, minExactCount, filter, filterCached, wantSegBits, null, arena);
  }

  /**
   * As above; with {@code sort} (no seg bits), each segment's top {@code sort.k()} (> 0) by the sort
   * column as Luxir finds its matches (no scores, no hits by score), in {@link Run#tops()}.
   */
  private static Run run(IndexSearcher searcher, Query query, List<Query> filters, int k, int minExactCount,
      FixedBitSet filter, boolean filterCached, boolean wantSegBits, ColumnSort sort, Arena arena)
      throws IOException {
    Query rewritten = searcher.rewrite(withFilters(query, filters));
    IndexReader top = searcher.getIndexReader();
    if (rewritten instanceof MatchNoDocsQuery) return new Run(new int[0], new float[0], 0, true, null, null);
    // the filter also as a FILTER clause, so Luxir's planner sees its cost and can lead with it
    Query planned = filter == null ? rewritten : new BooleanQuery.Builder()
        .add(rewritten, BooleanClause.Occur.MUST)
        .add(FilterSlotQuery.INSTANCE, BooleanClause.Occur.FILTER)
        .build();
    LxlQuery.Encoded enc = LxlQuery.encode(planned, searcher, 1f, true, true, true);
    if (enc == null) return null;
    NativeMulti multi = NativeMulti.of(top);
    if (multi == null || !nativeLeaves(multi, top, enc)) return null;
    List<LeafReaderContext> leaves = top.leaves();
    int n = multi.leafOfSeg.length;
    {
      // per segment: live docs and the filter, as the native call's live words and as the filter slot
      NativeLeaf.Words[] liveWords = new NativeLeaf.Words[n];
      for (int s = 0; s < n; s++) {
        LeafReaderContext ctx = leaves.get(multi.leafOfSeg[s]);
        liveWords[s] = filter != null && filterCached
            ? NativeLeaf.cachedLiveAndFilter(ctx, filter, leaves.size())
            : NativeLeaf.liveAndFilter(ctx.reader().getLiveDocs(), filter, ctx.docBase, ctx.reader().maxDoc(), arena);
      }
      int numSlots = enc.ranges().size();
      MemorySegment slotBits = arena.allocate(ValueLayout.ADDRESS, Math.max(1, (long) numSlots * n));
      MemorySegment slotCounts = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, (long) numSlots * n));
      for (int sl = 0; sl < numSlots; sl++) {
        LxlQuery.Range range = enc.ranges().get(sl);
        for (int s = 0; s < n; s++) {
          if (range.isFilter()) {
            NativeLeaf.Words w = liveWords[s];
            slotBits.setAtIndex(ValueLayout.ADDRESS, (long) sl * n + s, w.words());
            slotCounts.setAtIndex(ValueLayout.JAVA_INT, (long) sl * n + s, w.count());
            continue;
          }
          LeafReader leaf = leaves.get(multi.leafOfSeg[s]).reader();
          MemorySegment points = NativeLeaf.nativePoints(leaf, range.field());
          if (points == null) return null; // points not in Luxir format: Lucene evaluates the range
          if (points.equals(MemorySegment.NULL)) continue; // no values here: no match
          int leafMax = leaf.maxDoc();
          MemorySegment words = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, FixedBitSet.bits2words(leafMax)));
          int card = 0;
          try {
            if (range.values() == null) {
              card = (int) LxlNative.POINTS_RANGE.invokeExact(points, range.lo(), range.hi(), leafMax, words);
            } else {
              // a point set: every value ORed into the same words, counted once
              MemorySegment vals = arena.allocateFrom(ValueLayout.JAVA_LONG, range.values());
              card = (int) LxlNative.POINTS_SET.invokeExact(points, vals, range.values().length, leafMax, words);
            }
          } catch (Throwable t) {
            throw LxlNative.rethrow(t);
          }
          check(card);
          slotBits.setAtIndex(ValueLayout.ADDRESS, (long) sl * n + s, words);
          slotCounts.setAtIndex(ValueLayout.JAVA_INT, (long) sl * n + s, card);
        }
      }
      int numCols = enc.columns().size();
      MemorySegment colStores = arena.allocate(ValueLayout.ADDRESS, Math.max(1, (long) numCols * n));
      MemorySegment colNames = arena.allocate(ValueLayout.ADDRESS, Math.max(1, (long) numCols * n));
      MemorySegment colPoints = arena.allocate(ValueLayout.ADDRESS, Math.max(1, (long) numCols * n));
      MemorySegment colOffsets = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, (long) numCols * n));
      for (int c = 0; c < numCols; c++) {
        LxlQuery.ColumnRange cr = enc.columns().get(c);
        for (int s = 0; s < n; s++) {
          LeafReader leaf = leaves.get(multi.leafOfSeg[s]).reader();
          NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(leaf, cr.field());
          if (col == null) return null;
          if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL)) continue; // no values here
          long at = (long) c * n + s;
          colStores.setAtIndex(ValueLayout.ADDRESS, at, col.store());
          byte[] name = java.util.Arrays.copyOf(col.column(), col.column().length + 1); // NUL-terminated
          colNames.setAtIndex(ValueLayout.ADDRESS, at, arena.allocateFrom(ValueLayout.JAVA_BYTE, name));
          MemorySegment pts = NativeLeaf.nativePoints(leaf, cr.field());
          if (pts == null) return null;
          colPoints.setAtIndex(ValueLayout.ADDRESS, at, pts); // NULL: no points here
          colOffsets.setAtIndex(ValueLayout.JAVA_LONG, at, cr.pointsOffset());
        }
      }
      MemorySegment q = arena.allocate(enc.bytes().length);
      MemorySegment.copy(enc.bytes(), 0, q, ValueLayout.JAVA_BYTE, 0, enc.bytes().length);
      MemorySegment live = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment numLive = arena.allocate(ValueLayout.JAVA_INT, n);
      MemorySegment bits = wantSegBits ? arena.allocate(ValueLayout.ADDRESS, n) : MemorySegment.NULL;
      MemorySegment[] segBits = new MemorySegment[n];
      for (int s = 0; s < n; s++) {
        LeafReaderContext ctx = leaves.get(multi.leafOfSeg[s]);
        int leafMax = ctx.reader().maxDoc();
        NativeLeaf.Words w = liveWords[s];
        live.setAtIndex(ValueLayout.ADDRESS, s, w == null ? MemorySegment.NULL : w.words());
        numLive.setAtIndex(ValueLayout.JAVA_INT, s, w == null ? leafMax : w.count());
        if (wantSegBits) {
          segBits[s] = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, FixedBitSet.bits2words(leafMax)));
          bits.setAtIndex(ValueLayout.ADDRESS, s, segBits[s]);
        }
      }
      if (sort != null) {
        return runSorted(multi, leaves, q, enc.bytes().length, live, numLive, numSlots, slotBits, slotCounts, numCols,
            colStores, colNames, colPoints, colOffsets, sort, arena);
      }
      MemorySegment segsOut = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, k));
      MemorySegment docsOut = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, k));
      MemorySegment scoresOut = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, k));
      MemorySegment totalOut = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment exactOut = arena.allocate(ValueLayout.JAVA_INT);
      long limit = minExactCount == Integer.MAX_VALUE ? -1 : Math.max(1, Math.max(minExactCount, k));
      int got;
      try {
        got = (int) LxlNative.MULTI_TOP_K.invokeExact(multi.handle, q, (long) enc.bytes().length, live, numLive, k,
            limit, segsOut, docsOut, scoresOut, totalOut, exactOut, bits, numSlots, slotBits, slotCounts, numCols,
            colStores, colNames, colPoints, colOffsets);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      check(got);
      int[] docs = new int[got];
      float[] scores = new float[got];
      for (int i = 0; i < got; i++) {
        int leaf = multi.leafOfSeg[segsOut.getAtIndex(ValueLayout.JAVA_INT, i)];
        docs[i] = leaves.get(leaf).docBase + docsOut.getAtIndex(ValueLayout.JAVA_INT, i);
        scores[i] = scoresOut.getAtIndex(ValueLayout.JAVA_FLOAT, i);
      }
      return new Run(docs, scores, totalOut.get(ValueLayout.JAVA_LONG, 0), exactOut.get(ValueLayout.JAVA_INT, 0) != 0,
          multi, wantSegBits ? segBits : null);
    }
  }

  private static Run runSorted(NativeMulti multi, List<LeafReaderContext> leaves, MemorySegment q, int qlen,
      MemorySegment live, MemorySegment numLive, int numSlots, MemorySegment slotBits, MemorySegment slotCounts,
      int numCols, MemorySegment colStores, MemorySegment colNames, MemorySegment colPoints, MemorySegment colOffsets,
      ColumnSort sort, Arena arena) {
    int n = multi.leafOfSeg.length;
    int k = sort.k();
    MemorySegment sortStores = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    MemorySegment sortCols = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
    for (int s = 0; s < n; s++) {
      NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(leaves.get(multi.leafOfSeg[s]).reader(), sort.field());
      if (col == null) return null;
      if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL)) continue; // missing key here
      sortStores.setAtIndex(ValueLayout.ADDRESS, s, col.store());
      byte[] name = Arrays.copyOf(col.column(), col.column().length + 1); // NUL-terminated
      sortCols.setAtIndex(ValueLayout.ADDRESS, s, arena.allocateFrom(ValueLayout.JAVA_BYTE, name));
    }
    MemorySegment counts = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
    MemorySegment docs = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, (long) n * k));
    MemorySegment keys = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, (long) n * k));
    MemorySegment totalOut = arena.allocate(ValueLayout.JAVA_LONG);
    try {
      check((int) LxlNative.MULTI_TOP_K_SORTED.invokeExact(multi.handle, q, (long) qlen, live, numLive, numSlots,
          slotBits, slotCounts, numCols, colStores, colNames, colPoints, colOffsets, sortStores, sortCols, sort.kind(),
          k, sort.reverse() ? 1 : 0, sort.missing(), counts, docs, keys, totalOut));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
    SegTops tops = new SegTops(k, counts.toArray(ValueLayout.JAVA_INT), docs.toArray(ValueLayout.JAVA_INT),
        keys.toArray(ValueLayout.JAVA_LONG));
    return new Run(new int[0], new float[0], totalOut.get(ValueLayout.JAVA_LONG, 0), true, multi, null, tops);
  }

  /**
   * Whether {@link #topKSorted} can sort by {@code sf}: a plain single-valued sort on a field whose
   * doc values are native in every segment (numeric types on NUMERIC, strings on SORTED).
   */
  public static boolean sortSupported(IndexSearcher searcher, SortField sf) {
    if (sf.getClass() != SortField.class || sf.getField() == null) return false;
    DocValuesType want = switch (sf.getType()) {
      case INT, LONG, FLOAT, DOUBLE -> DocValuesType.NUMERIC;
      case STRING -> DocValuesType.SORTED;
      default -> null;
    };
    if (want == null) return false;
    for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
      FieldInfo fi = ctx.reader().getFieldInfos().fieldInfo(sf.getField());
      if (fi == null) continue; // no values in this segment: every doc takes the missing value
      if (fi.getDocValuesType() != want) return false;
      NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), sf.getField());
      if (col == null || col.multi()) return false;
    }
    return true;
  }

  /**
   * The top {@code k} matches of the query (and filters) by one field, ties by doc id, computed
   * natively: Luxir finds the matches, then each segment's best k by its column; Java merges.
   * Scores are not computed (NaN). Null if the query, a filter or the sort cannot run natively.
   */
  public static Result topKSorted(IndexSearcher searcher, Query query, List<Query> filters, SortField sf, int k,
      FixedBitSet filter, boolean wantMatches) throws IOException {
    return topKSorted(searcher, query, filters, sf, k, filter, false, wantMatches);
  }

  /** As above; {@code filterCached} as for {@link #topK}. */
  public static Result topKSorted(IndexSearcher searcher, Query query, List<Query> filters, SortField sf, int k,
      FixedBitSet filter, boolean filterCached, boolean wantMatches) throws IOException {
    return topKSorted(searcher, query, filters, sf, k, filter, filterCached, wantMatches, false);
  }

  /** As above; {@code sparseMatches} as for {@link #topK}. */
  public static Result topKSorted(IndexSearcher searcher, Query query, List<Query> filters, SortField sf, int k,
      FixedBitSet filter, boolean filterCached, boolean wantMatches, boolean sparseMatches) throws IOException {
    if (!sortSupported(searcher, sf)) return null;
    try (Arena arena = Arena.ofConfined()) {
      // without the match set, each segment's top k is kept as Luxir finds the matches
      ColumnSort sort = wantMatches || k <= 0 ? null
          : new ColumnSort(sf.getField(), sortKind(sf), sf.getReverse(), missingKey(sf), k);
      Run all = run(searcher, query, filters, 0, Integer.MAX_VALUE, filter, filterCached, sort == null, sort, arena);
      if (all == null) return null;
      return sorted(searcher, all, sf, k, wantMatches, sparseMatches, arena);
    }
  }

  private static Result sorted(IndexSearcher searcher, Run all, SortField sf, int k, boolean wantMatches,
      boolean sparseMatches, Arena arena) throws IOException {
    int kind = sortKind(sf);
    boolean string = sf.getType() == SortField.Type.STRING;
    boolean reverse = sf.getReverse();
    long missing = missingKey(sf);
    List<Candidate> cands = new ArrayList<>();
    if (k > 0 && all.total() > 0 && all.multi() != null && all.tops() != null) {
      List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      SegTops tops = all.tops();
      for (int s = 0; s < tops.counts().length; s++) {
        LeafReaderContext ctx = leaves.get(all.multi().leafOfSeg[s]);
        SortedDocValues terms = string && tops.counts()[s] > 0 ? ctx.reader().getSortedDocValues(sf.getField()) : null;
        for (int i = s * tops.k(); i < s * tops.k() + tops.counts()[s]; i++) {
          long key = tops.keys()[i];
          BytesRef term = null;
          if (string && terms != null && key >= 0 && key != Long.MAX_VALUE) {
            term = BytesRef.deepCopyOf(terms.lookupOrd((int) key));
          }
          cands.add(new Candidate(ctx.docBase + tops.docs()[i], key, term));
        }
      }
    } else if (k > 0 && all.total() > 0 && all.multi() != null) {
      List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      {
        MemorySegment docsOut = arena.allocate(ValueLayout.JAVA_INT, k);
        MemorySegment keysOut = arena.allocate(ValueLayout.JAVA_LONG, k);
        for (int s = 0; s < all.segBits().length; s++) {
          LeafReaderContext ctx = leaves.get(all.multi().leafOfSeg[s]);
          int leafMax = ctx.reader().maxDoc();
          // the segment's match count is not known here; the total only picks the column iterator
          NativeLeaf.Words m = new NativeLeaf.Words(all.segBits()[s], (int) Math.min(all.total(), leafMax));
          NativeLeaf.NativeColumn col = NativeLeaf.nativeColumn(ctx.reader(), sf.getField());
          if (col == null) return null;
          if (col == NativeLeaf.NO_COLUMN || col.store().equals(MemorySegment.NULL)) {
            // no values here: the lowest matching docs, all with the missing key
            int taken = 0;
            for (int w = 0; taken < k && w < FixedBitSet.bits2words(leafMax); w++) {
              long word = m.words().getAtIndex(ValueLayout.JAVA_LONG, w);
              while (word != 0 && taken < k) {
                int d = (w << 6) + Long.numberOfTrailingZeros(word);
                word &= word - 1;
                cands.add(new Candidate(ctx.docBase + d, missing, null));
                taken++;
              }
            }
            continue;
          }
          MemorySegment name = arena.allocateFrom(ValueLayout.JAVA_BYTE, col.column());
          int got;
          try {
            got = (int) LxlNative.COLUMN_TOP_K.invokeExact(col.store(), name, col.column().length, kind, m.words(),
                m.count(), leafMax, k, reverse ? 1 : 0, missing, docsOut, keysOut);
          } catch (Throwable t) {
            throw LxlNative.rethrow(t);
          }
          check(got);
          SortedDocValues terms = string ? ctx.reader().getSortedDocValues(sf.getField()) : null;
          for (int i = 0; i < got; i++) {
            long key = keysOut.getAtIndex(ValueLayout.JAVA_LONG, i);
            BytesRef term = null;
            if (string && key >= 0 && key != Long.MAX_VALUE) term = BytesRef.deepCopyOf(terms.lookupOrd((int) key));
            cands.add(new Candidate(ctx.docBase + docsOut.getAtIndex(ValueLayout.JAVA_INT, i), key, term));
          }
        }
      }
    }
    Comparator<Candidate> byValue = string
        ? (a, b) -> {
          int ra = a.term != null ? 1 : a.key < 0 ? 0 : 2, rb = b.term != null ? 1 : b.key < 0 ? 0 : 2;
          return ra != rb ? Integer.compare(ra, rb) : ra == 1 ? a.term.compareTo(b.term) : 0;
        }
        : (a, b) -> Long.compare(a.key, b.key);
    Comparator<Candidate> order = (reverse ? byValue.reversed() : byValue).thenComparingInt(c -> c.doc);
    cands.sort(order);
    int n = Math.min(k, cands.size());
    int[] docs = new int[n];
    float[] scores = new float[n];
    for (int i = 0; i < n; i++) {
      docs[i] = cands.get(i).doc;
      scores[i] = Float.NaN;
    }
    return all.result(searcher, docs, scores, true, wantMatches, sparseMatches);
  }

  private record Candidate(int doc, long key, BytesRef term) {}

  /** How the sort's column keys order (see {@link LxlNative#SORT_LONG}). */
  private static int sortKind(SortField sf) {
    return switch (sf.getType()) {
      case FLOAT -> LxlNative.SORT_FLOAT_BITS;
      case DOUBLE -> LxlNative.SORT_DOUBLE_BITS;
      default -> LxlNative.SORT_LONG;
    };
  }

  /** The sortable key of the sort's missing value (Lucene's defaults: 0, strings first). */
  private static long missingKey(SortField sf) {
    Object mv = sf.getMissingValue();
    return switch (sf.getType()) {
      case STRING -> mv == SortField.STRING_LAST ? Long.MAX_VALUE : -1L;
      case INT -> mv == null ? 0 : ((Number) mv).intValue();
      case LONG -> mv == null ? 0 : ((Number) mv).longValue();
      case FLOAT -> {
        int bits = Float.floatToIntBits(mv == null ? 0f : ((Number) mv).floatValue());
        yield bits ^ ((bits >> 31) & 0x7fffffff);
      }
      case DOUBLE -> {
        long bits = Double.doubleToLongBits(mv == null ? 0d : ((Number) mv).doubleValue());
        yield bits ^ ((bits >> 63) & 0x7fffffffffffffffL);
      }
      default -> throw new IllegalArgumentException(sf.toString());
    };
  }

  /** Whether every leaf can run the encoded query natively in {@code multi}. */
  private static boolean nativeLeaves(NativeMulti multi, IndexReader top, LxlQuery.Encoded enc) throws IOException {
    for (String f : enc.fields()) {
      if (multi.mixedNames(f)) return false;
    }
    List<LeafReaderContext> leaves = top.leaves();
    for (int i = 0; i < leaves.size(); i++) {
      LeafReaderContext ctx = leaves.get(i);
      // match-all and range clauses can match in any segment: every one must be native
      if (enc.fieldless() && multi.segOfLeaf[i] < 0 && ctx.reader().maxDoc() > 0) return false;
      Supplier<MemorySegment> r = NativeLeaf.reader(ctx.reader(), enc);
      if (r == null) {
        if (!NativeLeaf.empty(ctx.reader(), enc.fields())) return false;
      } else if (r.get().address() != multi.readerOfLeaf[i]) {
        return false;
      }
      for (LxlQuery.Range range : enc.ranges()) {
        if (range.isFilter()) continue; // the caller's bits
        if (NativeLeaf.nativePoints(ctx.reader(), range.field()) == null) return false;
      }
      for (LxlQuery.ColumnRange col : enc.columns()) {
        if (NativeLeaf.nativeColumn(ctx.reader(), col.field()) == null) return false;
        if (NativeLeaf.nativePoints(ctx.reader(), col.field()) == null) return false;
      }
    }
    return true;
  }

  /** The query restricted by non-scoring filters (FILTER clauses), or the query itself. */
  static Query withFilters(Query query, List<Query> filters) {
    if (filters.isEmpty()) return query;
    BooleanQuery.Builder b = new BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST);
    for (Query f : filters) b.add(f, BooleanClause.Occur.FILTER);
    return b.build();
  }

  /** Whether {@code filter}, alone, can run natively as a filter of {@link #topK}. */
  public static boolean nativeFilter(IndexSearcher searcher, Query filter) throws IOException {
    Query rewritten = searcher.rewrite(filter);
    if (rewritten instanceof MatchNoDocsQuery) return true;
    LxlQuery.Encoded enc = LxlQuery.probe(rewritten, searcher, false, true, true);
    if (enc == null) return false;
    NativeMulti multi = NativeMulti.of(searcher.getIndexReader());
    return multi != null && nativeLeaves(multi, searcher.getIndexReader(), enc);
  }

  /**
   * Why {@link #topK} would return null for this query with these filters, or null if it would run
   * natively.
   */
  public static String whyNotNative(IndexSearcher searcher, Query query, List<Query> filters) throws IOException {
    Query rewritten = searcher.rewrite(withFilters(query, filters));
    if (rewritten instanceof MatchNoDocsQuery) return null;
    LxlQuery.Encoded enc = LxlQuery.encode(rewritten, searcher, 1f, true, true, true);
    if (enc == null) {
      return "query not supported natively (" + rewritten.getClass().getSimpleName() + ", similarity "
          + searcher.getSimilarity() + "): " + rewritten;
    }
    for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
      if (NativeLeaf.reader(ctx.reader(), enc) == null && !NativeLeaf.empty(ctx.reader(), enc.fields())) {
        org.apache.lucene.index.LeafReader base = NativeLeaf.unwrap(ctx.reader());
        StringBuilder sb = new StringBuilder("segment " + ctx.ord + " (" + base.getClass().getSimpleName() + ")");
        for (String f : enc.fields()) {
          org.apache.lucene.index.Terms t = base.terms(f);
          sb.append(' ').append(f).append('=').append(t == null ? "absent" : t.getClass().getSimpleName());
        }
        return sb.append(" is not in Luxir format").toString();
      }
    }
    return null;
  }

  /**
   * Declares a FilterLeafReader subclass that does not change documents, terms or postings (it
   * only adds checks or doc values), so native execution may look through it to the segment.
   */
  public static void registerTransparentWrapper(Class<? extends org.apache.lucene.index.FilterLeafReader> wrapper) {
    NativeLeaf.registerTransparentWrapper(wrapper);
  }

  /** Exact count of a leaf's matches (its live docs only). */
  static long countLeaf(Supplier<MemorySegment> reader, LeafReaderContext ctx, byte[] query) {
    try (Arena arena = Arena.ofConfined()) {
      int leafMax = ctx.reader().maxDoc();
      NativeLeaf.Words live = NativeLeaf.live(ctx.reader().getLiveDocs(), leafMax);
      MemorySegment q = arena.allocate(query.length);
      MemorySegment.copy(query, 0, q, ValueLayout.JAVA_BYTE, 0, query.length);
      MemorySegment totalOut = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment liveWords = live == null ? MemorySegment.NULL : live.words();
      int rc = (int) LxlNative.TOP_K.invokeExact(reader.get(), q, (long) query.length,
          liveWords, live == null ? leafMax : live.count(),
          0, -Float.MAX_VALUE, 1, MemorySegment.NULL, MemorySegment.NULL, totalOut, MemorySegment.NULL);
      check(rc);
      return totalOut.get(ValueLayout.JAVA_LONG, 0);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  // ORs a segment's match words into the index-wide set at docBase, a word at a time.
  static void orInto(FixedBitSet global, MemorySegment leafBits, int docBase, int leafMax) {
    long[] g = global.getBits();
    int shift = docBase & 63;
    int base = docBase >>> 6;
    int nwords = FixedBitSet.bits2words(leafMax);
    // bulk copies in chunks: per-word segment access is slow until the JIT has compiled it
    long[] chunk = NativeLeaf.scratch(0);
    for (int start = 0; start < nwords; start += chunk.length) {
      int len = Math.min(chunk.length, nwords - start);
      MemorySegment.copy(leafBits, ValueLayout.JAVA_LONG, (long) start * Long.BYTES, chunk, 0, len);
      for (int i = 0; i < len; i++) {
        long word = chunk[i];
        if (word == 0) continue;
        int w = start + i;
        g[base + w] |= word << shift;
        if (shift != 0 && base + w + 1 < g.length) g[base + w + 1] |= word >>> (64 - shift);
      }
    }
  }

}
