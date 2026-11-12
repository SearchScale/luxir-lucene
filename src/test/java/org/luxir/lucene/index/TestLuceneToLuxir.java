// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.index;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.CleanerThreadFilter;
import org.luxir.lucene.codec.LuxirCodec;
import org.luxir.lucene.codec.LuxirTerms;

/**
 * A stock Lucene index, rewritten by {@link LuceneToLuxir}, keeps its terms, postings and stored
 * values. Payloads stay on the embedded Lucene104 postings; a term longer than 255 bytes stays in
 * the Luxir field as an overflow term.
 */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuceneToLuxir extends LuceneTestCase {
  private static final FieldType PAYLOADS;

  static {
    PAYLOADS = new FieldType(TextField.TYPE_NOT_STORED);
    PAYLOADS.freeze();
  }

  public void testRefusesToWriteInPlace() throws Exception {
    Path source = createTempDir("src");
    writeIndex(source, true);
    IllegalArgumentException same = expectThrows(IllegalArgumentException.class, () -> LuceneToLuxir.convert(source, source));
    assertTrue(same.getMessage(), same.getMessage().contains("same path"));
    Path dest = createTempDir("dest");
    IllegalArgumentException exists = expectThrows(IllegalArgumentException.class, () -> LuceneToLuxir.convert(source, dest));
    assertTrue(exists.getMessage(), exists.getMessage().contains("already exists"));
    try (FSDirectory dir = FSDirectory.open(source); DirectoryReader reader = DirectoryReader.open(dir)) {
      assertNotEquals(LuxirCodec.NAME, codecName(reader));
    }
  }

  public void testConvertsOneSegment() throws Exception {
    Path source = createTempDir("one");
    writeIndex(source, true);
    assertConverts(source);
  }

  public void testConvertsSeveralSegments() throws Exception {
    Path source = createTempDir("many");
    writeIndex(source, false);
    try (FSDirectory dir = FSDirectory.open(source); DirectoryReader reader = DirectoryReader.open(dir)) {
      assertTrue(reader.leaves().size() > 1);
    }
    assertConverts(source);
  }

  private void assertConverts(Path source) throws Exception {
    Path dest = source.resolveSibling(source.getFileName().toString() + "-luxir");
    assertFalse(Files.exists(dest));
    TreeMap<String, Long> before = fileSizes(source);
    LuceneToLuxir.convert(source, dest);
    assertEquals(before, fileSizes(source));
    assertClean(dest);
    try (FSDirectory srcDir = FSDirectory.open(source);
        FSDirectory dstDir = FSDirectory.open(dest);
        DirectoryReader src = DirectoryReader.open(srcDir);
        DirectoryReader dst = DirectoryReader.open(dstDir)) {
      assertNotEquals(LuxirCodec.NAME, codecName(src));
      assertEquals(LuxirCodec.NAME, codecName(dst));
      assertEquals(1, dst.leaves().size());
      assertEquals(src.maxDoc(), dst.maxDoc());
      assertEquals(src.numDocs(), dst.numDocs());
      assertSameStored(src, dst);
      for (String field : List.of("id", "body", "pay", "wide")) assertSamePostings(src, dst, field);
      LeafReader leaf = dst.leaves().get(0).reader();
      Terms body = leaf.terms("body");
      assertTrue(body instanceof LuxirTerms);
      assertFalse(((LuxirTerms) body).test(new byte[0]));
      Terms wide = leaf.terms("wide");
      assertTrue(wide instanceof LuxirTerms);
      assertTrue("a term longer than 255 bytes is an overflow term, still Luxir", ((LuxirTerms) wide).test(new byte[0]));
      assertFalse("payloads stay on Lucene104", leaf.terms("pay") instanceof LuxirTerms);
      assertTrue(leaf.getFieldInfos().fieldInfo("pay").hasPayloads());
      assertFalse(leaf.terms("id") instanceof LuxirTerms);
      assertEquals(
          List.of(
              "body: Luxir",
              "id: Lucene104 (index options DOCS, missing norms)",
              "pay: Lucene104 (payloads)",
              "wide: Luxir (term longer than 255 bytes)"),
          LuceneToLuxir.describeFields(dst));
    }
  }

  private static void writeIndex(Path path, boolean oneSegment) throws IOException {
    IndexWriterConfig cfg = new IndexWriterConfig(new WhitespaceAnalyzer())
        .setCodec(Codec.getDefault())
        .setMergePolicy(NoMergePolicy.INSTANCE)
        .setMergeScheduler(new SerialMergeScheduler())
        .setMaxBufferedDocs(oneSegment ? 100 : 2)
        .setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    try (FSDirectory dir = FSDirectory.open(path); IndexWriter writer = new IndexWriter(dir, cfg)) {
      for (int i = 0; i < 4; i++) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        doc.add(new TextField("body", i == 0 ? "fox fox" : "fox dog", Field.Store.YES));
        // CharTokenizer drops past 255 chars, so the long term is supplied already tokenized.
        doc.add(new Field("wide", singleTerm("short"), PAYLOADS));
        doc.add(new StoredField("wide", "short"));
        if (i == 1) {
          String longTerm = "w".repeat(300);
          doc.add(new Field("wide", singleTerm(longTerm), PAYLOADS));
          doc.add(new StoredField("wide", longTerm));
        }
        doc.add(new Field("pay", payloads(i, "alpha beta"), PAYLOADS));
        doc.add(new StoredField("n", i));
        writer.addDocument(doc);
      }
      writer.commit();
    }
  }

  private static TokenStream singleTerm(String text) {
    return new TokenStream() {
      private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
      private final PositionIncrementAttribute posInc = addAttribute(PositionIncrementAttribute.class);
      private boolean done;

      @Override
      public void reset() throws IOException {
        super.reset();
        done = false;
      }

      @Override
      public boolean incrementToken() {
        if (done) return false;
        clearAttributes();
        term.setEmpty().append(text);
        posInc.setPositionIncrement(1);
        done = true;
        return true;
      }
    };
  }

  private static TokenStream payloads(int doc, String text) {
    return new TokenStream() {
      private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
      private final PayloadAttribute payload = addAttribute(PayloadAttribute.class);
      private final PositionIncrementAttribute posInc = addAttribute(PositionIncrementAttribute.class);
      private final String[] parts = text.split(" ");
      private int i;

      @Override
      public void reset() throws IOException {
        super.reset();
        i = 0;
      }

      @Override
      public boolean incrementToken() {
        if (i >= parts.length) return false;
        clearAttributes();
        term.setEmpty().append(parts[i]);
        posInc.setPositionIncrement(1);
        payload.setPayload(new BytesRef(new byte[] {(byte) doc, (byte) i}));
        i++;
        return true;
      }
    };
  }

  private static void assertSamePostings(IndexReader src, IndexReader dst, String field) throws IOException {
    Terms a = MultiTerms.getTerms(src, field);
    Terms b = MultiTerms.getTerms(dst, field);
    assertNotNull(field, a);
    assertNotNull(field, b);
    assertEquals(field, a.hasFreqs(), b.hasFreqs());
    assertEquals(field, a.hasPositions(), b.hasPositions());
    assertEquals(field, a.hasOffsets(), b.hasOffsets());
    assertEquals(field, a.hasPayloads(), b.hasPayloads());
    int flags = PostingsEnum.NONE;
    if (a.hasFreqs()) flags |= PostingsEnum.FREQS;
    if (a.hasPositions()) flags |= PostingsEnum.POSITIONS;
    if (a.hasOffsets()) flags |= PostingsEnum.OFFSETS;
    if (a.hasPayloads()) flags |= PostingsEnum.PAYLOADS;
    TermsEnum ea = a.iterator(), eb = b.iterator();
    PostingsEnum pa = null, pb = null;
    for (BytesRef term = ea.next(); term != null; term = ea.next()) {
      BytesRef got = eb.next();
      assertEquals(field, term, got);
      assertEquals(term.utf8ToString(), ea.docFreq(), eb.docFreq());
      assertEquals(term.utf8ToString(), ea.totalTermFreq(), eb.totalTermFreq());
      pa = ea.postings(pa, flags);
      pb = eb.postings(pb, flags);
      for (int d = pa.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = pa.nextDoc()) {
        assertEquals(term.utf8ToString(), d, pb.nextDoc());
        if (a.hasFreqs()) assertEquals(term.utf8ToString(), pa.freq(), pb.freq());
        if (a.hasPositions()) {
          int freq = a.hasFreqs() ? pa.freq() : 1;
          for (int k = 0; k < freq; k++) {
            assertEquals(term.utf8ToString(), pa.nextPosition(), pb.nextPosition());
            if (a.hasOffsets()) {
              assertEquals(pa.startOffset(), pb.startOffset());
              assertEquals(pa.endOffset(), pb.endOffset());
            }
            if (a.hasPayloads()) assertEquals(pa.getPayload(), pb.getPayload());
          }
        }
      }
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, pb.nextDoc());
    }
    assertNull(field, eb.next());
  }

  private static void assertSameStored(IndexReader src, IndexReader dst) throws IOException {
    StoredFields a = src.storedFields(), b = dst.storedFields();
    for (int doc = 0; doc < src.maxDoc(); doc++) {
      assertEquals("doc " + doc, stored(a.document(doc)), stored(b.document(doc)));
    }
  }

  private static List<String> stored(Document doc) {
    List<String> values = new ArrayList<>();
    for (var f : doc) {
      values.add(f.name() + "=" + f.stringValue() + "|" + f.numericValue() + "|" + f.binaryValue());
    }
    Collections.sort(values);
    return values;
  }

  private static void assertClean(Path path) throws IOException {
    try (FSDirectory dir = FSDirectory.open(path); CheckIndex checker = new CheckIndex(dir)) {
      checker.setLevel(CheckIndex.Level.MIN_LEVEL_FOR_INTEGRITY_CHECKS);
      CheckIndex.Status status = checker.checkIndex();
      assertTrue(status.clean);
      assertEquals(0, status.numBadSegments);
    }
  }

  private static String codecName(IndexReader reader) {
    LeafReader leaf = FilterLeafReader.unwrap(reader.leaves().get(0).reader());
    return ((SegmentReader) leaf).getSegmentInfo().info.getCodec().getName();
  }

  private static TreeMap<String, Long> fileSizes(Path root) throws IOException {
    TreeMap<String, Long> sizes = new TreeMap<>();
    try (var walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).toList()) {
        sizes.put(root.relativize(p).toString(), Files.size(p));
      }
    }
    return sizes;
  }
}
