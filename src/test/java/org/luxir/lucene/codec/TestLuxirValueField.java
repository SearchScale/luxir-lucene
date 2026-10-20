// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.luxir.lucene.CleanerThreadFilter;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Luxir value fields (strings and numbers indexed natively, Lucene's chain buffering nothing)
 * against the same values indexed through Lucene's own fields, with Solr's doc-values encodings:
 * doc values, stored values, terms, term and range queries and sorting must agree, through
 * updates, deletes, flushes and merges.
 */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirValueField extends LuceneTestCase {
  private static final LuxirValueField.Type KIND = LuxirValueField.Type.string(true, DocValuesType.SORTED, false);
  private static final LuxirValueField.Type CATS = LuxirValueField.Type.string(true, DocValuesType.SORTED_SET, false);
  private static final LuxirValueField.Type TAG = LuxirValueField.Type.string(true, DocValuesType.NONE, true);
  private static final LuxirValueField.Type CODE = LuxirValueField.Type.string(false, DocValuesType.SORTED, false);
  private static final LuxirValueField.Type YEAR = LuxirValueField.Type.number(4, DocValuesType.NUMERIC, false);
  private static final LuxirValueField.Type REV = LuxirValueField.Type.number(8, DocValuesType.NUMERIC, true);
  private static final LuxirValueField.Type POP = LuxirValueField.Type.number(4, DocValuesType.NUMERIC, false);
  private static final LuxirValueField.Type Q = LuxirValueField.Type.number(8, DocValuesType.SORTED_NUMERIC, false);
  private static final LuxirValueField.Type N = LuxirValueField.Type.number(4, DocValuesType.SORTED_NUMERIC, false);
  private static final LuxirValueField.Type DVONLY = LuxirValueField.Type.number(0, DocValuesType.NUMERIC, false);

  private static final String[] STRING_FIELDS = {"kind", "cats", "tag", "code"};

  private static String word(Random r, int vocab) {
    int k = r.nextInt(vocab);
    return switch (k % 5) {
      case 0 -> "w" + k;
      case 1 -> "Ünï" + k;
      case 2 -> "日本" + k;
      case 3 -> k % 2 == 0 ? "" : "x".repeat(1 + k % 200);
      default -> "Ab-" + Integer.toString(k, 36);
    };
  }

  private static void addDoc(Random r, String id, Document lux, Document luc) {
    lux.add(new StringField("id", id, Field.Store.YES));
    luc.add(new StringField("id", id, Field.Store.YES));
    if (r.nextInt(5) > 0) {
      String v = word(r, 12);
      lux.add(stringField(r, "kind", KIND, v));
      luc.add(new StringField("kind", v, Field.Store.NO));
      luc.add(new SortedDocValuesField("kind", new BytesRef(v)));
    }
    int cats = r.nextInt(4) == 0 ? 0 : 1 + r.nextInt(r.nextInt(5) == 0 ? 12 : 3);
    for (int i = 0; i < cats; i++) {
      String v = word(r, 3000);
      lux.add(stringField(r, "cats", CATS, v));
      luc.add(new StringField("cats", v, Field.Store.NO));
      luc.add(new SortedSetDocValuesField("cats", new BytesRef(v)));
    }
    if (r.nextInt(3) == 0) {
      String v = word(r, 50);
      lux.add(stringField(r, "tag", TAG, v));
      luc.add(new StringField("tag", v, Field.Store.YES));
    }
    if (r.nextInt(3) > 0) {
      String v = word(r, 500) + "-" + r.nextInt(100);
      lux.add(stringField(r, "code", CODE, v));
      luc.add(new SortedDocValuesField("code", new BytesRef(v)));
    }
    if (r.nextInt(6) > 0) {
      int v = r.nextInt(10) == 0 ? (r.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE) : r.nextInt(400) - 200;
      lux.add(new LuxirValueField("year", YEAR, v));
      luc.add(new IntPoint("year", v));
      luc.add(new NumericDocValuesField("year", v));
    }
    if (r.nextInt(6) > 0) {
      long v = r.nextInt(8) == 0 ? r.nextLong() : 400_000_000_000L + r.nextInt(1_000_000);
      lux.add(new LuxirValueField("rev", REV, v));
      luc.add(new LongPoint("rev", v));
      luc.add(new NumericDocValuesField("rev", v));
      luc.add(new StoredField("rev", v));
    }
    if (r.nextInt(6) > 0) {
      float v = r.nextInt(20) == 0 ? -0f : (r.nextFloat() - 0.3f) * 1000;
      lux.add(new LuxirValueField("pop", POP, v));
      luc.add(new FloatPoint("pop", v));
      luc.add(new NumericDocValuesField("pop", Float.floatToIntBits(v)));
    }
    int qs = r.nextInt(4);
    for (int i = 0; i < qs; i++) {
      double v = r.nextInt(10) == 0 ? Double.NEGATIVE_INFINITY : r.nextGaussian() * 100;
      lux.add(new LuxirValueField("q", Q, v));
      luc.add(new DoublePoint("q", v));
      luc.add(new SortedNumericDocValuesField("q", NumericUtils.doubleToSortableLong(v)));
    }
    int ns = r.nextInt(3);
    for (int i = 0; i < ns; i++) {
      int v = r.nextInt(50);
      lux.add(new LuxirValueField("n", N, v));
      luc.add(new IntPoint("n", v));
      luc.add(new SortedNumericDocValuesField("n", v));
    }
    if (r.nextBoolean()) {
      long v = r.nextLong();
      lux.add(new LuxirValueField("dvonly", DVONLY, v));
      luc.add(new NumericDocValuesField("dvonly", v));
    }
  }

  // The String and the UTF-8 bytes constructors must index identically.
  private static LuxirValueField stringField(Random r, String name, LuxirValueField.Type type, String v) {
    if (r.nextBoolean()) return new LuxirValueField(name, type, v);
    byte[] utf8 = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] padded = new byte[utf8.length + 5];
    System.arraycopy(utf8, 0, padded, 2, utf8.length);
    return new LuxirValueField(name, type, new BytesRef(padded, 2, utf8.length));
  }

  private static IndexWriterConfig config(Codec codec, int buffered, boolean merges) {
    IndexWriterConfig c = new IndexWriterConfig(new MockAnalyzer(random())).setCodec(codec)
        .setMaxBufferedDocs(buffered).setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    if (merges) {
      LogDocMergePolicy mp = new LogDocMergePolicy();
      mp.setMergeFactor(3);
      c.setMergePolicy(mp).setMergeScheduler(new SerialMergeScheduler());
    } else {
      c.setMergePolicy(NoMergePolicy.INSTANCE);
    }
    return c;
  }

  public void testSameAsLuceneFields() throws Exception {
    Random r = random();
    int numDocs = atLeast(3000);
    int buffered = 50 + r.nextInt(1000);
    boolean merges = r.nextBoolean();
    try (Directory luxDir = newFSDirectory(createTempDir());
        Directory lucDir = newDirectory();
        IndexWriter lux = new IndexWriter(luxDir, config(new LuxirCodec(), buffered, merges));
        IndexWriter luc = new IndexWriter(lucDir, config(Codec.getDefault(), buffered, merges))) {
      for (int i = 0; i < numDocs; i++) {
        // ids repeat: some documents replace earlier ones, in the same buffer or a flushed segment
        String id = Integer.toString(r.nextInt(5) == 0 ? r.nextInt(i + 1) : i);
        Document a = new Document(), b = new Document();
        addDoc(r, id, a, b);
        lux.updateDocument(new Term("id", id), a);
        luc.updateDocument(new Term("id", id), b);
        if (r.nextInt(50) == 0) {
          String del = Integer.toString(r.nextInt(i + 1));
          lux.deleteDocuments(new Term("id", del));
          luc.deleteDocuments(new Term("id", del));
        }
        if (r.nextInt(1000) == 0) {
          lux.commit();
          luc.commit();
        }
      }
      lux.commit();
      luc.commit();
      try (DirectoryReader a = DirectoryReader.open(luxDir); DirectoryReader b = DirectoryReader.open(lucDir)) {
        assertSame(r, a, b, false);
      }
      if (!merges) return; // (NoMergePolicy: forceMerge is a no-op)
      lux.forceMerge(1);
      luc.forceMerge(1);
      lux.commit();
      luc.commit();
      try (DirectoryReader a = DirectoryReader.open(luxDir); DirectoryReader b = DirectoryReader.open(lucDir)) {
        assertEquals(1, a.leaves().size());
        assertSame(r, a, b, true);
      }
    }
  }

  /** A segment with no postings at all (numbers only) flushes, merges and frees its native buffers. */
  public void testNumbersOnly() throws Exception {
    try (Directory dir = newFSDirectory(createTempDir());
        IndexWriter w = new IndexWriter(dir, config(new LuxirCodec(), 7, true))) {
      for (int i = 0; i < 100; i++) {
        Document d = new Document();
        d.add(new LuxirValueField("year", YEAR, i));
        d.add(new LuxirValueField("q", Q, (double) -i));
        w.addDocument(d);
      }
      w.forceMerge(1);
      w.commit();
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        IndexSearcher s = newSearcher(r);
        assertEquals(10, s.count(IntPoint.newRangeQuery("year", 10, 19)));
        assertEquals(5, s.count(DoublePoint.newRangeQuery("q", -4, 0)));
        NumericDocValues dv = r.leaves().get(0).reader().getNumericDocValues("year");
        long sum = 0;
        while (dv.nextDoc() != NumericDocValues.NO_MORE_DOCS) sum += dv.longValue();
        assertEquals(4950, sum);
      }
    }
  }

  public void testLongStringRejected() {
    String v = "é".repeat(128); // 256 bytes
    expectThrows(IllegalArgumentException.class, () -> new LuxirValueField("kind", KIND, v));
    new LuxirValueField("code", CODE, v); // not searchable: no limit
  }

  /** Without LuxirCodec the value would reach Lucene's inverter: that fails loudly. */
  public void testOtherCodecFails() throws Exception {
    try (Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(new MockAnalyzer(random())))) {
      Document d = new Document();
      d.add(new LuxirValueField("kind", KIND, "x"));
      expectThrows(IllegalStateException.class, () -> w.addDocument(d));
    }
  }

  private static void assertSame(Random r, DirectoryReader lux, DirectoryReader luc, boolean exactTerms)
      throws IOException {
    assertEquals(luc.numDocs(), lux.numDocs());
    Map<String, String> a = dump(lux), b = dump(luc);
    assertEquals(b.size(), a.size());
    for (Map.Entry<String, String> e : b.entrySet()) assertEquals("id " + e.getKey(), e.getValue(), a.get(e.getKey()));

    IndexSearcher sa = newSearcher(lux), sb = newSearcher(luc);
    for (int i = 0; i < 200; i++) {
      String field = STRING_FIELDS[r.nextInt(3)]; // code has no postings
      String v = word(r, field.equals("cats") ? 3000 : field.equals("kind") ? 12 : 50);
      Query q = new TermQuery(new Term(field, v));
      assertEquals(q.toString(), ids(sb, q), ids(sa, q));
    }
    for (int i = 0; i < 100; i++) {
      Query q = switch (r.nextInt(5)) {
        case 0 -> IntPoint.newRangeQuery("year", r.nextInt(400) - 220, r.nextInt(400) - 180);
        case 1 -> LongPoint.newRangeQuery("rev", 400_000_000_000L + r.nextInt(500_000), 400_000_000_000L + r.nextInt(1_000_000));
        case 2 -> FloatPoint.newRangeQuery("pop", (r.nextFloat() - 0.5f) * 1000, (r.nextFloat() - 0.2f) * 1000);
        case 3 -> DoublePoint.newRangeQuery("q", r.nextGaussian() * 100, r.nextGaussian() * 100 + 50);
        default -> IntPoint.newSetQuery("n", r.nextInt(50), r.nextInt(50), r.nextInt(50));
      };
      assertEquals(q.toString(), ids(sb, q), ids(sa, q));
    }
    for (String field : new String[] {"year", "pop", "kind", "q"}) {
      SortField sf = switch (field) {
        case "year" -> new SortField("year", SortField.Type.INT, r.nextBoolean());
        case "pop" -> new SortField("pop", SortField.Type.FLOAT, r.nextBoolean());
        case "kind" -> new SortField("kind", SortField.Type.STRING, r.nextBoolean());
        default -> new SortedNumericSortField("q", SortField.Type.DOUBLE, r.nextBoolean());
      };
      assertEquals(field, sorted(sb, new Sort(sf)), sorted(sa, new Sort(sf)));
    }
    if (exactTerms) {
      for (String field : new String[] {"kind", "cats", "tag"}) {
        assertEquals(field, terms(luc, field), terms(lux, field));
        Terms t = MultiTerms.getTerms(lux, field);
        if (t != null) {
          assertFalse(t.hasFreqs());
          assertEquals(t.getSumDocFreq(), t.getSumTotalTermFreq());
          assertEquals(MultiTerms.getTerms(luc, field).getDocCount(), t.getDocCount());
        }
      }
    }
  }

  private static TreeSet<String> ids(IndexSearcher s, Query q) throws IOException {
    TreeSet<String> out = new TreeSet<>();
    StoredFields sf = s.getIndexReader().storedFields();
    for (ScoreDoc sd : s.search(q, Math.max(1, s.getIndexReader().maxDoc())).scoreDocs) {
      out.add(sf.document(sd.doc).get("id"));
    }
    return out;
  }

  /** The sort values in order (ids of equal values may come in any order). */
  private static List<Object> sorted(IndexSearcher s, Sort sort) throws IOException {
    TopFieldDocs td = s.search(org.apache.lucene.search.MatchAllDocsQuery.INSTANCE, 100, sort);
    List<Object> out = new ArrayList<>();
    for (ScoreDoc sd : td.scoreDocs) {
      Object v = ((org.apache.lucene.search.FieldDoc) sd).fields[0];
      out.add(v instanceof BytesRef br ? br.utf8ToString() : v);
    }
    return out;
  }

  private static String terms(IndexReader reader, String field) throws IOException {
    Terms t = MultiTerms.getTerms(reader, field);
    if (t == null) return "";
    StringBuilder sb = new StringBuilder();
    TermsEnum te = t.iterator();
    for (BytesRef term = te.next(); term != null; term = te.next()) {
      sb.append(term.utf8ToString()).append(':').append(te.docFreq()).append(',');
    }
    return sb.toString();
  }

  /** Per live doc (by id): every doc value and stored value, rendered. */
  private static Map<String, String> dump(DirectoryReader reader) throws IOException {
    Map<String, String> out = new TreeMap<>();
    for (LeafReaderContext ctx : reader.leaves()) {
      LeafReader leaf = ctx.reader();
      StoredFields stored = leaf.storedFields();
      for (int d = 0; d < leaf.maxDoc(); d++) {
        if (leaf.getLiveDocs() != null && !leaf.getLiveDocs().get(d)) continue;
        Document doc = stored.document(d);
        StringBuilder sb = new StringBuilder();
        sb.append("tag=").append(doc.get("tag")).append(" rev*=").append(doc.get("rev"));
        for (String f : new String[] {"kind", "code"}) {
          SortedDocValues dv = leaf.getSortedDocValues(f);
          sb.append(' ').append(f).append('=');
          if (dv != null && dv.advanceExact(d)) sb.append(dv.lookupOrd(dv.ordValue()).utf8ToString());
        }
        SortedSetDocValues cats = leaf.getSortedSetDocValues("cats");
        sb.append(" cats=");
        if (cats != null && cats.advanceExact(d)) {
          for (int i = 0; i < cats.docValueCount(); i++) sb.append(cats.lookupOrd(cats.nextOrd()).utf8ToString()).append('|');
        }
        for (String f : new String[] {"year", "rev", "pop", "dvonly"}) {
          NumericDocValues dv = leaf.getNumericDocValues(f);
          sb.append(' ').append(f).append('=');
          if (dv != null && dv.advanceExact(d)) sb.append(dv.longValue());
        }
        for (String f : new String[] {"q", "n"}) {
          SortedNumericDocValues dv = leaf.getSortedNumericDocValues(f);
          sb.append(' ').append(f).append('=');
          if (dv != null && dv.advanceExact(d)) {
            for (int i = 0; i < dv.docValueCount(); i++) sb.append(dv.nextValue()).append('|');
          }
        }
        String id = doc.get("id");
        assertNull("duplicate live id " + id, out.put(id, sb.toString()));
      }
    }
    return out;
  }
}
