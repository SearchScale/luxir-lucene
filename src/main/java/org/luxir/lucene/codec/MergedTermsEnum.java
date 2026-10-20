// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

/**
 * The union, in term order, of a field's Luxir terms and its overflow terms. The two sets are
 * disjoint (by length), so at most one source is positioned on any given term.
 */
final class MergedTermsEnum extends BaseTermsEnum {
  private final BaseTermsEnum a;
  private final BaseTermsEnum b;
  private final BytesRefBuilder aTerm = new BytesRefBuilder();
  private final BytesRefBuilder bTerm = new BytesRefBuilder();
  private boolean aLive, bLive, started;
  private BaseTermsEnum current;

  MergedTermsEnum(BaseTermsEnum a, BaseTermsEnum b) {
    this.a = a;
    this.b = b;
  }

  private static boolean load(BytesRef t, BytesRefBuilder into) {
    if (t == null) return false;
    into.copyBytes(t);
    return true;
  }

  private BytesRef pick() {
    if (!aLive && !bLive) {
      current = null;
      return null;
    }
    current = !bLive || (aLive && aTerm.get().compareTo(bTerm.get()) < 0) ? a : b;
    return current == a ? aTerm.get() : bTerm.get();
  }

  @Override
  public BytesRef next() throws IOException {
    if (!started) {
      started = true;
      aLive = load(a.next(), aTerm);
      bLive = load(b.next(), bTerm);
    } else if (current == a) {
      aLive = load(a.next(), aTerm);
    } else if (current == b) {
      bLive = load(b.next(), bTerm);
    } else {
      return null;
    }
    return pick();
  }

  @Override
  public SeekStatus seekCeil(BytesRef text) throws IOException {
    started = true;
    aLive = a.seekCeil(text) != SeekStatus.END && load(a.term(), aTerm);
    bLive = b.seekCeil(text) != SeekStatus.END && load(b.term(), bTerm);
    BytesRef t = pick();
    if (t == null) return SeekStatus.END;
    return t.bytesEquals(text) ? SeekStatus.FOUND : SeekStatus.NOT_FOUND;
  }

  @Override
  public void seekExact(long ord) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BytesRef term() throws IOException {
    return current.term();
  }

  @Override
  public long ord() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int docFreq() throws IOException {
    return current.docFreq();
  }

  @Override
  public long totalTermFreq() throws IOException {
    return current.totalTermFreq();
  }

  @Override
  public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
    return current.postings(null, flags);
  }

  @Override
  public ImpactsEnum impacts(int flags) throws IOException {
    return current.impacts(flags);
  }
}
