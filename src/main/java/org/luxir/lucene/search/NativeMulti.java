// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.luxir.lucene.LxlNative;

/**
 * The native multi-segment view (lxl_multi) of one top-level reader: one native segment per leaf
 * that stores any field in Luxir format. Cached per reader and freed when the reader closes;
 * Lucene's reference counting keeps the leaves (and the native segments they own) open while a
 * search runs.
 */
final class NativeMulti {
  final MemorySegment handle;
  final int[] segOfLeaf; // -1: the leaf has no Luxir fields
  final int[] leafOfSeg;
  final long[] readerOfLeaf; // native reader address per leaf, 0 if none

  private NativeMulti(MemorySegment handle, int[] segOfLeaf, int[] leafOfSeg, long[] readerOfLeaf) {
    this.handle = handle;
    this.segOfLeaf = segOfLeaf;
    this.leafOfSeg = leafOfSeg;
    this.readerOfLeaf = readerOfLeaf;
  }

  private static final Map<IndexReader.CacheKey, NativeMulti> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

  /** The view for {@code reader}, or null if no leaf stores a field in Luxir format. */
  static NativeMulti of(IndexReader reader) throws IOException {
    IndexReader.CacheHelper helper = reader.getReaderCacheHelper();
    if (helper != null) {
      NativeMulti m = CACHE.get(helper.getKey());
      if (m != null) return m;
    }
    NativeMulti m = build(reader.leaves());
    if (m != null && helper != null) {
      synchronized (CACHE) {
        NativeMulti prior = CACHE.get(helper.getKey());
        if (prior != null) {
          m.free();
          return prior;
        }
        CACHE.put(helper.getKey(), m);
      }
      NativeMulti built = m;
      helper.addClosedListener(key -> {
        CACHE.remove(key);
        built.free();
      });
    }
    return m;
  }

  private static NativeMulti build(List<LeafReaderContext> leaves) throws IOException {
    int[] segOfLeaf = new int[leaves.size()];
    long[] readerOfLeaf = new long[leaves.size()];
    int n = 0;
    for (int i = 0; i < leaves.size(); i++) {
      MemorySegment r = anyNativeReader(leaves.get(i).reader());
      segOfLeaf[i] = r == null ? -1 : n++;
      readerOfLeaf[i] = r == null ? 0 : r.address();
    }
    if (n == 0) return null;
    int[] leafOfSeg = new int[n];
    MemorySegment handle;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment readers = arena.allocate(ValueLayout.ADDRESS, n);
      for (int i = 0; i < leaves.size(); i++) {
        if (segOfLeaf[i] < 0) continue;
        leafOfSeg[segOfLeaf[i]] = i;
        readers.setAtIndex(ValueLayout.ADDRESS, segOfLeaf[i], MemorySegment.ofAddress(readerOfLeaf[i]));
      }
      handle = check((MemorySegment) LxlNative.MULTI_NEW.invokeExact(n, readers));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
    return new NativeMulti(handle, segOfLeaf, leafOfSeg, readerOfLeaf);
  }

  private static MemorySegment anyNativeReader(LeafReader leaf) throws IOException {
    LeafReader base = NativeLeaf.unwrap(leaf);
    for (FieldInfo fi : base.getFieldInfos()) {
      Terms t = base.terms(fi.name);
      MemorySegment r = NativeLeaf.nativeReader(t);
      if (r != null) return r;
    }
    return null;
  }

  private boolean freed;

  synchronized void free() {
    if (freed) return;
    freed = true;
    try {
      LxlNative.MULTI_FREE.invokeExact(handle);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }
}
