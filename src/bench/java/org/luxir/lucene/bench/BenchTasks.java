// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.bench;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.util.BytesRef;

/**
 * Benchmark requests chosen from an index's term statistics, luceneutil style: terms by document
 * frequency (high, medium, low), their conjunctions and disjunctions, 3-letter prefixes, edit
 * distance 1 fuzzy, a date sort, and two-word phrases that occur in titles.
 */
final class BenchTasks {
  /** enwiki line-doc dates, e.g. 12-JAN-2010 13:45:00.000 */
  static final DateTimeFormatter DATE =
      new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss.SSS").toFormatter(Locale.ENGLISH);

  /** A category and its query text in luceneutil form (AND/OR, x*, x~1, "a b", "t sort:ts"). */
  record Task(String category, String text) {}

  private BenchTasks() {}

  static List<Task> build(DirectoryReader reader, IndexSearcher searcher) throws IOException {
    int maxDoc = reader.maxDoc();
    List<String> high = new ArrayList<>(), med = new ArrayList<>(), low = new ArrayList<>();
    TermsEnum te = MultiTerms.getTerms(reader, "body").iterator();
    for (BytesRef b = te.next(); b != null; b = te.next()) {
      String s = b.utf8ToString();
      if (s.length() < 3 || !s.chars().allMatch(Character::isLetter)) continue;
      int df = te.docFreq();
      if (df > maxDoc / 10) high.add(s);
      else if (df > maxDoc / 200 && df < maxDoc / 50) med.add(s);
      else if (df > maxDoc / 10000 && df < maxDoc / 2000) low.add(s);
    }
    Random r = new Random(1234);
    List<Task> tasks = new ArrayList<>();
    int n = 20;
    for (int i = 0; i < n; i++) {
      tasks.add(new Task("TermHigh", pick(r, high)));
      tasks.add(new Task("TermMed", pick(r, med)));
      tasks.add(new Task("TermLow", pick(r, low)));
      tasks.add(new Task("AndHighHigh", pick(r, high) + " AND " + pick(r, high)));
      tasks.add(new Task("AndHighMed", pick(r, high) + " AND " + pick(r, med)));
      tasks.add(new Task("OrHighHigh", pick(r, high) + " OR " + pick(r, high)));
      tasks.add(new Task("OrHighMed", pick(r, high) + " OR " + pick(r, med)));
      tasks.add(new Task("Prefix3", pick(r, med).substring(0, 3) + "*"));
      tasks.add(new Task("Fuzzy1", pick(r, med) + "~1"));
      tasks.add(new Task("TermSortTs", pick(r, med) + " sort:ts"));
    }
    tasks.addAll(phrases(reader, searcher, r, n));
    return tasks;
  }

  private static String pick(Random r, List<String> l) {
    return l.get(r.nextInt(l.size()));
  }

  private static Analyzer analyzer() throws IOException {
    return CustomAnalyzer.builder()
        .withTokenizer("standard")
        .addTokenFilter("lowercase")
        .addTokenFilter("asciiFolding")
        .addTokenFilter("kStem")
        .build();
  }

  // Two-word phrases that occur at least 50 times: analyze sampled titles.
  private static List<Task> phrases(DirectoryReader reader, IndexSearcher searcher, Random r, int n) throws IOException {
    List<Task> out = new ArrayList<>();
    var stored = reader.storedFields();
    try (Analyzer a = analyzer()) {
      int attempts = 0;
      while (out.size() < n && attempts++ < 10000) {
        String title = stored.document(r.nextInt(reader.maxDoc())).get("title");
        if (title == null) continue;
        List<String> toks = new ArrayList<>();
        try (TokenStream ts = a.tokenStream("body", title)) {
          CharTermAttribute ct = ts.addAttribute(CharTermAttribute.class);
          ts.reset();
          while (ts.incrementToken()) toks.add(ct.toString());
          ts.end();
        }
        if (toks.size() < 2) continue;
        int i = r.nextInt(toks.size() - 1);
        String w1 = toks.get(i), w2 = toks.get(i + 1);
        if (w1.length() < 3 || w2.length() < 3) continue;
        if (searcher.count(new PhraseQuery("body", w1, w2)) < 50) continue;
        out.add(new Task("Phrase", "\"" + w1 + " " + w2 + "\""));
      }
    }
    return out;
  }
}
