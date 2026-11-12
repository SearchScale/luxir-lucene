// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.lucene.index.ExitableDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.luxir.lucene.codec.LuxirTerms;

/** Per-leaf plumbing shared by the native search paths. */
final class NativeLeaf {
  private NativeLeaf() {}

  /** As {@link #reader(LeafReader, Set)}, also requiring no overflow term to match a native prefix. */
  @SuppressWarnings("unchecked")
  static Supplier<MemorySegment> reader(LeafReader reader, LxlQuery.Encoded enc) throws IOException {
    Supplier<MemorySegment> r = reader(reader, enc.fields());
    if (r == null || enc.prefixes().isEmpty()) return r;
    LeafReader base = unwrap(reader);
    for (Map.Entry<String, byte[]> p : enc.prefixes()) {
      if (base.terms(p.getKey()) instanceof Predicate<?> overflow && ((Predicate<byte[]>) overflow).test(p.getValue())) {
        return null;
      }
    }
    return r;
  }

  /**
   * The segment's native reader if every one of {@code fields} that exists in this leaf is stored
   * in Luxir format in one segment; null if any is not (or none exists), meaning use Lucene. The
   * supplier checks the segment is still open each time it is called.
   */
  @SuppressWarnings("unchecked")
  static Supplier<MemorySegment> reader(LeafReader reader, Set<String> fields) throws IOException {
    LeafReader base = unwrap(reader);
    Supplier<MemorySegment> found = null;
    for (String f : fields) {
      Terms t = base.terms(f);
      if (t == null) continue;
      if (!(t instanceof Supplier<?> s) || !t.getClass().getName().equals(LUXIR_TERMS)) return null;
      if (found == null) found = (Supplier<MemorySegment>) s;
      else if (found.get().address() != ((MemorySegment) s.get()).address()) return null;
    }
    return found;
  }

  private static final String LUXIR_TERMS = LuxirTerms.class.getName();

  /** The native reader behind a Luxir-format Terms (from any classloader), else null. */
  static MemorySegment nativeReader(Terms t) {
    if (t instanceof Supplier<?> s && t.getClass().getName().equals(LUXIR_TERMS)) {
      return (MemorySegment) s.get();
    }
    return null;
  }

  // Reader wrappers known not to change documents, terms or postings (only add checks or doc
  // values), which native execution may look through.
  private static final Set<Class<?>> TRANSPARENT =
      new CopyOnWriteArraySet<>(List.of(ExitableDirectoryReader.ExitableFilterAtomicReader.class));

  static void registerTransparentWrapper(Class<? extends FilterLeafReader> wrapper) {
    TRANSPARENT.add(wrapper);
  }

  static LeafReader unwrap(LeafReader r) {
    while (r instanceof FilterLeafReader f && TRANSPARENT.contains(r.getClass())) r = f.getDelegate();
    return r;
  }

  private static final String NATIVE_POINTS = "org.luxir.lucene.codec.LuxirPointsFormat$NativePoints";

  /**
   * The leaf's native points for {@code field} (lxl_points*); {@link MemorySegment#NULL} if the
   * leaf has no points for it; null if they are not in Luxir format.
   */
  static MemorySegment nativePoints(LeafReader reader, String field) throws IOException {
    org.apache.lucene.index.PointValues pv = unwrap(reader).getPointValues(field);
    if (pv == null) return MemorySegment.NULL;
    if (pv instanceof Supplier<?> s && pv.getClass().getName().equals(NATIVE_POINTS)) {
      return (MemorySegment) s.get();
    }
    return null;
  }

  private static final String NATIVE_DV = "org.luxir.lucene.codec.LuxirDocValuesFormat$Producer";

  /** A leaf's native doc values for one field: the store (lxl_reader*) and its column. */
  record NativeColumn(MemorySegment store, byte[] column, boolean multi) {}

  /**
   * The leaf's native column for {@code field}; {@link #NO_COLUMN} if the leaf's native doc
   * values have no values for it; null if its doc values are not native.
   */
  @SuppressWarnings("unchecked")
  static NativeColumn nativeColumn(LeafReader reader, String field) {
    if (!(unwrap(reader) instanceof org.apache.lucene.index.CodecReader cr)) return null;
    Object p = cr.getDocValuesReader();
    if (!(p instanceof Supplier<?> s) || !p.getClass().getName().equals(NATIVE_DV)) return null;
    Map<String, Object> m = (Map<String, Object>) s.get();
    long[] f = ((Map<String, long[]>) m.get("fields")).get(field);
    if (f == null) return NO_COLUMN;
    return new NativeColumn((MemorySegment) m.get("reader"),
        Integer.toString((int) f[0]).getBytes(java.nio.charset.StandardCharsets.US_ASCII), f[3] != 0);
  }

  static final NativeColumn NO_COLUMN = new NativeColumn(MemorySegment.NULL, new byte[0], false);

  /** Whether none of {@code fields} has postings in this leaf (so the query matches nothing). */
  static boolean empty(LeafReader reader, Set<String> fields) throws IOException {
    LeafReader base = unwrap(reader);
    for (String f : fields) {
      if (base.terms(f) != null) return false;
    }
    return true;
  }

  /** Native copy of a Bits (live docs), cached per instance: live docs only change on reopen. */
  record Words(MemorySegment words, int count) {}

  private static final Map<Bits, Words> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

  static Words live(Bits bits, int maxDoc) {
    if (bits == null) return null;
    Words w = CACHE.get(bits);
    if (w == null) {
      w = copy(bits, maxDoc, Arena.ofAuto());
      CACHE.put(bits, w);
    }
    return w;
  }

  private static Words copy(Bits bits, int maxDoc, Arena arena) {
    int nwords = FixedBitSet.bits2words(maxDoc);
    MemorySegment seg = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, nwords));
    int count = 0;
    for (int w = 0; w < nwords; w++) {
      long word = 0;
      int base = w << 6;
      int end = Math.min(64, maxDoc - base);
      for (int i = 0; i < end; i++) {
        if (bits.get(base + i)) word |= 1L << i;
      }
      seg.setAtIndex(ValueLayout.JAVA_LONG, w, word);
      count += Long.bitCount(word);
    }
    return new Words(seg, count);
  }

  private record LeafWords(LeafReader leaf, Words words) {}

  // Filters that outlive the request (Solr's cached filter doc sets), ANDed with each leaf's live
  // docs, copied once: keyed by the filter's words (arrays hash by identity), freed with them.
  private static final Map<long[], LeafWords[]> FILTERS = Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * As {@link #liveAndFilter}, for a filter that is not modified while it lives (one from a
   * cache): its native copy for the leaf is made once and reused.
   */
  static Words cachedLiveAndFilter(LeafReaderContext ctx, FixedBitSet filter, int numLeaves) {
    LeafWords[] perLeaf = FILTERS.computeIfAbsent(filter.getBits(), k -> new LeafWords[numLeaves]);
    if (ctx.ord >= perLeaf.length) {
      return liveAndFilter(ctx.reader().getLiveDocs(), filter, ctx.docBase, ctx.reader().maxDoc(), Arena.ofAuto());
    }
    synchronized (perLeaf) {
      LeafWords lw = perLeaf[ctx.ord];
      if (lw == null || lw.leaf() != ctx.reader()) {
        Words w = liveAndFilter(ctx.reader().getLiveDocs(), filter, ctx.docBase, ctx.reader().maxDoc(), Arena.ofAuto());
        perLeaf[ctx.ord] = lw = new LeafWords(ctx.reader(), w);
      }
      return lw.words();
    }
  }

  /**
   * Live docs of a leaf ANDed with a filter over the whole index (global doc ids), in native
   * memory from {@code arena}. Either may be null; null result means "all docs".
   */
  static Words liveAndFilter(Bits live, FixedBitSet filter, int docBase, int maxDoc, Arena arena) {
    if (filter == null) return live(live, maxDoc);
    Words lw = live(live, maxDoc);
    int nwords = FixedBitSet.bits2words(maxDoc);
    MemorySegment seg = arena.allocate(ValueLayout.JAVA_LONG, Math.max(1, nwords));
    long[] fb = filter.getBits();
    int shift = docBase & 63;
    int first = docBase >>> 6;
    int count = 0;
    // built in Java chunks and bulk-copied: per-word segment access is slow until JIT-compiled
    long[] out = scratch(0);
    long[] liveChunk = scratch(1);
    for (int start = 0; start < nwords; start += out.length) {
      int len = Math.min(out.length, nwords - start);
      if (lw != null) MemorySegment.copy(lw.words(), ValueLayout.JAVA_LONG, (long) start * Long.BYTES, liveChunk, 0, len);
      for (int j = 0; j < len; j++) {
        int w = start + j;
        int i = first + w;
        long lo = i < fb.length ? fb[i] : 0;
        long word;
        if (shift == 0) {
          word = lo;
        } else {
          long hi = i + 1 < fb.length ? fb[i + 1] : 0;
          word = (lo >>> shift) | (hi << (64 - shift));
        }
        int rem = maxDoc - (w << 6);
        if (rem < 64) word &= (1L << rem) - 1;
        if (lw != null) word &= liveChunk[j];
        out[j] = word;
        count += Long.bitCount(word);
      }
      MemorySegment.copy(out, 0, seg, ValueLayout.JAVA_LONG, (long) start * Long.BYTES, len);
    }
    return new Words(seg, count);
  }

  private static final ThreadLocal<long[][]> SCRATCH = ThreadLocal.withInitial(() -> new long[2][4096]);

  /** One of the calling thread's two scratch word arrays (for chunked copies; not reentrant). */
  static long[] scratch(int which) {
    return SCRATCH.get()[which];
  }
}
