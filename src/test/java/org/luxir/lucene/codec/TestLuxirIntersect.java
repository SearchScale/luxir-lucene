// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockTokenizer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.RegExp;
import org.luxir.lucene.CleanerThreadFilter;

/**
 * Terms.intersect over a Luxir term dictionary, natively walked, against a scan of every term: the
 * same accepted terms in the same order, with and without a start term (exclusive), including terms
 * over Luxir's 255-byte limit (kept in the overflow terms).
 */
@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
public class TestLuxirIntersect extends LuceneTestCase {

  private static String word(Random r) {
    int len = 1 + r.nextInt(r.nextInt(4) == 0 ? 12 : 6);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) sb.append((char) ('a' + r.nextInt(r.nextBoolean() ? 4 : 26)));
    if (r.nextInt(200) == 0) sb.append("é".repeat(140)); // over 255 bytes
    return sb.toString();
  }

  private static Automaton automaton(Random r, String w) {
    switch (r.nextInt(4)) {
      case 0:
        return PrefixQuery.toAutomaton(new BytesRef(w.substring(0, 1 + r.nextInt(Math.min(3, w.length())))));
      case 1:
        return WildcardQuery.toAutomaton(new Term("f", w.charAt(0) + "?" + w.charAt(w.length() - 1) + "*"),
            Operations.DEFAULT_DETERMINIZE_WORK_LIMIT);
      case 2:
        return Operations.determinize(new RegExp("[a-d]" + w.charAt(0) + "[a-z]*").toAutomaton(),
            Operations.DEFAULT_DETERMINIZE_WORK_LIMIT);
      default:
        int prefix = r.nextInt(Math.min(2, w.length()));
        return new LevenshteinAutomata(w.substring(prefix), true).toAutomaton(1 + r.nextInt(2), w.substring(0, prefix));
    }
  }

  public void testIntersectWithStartTerm() throws Exception {
    Random r = random();
    try (Directory dir = newDirectory()) {
      // whitespace tokens, no payloads (a field with payloads is not stored natively)
      Analyzer analyzer = new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(String field) {
          return new TokenStreamComponents(new MockTokenizer(MockTokenizer.WHITESPACE, false));
        }
      };
      IndexWriterConfig iwc = new IndexWriterConfig(analyzer)
          .setCodec(TestUtil.alwaysPostingsFormat(new LuxirPostingsFormat()));
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        int docs = 200 + r.nextInt(2000);
        for (int i = 0; i < docs; i++) {
          Document d = new Document();
          StringBuilder text = new StringBuilder();
          for (int j = 1 + r.nextInt(5); j > 0; j--) text.append(word(r)).append(' ');
          d.add(new TextField("f", text.toString(), Field.Store.NO));
          w.addDocument(d);
        }
        if (r.nextBoolean()) w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        for (LeafReaderContext leaf : reader.leaves()) {
          Terms terms = leaf.reader().terms("f");
          assertTrue(terms.getClass().getName(), terms instanceof LuxirTerms);
          List<BytesRef> all = new ArrayList<>();
          TermsEnum it = terms.iterator();
          for (BytesRef t = it.next(); t != null; t = it.next()) all.add(BytesRef.deepCopyOf(t));
          for (int iter = 0; iter < 200; iter++) {
            CompiledAutomaton ca = new CompiledAutomaton(automaton(r, word(r)), false, true, false);
            if (ca.type != CompiledAutomaton.AUTOMATON_TYPE.NORMAL) continue;
            BytesRef start = switch (r.nextInt(4)) {
              case 0 -> null;
              case 1 -> all.get(r.nextInt(all.size()));
              case 2 -> new BytesRef(word(r));
              default -> new BytesRef("");
            };
            List<BytesRef> expected = new ArrayList<>();
            for (BytesRef t : all) {
              if ((start == null || t.compareTo(start) > 0) && ca.runAutomaton.run(t.bytes, t.offset, t.length)) {
                expected.add(t);
              }
            }
            List<BytesRef> actual = new ArrayList<>();
            TermsEnum te = terms.intersect(ca, start);
            for (BytesRef t = te.next(); t != null; t = te.next()) {
              actual.add(BytesRef.deepCopyOf(t));
              assertEquals(t.utf8ToString(), te.docFreq(), countDocs(leaf, t));
            }
            assertEquals("start " + (start == null ? null : start.utf8ToString()), expected, actual);
          }
        }
      }
    }
  }

  private static int countDocs(LeafReaderContext leaf, BytesRef t) throws java.io.IOException {
    TermsEnum te = leaf.reader().terms("f").iterator();
    return te.seekExact(t) ? te.docFreq() : -1;
  }
}
