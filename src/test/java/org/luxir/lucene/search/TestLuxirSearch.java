// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.analysis.Analyzer;
import org.luxir.lucene.document.LuxirValueField;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.luxir.lucene.LxlNative;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.CleanerThreadFilter;
import org.luxir.lucene.codec.LuxirCodec;
import org.luxir.lucene.codec.LuxirTerms;

/**
 * The same random corpus indexed with Lucene's codec and with the Luxir codec: every native search
 * path must return Lucene's hits, scores and counts.
 */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirSearch extends LuceneTestCase {
  private static final String[] VOCAB = vocab(300);

  private static String[] vocab(int n) {
    String[] v = new String[n];
    for (int i = 0; i < n; i++) v[i] = "w" + Integer.toString(i, 36);
    return v;
  }

  // Zipf-like word choice so some terms are dense (block-max matters) and some rare.
  private static String word(Random r) {
    double u = r.nextDouble();
    return VOCAB[(int) Math.min(VOCAB.length - 1, Math.floor(Math.pow(VOCAB.length, u) - 1))];
  }

  private static String longWord(Random r) {
    // 2-byte UTF-8 characters: 130 of them make a 260-byte term
    return "\u00e9".repeat(128) + (char) ('a' + r.nextInt(3)) + (char) ('a' + r.nextInt(3));
  }

  private Directory dir(boolean mmap) throws IOException {
    return mmap ? new MMapDirectory(createTempDir()) : newDirectory();
  }

  // Both indexes must segment and merge identically so doc ids line up: deterministic flushes,
  // and either no merges or adjacent (LogDoc) merges run serially.
  private static IndexWriterConfig config(Analyzer a, Codec codec, int buffered, boolean merges) {
    IndexWriterConfig c = new IndexWriterConfig(a).setCodec(codec).setMaxBufferedDocs(buffered)
        .setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    if (merges) {
      LogDocMergePolicy mp = new LogDocMergePolicy();
      mp.setMergeFactor(3);
      c.setMergePolicy(mp).setMergeScheduler(new SerialMergeScheduler());
    } else {
      c.setMergePolicy(NoMergePolicy.INSTANCE);
    }
    return c;
  }

  public void testMatchesLucene() throws Exception {
    Random r = random();
    // Some runs build segments much larger than one native scoring window (4096 docs).
    boolean big = r.nextInt(3) == 0;
    int numDocs = big ? atLeast(20000) : atLeast(3000);
    int buffered = big ? numDocs : 200 + r.nextInt(1500);
    boolean mmap = r.nextBoolean();
    boolean merges = r.nextBoolean();
    Analyzer analyzer = new WhitespaceAnalyzer();
    try (Directory luxDir = dir(mmap);
        Directory lucDir = newDirectory();
        IndexWriter lux = new IndexWriter(luxDir, config(analyzer, new LuxirCodec(), buffered, merges));
        IndexWriter luc = new IndexWriter(lucDir, config(analyzer, Codec.getDefault(), buffered, merges))) {
      for (int i = 0; i < numDocs; i++) {
        StringBuilder sb = new StringBuilder();
        int len = 1 + r.nextInt(r.nextInt(10) == 0 ? 400 : 40);
        for (int j = 0; j < len; j++) sb.append(word(r)).append(' ');
        // Occasionally a term longer than Luxir's 255-byte limit (stored as an overflow term).
        if (r.nextInt(40) == 0) sb.append(longWord(r)).append(' ');
        Document d = new Document();
        d.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        d.add(new TextField("body", sb.toString(), Field.Store.NO));
        if (r.nextInt(4) == 0) d.add(new TextField("title", word(r) + " " + word(r), Field.Store.NO));
        lux.addDocument(d);
        luc.addDocument(d);
      }
      // deletes, applied identically
      for (int i = 0; i < numDocs / 20; i++) {
        Term t = new Term("id", Integer.toString(r.nextInt(numDocs)));
        lux.deleteDocuments(t);
        luc.deleteDocuments(t);
      }
      if (merges && r.nextInt(3) == 0) {
        int segs = 1 + r.nextInt(3);
        lux.forceMerge(segs);
        luc.forceMerge(segs);
      }
      lux.commit();
      luc.commit();
      try (DirectoryReader lr = DirectoryReader.open(luxDir);
          DirectoryReader cr = DirectoryReader.open(lucDir)) {
        assertEquals(lr.leaves().size(), cr.leaves().size());
        int nativeLeaves = 0;
        for (LeafReaderContext ctx : lr.leaves()) {
          if (ctx.reader().terms("body") instanceof LuxirTerms) nativeLeaves++;
        }
        assertEquals("body must be stored natively in every segment", lr.leaves().size(), nativeLeaves);
        IndexSearcher ls = new IndexSearcher(lr);
        IndexSearcher cs = new IndexSearcher(cr);
        ls.setQueryCache(null);
        cs.setQueryCache(null);
        int iters = atLeast(150);
        int ran = 0;
        for (int it = 0; it < iters; it++) {
          Query q = randomQuery(r);
          if (check(q, ls, cs)) ran++;
        }
        assertTrue("native path ran for only " + ran + " of " + iters, ran > iters * 0.6);
      }
    }
  }

  private static final LuxirValueField.Type KIND = LuxirValueField.Type.string(true, DocValuesType.SORTED, false);
  private static final LuxirValueField.Type CATS = LuxirValueField.Type.string(true, DocValuesType.SORTED_SET, false);
  private static final LuxirValueField.Type INT = LuxirValueField.Type.number(4, DocValuesType.NUMERIC, false);
  private static final LuxirValueField.Type LONG = LuxirValueField.Type.number(8, DocValuesType.NUMERIC, false);
  private static final LuxirValueField.Type FLOAT = LuxirValueField.Type.number(4, DocValuesType.NUMERIC, false);
  private static final LuxirValueField.Type DOUBLES =
      LuxirValueField.Type.number(8, DocValuesType.SORTED_NUMERIC, false);

  /**
   * Text with Luxir value fields (docs-only strings, numbers with points): term queries on the
   * strings, point ranges, match-all and filters run natively and must equal Lucene over Lucene's
   * own fields (Solr's encodings).
   */
  public void testValueFieldsMatchLucene() throws Exception {
    Random r = random();
    // some runs span several 4096-value column blocks per segment (block pruning in sorts)
    boolean big = r.nextInt(3) == 0;
    int numDocs = big ? atLeast(20000) : atLeast(4000);
    int buffered = big ? numDocs : 300 + r.nextInt(1500);
    boolean merges = r.nextBoolean();
    Analyzer analyzer = new WhitespaceAnalyzer();
    try (Directory luxDir = dir(r.nextBoolean());
        Directory lucDir = newDirectory();
        IndexWriter lux = new IndexWriter(luxDir, config(analyzer, new LuxirCodec(), buffered, merges));
        IndexWriter luc = new IndexWriter(lucDir, config(analyzer, Codec.getDefault(), buffered, merges))) {
      for (int i = 0; i < numDocs; i++) {
        Document a = new Document(), b = new Document();
        String id = Integer.toString(i);
        a.add(new StringField("id", id, Field.Store.YES));
        b.add(new StringField("id", id, Field.Store.YES));
        StringBuilder sb = new StringBuilder();
        int len = 1 + r.nextInt(30);
        for (int j = 0; j < len; j++) sb.append(word(r)).append(' ');
        a.add(new TextField("body", sb.toString(), Field.Store.NO));
        b.add(new TextField("body", sb.toString(), Field.Store.NO));
        if (r.nextInt(5) > 0) {
          String v = "k" + r.nextInt(r.nextBoolean() ? 3 : 40);
          a.add(new LuxirValueField("kind", KIND, v));
          b.add(new StringField("kind", v, Field.Store.NO));
          b.add(new SortedDocValuesField("kind", new BytesRef(v)));
        }
        int cats = r.nextInt(4);
        for (int j = 0; j < cats; j++) {
          String v = "c" + r.nextInt(200);
          a.add(new LuxirValueField("cats", CATS, v));
          b.add(new StringField("cats", v, Field.Store.NO));
          b.add(new SortedSetDocValuesField("cats", new BytesRef(v)));
        }
        if (r.nextInt(6) > 0) {
          int v = r.nextInt(400) - 200;
          a.add(new LuxirValueField("year", INT, v));
          b.add(new IntPoint("year", v));
          b.add(new NumericDocValuesField("year", v));
        }
        if (r.nextInt(6) > 0) {
          long v = r.nextInt(8) == 0 ? r.nextLong() : 1_300_000_000_000L + r.nextInt(1_000_000_000);
          a.add(new LuxirValueField("ts", LONG, v));
          b.add(new LongPoint("ts", v));
          b.add(new NumericDocValuesField("ts", v));
        }
        if (r.nextInt(6) > 0) {
          float v = r.nextInt(20) == 0 ? -0f : (r.nextFloat() - 0.3f) * 1000;
          a.add(new LuxirValueField("pop", FLOAT, v));
          b.add(new FloatPoint("pop", v));
          b.add(new NumericDocValuesField("pop", Float.floatToIntBits(v)));
        }
        int qs = r.nextInt(3);
        for (int j = 0; j < qs; j++) {
          double v = r.nextGaussian() * 100;
          a.add(new LuxirValueField("q", DOUBLES, v));
          b.add(new DoublePoint("q", v));
          b.add(new SortedNumericDocValuesField("q", NumericUtils.doubleToSortableLong(v)));
        }
        lux.addDocument(a);
        luc.addDocument(b);
      }
      for (int i = 0; i < numDocs / 20; i++) {
        Term t = new Term("id", Integer.toString(r.nextInt(numDocs)));
        lux.deleteDocuments(t);
        luc.deleteDocuments(t);
      }
      lux.commit();
      luc.commit();
      try (DirectoryReader lr = DirectoryReader.open(luxDir);
          DirectoryReader cr = DirectoryReader.open(lucDir)) {
        assertEquals(lr.leaves().size(), cr.leaves().size());
        IndexSearcher ls = new IndexSearcher(lr);
        IndexSearcher cs = new IndexSearcher(cr);
        ls.setQueryCache(null);
        cs.setQueryCache(null);
        int iters = atLeast(200);
        int ran = 0;
        for (int it = 0; it < iters; it++) {
          Query q = valueQuery(r, 0);
          List<Query> filters = new ArrayList<>();
          int nf = r.nextInt(3);
          for (int j = 0; j < nf; j++) filters.add(valueQuery(r, 1));
          if (checkFiltered(q, filters, ls, cs)) ran++;
          if (filters.isEmpty()) check(q, ls, cs);
          checkSorted(r, q, filters, ls, cs);
          checkFacets(r, q, filters, ls, cs);
        }
        assertTrue("native path ran for only " + ran + " of " + iters, ran > iters * 0.9);
      }
    }
  }

  private static Query valueQuery(Random r, int depth) {
    switch (r.nextInt(depth > 1 ? 7 : 9)) {
      case 0:
        return new TermQuery(new Term("kind", "k" + r.nextInt(r.nextBoolean() ? 3 : 45)));
      case 1:
        return new TermQuery(new Term("cats", "c" + r.nextInt(210)));
      case 2:
        return rangeQuery(r);
      case 3:
        return MatchAllDocsQuery.INSTANCE;
      case 4:
        return new TermQuery(new Term("body", word(r)));
      case 5:
        return new BoostQuery(rangeQuery(r), 0.5f + 3 * r.nextFloat());
      case 6:
        return new ConstantScoreQuery(r.nextBoolean() ? rangeQuery(r) : new TermQuery(new Term("kind", "k" + r.nextInt(3))));
      default: {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        int n = 2 + r.nextInt(3);
        boolean positive = false;
        for (int i = 0; i < n; i++) {
          BooleanClause.Occur o = BooleanClause.Occur.values()[r.nextInt(4)];
          if (o != BooleanClause.Occur.MUST_NOT) positive = true;
          b.add(valueQuery(r, depth + 1), o);
        }
        if (!positive) b.add(new TermQuery(new Term("body", word(r))), BooleanClause.Occur.SHOULD);
        return b.build();
      }
    }
  }

  private static Query rangeQuery(Random r) {
    if (r.nextInt(6) == 0) {
      // point sets (Solr's {!terms} on point fields): ints and longs, some values absent
      if (r.nextBoolean()) {
        int[] v = new int[1 + r.nextInt(40)];
        for (int i = 0; i < v.length; i++) v[i] = r.nextInt(400) - 220;
        return r.nextBoolean() ? IntPoint.newSetQuery("year", v)
            : new IndexOrDocValuesQuery(IntPoint.newSetQuery("year", v),
                NumericDocValuesField.newSlowSetQuery("year", java.util.Arrays.stream(v).asLongStream().toArray()));
      }
      long[] v = new long[1 + r.nextInt(40)];
      for (int i = 0; i < v.length; i++) v[i] = 1_300_000_000_000L + r.nextInt(1_000_000_000);
      return LongPoint.newSetQuery("ts", v);
    }
    switch (r.nextInt(5)) {
      case 0: {
        int lo = r.nextInt(400) - 220, hi = lo + r.nextInt(150);
        return IntPoint.newRangeQuery("year", lo, hi);
      }
      case 1: {
        int lo = r.nextInt(400) - 220, hi = lo + r.nextInt(150);
        return new IndexOrDocValuesQuery(IntPoint.newRangeQuery("year", lo, hi),
            NumericDocValuesField.newSlowRangeQuery("year", lo, hi));
      }
      case 2: {
        long lo = 1_300_000_000_000L + r.nextInt(1_000_000_000), hi = r.nextInt(5) == 0 ? Long.MAX_VALUE : lo + r.nextInt(300_000_000);
        return new IndexOrDocValuesQuery(LongPoint.newRangeQuery("ts", lo, hi),
            NumericDocValuesField.newSlowRangeQuery("ts", lo, hi));
      }
      case 3: {
        float lo = (r.nextFloat() - 0.4f) * 1000;
        return FloatPoint.newRangeQuery("pop", r.nextInt(8) == 0 ? Float.NEGATIVE_INFINITY : lo, lo + r.nextFloat() * 300);
      }
      default: {
        double lo = r.nextGaussian() * 100;
        return DoublePoint.newRangeQuery("q", lo, lo + r.nextDouble() * 80);
      }
    }
  }

  /** The query with filters, natively, against Lucene's search of the same filtered query. */
  private boolean checkFiltered(Query q, List<Query> filters, IndexSearcher ls, IndexSearcher cs) throws IOException {
    Query full = LuxirSearch.withFilters(q, filters);
    String info = full.toString();
    TopDocs expected = cs.search(full, new TopScoreDocCollectorManager(10, Integer.MAX_VALUE));
    LuxirSearch.Result res = LuxirSearch.topK(ls, q, filters, 10, Integer.MAX_VALUE, null, true);
    if (res == null) return false;
    assertEquals(info, expected.totalHits.value(), res.totalHits());
    ScoreDoc[] got = toScoreDocs(res);
    assertSameHits(info + " (filtered topK)", expected.scoreDocs, got);
    assertSameTieOrder(info, expected.scoreDocs, got);
    TopDocs all = cs.search(full, new TopScoreDocCollectorManager(Math.max(1, (int) expected.totalHits.value()), Integer.MAX_VALUE));
    FixedBitSet want = new FixedBitSet(Math.max(1, cs.getIndexReader().maxDoc()));
    for (ScoreDoc sd : all.scoreDocs) want.set(sd.doc);
    assertEquals(info, want, res.matches());
    return true;
  }

  /** A field sort, natively, must give exactly Lucene's order (ties by doc id). */
  private void checkSorted(Random r, Query q, List<Query> filters, IndexSearcher ls, IndexSearcher cs)
      throws IOException {
    boolean rev = r.nextBoolean(), withMissing = r.nextBoolean(), low = r.nextBoolean();
    SortField sf = switch (r.nextInt(4)) {
      case 0 -> new SortField("year", SortField.Type.INT, rev,
          withMissing ? (low ? Integer.MIN_VALUE : Integer.MAX_VALUE) : null);
      case 1 -> new SortField("ts", SortField.Type.LONG, rev, withMissing ? (low ? Long.MIN_VALUE : Long.MAX_VALUE) : null);
      case 2 -> new SortField("pop", SortField.Type.FLOAT, rev,
          withMissing ? (low ? Float.NEGATIVE_INFINITY : 7.5f) : null);
      default -> new SortField("kind", SortField.Type.STRING, rev,
          withMissing ? (low ? SortField.STRING_FIRST : SortField.STRING_LAST) : null);
    };
    int k = 1 + r.nextInt(20);
    Query full = LuxirSearch.withFilters(q, filters);
    String info = full + " sort " + sf;
    TopDocs expected = cs.search(full, new TopFieldCollectorManager(new Sort(sf), k, Integer.MAX_VALUE));
    LuxirSearch.Result res = LuxirSearch.topKSorted(ls, q, filters, sf, k, null, false);
    assertNotNull(info + ": " + LuxirSearch.whyNotNative(ls, q, filters), res);
    assertEquals(info, expected.totalHits.value(), res.totalHits());
    assertEquals(info, expected.scoreDocs.length, res.docs().length);
    for (int i = 0; i < res.docs().length; i++) assertEquals(info + " rank " + i, expected.scoreDocs[i].doc, res.docs()[i]);
  }

  /** Native facet counts over a query's matches must equal counting Lucene's doc values. */
  private void checkFacets(Random r, Query q, List<Query> filters, IndexSearcher ls, IndexSearcher cs)
      throws IOException {
    LuxirSearch.Result res = LuxirSearch.topK(ls, q, filters, 0, Integer.MAX_VALUE, null, true);
    if (res == null) return;
    FixedBitSet m = res.matches();
    String info = LuxirSearch.withFilters(q, filters).toString();
    List<LeafReaderContext> lux = ls.getIndexReader().leaves(), luc = cs.getIndexReader().leaves();
    for (int i = 0; i < lux.size(); i++) {
      LeafReaderContext a = lux.get(i), b = luc.get(i);
      for (String f : new String[] {"kind", "cats"}) {
        SortedSetDocValues dv = DocValues.getSortedSet(b.reader(), f);
        int n = (int) dv.getValueCount();
        int[] want = new int[n + 1];
        for (int d = 0; d < b.reader().maxDoc(); d++) {
          if (!m.get(b.docBase + d)) continue;
          if (dv.advanceExact(d)) {
            for (int c = 0; c < dv.docValueCount(); c++) want[(int) dv.nextOrd() + 1]++;
          } else {
            want[0]++;
          }
        }
        LuxirFacets.OrdCounts got = LuxirFacets.sparseOrdCounts(a, f, m, n);
        int[] dense = new int[n + 1];
        dense[0] = got.missing();
        for (int o = 0; o < got.ords().length; o++) dense[got.ords()[o] + 1] = got.counts()[o];
        assertArrayEquals(info + " " + f + " seg " + i, want, dense);
      }
      for (String f : new String[] {"year", "ts", "pop"}) {
        NumericDocValues dv = DocValues.getNumeric(b.reader(), f);
        java.util.Map<Long, Integer> want = new java.util.HashMap<>();
        java.util.Map<Long, Integer> first = new java.util.HashMap<>();
        int missing = 0;
        // listed in doc order (a value can repeat) if more than half of the first `sample` docs
        // with a value add a new one
        int sample = r.nextBoolean() ? 0 : 1 + r.nextInt(40), seen = 0;
        boolean listed = false;
        for (int d = 0; d < b.reader().maxDoc(); d++) {
          if (!m.get(b.docBase + d)) continue;
          if (dv.advanceExact(d)) {
            want.merge(dv.longValue(), 1, Integer::sum);
            first.putIfAbsent(dv.longValue(), b.docBase + d);
            if (++seen == sample && want.size() * 2 > seen) listed = true;
          } else {
            missing++;
          }
        }
        LuxirFacets.ValueCounts got = LuxirFacets.valueCounts(a, f, m, sample);
        java.util.Map<Long, Integer> gotCounts = new java.util.HashMap<>();
        java.util.Map<Long, Integer> gotFirst = new java.util.HashMap<>();
        for (int j = 0; j < got.values().length; j++) {
          if (j > 0) assertNotEquals(info + " " + f + " repeat merged", got.values()[j - 1], got.values()[j]);
          gotCounts.merge(got.values()[j], got.counts()[j], Integer::sum);
          gotFirst.merge(got.values()[j], got.firstDocs()[j], Math::min);
        }
        if (!listed) assertEquals(info + " " + f + " distinct", gotCounts.size(), got.values().length);
        assertEquals(info + " " + f, missing, got.missing());
        assertEquals(info + " " + f, want, gotCounts);
        assertEquals(info + " " + f + " first doc", first, gotFirst);
      }
    }
    // buckets over year (ints) and pop (floats, by Float.compare order), counted with Lucene's ranges
    int lo = r.nextInt(300) - 200, gap = 1 + r.nextInt(60);
    long[] los = new long[4], his = new long[4];
    long[] want = new long[4];
    for (int j = 0; j < 4; j++) {
      los[j] = lo + j * gap;
      his[j] = lo + (j + 1) * gap - 1;
      want[j] = countIn(cs, IntPoint.newRangeQuery("year", (int) los[j], (int) his[j]), m);
    }
    assertArrayEquals(info, want, toLongs(LuxirFacets.rangeCounts(ls, "year", LxlNative.SORT_LONG, m, los, his)));
    float flo = (r.nextFloat() - 0.4f) * 1000, fgap = 1 + r.nextFloat() * 200;
    for (int j = 0; j < 4; j++) {
      float a = flo + j * fgap, b = Math.nextDown(flo + (j + 1) * fgap);
      los[j] = floatKey(a);
      his[j] = floatKey(b);
      want[j] = countIn(cs, FloatPoint.newRangeQuery("pop", a, b), m);
    }
    assertArrayEquals(info, want, toLongs(LuxirFacets.rangeCounts(ls, "pop", LxlNative.SORT_FLOAT_BITS, m, los, his)));
  }

  private static long floatKey(float f) {
    int bits = Float.floatToIntBits(f);
    return bits ^ ((bits >> 31) & 0x7fffffff);
  }

  private static long[] toLongs(int[] a) {
    long[] out = new long[a.length];
    for (int i = 0; i < a.length; i++) out[i] = a[i];
    return out;
  }

  private static long countIn(IndexSearcher s, Query q, FixedBitSet m) throws IOException {
    TopDocs all = s.search(q, new TopScoreDocCollectorManager(Math.max(1, s.getIndexReader().maxDoc()), Integer.MAX_VALUE));
    long n = 0;
    for (ScoreDoc sd : all.scoreDocs) if (m.get(sd.doc)) n++;
    return n;
  }

  /** Where Lucene's top hits all score the same, ties are by doc id: the docs must be the same. */
  private static void assertSameTieOrder(String info, ScoreDoc[] expected, ScoreDoc[] actual) {
    if (expected.length == 0) return;
    for (ScoreDoc sd : expected) if (sd.score != expected[0].score) return;
    for (int i = 0; i < expected.length; i++) assertEquals(info + " rank " + i, expected[i].doc, actual[i].doc);
  }

  /** Returns whether the one-call native path ran. */
  private boolean check(Query q, IndexSearcher ls, IndexSearcher cs) throws IOException {
    String info = q.toString();
    // exact counts and all matches
    TopDocs expected = cs.search(q, new TopScoreDocCollectorManager(10, Integer.MAX_VALUE));
    assertEquals(info, expected.totalHits.value(), ls.count(new LuxirAcceleratedQuery(q)));

    // windowed native bulk scoring through Lucene's own collectors: exact and pruned
    TopDocs complete = ls.search(new LuxirAcceleratedQuery(q), new TopScoreDocCollectorManager(10, Integer.MAX_VALUE));
    assertEquals(info, expected.totalHits.value(), complete.totalHits.value());
    assertSameHits(info + " (complete)", expected.scoreDocs, complete.scoreDocs);
    TopDocs pruned = ls.search(new LuxirAcceleratedQuery(q), new TopScoreDocCollectorManager(10, 10));
    assertSameHits(info + " (top scores)", expected.scoreDocs, pruned.scoreDocs);

    // one-call top-k + exact count + match set (null: shape not supported natively)
    LuxirSearch.Result res = LuxirSearch.topK(ls, q, 10, null, true);
    if (res == null) return false;
    assertEquals(info, expected.totalHits.value(), res.totalHits());
    ScoreDoc[] got = new ScoreDoc[res.docs().length];
    for (int i = 0; i < got.length; i++) got[i] = new ScoreDoc(res.docs()[i], res.scores()[i]);
    assertSameHits(info + " (topK)", expected.scoreDocs, got);
    TopDocs all = cs.search(q, new TopScoreDocCollectorManager(Math.max(1, (int) expected.totalHits.value()), Integer.MAX_VALUE));
    FixedBitSet want = new FixedBitSet(Math.max(1, cs.getIndexReader().maxDoc()));
    for (ScoreDoc sd : all.scoreDocs) want.set(sd.doc);
    assertEquals(info, want, res.matches());
    assertTrue(info, res.exactCount());

    // minExactCount: exact below the threshold, a lower bound of at least the threshold above it
    int threshold = 1 + random().nextInt(50);
    LuxirSearch.Result bounded = LuxirSearch.topK(ls, q, 10, threshold, null, false);
    long actual = expected.totalHits.value();
    assertSameHits(info + " (bounded)", expected.scoreDocs, toScoreDocs(bounded));
    if (bounded.exactCount()) {
      assertEquals(info, actual, bounded.totalHits());
    } else {
      assertTrue(info, bounded.totalHits() >= Math.max(threshold, 10) && bounded.totalHits() <= actual);
    }
    return true;
  }

  private static ScoreDoc[] toScoreDocs(LuxirSearch.Result r) {
    ScoreDoc[] out = new ScoreDoc[r.docs().length];
    for (int i = 0; i < out.length; i++) out[i] = new ScoreDoc(r.docs()[i], r.scores()[i]);
    return out;
  }

  /** Same docs and scores in order; docs whose scores tie within float noise may swap. */
  private static void assertSameHits(String info, ScoreDoc[] expected, ScoreDoc[] actual) {
    assertEquals(info, expected.length, actual.length);
    for (int i = 0; i < expected.length; i++) {
      float e = expected[i].score, a = actual[i].score;
      assertEquals(info + " rank " + i, e, a, Math.max(1e-5f, Math.abs(e) * 1e-5f));
      // A different doc at a rank is a near-tie (its score already matched within float noise:
      // boolean sums may differ by an ulp, which can reorder docs whose scores almost tie).
    }
  }

  private Query randomQuery(Random r) {
    if (r.nextInt(12) == 0) {
      // Long terms: queries on them run in Lucene; expansions see them through the merged terms.
      return r.nextBoolean()
          ? new TermQuery(new Term("body", longWord(r)))
          : new PrefixQuery(new Term("body", "\u00e9\u00e9"));
    }
    if (r.nextInt(5) == 0) {
      // Multi-term queries: Lucene expands them through Terms.intersect (native DFA walk).
      String w = word(r);
      switch (r.nextInt(5)) {
        case 4: {
          // a term set (Solr's {!terms}): some words, one perhaps absent, one perhaps alone
          List<BytesRef> set = new ArrayList<>();
          for (int i = r.nextInt(3) == 0 ? 1 : 2 + r.nextInt(30); i > 0; i--) set.add(new BytesRef(word(r)));
          if (r.nextBoolean()) set.add(new BytesRef("nosuchword"));
          return new TermInSetQuery("body", set);
        }
        case 0:
          return new PrefixQuery(new Term("body", w.substring(0, Math.min(w.length(), 2))));
        case 1:
          return new WildcardQuery(new Term("body", "w?" + w.charAt(w.length() - 1) + "*"));
        case 2:
          return new RegexpQuery(new Term("body", "w[0-9a-c][a-z0-9]?"));
        default:
          return new FuzzyQuery(new Term("body", w), 1 + r.nextInt(2));
      }
    }
    switch (r.nextInt(7)) {
      case 0:
        return new TermQuery(new Term("body", word(r)));
      case 1:
      case 2: {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        int n = 2 + r.nextInt(4);
        boolean positive = false;
        for (int i = 0; i < n; i++) {
          BooleanClause.Occur o = BooleanClause.Occur.values()[r.nextInt(4)];
          if (o != BooleanClause.Occur.MUST_NOT) positive = true;
          String field = r.nextInt(6) == 0 ? "title" : "body";
          b.add(new TermQuery(new Term(field, word(r))), o);
        }
        if (!positive) b.add(new TermQuery(new Term("body", word(r))), BooleanClause.Occur.SHOULD);
        if (r.nextInt(4) == 0) b.setMinimumNumberShouldMatch(1 + r.nextInt(2));
        return b.build();
      }
      case 3:
        return new PhraseQuery("body", word(r), word(r));
      case 4:
        return new PhraseQuery(r.nextInt(3), "body", word(r), word(r));
      case 5:
        return new BoostQuery(new TermQuery(new Term("body", word(r))), 0.5f + 3 * r.nextFloat());
      default: {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        if (r.nextBoolean()) {
          String w = word(r);
          b.add(new PrefixQuery(new Term("body", w.substring(0, Math.min(2, w.length())))),
              r.nextBoolean() ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
        }
        b.add(new TermQuery(new Term("body", word(r))), BooleanClause.Occur.MUST);
        b.add(new ConstantScoreQuery(new TermQuery(new Term("body", word(r)))), BooleanClause.Occur.SHOULD);
        b.add(new PhraseQuery("body", word(r), word(r)), BooleanClause.Occur.SHOULD);
        return b.build();
      }
    }
  }
}
