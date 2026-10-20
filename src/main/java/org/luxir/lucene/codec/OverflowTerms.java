// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SlowImpactsEnum;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * Terms longer than Luxir's 255-byte limit, kept beside a field's Luxir postings in the segment
 * metadata and held in memory. Such terms are rare (a token of multi-byte characters near the
 * tokenizer's length limit), so a field keeps its native storage instead of falling back whole.
 */
final class OverflowTerms {
  static final OverflowTerms EMPTY = new OverflowTerms(new BytesRef[0], new int[0][], new int[0][], new int[0][]);

  final BytesRef[] terms; // sorted
  final int[][] docs;
  final int[][] freqs;
  final int[][] positions; // per term, every doc's positions in order

  private OverflowTerms(BytesRef[] terms, int[][] docs, int[][] freqs, int[][] positions) {
    this.terms = terms;
    this.docs = docs;
    this.freqs = freqs;
    this.positions = positions;
  }

  boolean isEmpty() {
    return terms.length == 0;
  }

  /** Serializes terms as they are collected at write time (in term order). */
  static final class Writer {
    final ByteBuffersDataOutput out = new ByteBuffersDataOutput();
    int count;

    void add(BytesRef term, int[] docs, int[] freqs, int n, int[] positions) throws IOException {
      count++;
      out.writeVInt(term.length);
      out.writeBytes(term.bytes, term.offset, term.length);
      out.writeVInt(n);
      int prevDoc = 0, p = 0;
      for (int i = 0; i < n; i++) {
        out.writeVInt(docs[i] - prevDoc);
        prevDoc = docs[i];
        out.writeVInt(freqs[i]);
        int prevPos = 0;
        for (int j = 0; j < freqs[i]; j++, p++) {
          out.writeVInt(positions[p] - prevPos);
          prevPos = positions[p];
        }
      }
    }

    void writeTo(DataOutput meta) throws IOException {
      meta.writeVInt(count);
      meta.writeVLong(out.size());
      out.copyTo(meta);
    }
  }

  static OverflowTerms read(org.apache.lucene.store.DataInput meta) throws IOException {
    int count = meta.readVInt();
    long size = meta.readVLong();
    if (count == 0) return EMPTY;
    byte[] bytes = new byte[Math.toIntExact(size)];
    meta.readBytes(bytes, 0, bytes.length);
    ByteArrayDataInput in = new ByteArrayDataInput(bytes);
    BytesRef[] terms = new BytesRef[count];
    int[][] docs = new int[count][], freqs = new int[count][], positions = new int[count][];
    for (int t = 0; t < count; t++) {
      byte[] term = new byte[in.readVInt()];
      in.readBytes(term, 0, term.length);
      terms[t] = new BytesRef(term);
      int n = in.readVInt();
      docs[t] = new int[n];
      freqs[t] = new int[n];
      int[] pos = new int[16];
      int np = 0, doc = 0;
      for (int i = 0; i < n; i++) {
        doc += in.readVInt();
        docs[t][i] = doc;
        int f = in.readVInt();
        freqs[t][i] = f;
        if (np + f > pos.length) pos = Arrays.copyOf(pos, Math.max(np + f, pos.length * 2));
        int p = 0;
        for (int j = 0; j < f; j++) {
          p += in.readVInt();
          pos[np++] = p;
        }
      }
      positions[t] = Arrays.copyOf(pos, np);
    }
    return new OverflowTerms(terms, docs, freqs, positions);
  }

  /** Enumerates the overflow terms, optionally only those an automaton accepts. */
  final class Enum extends BaseTermsEnum {
    private final CompiledAutomaton filter;
    private int i = -1;

    Enum(CompiledAutomaton filter) {
      this.filter = filter;
    }

    private boolean accepted(int idx) {
      return filter == null || filter.runAutomaton.run(terms[idx].bytes, terms[idx].offset, terms[idx].length);
    }

    @Override
    public BytesRef next() {
      do {
        i++;
      } while (i < terms.length && !accepted(i));
      return i < terms.length ? terms[i] : null;
    }

    @Override
    public SeekStatus seekCeil(BytesRef text) {
      if (filter != null) throw new UnsupportedOperationException("seek on an intersect enum");
      int idx = Arrays.binarySearch(terms, text);
      if (idx >= 0) {
        i = idx;
        return SeekStatus.FOUND;
      }
      i = -idx - 1;
      return i < terms.length ? SeekStatus.NOT_FOUND : SeekStatus.END;
    }

    @Override
    public void seekExact(long ord) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BytesRef term() {
      return terms[i];
    }

    @Override
    public long ord() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int docFreq() {
      return docs[i].length;
    }

    @Override
    public long totalTermFreq() {
      long sum = 0;
      for (int f : freqs[i]) sum += f;
      return sum;
    }

    @Override
    public PostingsEnum postings(PostingsEnum reuse, int flags) {
      return new Postings(i);
    }

    @Override
    public ImpactsEnum impacts(int flags) {
      return new SlowImpactsEnum(postings(null, flags));
    }
  }

  private final class Postings extends PostingsEnum {
    private final int t;
    private int idx = -1, posBase, posIdx;

    Postings(int t) {
      this.t = t;
    }

    @Override
    public int docID() {
      return idx < 0 ? -1 : idx < docs[t].length ? docs[t][idx] : NO_MORE_DOCS;
    }

    @Override
    public int nextDoc() {
      if (idx >= 0 && idx < docs[t].length) posBase += freqs[t][idx];
      idx++;
      posIdx = 0;
      return docID();
    }

    @Override
    public int advance(int target) {
      int d;
      while ((d = nextDoc()) < target) {}
      return d;
    }

    @Override
    public int freq() {
      return freqs[t][idx];
    }

    @Override
    public int nextPosition() {
      return positions[t][posBase + posIdx++];
    }

    @Override
    public int startOffset() {
      return -1;
    }

    @Override
    public int endOffset() {
      return -1;
    }

    @Override
    public BytesRef getPayload() {
      return null;
    }

    @Override
    public long cost() {
      return docs[t].length;
    }
  }
}
