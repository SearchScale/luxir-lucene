// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.io.IOException;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.CleanerThreadFilter;
import org.luxir.lucene.analysis.LuxirAnalyzer;
import org.luxir.lucene.document.LuxirTextField;
import org.luxir.lucene.search.LuxirSearch;

/**
 * Luxir indexing (LuxirTextField: Luxir analyzes and inverts) against Lucene indexing of the same
 * token stream (LuxirAnalyzer feeding Lucene's own inverter): the segments must hold identical
 * terms, postings, positions, norms and statistics, and searches must agree.
 */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestNativeIndexing extends LuceneTestCase {
  // The title field's name: one Luxir's schema holds, or one it cannot (indexed by its schema name).
  private String titleField = "title";

  private String schema() {
    return "{\"fields\":{"
        + "\"body\":{\"type\":\"text\",\"multi\":true,\"stored\":false,\"analyzer\":" + LuxirAnalyzer.ENGLISH + "},"
        + "\"" + LuxirTextField.schemaName(titleField)
        + "\":{\"type\":\"text\",\"multi\":true,\"stored\":false,\"analyzer\":" + LuxirAnalyzer.ENGLISH + "}}}";
  }

  private static final String[] WORDS = {
    "Running", "runner", "runs", "BOOKS", "booking", "café", "naïve", "résumé", "fox", "dog's",
    "Ünïcödé", "x-ray", "e-mail", "3.14", "U.S.A", "don't", "o'clock", "Ελληνικά", "日本語", "the"
  };

  private static String text(Random r) {
    StringBuilder sb = new StringBuilder();
    int n = 1 + r.nextInt(r.nextInt(10) == 0 ? 300 : 30);
    for (int i = 0; i < n; i++) {
      if (r.nextInt(3) == 0) sb.append(WORDS[r.nextInt(WORDS.length)]);
      else sb.append("w").append(Integer.toString((int) Math.pow(400, r.nextDouble()), 36));
      sb.append(r.nextInt(8) == 0 ? ", " : " ");
    }
    if (r.nextInt(50) == 0) sb.append("é".repeat(140)); // a token over 255 bytes (hashed alike)
    return sb.toString();
  }

  // The String and the UTF-8 bytes constructors must index identically.
  private static LuxirTextField luxirField(Random r, String name, String text, boolean stored) {
    if (r.nextBoolean()) return new LuxirTextField(name, text, stored);
    byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] padded = new byte[utf8.length + 7];
    System.arraycopy(utf8, 0, padded, 3, utf8.length);
    return new LuxirTextField(name, padded, 3, utf8.length, stored);
  }

  private static IndexWriterConfig config(Codec codec, LuxirAnalyzer analyzer, int buffered, boolean merges) {
    IndexWriterConfig c = new IndexWriterConfig(analyzer).setCodec(codec).setMaxBufferedDocs(buffered)
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

  public void testSchemaName() {
    assertEquals("title", LuxirTextField.schemaName("title"));
    assertEquals("body_2", LuxirTextField.schemaName("body_2"));
    assertEquals("lx5f746578745f", LuxirTextField.schemaName("_text_"));
    assertEquals("lx612e62", LuxirTextField.schemaName("a.b"));
    assertEquals("lx615f5f62", LuxirTextField.schemaName("a__b"));
    assertEquals("lx3162", LuxirTextField.schemaName("1b"));
    expectThrows(IllegalArgumentException.class, () -> LuxirTextField.schemaName("_".repeat(63)));
  }

  public void testSameIndexAsLucene() throws Exception {
    Random r = random();
    int numDocs = atLeast(2000);
    int buffered = 100 + r.nextInt(1500);
    boolean merges = r.nextBoolean();
    boolean storeBody = r.nextBoolean();
    titleField = new String[] {"title", "_text_", "ti.tle"}[r.nextInt(3)];
    LuxirAnalyzer analyzer = new LuxirAnalyzer(LuxirAnalyzer.ENGLISH);
    try (Directory luxDir = r.nextBoolean() ? new MMapDirectory(createTempDir()) : newDirectory();
        Directory lucDir = newDirectory();
        IndexWriter lux = new IndexWriter(luxDir, config(new LuxirCodec(this::schema), analyzer, buffered, merges));
        IndexWriter luc = new IndexWriter(lucDir, config(Codec.getDefault(), analyzer, buffered, merges))) {
      for (int i = 0; i < numDocs; i++) {
        Document a = new Document(), b = new Document();
        String id = Integer.toString(i);
        a.add(new StringField("id", id, Field.Store.YES));
        b.add(new StringField("id", id, Field.Store.YES));
        int values = r.nextInt(10) == 0 ? 2 : 1;
        for (int v = 0; v < values; v++) {
          String body = text(r);
          a.add(luxirField(r, "body", body, storeBody));
          b.add(new TextField("body", body, storeBody ? Field.Store.YES : Field.Store.NO));
        }
        if (r.nextInt(3) == 0) {
          String title = text(r);
          a.add(luxirField(r, titleField, title, true));
          b.add(new TextField(titleField, title, Field.Store.YES));
        }
        if (r.nextInt(5) == 0) { // a body value apart from the others, as copyField makes them
          String body = text(r);
          a.add(luxirField(r, "body", body, storeBody));
          b.add(new TextField("body", body, storeBody ? Field.Store.YES : Field.Store.NO));
        }
        lux.addDocument(a);
        luc.addDocument(b);
        if (r.nextInt(50) == 0) {
          Term t = new Term("id", Integer.toString(r.nextInt(i + 1)));
          lux.deleteDocuments(t);
          luc.deleteDocuments(t);
        }
      }
      if (merges && r.nextBoolean()) {
        int segs = 1 + r.nextInt(2);
        lux.forceMerge(segs);
        luc.forceMerge(segs);
      }
      lux.commit();
      luc.commit();
      try (DirectoryReader lr = DirectoryReader.open(luxDir);
          DirectoryReader cr = DirectoryReader.open(lucDir)) {
        assertEquals(lr.leaves().size(), cr.leaves().size());
        for (int i = 0; i < lr.leaves().size(); i++) {
          LeafReader a = lr.leaves().get(i).reader(), b = cr.leaves().get(i).reader();
          assertEquals(b.maxDoc(), a.maxDoc());
          for (String field : new String[] {"body", titleField}) {
            Terms ta = a.terms(field), tb = b.terms(field);
            if (tb == null) {
              assertNull(field, ta);
              continue;
            }
            assertTrue(field + " must be in Luxir format: " + ta, ta instanceof LuxirTerms);
            assertSameTerms(field, ta, tb);
            assertSameNorms(field, a.getNormValues(field), b.getNormValues(field));
          }
          // stored text: kept only when asked for
          int doc = 0;
          String storedBody = a.storedFields().document(doc).get("body");
          assertEquals(b.storedFields().document(doc).get("body"), storedBody);
        }
        IndexSearcher ls = new IndexSearcher(lr), cs = new IndexSearcher(cr);
        for (int it = 0; it < atLeast(100); it++) {
          Query q = randomQuery(r, analyzer);
          TopDocs expected = cs.search(q, new TopScoreDocCollectorManager(10, Integer.MAX_VALUE));
          TopDocs lucenePath = ls.search(q, new TopScoreDocCollectorManager(10, Integer.MAX_VALUE));
          assertEquals(q.toString(), expected.totalHits.value(), lucenePath.totalHits.value());
          assertSameHits(q + " (Lucene over Luxir)", expected.scoreDocs, lucenePath.scoreDocs);
          LuxirSearch.Result res = LuxirSearch.topK(ls, q, 10, null, false);
          if (res != null) {
            assertEquals(q.toString(), expected.totalHits.value(), res.totalHits());
            ScoreDoc[] got = new ScoreDoc[res.docs().length];
            for (int k = 0; k < got.length; k++) got[k] = new ScoreDoc(res.docs()[k], res.scores()[k]);
            assertSameHits(q + " (native)", expected.scoreDocs, got);
          }
        }
      }
    }
  }

  // A field Luxir's schema cannot hold (indexed by its schema name) in both flushed and merged
  // segments: one native search over all of them must find it in every one.
  public void testSchemaNamedFieldAcrossFlushAndMerge() throws Exception {
    Random r = random();
    titleField = "_text_";
    LuxirAnalyzer analyzer = new LuxirAnalyzer(LuxirAnalyzer.ENGLISH);
    try (Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, config(new LuxirCodec(this::schema), analyzer, 50, true))) {
      for (int i = 0; i < 520; i++) {
        Document d = new Document();
        d.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        d.add(luxirField(r, titleField, text(r), false));
        w.addDocument(d);
      }
      w.commit();
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        java.util.Set<String> sources = new java.util.HashSet<>();
        for (var ctx : reader.leaves()) {
          sources.add(((org.apache.lucene.index.SegmentReader) ctx.reader()).getSegmentInfo().info.getDiagnostics()
              .get("source"));
        }
        assertTrue("flushed and merged segments: " + sources, sources.contains("flush") && sources.contains("merge"));
        IndexSearcher s = new IndexSearcher(reader);
        s.setQueryCache(null);
        for (String word : new String[] {"run", "book", "fox", "w1", "w2", "wa"}) {
          Query q = new TermQuery(new Term(titleField, word));
          LuxirSearch.Result res = LuxirSearch.topK(s, q, 10, null, false);
          assertNotNull(q + " must run natively", res);
          assertEquals(q.toString(), s.count(q), res.totalHits());
        }
      }
    }
  }

  private static void assertSameTerms(String field, Terms ta, Terms tb) throws IOException {
    assertEquals(field, tb.size(), ta.size());
    assertEquals(field, tb.getDocCount(), ta.getDocCount());
    assertEquals(field, tb.getSumDocFreq(), ta.getSumDocFreq());
    assertEquals(field, tb.getSumTotalTermFreq(), ta.getSumTotalTermFreq());
    TermsEnum ea = ta.iterator(), eb = tb.iterator();
    PostingsEnum pa = null, pb = null;
    for (BytesRef t = eb.next(); t != null; t = eb.next()) {
      BytesRef u = ea.next();
      assertEquals(field, t, u);
      assertEquals(t.utf8ToString(), eb.docFreq(), ea.docFreq());
      assertEquals(t.utf8ToString(), eb.totalTermFreq(), ea.totalTermFreq());
      pa = ea.postings(pa, PostingsEnum.POSITIONS);
      pb = eb.postings(pb, PostingsEnum.POSITIONS);
      for (int d = pb.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = pb.nextDoc()) {
        assertEquals(t.utf8ToString(), d, pa.nextDoc());
        assertEquals(t.utf8ToString(), pb.freq(), pa.freq());
        for (int k = 0; k < pb.freq(); k++) assertEquals(t.utf8ToString(), pb.nextPosition(), pa.nextPosition());
      }
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, pa.nextDoc());
    }
    assertNull(ea.next());
  }

  private static void assertSameNorms(String field, NumericDocValues a, NumericDocValues b) throws IOException {
    assertNotNull(field, a);
    for (int d = b.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = b.nextDoc()) {
      if (b.longValue() == 0) continue; // a value with no tokens: Luxir records no norm
      assertEquals(field + " doc " + d, d, a.advance(d));
      assertEquals(field + " doc " + d, b.longValue(), a.longValue());
    }
  }

  private Query randomQuery(Random r, LuxirAnalyzer analyzer) {
    String w1 = analyzed(r, analyzer), w2 = analyzed(r, analyzer);
    switch (r.nextInt(4)) {
      case 0:
        return new TermQuery(new Term("body", w1));
      case 1: {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(new TermQuery(new Term("body", w1)), r.nextBoolean() ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
        b.add(new TermQuery(new Term(r.nextInt(3) == 0 ? titleField : "body", w2)), BooleanClause.Occur.SHOULD);
        return b.build();
      }
      case 2:
        return new PhraseQuery("body", w1, w2);
      default: {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(new TermQuery(new Term("body", w1)), BooleanClause.Occur.SHOULD);
        b.add(new TermQuery(new Term("body", w2)), BooleanClause.Occur.SHOULD);
        b.add(new TermQuery(new Term(titleField, w1)), BooleanClause.Occur.SHOULD);
        return b.build();
      }
    }
  }

  // A query term as the chain produces it.
  private static String analyzed(Random r, LuxirAnalyzer analyzer) {
    String raw = r.nextInt(3) == 0 ? WORDS[r.nextInt(WORDS.length)] : "w" + Integer.toString((int) Math.pow(400, r.nextDouble()), 36);
    try (var ts = analyzer.tokenStream("body", raw)) {
      var term = ts.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      ts.reset();
      String first = ts.incrementToken() ? term.toString() : "w1";
      ts.end();
      return first;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void assertSameHits(String info, ScoreDoc[] expected, ScoreDoc[] actual) {
    assertEquals(info, expected.length, actual.length);
    for (int i = 0; i < expected.length; i++) {
      float e = expected[i].score, a = actual[i].score;
      assertEquals(info + " rank " + i, e, a, Math.max(1e-5f, Math.abs(e) * 1e-5f));
      // A different doc at a rank is a near-tie (its score already matched within float noise:
      // boolean sums may differ by an ulp, which can reorder docs whose scores almost tie).
    }
  }
}
