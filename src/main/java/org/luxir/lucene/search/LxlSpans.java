// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.Query;

/**
 * Recognizes span queries Luxir runs natively. Lucene's spans are in lucene-queries, which this
 * library does not require: only touch this class when {@link #AVAILABLE}.
 */
final class LxlSpans {
  static final boolean AVAILABLE = available();

  private LxlSpans() {}

  private static boolean available() {
    try {
      Class.forName("org.apache.lucene.queries.spans.SpanNearQuery", false, LxlSpans.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** A SpanNearQuery's field, slop, terms (in query order) and whether they must be in order. */
  record TermNear(String field, int slop, Term[] terms, boolean inOrder) {}

  /** A SpanNearQuery (ordered or not) of two or more single distinct terms, or null. */
  static TermNear termNear(Query q) {
    if (!(q instanceof SpanNearQuery near) || near.getSlop() < 0) return null;
    SpanQuery[] clauses = near.getClauses();
    if (clauses.length < 2) return null;
    Term[] terms = new Term[clauses.length];
    Set<Term> seen = new HashSet<>();
    for (int i = 0; i < clauses.length; i++) {
      if (!(clauses[i] instanceof SpanTermQuery st)) return null;
      terms[i] = st.getTerm();
      if (!terms[i].field().equals(near.getField()) || !seen.add(terms[i])) return null;
    }
    return new TermNear(near.getField(), near.getSlop(), terms, near.isInOrder());
  }
}
