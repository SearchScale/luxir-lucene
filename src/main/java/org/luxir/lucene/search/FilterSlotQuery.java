// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;

/**
 * The caller's filter (live docs and the request's filter bitset) as a FILTER clause of the query
 * {@link LuxirSearch} runs natively: a bitset clause whose per-segment bits and counts are the
 * filter's own. The filter is still applied as the live docs; as a clause it lets Luxir's planner
 * see how selective it is and lead with it, verifying the other clauses (doc-values ranges) per
 * candidate instead of materializing them over the whole segment. Never executed by Lucene.
 */
final class FilterSlotQuery extends Query {
  static final FilterSlotQuery INSTANCE = new FilterSlotQuery();

  private FilterSlotQuery() {}

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
    throw new UnsupportedOperationException("only runs natively");
  }

  @Override
  public void visit(QueryVisitor visitor) {
    visitor.visitLeaf(this);
  }

  @Override
  public String toString(String field) {
    return "luxir-filter";
  }

  @Override
  public boolean equals(Object other) {
    return other == this;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }
}
