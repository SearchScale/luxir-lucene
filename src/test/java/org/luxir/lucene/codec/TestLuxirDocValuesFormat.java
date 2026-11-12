// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.util.Random;
import java.util.function.LongUnaryOperator;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.BaseDocValuesFormatTestCase;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.CleanerThreadFilter;

/** Lucene's randomized doc-values conformance suite, run against the Luxir codec. */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirDocValuesFormat extends BaseDocValuesFormatTestCase {
  private final Codec codec = new LuxirCodec();

  @Override
  protected Codec getCodec() {
    return codec;
  }

  /**
   * Field order changes the file size by a few bytes: Lucene flushes doc values in field-hash
   * order but merges them in field-number order, and the Luxir segment pads its blocks for
   * alignment, so where padding falls depends on what precedes each block.
   */
  @Override
  protected boolean mergeIsStable() {
    return false;
  }

  /**
   * Single-valued columns are read in place (DirectColumn), not through native fills: those with a
   * value in every doc, and sparse ones by their docs' bitset; every block encoding, across block
   * boundaries, read in random order and in order.
   */
  public void testColumnsReadInPlace() throws Exception {
    Random r = random();
    long base = r.nextLong();
    LongUnaryOperator[] shapes = {
      d -> 42, // constant: zero bits
      d -> r.nextInt(1000), // narrow
      d -> 1_000_000L + 1000L * r.nextInt(5000), // gcd
      d -> 7 * d + 3, // a line: slope, no residuals
      d -> -13 * d + r.nextInt(16), // a falling line with noise: negative slope, residuals
      d -> base + d * 977 + r.nextInt(100), // a line far from zero
      d -> r.nextLong(), // raw 64-bit blocks
      d -> r.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE,
      d -> r.nextLong() >> r.nextInt(64), // widths varying from block to block
    };
    int numDocs = atLeast(9000); // three or more 4096-value blocks
    long[][] expected = new long[shapes.length][numDocs];
    boolean[][] has = new boolean[shapes.length][numDocs]; // the sparse copies ("p" + shape): some docs only
    int[] ords = new int[numDocs];
    boolean[] hasOrd = new boolean[numDocs];
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig().setCodec(codec);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int d = 0; d < numDocs; d++) {
          Document doc = new Document();
          for (int s = 0; s < shapes.length; s++) {
            expected[s][d] = shapes[s].applyAsLong(d);
            doc.add(new NumericDocValuesField("n" + s, expected[s][d]));
            has[s][d] = r.nextInt(s + 2) == 0; // from half the docs to a few
            if (has[s][d]) doc.add(new NumericDocValuesField("p" + s, expected[s][d]));
          }
          ords[d] = r.nextInt(300);
          doc.add(new SortedDocValuesField("s", new BytesRef(String.format("%05d", ords[d]))));
          hasOrd[d] = r.nextInt(4) > 0;
          if (hasOrd[d]) doc.add(new SortedDocValuesField("sp", new BytesRef(String.format("%05d", ords[d]))));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        LeafReader leaf = reader.leaves().get(0).reader();
        for (int s = 0; s < shapes.length; s++) {
          NumericDocValues v = leaf.getNumericDocValues("n" + s);
          assertEquals("DirectNumeric", v.getClass().getSimpleName());
          for (int k = 0; k < 2000; k++) {
            int d = r.nextInt(numDocs);
            v = r.nextInt(10) == 0 ? leaf.getNumericDocValues("n" + s) : v;
            if (v.docID() > d) v = leaf.getNumericDocValues("n" + s);
            assertTrue(v.advanceExact(d));
            assertEquals("shape " + s + " doc " + d, expected[s][d], v.longValue());
          }
          v = leaf.getNumericDocValues("n" + s);
          for (int d = 0; d < numDocs; d++) {
            assertEquals(d, v.nextDoc());
            assertEquals("shape " + s + " doc " + d, expected[s][d], v.longValue());
          }
          assertEquals(DocIdSetIterator.NO_MORE_DOCS, v.nextDoc());
        }
        for (int s = 0; s < shapes.length; s++) {
          NumericDocValues v = leaf.getNumericDocValues("p" + s);
          assertEquals("SparseNumeric", v.getClass().getSimpleName());
          for (int k = 0; k < 2000; k++) {
            int d = r.nextInt(numDocs);
            if (v.docID() > d) v = leaf.getNumericDocValues("p" + s);
            assertEquals("sparse shape " + s + " doc " + d, has[s][d], v.advanceExact(d));
            if (has[s][d]) assertEquals("sparse shape " + s + " doc " + d, expected[s][d], v.longValue());
          }
          v = leaf.getNumericDocValues("p" + s);
          for (int d = v.nextDoc(), want = 0; ; d = r.nextBoolean() ? v.nextDoc() : v.advance(d + 1)) {
            while (want < numDocs && !has[s][want]) want++;
            if (want == numDocs) {
              assertEquals(DocIdSetIterator.NO_MORE_DOCS, d);
              break;
            }
            assertEquals("sparse shape " + s, want, d);
            assertEquals("sparse shape " + s + " doc " + d, expected[s][d], v.longValue());
            want++;
          }
        }
        SortedDocValues sp = leaf.getSortedDocValues("sp");
        for (int d = 0; d < numDocs; d += 1 + r.nextInt(50)) {
          assertEquals(hasOrd[d], sp.advanceExact(d));
          if (hasOrd[d]) assertEquals(String.format("%05d", ords[d]), sp.lookupOrd(sp.ordValue()).utf8ToString());
        }
        SortedDocValues sv = leaf.getSortedDocValues("s");
        for (int d = 0; d < numDocs; d += 1 + r.nextInt(50)) {
          assertTrue(sv.advanceExact(d));
          assertEquals(String.format("%05d", ords[d]), sv.lookupOrd(sv.ordValue()).utf8ToString());
        }
      }
    }
  }
}
