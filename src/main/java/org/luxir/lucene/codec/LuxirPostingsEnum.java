// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.LxlNative;

/**
 * Lucene postings over a Luxir term. Docs-and-freqs iteration fills a block of {@value #BLOCK}
 * docs per native call; positional iteration makes one call per doc plus one for its positions.
 * The native enum is freed by NativeCleaner, so native calls are followed by reachability fences.
 */
final class LuxirPostingsEnum extends PostingsEnum {
  static final int BLOCK = 128;

  private final LuxirFieldsProducer producer;
  final boolean positions;
  private final NativeHandle handle;
  // docs-and-freqs mode
  private final int[] docs;
  private final int[] freqs;
  private int count;
  private int idx;
  // positions mode
  private final int[] freqOut;
  private int[] posBuf;
  private int posIdx;
  private boolean posLoaded;
  private int freq;

  private int doc = -1;
  private long cost;

  LuxirPostingsEnum(LuxirFieldsProducer producer, boolean positions) {
    this.producer = producer;
    this.positions = positions;
    this.handle = NativeHandle.register(this, LxlNative.POSTINGS_FREE);
    this.docs = positions ? null : new int[BLOCK];
    this.freqs = positions ? null : new int[BLOCK];
    this.freqOut = positions ? new int[1] : null;
    this.posBuf = positions ? new int[16] : null;
  }

  void reset(MemorySegment p, long docFreq) {
    handle.set(p);
    cost = docFreq;
    doc = -1;
    count = idx = 0;
    posLoaded = true;
  }

  private void refill(int target) {
    producer.ensureOpen();
    int n;
    try {
      n = (int) LxlNative.POSTINGS_FILL.invokeExact(handle.get(), target, heap(docs), heap(freqs), BLOCK);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    count = check(n);
    idx = 0;
    doc = count == 0 ? NO_MORE_DOCS : docs[0];
  }

  private int step(int target) {
    producer.ensureOpen();
    int d;
    try {
      d = (int) LxlNative.POSTINGS_NEXT.invokeExact(handle.get(), target, heap(freqOut));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    check(d);
    if (d == LxlNative.END) return doc = NO_MORE_DOCS;
    freq = freqOut[0];
    posLoaded = false;
    posIdx = 0;
    return doc = d;
  }

  @Override
  public int nextDoc() throws IOException {
    if (doc == NO_MORE_DOCS) return doc;
    if (positions) return step(-1);
    if (++idx < count) return doc = docs[idx];
    refill(-1);
    return doc;
  }

  @Override
  public int advance(int target) throws IOException {
    if (doc == NO_MORE_DOCS) return doc;
    if (positions) return step(target);
    for (int i = idx + 1; i < count; i++) {
      if (docs[i] >= target) {
        idx = i;
        return doc = docs[i];
      }
    }
    refill(target);
    return doc;
  }

  @Override
  public int docID() {
    return doc;
  }

  @Override
  public int freq() throws IOException {
    return positions ? freq : freqs[idx];
  }

  @Override
  public int nextPosition() throws IOException {
    if (!positions) return -1;
    if (!posLoaded) {
      if (posBuf.length < freq) posBuf = new int[ArrayUtil.oversize(freq, Integer.BYTES)];
      int n;
      try {
        n = (int) LxlNative.POSTINGS_POSITIONS.invokeExact(handle.get(), heap(posBuf), posBuf.length);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      } finally {
        Reference.reachabilityFence(this);
      }
      check(n);
      posLoaded = true;
    }
    return posBuf[posIdx++];
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
    return cost;
  }
}
