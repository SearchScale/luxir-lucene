package org.luxir.lucene.search;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.util.List;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.luxir.lucene.CleanerThreadFilter;
import org.luxir.lucene.codec.LuxirCodec;

@ThreadLeakFilters(defaultFilters = true, filters = {CleanerThreadFilter.class})
/**
 * Booleans nesting a match-all next to prohibited clauses, under filters and optional clauses, on
 * Luxir value fields: every count (direct, through the accelerated query, native top-k with and
 * without match bits or a filter) must be Lucene's. Unscored, a bare match-all once changed what
 * the enclosing boolean matched.
 */
public class TestNestedBooleanCounts extends LuceneTestCase {
  public void testCountsMatchLucene() throws Exception {
    try (Directory lux = newDirectory(); Directory luc = newDirectory()) {
      for (Directory d : new Directory[] {lux, luc}) {
        IndexWriterConfig c = new IndexWriterConfig(new WhitespaceAnalyzer());
        c.setCodec(d == lux ? new LuxirCodec() : Codec.getDefault());
        try (IndexWriter w = new IndexWriter(d, c)) {
          for (int i = 0; i < 2000; i++) {
            Document doc = new Document();
            if (d == lux) {
              doc.add(new org.luxir.lucene.document.LuxirValueField("k",
                  org.luxir.lucene.document.LuxirValueField.Type.string(true, org.apache.lucene.index.DocValuesType.SORTED, false), "k" + (i % 7)));
            } else {
              doc.add(new StringField("k", "k" + (i % 7), Field.Store.NO));
              doc.add(new org.apache.lucene.document.SortedDocValuesField("k", new org.apache.lucene.util.BytesRef("k" + (i % 7))));
            }
            doc.add(new TextField("b", "w" + (i % 5) + " x", Field.Store.NO));
            w.addDocument(doc);
          }
        }
      }
      try (DirectoryReader lr = DirectoryReader.open(lux); DirectoryReader cr = DirectoryReader.open(luc)) {
        IndexSearcher ls = new IndexSearcher(lr), cs = new IndexSearcher(cr);
        Query a = new ConstantScoreQuery(new TermQuery(new Term("k", "k2")));
        Query b = new TermQuery(new Term("b", "w4"));
        Query c = new ConstantScoreQuery(new TermQuery(new Term("k", "k1")));
        Query inner = new BooleanQuery.Builder().add(a, Occur.MUST_NOT).add(MatchAllDocsQuery.INSTANCE, Occur.SHOULD).build();
        Query[] qs = {
          inner,
          new BooleanQuery.Builder().add(inner, Occur.FILTER).build(),
          new BooleanQuery.Builder().add(inner, Occur.FILTER).add(c, Occur.SHOULD).build(),
          new BooleanQuery.Builder().add(inner, Occur.FILTER).add(b, Occur.MUST_NOT).build(),
          new BooleanQuery.Builder().add(inner, Occur.FILTER).add(b, Occur.MUST_NOT).add(c, Occur.SHOULD).build(),
          new BooleanQuery.Builder().add(new TermQuery(new Term("b", "x")), Occur.FILTER).add(b, Occur.MUST_NOT).add(c, Occur.SHOULD).build(),
          new BooleanQuery.Builder().add(MatchAllDocsQuery.INSTANCE, Occur.FILTER).add(b, Occur.MUST_NOT).add(c, Occur.SHOULD).build(),
          new BooleanQuery.Builder().add(MatchAllDocsQuery.INSTANCE, Occur.FILTER).add(c, Occur.SHOULD).build(),
        };
        Query filter = new TermQuery(new Term("b", "x"));
        for (Query q : qs) {
          String info = q.toString();
          int want = cs.count(q);
          assertEquals(info, want, ls.count(q));
          assertEquals(info, want, ls.count(new LuxirAcceleratedQuery(q)));
          LuxirSearch.Result bits = LuxirSearch.topK(ls, q, 1, null, true);
          assertNotNull(info, bits);
          assertEquals(info, want, bits.totalHits());
          assertEquals(info, want, LuxirSearch.topK(ls, q, 1, null, false).totalHits());
          int wantFiltered = cs.count(new BooleanQuery.Builder().add(q, Occur.MUST).add(filter, Occur.FILTER).build());
          assertEquals(info, wantFiltered,
              LuxirSearch.topK(ls, q, List.of(filter), 1, Integer.MAX_VALUE, null, false, false, false).totalHits());
        }
      }
    }
  }
}
