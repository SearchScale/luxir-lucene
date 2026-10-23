// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.AutomatonQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PointInSetQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.luxir.lucene.LxlNative;

/**
 * Encodes a rewritten Lucene query for native execution, carrying the searcher's collection-wide
 * BM25 statistics so native scores equal Lucene's.
 *
 * <p>Supported: TermQuery, BooleanQuery (any occurs and minimumShouldMatch, not purely negative),
 * BoostQuery, ConstantScoreQuery, exact PhraseQuery, MatchAll/MatchNoDocsQuery, constant-score prefix, over fields scored by
 * BM25Similarity(1.2, 0.75); and, where the caller evaluates them ({@code ranges}), one-dimensional
 * point ranges (PointRangeQuery, alone or as IndexOrDocValuesQuery's index side) as constant-score
 * bitset clauses. Anything else returns null and the caller uses Lucene.
 *
 * <p>Format (little-endian): magic "LXQ1"; field count, then per field name, docCount,
 * sumTotalTermFreq, sumDocFreq; then the root node. Nodes: 1 term (field, term, docFreq, ttf), 2
 * boolean (msm, counts of must/should/mustNot/filter, then those clauses), 3 phrase (field, slop,
 * n, then n x (position, term, docFreq, ttf)), 4 boost (float, child), 5 constant (float, child),
 * 6 match nothing, 7 match all, 8 constant-score prefix (field, prefix), 9 constant-score bitset
 * clause (slot, score): the caller's per-segment bitset for {@link Encoded#ranges()}[slot], 10
 * numeric range (slot, lo, hi) over the caller's per-segment doc-values column [slot], 11
 * constant-score term set (field, n, then n sorted unique terms). Strings are an int length and
 * bytes.
 */
public final class LxlQuery {
  private static final int MAGIC = 0x3151584c; // "LXQ1"

  /**
   * An encoded query, the fields it reads, and the (field, prefix) pairs it expands natively
   * (which must not have matching overflow terms).
   */
  public record Encoded(
      byte[] bytes, Set<String> fields, List<Map.Entry<String, byte[]>> prefixes, List<Range> ranges,
      List<ColumnRange> columns, boolean fieldless) {}

  /**
   * A range over an int or long field's doc-values column (raw values, inclusive), which Luxir
   * plans like its own numeric ranges: verified per candidate behind a selective clause, or
   * materialized from the field's points, whose values are the column's plus {@code
   * pointsOffset} (2^31 for 4-byte points, 0 for 8-byte).
   */
  public record ColumnRange(String field, long lo, long hi, long pointsOffset) {}

  /**
   * A point range over {@code field}, inclusive, as the ordered int64s Luxir's points hold; or,
   * with {@code values}, exactly those points (a point set).
   */
  public record Range(String field, long lo, long hi, long[] values) {
    /** The caller's filter as a bitset clause ({@link FilterSlotQuery}): its bits are the filter's. */
    static final Range FILTER = new Range(null, 0, -1, null);

    Range(String field, long lo, long hi) {
      this(field, lo, hi, null);
    }

    boolean isFilter() {
      return this == FILTER;
    }
  }

  private final IndexSearcher searcher;
  private final boolean scores;
  private final boolean segmentTermStats;
  private final Buffer body = new Buffer();
  private final Map<String, CollectionStatistics> fields = new LinkedHashMap<>();
  private final List<Map.Entry<String, byte[]>> prefixes = new ArrayList<>();
  private final boolean allowRanges;
  // checking only whether the query can run natively: term sets' terms are not written
  private final boolean probe;
  private final List<Range> ranges = new ArrayList<>();
  private final List<ColumnRange> columns = new ArrayList<>();
  // a clause that can match docs without any of the query's postings fields (match all, ranges)
  private boolean fieldless;

  private LxlQuery(IndexSearcher searcher, boolean scores, boolean segmentTermStats, boolean allowRanges,
      boolean probe) {
    this.searcher = searcher;
    this.scores = scores;
    this.segmentTermStats = segmentTermStats;
    this.allowRanges = allowRanges;
    this.probe = probe;
  }

  /** Encodes {@code query} (already rewritten), or returns null if it cannot run natively. */
  public static Encoded encode(Query query, IndexSearcher searcher, float boost, boolean scores) throws IOException {
    return encode(query, searcher, boost, scores, false);
  }

  /**
   * As {@link #encode(Query, IndexSearcher, float, boolean)}; with {@code segmentTermStats} the
   * native side sums term statistics over the segments it searches (all of the searcher's), so no
   * per-segment dictionary lookups happen in Java.
   */
  static Encoded encode(Query query, IndexSearcher searcher, float boost, boolean scores, boolean segmentTermStats)
      throws IOException {
    return encode(query, searcher, boost, scores, segmentTermStats, false);
  }

  /** As above; with {@code allowRanges} point ranges become bitset clauses the caller fills. */
  static Encoded encode(Query query, IndexSearcher searcher, float boost, boolean scores, boolean segmentTermStats,
      boolean allowRanges) throws IOException {
    return encode(query, searcher, boost, scores, segmentTermStats, allowRanges, false);
  }

  /**
   * As encode, for whether the query can run natively and what it reads: the bytes leave out term
   * sets' terms, so they are not to be run.
   */
  static Encoded probe(Query query, IndexSearcher searcher, boolean scores, boolean segmentTermStats,
      boolean allowRanges) throws IOException {
    return encode(query, searcher, 1f, scores, segmentTermStats, allowRanges, true);
  }

  private static Encoded encode(Query query, IndexSearcher searcher, float boost, boolean scores,
      boolean segmentTermStats, boolean allowRanges, boolean probe) throws IOException {
    LxlQuery enc = new LxlQuery(searcher, scores, segmentTermStats, allowRanges, probe);
    if (boost != 1f) {
      enc.body.write(4);
      enc.f32(boost);
    }
    if (!enc.node(query)) return null;
    LxlQuery header = new LxlQuery(searcher, scores, segmentTermStats, false, false);
    header.i32(MAGIC);
    header.i32(enc.fields.size());
    for (Map.Entry<String, CollectionStatistics> e : enc.fields.entrySet()) {
      header.str(e.getKey().getBytes(StandardCharsets.UTF_8));
      CollectionStatistics cs = e.getValue();
      header.i64(cs == null ? 0 : cs.docCount());
      header.i64(cs == null ? 0 : cs.sumTotalTermFreq());
      header.i64(cs == null ? 0 : cs.sumDocFreq());
    }
    byte[] bytes = Arrays.copyOf(header.body.array(), header.body.size() + enc.body.size());
    System.arraycopy(enc.body.array(), 0, bytes, header.body.size(), enc.body.size());
    return new Encoded(bytes, new LinkedHashSet<>(enc.fields.keySet()), List.copyOf(enc.prefixes),
        List.copyOf(enc.ranges), List.copyOf(enc.columns), enc.fieldless);
  }

  private boolean node(Query q) throws IOException {
    if (q instanceof TermQuery tq) {
      Term t = tq.getTerm();
      if (!field(t.field()) || t.bytes().length > LxlNative.MAX_TERM) return false;
      if (scores && docsOnly(t.field())) {
        // Docs-only, norm-free terms (Luxir value strings) score the same in every matching doc
        // (freq 1, norm 1): Lucene's own similarity computes that score; Luxir matches the term.
        body.write(5);
        f32(docsOnlyScore(tq));
      }
      body.write(1);
      str(t.field().getBytes(StandardCharsets.UTF_8));
      bytes(t.bytes());
      // Rewrites such as fuzzy's blended scoring attach their own (adjusted) statistics; Lucene's
      // TermWeight uses them when they were built for this searcher.
      TermStates states = tq.getTermStates();
      if (states != null && states.wasBuiltFor(searcher.getTopReaderContext())) {
        termStats(t, states);
      } else {
        termStats(t);
      }
      return true;
    }
    if (q instanceof BooleanQuery bq) {
      List<List<Query>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
      for (BooleanClause c : bq.clauses()) {
        int g = switch (c.occur()) {
          case MUST -> 0;
          case SHOULD -> 1;
          case MUST_NOT -> 2;
          case FILTER -> 3;
        };
        groups.get(g).add(c.query());
      }
      if (groups.get(0).isEmpty() && groups.get(1).isEmpty() && groups.get(3).isEmpty()) return false;
      // Lucene counts a repeated SHOULD clause once per occurrence towards minimumShouldMatch;
      // Luxir removes duplicate clauses first.
      if (bq.getMinimumNumberShouldMatch() > 0 && Set.copyOf(groups.get(1)).size() != groups.get(1).size()) {
        return false;
      }
      body.write(2);
      i32(bq.getMinimumNumberShouldMatch());
      for (List<Query> g : groups) i32(g.size());
      for (List<Query> g : groups) {
        for (Query c : g) {
          if (!node(c)) return false;
        }
      }
      return true;
    }
    TermInSetQuery set = q instanceof TermInSetQuery t ? t : constantScoreTermSet(q);
    if (set != null) {
      // Lucene's constant-score rewrite of a term set (Solr's {!terms}): Luxir's term-set query
      // over the same sorted unique terms, scoring 1
      String f = set.getField();
      if (!field(f)) return false;
      long count = set.getTermsCount();
      if (count == 0) {
        body.write(6);
        return true;
      }
      // streamed straight from the query's prefix-coded terms; a failed node fails the whole encode
      body.write(11);
      str(f.getBytes(StandardCharsets.UTF_8));
      i32(Math.toIntExact(count));
      org.apache.lucene.util.BytesRefIterator it = set.getBytesRefIterator();
      for (BytesRef term = it.next(); term != null; term = it.next()) {
        if (term.length > LxlNative.MAX_TERM) return false; // kept outside Luxir's postings
        if (!probe) bytes(term);
      }
      return true;
    }
    AutomatonQuery automaton = constantScoreAutomaton(q);
    if (automaton != null && !(automaton instanceof PrefixQuery)) {
      // Lucene's constant-score rewrite of a wildcard/regexp: the union of the matching terms,
      // expanded through each segment's terms (Luxir's own DFA walk), scoring 1
      String f = automaton.getField();
      if (!field(f)) return false;
      java.util.TreeSet<BytesRef> terms = new java.util.TreeSet<>();
      for (org.apache.lucene.index.LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
        org.apache.lucene.index.Terms t = ctx.reader().terms(f);
        if (t == null) continue;
        org.apache.lucene.index.TermsEnum te = automaton.getCompiled().getTermsEnum(t);
        for (BytesRef term = te.next(); term != null; term = te.next()) {
          if (term.length > LxlNative.MAX_TERM) return false; // kept outside Luxir's postings
          terms.add(BytesRef.deepCopyOf(term));
          if (terms.size() > MAX_EXPANSION) return false;
        }
      }
      if (terms.isEmpty()) {
        body.write(6);
        return true;
      }
      // one native union of the terms' postings, scoring 1 (Luxir's TermInSetQuery)
      body.write(11);
      str(f.getBytes(StandardCharsets.UTF_8));
      i32(terms.size());
      for (BytesRef term : terms) bytes(term);
      return true;
    }
    PrefixQuery prefix = constantScorePrefix(q);
    if (prefix != null) {
      BytesRef p = prefix.getPrefix().bytes();
      if (p.length == 0 || p.length > LxlNative.MAX_TERM || !field(prefix.getField())) return false;
      body.write(8);
      str(prefix.getField().getBytes(StandardCharsets.UTF_8));
      bytes(p);
      prefixes.add(Map.entry(prefix.getField(), BytesRef.deepCopyOf(p).bytes));
      return true;
    }
    if (q instanceof PointInSetQuery ps && allowRanges && ps.getNumDims() == 1 && ps.getBytesPerDim() <= Long.BYTES) {
      // an exact point set: one bitset of the listed values
      List<long[]> vals = new ArrayList<>();
      for (byte[] packed : ps.getPackedPoints()) vals.add(new long[] {toLong(packed, ps.getBytesPerDim())});
      long[] v = vals.stream().mapToLong(a -> a[0]).toArray();
      ranges.add(new Range(ps.getField(), 0, -1, v));
      fieldless = true;
      body.write(9);
      i32(ranges.size() - 1);
      f32(1f); // constant score, as Lucene's point set query
      return true;
    }
    if (q instanceof IndexOrDocValuesQuery iodv && iodv.getIndexQuery() instanceof TermInSetQuery) {
      return node(iodv.getIndexQuery()); // both sides match the same docs
    }
    if (q instanceof IndexOrDocValuesQuery iodv && iodv.getIndexQuery() instanceof PointInSetQuery) {
      return node(iodv.getIndexQuery());
    }
    if (q instanceof IndexOrDocValuesQuery iodv) {
      // Both sides match the same docs (IndexOrDocValuesQuery's contract): for ints and longs the
      // doc values hold the raw values, so Luxir can verify the range from the column.
      if (allowRanges && iodv.getIndexQuery() instanceof PointRangeQuery pr && pr.getNumDims() == 1) {
        Class<?> owner = pr.getClass().getEnclosingClass();
        if (owner == org.apache.lucene.document.IntPoint.class || owner == org.apache.lucene.document.LongPoint.class) {
          boolean longs = owner == org.apache.lucene.document.LongPoint.class;
          long lo = longs ? org.apache.lucene.document.LongPoint.decodeDimension(pr.getLowerPoint(), 0)
              : org.apache.lucene.document.IntPoint.decodeDimension(pr.getLowerPoint(), 0);
          long hi = longs ? org.apache.lucene.document.LongPoint.decodeDimension(pr.getUpperPoint(), 0)
              : org.apache.lucene.document.IntPoint.decodeDimension(pr.getUpperPoint(), 0);
          columns.add(new ColumnRange(pr.getField(), lo, hi, longs ? 0 : 1L << 31));
          fieldless = true;
          if (scores) {
            body.write(5); // constant score 1, as Lucene's range queries score
            f32(1f);
          }
          body.write(10);
          i32(columns.size() - 1);
          i64(lo);
          i64(hi);
          return true;
        }
      }
      return node(iodv.getIndexQuery()); // same matches and constant score
    }
    if (q instanceof FilterSlotQuery) {
      if (!allowRanges) return false;
      ranges.add(Range.FILTER);
      fieldless = true;
      body.write(9);
      i32(ranges.size() - 1);
      f32(0f); // a FILTER clause: never scored
      return true;
    }
    if (q instanceof PointRangeQuery pr) {
      if (!allowRanges || pr.getNumDims() != 1 || pr.getBytesPerDim() > Long.BYTES) return false;
      int bytes = pr.getBytesPerDim();
      ranges.add(new Range(pr.getField(), toLong(pr.getLowerPoint(), bytes), toLong(pr.getUpperPoint(), bytes)));
      fieldless = true;
      body.write(9);
      i32(ranges.size() - 1);
      f32(1f); // Lucene's PointRangeQuery scores its boost (applied by an enclosing BoostQuery)
      return true;
    }
    if (q instanceof FieldExistsQuery fe && allowRanges && onePointDim(fe.getField())) {
      // Lucene rewrites a range spanning all of a point field's values to "has a value": for
      // point fields (whose docs with points are its docs with values) the full range
      ranges.add(new Range(fe.getField(), Long.MIN_VALUE, Long.MAX_VALUE));
      fieldless = true;
      body.write(9);
      i32(ranges.size() - 1);
      f32(1f);
      return true;
    }
    if (q instanceof MatchNoDocsQuery) {
      body.write(6);
      return true;
    }
    if (q instanceof MatchAllDocsQuery) {
      fieldless = true;
      if (scores) {
        // Lucene scores every doc 1; Luxir's match-all contributes 0 to a scored sum
        body.write(5);
        f32(1f);
      }
      body.write(7);
      return true;
    }
    if (q instanceof BoostQuery b) {
      body.write(4);
      f32(b.getBoost());
      return node(b.getQuery());
    }
    if (q instanceof ConstantScoreQuery c) {
      body.write(5);
      f32(1f);
      return node(c.getQuery());
    }
    if (q instanceof PhraseQuery pq) {
      Term[] terms = pq.getTerms();
      // Sloppy phrases match the same documents, but Lucene's sloppy frequency is a heuristic
      // that Luxir's matcher does not reproduce exactly (it can find a closer match), so their
      // scores can differ: leave them to Lucene.
      if (terms.length < 2 || pq.getSlop() != 0 || !field(pq.getField())) return false;
      int[] positions = pq.getPositions();
      body.write(3);
      str(pq.getField().getBytes(StandardCharsets.UTF_8));
      i32(pq.getSlop());
      i32(terms.length);
      for (int i = 0; i < terms.length; i++) {
        if (terms[i].bytes().length > LxlNative.MAX_TERM) return false;
        i32(positions[i]);
        bytes(terms[i].bytes());
        termStats(terms[i]);
      }
      return true;
    }
    return false;
  }

  /** Most terms a wildcard may expand to natively (more: Lucene runs it). */
  static final int MAX_EXPANSION = 10_000;

  /** The TermInSetQuery inside Lucene's constant-score rewrite, or null. */
  static TermInSetQuery constantScoreTermSet(Query q) {
    String name = q.getClass().getSimpleName();
    if (!name.equals("MultiTermQueryConstantScoreBlendedWrapper") && !name.equals("MultiTermQueryConstantScoreWrapper")) {
      return null;
    }
    TermInSetQuery[] found = new TermInSetQuery[1];
    q.visit(new QueryVisitor() {
      @Override
      public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
        return this;
      }

      @Override
      public void consumeTerms(Query query, org.apache.lucene.index.Term... terms) {
        if (query instanceof TermInSetQuery t) found[0] = t;
      }

      @Override
      public void consumeTermsMatching(Query query, String field, java.util.function.Supplier<ByteRunAutomaton> automaton) {
        if (query instanceof TermInSetQuery t) found[0] = t;
      }
    });
    return found[0];
  }

  /** The AutomatonQuery (wildcard, regexp...) inside Lucene's constant-score rewrite, or null. */
  static AutomatonQuery constantScoreAutomaton(Query q) {
    String name = q.getClass().getSimpleName();
    if (!name.equals("MultiTermQueryConstantScoreBlendedWrapper") && !name.equals("MultiTermQueryConstantScoreWrapper")) {
      return null;
    }
    AutomatonQuery[] found = new AutomatonQuery[1];
    q.visit(new QueryVisitor() {
      @Override
      public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
        return this;
      }

      @Override
      public void consumeTermsMatching(Query query, String field, java.util.function.Supplier<ByteRunAutomaton> automaton) {
        if (query instanceof AutomatonQuery a) found[0] = a;
      }
    });
    return found[0];
  }

  /**
   * The PrefixQuery inside Lucene's constant-score multi-term rewrite (a package-private wrapper
   * class), found through the query visitor, or null.
   */
  static PrefixQuery constantScorePrefix(Query q) {
    String name = q.getClass().getSimpleName();
    if (!name.equals("MultiTermQueryConstantScoreBlendedWrapper") && !name.equals("MultiTermQueryConstantScoreWrapper")) {
      return null;
    }
    PrefixQuery[] found = new PrefixQuery[1];
    q.visit(new QueryVisitor() {
      @Override
      public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
        return this;
      }

      @Override
      public void consumeTermsMatching(Query query, String field, java.util.function.Supplier<ByteRunAutomaton> automaton) {
        if (query instanceof PrefixQuery p) found[0] = p;
      }
    });
    return found[0];
  }

  /** Whether the field is one-dimensional points of at most 8 bytes, without norms or vectors. */
  private boolean onePointDim(String field) {
    for (org.apache.lucene.index.LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
      org.apache.lucene.index.FieldInfo fi = ctx.reader().getFieldInfos().fieldInfo(field);
      if (fi == null) continue;
      if (fi.getPointDimensionCount() != 1 || fi.getPointNumBytes() > Long.BYTES || fi.hasNorms()
          || fi.getVectorDimension() != 0) {
        return false;
      }
    }
    return true;
  }

  private final Map<String, Boolean> docsOnly = new java.util.HashMap<>();

  /** Whether the field is indexed docs-only without norms (in any segment that has it). */
  private boolean docsOnly(String field) {
    return docsOnly.computeIfAbsent(field, f -> {
      for (org.apache.lucene.index.LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
        org.apache.lucene.index.FieldInfo fi = ctx.reader().getFieldInfos().fieldInfo(f);
        if (fi != null) return fi.getIndexOptions() == org.apache.lucene.index.IndexOptions.DOCS && fi.omitsNorms();
      }
      return false;
    });
  }

  /** Lucene's score of a docs-only term in any matching doc (freq 1, norm 1), boost 1. */
  private float docsOnlyScore(TermQuery tq) throws IOException {
    Term t = tq.getTerm();
    TermStates states = tq.getTermStates();
    if (states == null || !states.wasBuiltFor(searcher.getTopReaderContext())) {
      states = TermStates.build(searcher, t, true);
    }
    if (states.docFreq() == 0) return 0f;
    TermStatistics ts = searcher.termStatistics(t, states.docFreq(), states.totalTermFreq());
    CollectionStatistics cs = searcher.collectionStatistics(t.field());
    if (ts == null || cs == null) return 0f;
    return searcher.getSimilarity().scorer(1f, cs, ts).score(1f, 1L);
  }

  /** Lucene's sortable point bytes as the order-preserving int64 Luxir's points index holds. */
  static long toLong(byte[] packed, int bytes) {
    long u = 0;
    for (int k = 0; k < bytes; k++) u = (u << 8) | (packed[k] & 0xFF);
    return bytes == Long.BYTES ? u ^ Long.MIN_VALUE : u;
  }

  /** Records a field's statistics; false if its similarity is not plain BM25. */
  private boolean field(String field) throws IOException {
    if (scores && !isDefaultBm25(searcher.getSimilarity(), field)) return false;
    if (!fields.containsKey(field)) fields.put(field, searcher.collectionStatistics(field));
    return true;
  }

  static boolean isDefaultBm25(Similarity sim, String field) {
    if (sim instanceof PerFieldSimilarityWrapper p) sim = p.get(field);
    return sim instanceof BM25Similarity b && b.getK1() == 1.2f && b.getB() == 0.75f;
  }

  private void termStats(Term t) throws IOException {
    if (scores && segmentTermStats) {
      i64(-1); // summed natively
      i64(-1);
      return;
    }
    termStats(t, scores ? TermStates.build(searcher, t, true) : null);
  }

  private void termStats(Term t, TermStates states) throws IOException {
    long df = 0, ttf = 0;
    if (scores) {
      if (states.docFreq() > 0) {
        TermStatistics ts = searcher.termStatistics(t, states.docFreq(), states.totalTermFreq());
        if (ts != null) {
          df = ts.docFreq();
          ttf = ts.totalTermFreq();
        }
      }
    }
    i64(df);
    i64(ttf);
  }

  /** A ByteArrayOutputStream without the per-call locking: an encoder is confined to one thread. */
  private static final class Buffer extends ByteArrayOutputStream {
    private byte[] array() {
      return buf;
    }

    private void grow(int extra) {
      if (count + extra > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, count + extra));
    }

    @Override
    public void write(int b) {
      grow(1);
      buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
      grow(len);
      System.arraycopy(b, off, buf, count, len);
      count += len;
    }
  }

  private void i32(int v) {
    body.write(v);
    body.write(v >>> 8);
    body.write(v >>> 16);
    body.write(v >>> 24);
  }

  private void i64(long v) {
    body.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array());
  }

  private void f32(float v) {
    body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array());
  }

  private void str(byte[] b) {
    i32(b.length);
    body.writeBytes(b);
  }

  private void bytes(BytesRef b) {
    i32(b.length);
    body.write(b.bytes, b.offset, b.length);
  }
}
