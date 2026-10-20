// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.MutablePointTree;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.IntsRef;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Points kept by Luxir: one-dimensional fields of up to 8 bytes per value are Luxir's 1-D
 * sorted-leaf points index (value-sorted leaves of up to 512 points with a min/max fence
 * directory). Lucene's sortable bytes map to order-preserving int64s. Lucene walks an implicit
 * binary tree over the leaves, so range and exact queries, counts and estimates work unchanged.
 * Other shapes go to the delegate format.
 *
 * <p>Files: {@code .lxp} (the Luxir segment) and {@code .lxpm} (fields and file table).
 */
final class LuxirPointsFormat extends PointsFormat {
  static final String DATA_EXTENSION = "lxp";
  static final String META_EXTENSION = "lxpm";
  static final String DATA_CODEC = "LuxirPointsData";
  static final String META_CODEC = "LuxirPointsMeta";
  static final int VERSION = 0;

  private final PointsFormat delegate;

  LuxirPointsFormat(PointsFormat delegate) {
    this.delegate = delegate;
  }

  static boolean nativeShape(FieldInfo fi) {
    return fi.getPointDimensionCount() == 1 && fi.getPointIndexDimensionCount() == 1
        && fi.getPointNumBytes() <= Long.BYTES;
  }

  /** Lucene's sortable bytes (big-endian, order = unsigned byte order) as an ordered int64. */
  static long toLong(byte[] packed, int offset, int bytes) {
    long u = 0;
    for (int k = 0; k < bytes; k++) u = (u << 8) | (packed[offset + k] & 0xFF);
    return bytes == Long.BYTES ? u ^ Long.MIN_VALUE : u;
  }

  static void toBytes(long v, int bytes, byte[] out) {
    long u = bytes == Long.BYTES ? v ^ Long.MIN_VALUE : v;
    for (int k = bytes - 1; k >= 0; k--) {
      out[k] = (byte) u;
      u >>>= 8;
    }
  }

  /** Visits every point under {@code tree} (without asking for cell bounds, which merge views lack). */
  static void visitAll(PointValues.PointTree tree, PointValues.IntersectVisitor visitor) throws IOException {
    if (tree.moveToChild()) {
      do {
        visitAll(tree, visitor);
      } while (tree.moveToSibling());
      tree.moveToParent();
    } else {
      tree.visitDocValues(visitor);
    }
  }

  @Override
  public PointsWriter fieldsWriter(SegmentWriteState state) throws IOException {
    return new Writer(state);
  }

  @Override
  public PointsReader fieldsReader(SegmentReadState state) throws IOException {
    return new Reader(state);
  }

  record NativeField(String name, int bytesPerDim, long pointCount, int docCount) {}

  /** Lucene buffered values of a Luxir value field: the field's two-view protocol failed. */
  static IllegalStateException misrouted(FieldInfo fi) {
    return new IllegalStateException("Luxir value field \"" + fi.name + "\" was also indexed by Lucene's indexing chain");
  }

  // ---- writing -------------------------------------------------------------------------------

  private final class Writer extends PointsWriter {
    private final SegmentWriteState state;
    private MemorySegment w; // lxl_writer*
    private PointsWriter del;
    private final List<NativeField> fields = new ArrayList<>();
    private boolean finished;

    Writer(SegmentWriteState state) {
      this.state = state;
    }

    private PointsWriter delegate() throws IOException {
      if (del == null) del = delegate.fieldsWriter(state);
      return del;
    }

    @Override
    public void writeField(FieldInfo fieldInfo, PointsReader reader) throws IOException {
      if (!nativeShape(fieldInfo)) {
        delegate().writeField(fieldInfo, reader);
        return;
      }
      PointValues values = reader.getValues(fieldInfo.name);
      NativeIndexer ix = LuxirValueField.kind(fieldInfo) == 'n' ? NativeIndexer.get(state.segmentInfo) : null;
      if (ix != null) {
        // a Luxir value field: its points were buffered by the codec, Lucene's buffer is empty
        if (values != null && values.getPointTree().size() > 0) throw misrouted(fieldInfo);
        NativeIndexer.Numbers nums = ix.numbers(fieldInfo.name);
        if (nums == null || nums.numValues == 0) return;
        int[] docs = new int[nums.numValues];
        for (int i = 0, v = 0; i < nums.numDocs; i++) {
          for (int k = 0; k < nums.counts[i]; k++) docs[v++] = nums.docs[i];
        }
        writeNative(fieldInfo, docs, nums.points, nums.numValues, nums.numDocs);
        return;
      }
      if (values == null) return;
      int bytes = fieldInfo.getPointNumBytes();
      int maxDoc = state.segmentInfo.maxDoc();
      FixedBitSet withPoints = new FixedBitSet(maxDoc);
      int[][] docs = {new int[1024]};
      long[][] vals = {new long[1024]};
      int[] n = {0};
      PointValues.PointTree tree = values.getPointTree();
      if (tree instanceof MutablePointTree mutable) {
        // a segment being flushed: the buffered points, read by index
        BytesRef scratch = new BytesRef();
        int size = (int) mutable.size();
        docs[0] = new int[size];
        vals[0] = new long[size];
        for (int i = 0; i < size; i++) {
          mutable.getValue(i, scratch);
          int doc = mutable.getDocID(i);
          docs[0][i] = doc;
          vals[0][i] = toLong(scratch.bytes, scratch.offset, bytes);
          withPoints.set(doc);
        }
        n[0] = size;
      } else visitAll(tree, new PointValues.IntersectVisitor() {
        @Override
        public void visit(int docID) {
          throw new IllegalStateException();
        }

        @Override
        public void visit(int docID, byte[] packedValue) {
          int i = n[0];
          if (i == docs[0].length) {
            docs[0] = ArrayUtil.grow(docs[0], i + 1);
            vals[0] = ArrayUtil.growExact(vals[0], docs[0].length);
          }
          docs[0][i] = docID;
          vals[0][i] = toLong(packedValue, 0, bytes);
          n[0] = i + 1;
          withPoints.set(docID);
        }

        @Override
        public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
          return PointValues.Relation.CELL_CROSSES_QUERY;
        }
      });
      if (n[0] == 0) return;
      writeNative(fieldInfo, docs[0], vals[0], n[0], withPoints.cardinality());
    }

    private void writeNative(FieldInfo fieldInfo, int[] docs, long[] vals, int count, int docCount) {
      if (w == null) w = NativeStore.newWriter(state.segmentInfo.maxDoc());
      byte[] name = LuxirDocValuesFormat.nativeName(fields.size());
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment d = arena.allocate(ValueLayout.JAVA_INT, count);
        MemorySegment.copy(docs, 0, d, ValueLayout.JAVA_INT, 0, count);
        MemorySegment v = arena.allocate(ValueLayout.JAVA_LONG, count);
        MemorySegment.copy(vals, 0, v, ValueLayout.JAVA_LONG, 0, count);
        MemorySegment nm = arena.allocateFrom(ValueLayout.JAVA_BYTE, name);
        MemorySegment ww = w;
        long c = count;
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_POINTS.invokeExact(ww, nm, name.length, c, d, v)));
      }
      fields.add(new NativeField(fieldInfo.name, fieldInfo.getPointNumBytes(), count, docCount));
    }

    /**
     * Native fields are merged without leaving native code (leaves decoded, docs remapped,
     * points re-sorted) when every source is this format; others take Lucene's generic path.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void merge(MergeState ms) throws IOException {
      for (PointsReader r : ms.pointsReaders) {
        if (r != null) r.checkIntegrity();
      }
      boolean nativeOk = !ms.needsIndexSort && NativeStore.allNative(ms.pointsReaders, Reader.class);
      try (Arena arena = Arena.ofConfined()) {
        NativeStore.DocMaps maps = nativeOk ? NativeStore.DocMaps.of(ms, arena) : null;
        for (FieldInfo fi : ms.mergeFieldInfos) {
          if (fi.getPointDimensionCount() == 0) continue;
          if (!nativeOk || !nativeShape(fi)) {
            mergeOneField(ms, fi);
            continue;
          }
          int n = maps.n();
          MemorySegment sources = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
          MemorySegment names = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
          for (int i = 0; i < n; i++) {
            PointsReader r = ms.pointsReaders[i];
            if (r == null) continue;
            Map<String, Object> src = ((java.util.function.Supplier<Map<String, Object>>) r).get();
            Integer ord = ((Map<String, Integer>) src.get("fields")).get(fi.name);
            if (ord == null) continue;
            sources.setAtIndex(ValueLayout.ADDRESS, i, (MemorySegment) src.get("reader"));
            names.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(Integer.toString(ord)));
          }
          if (w == null) w = NativeStore.newWriter(state.segmentInfo.maxDoc());
          byte[] name = LuxirDocValuesFormat.nativeName(fields.size());
          MemorySegment nm = arena.allocateFrom(ValueLayout.JAVA_BYTE, name);
          MemorySegment stats = arena.allocate(ValueLayout.JAVA_LONG, 2);
          MemorySegment ww = w;
          check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_POINTS_MERGE.invokeExact(
              ww, nm, name.length, n, sources, names, maps.bases(), maps.maps(), stats)));
          long points = stats.getAtIndex(ValueLayout.JAVA_LONG, 0);
          if (points == 0) continue;
          fields.add(new NativeField(fi.name, fi.getPointNumBytes(), points, (int) stats.getAtIndex(ValueLayout.JAVA_LONG, 1)));
        }
      }
      finish();
    }

    @Override
    public void finish() throws IOException {
      if (finished) throw new IllegalStateException("already finished");
      finished = true;
      String dataName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, DATA_EXTENSION);
      String metaName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, META_EXTENSION);
      try (IndexOutput data = state.directory.createOutput(dataName, state.context);
          IndexOutput meta = state.directory.createOutput(metaName, state.context)) {
        CodecUtil.writeIndexHeader(data, DATA_CODEC, VERSION, state.segmentInfo.getId(), state.segmentSuffix);
        CodecUtil.writeIndexHeader(meta, META_CODEC, VERSION, state.segmentInfo.getId(), state.segmentSuffix);
        List<NativeStore.Entry> entries = w == null ? List.of() : NativeStore.writeFiles(w, data);
        NativeStore.finishData(data);
        meta.writeVInt(fields.size());
        for (NativeField f : fields) {
          meta.writeString(f.name());
          meta.writeVInt(f.bytesPerDim());
          meta.writeVLong(f.pointCount());
          meta.writeVInt(f.docCount());
        }
        meta.writeByte((byte) (del != null ? 1 : 0));
        NativeStore.writeEntries(meta, entries);
        CodecUtil.writeFooter(meta);
      } finally {
        NativeStore.free(w);
        w = null;
      }
      EarlyWriteback.submit(state.directory, dataName);
      if (del != null) del.finish();
    }

    @Override
    public void close() throws IOException {
      NativeStore.free(w);
      w = null;
      IOUtils.close(del);
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  /**
   * Reader; {@link #get()} exposes the native segment and fields to merges, through JDK types
   * only (the merging writer may come from another classloader).
   */
  private final class Reader extends PointsReader implements java.util.function.Supplier<Map<String, Object>> {
    private final IndexInput data;
    private final NativeStore.Opened store;
    private final Map<String, Integer> ords = new HashMap<>();
    private final List<NativeField> fields = new ArrayList<>();
    private final NativePoints[] opened;
    private final PointsReader del;

    Reader(SegmentReadState state) throws IOException {
      String dataName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, DATA_EXTENSION);
      String metaName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, META_EXTENSION);
      List<NativeStore.Entry> entries = List.of();
      boolean hasDelegate = false;
      try (ChecksumIndexInput meta = state.directory.openChecksumInput(metaName)) {
        Throwable failure = null;
        try {
          CodecUtil.checkIndexHeader(meta, META_CODEC, VERSION, VERSION, state.segmentInfo.getId(), state.segmentSuffix);
          int n = meta.readVInt();
          for (int i = 0; i < n; i++) {
            NativeField f = new NativeField(meta.readString(), meta.readVInt(), meta.readVLong(), meta.readVInt());
            ords.put(f.name(), fields.size());
            fields.add(f);
          }
          hasDelegate = meta.readByte() != 0;
          entries = NativeStore.readEntries(meta);
        } catch (Throwable t) {
          failure = t;
        } finally {
          CodecUtil.checkFooter(meta, failure);
        }
      }
      opened = new NativePoints[fields.size()];
      IndexInput in = null;
      NativeStore.Opened s = null;
      PointsReader d = null;
      boolean ok = false;
      try {
        in = state.directory.openInput(dataName, state.context);
        CodecUtil.checkIndexHeader(in, DATA_CODEC, VERSION, VERSION, state.segmentInfo.getId(), state.segmentSuffix);
        CodecUtil.retrieveChecksum(in);
        if (!entries.isEmpty()) s = new NativeStore.Opened(in, entries);
        if (hasDelegate) d = delegate.fieldsReader(state);
        ok = true;
      } finally {
        if (!ok) IOUtils.closeWhileHandlingException(s, d, in);
      }
      data = in;
      store = s;
      del = d;
    }

    @Override
    public PointValues getValues(String field) throws IOException {
      Integer ord = ords.get(field);
      if (ord == null) return del == null ? null : del.getValues(field);
      synchronized (opened) {
        if (opened[ord] == null) opened[ord] = new NativePoints(store, ord, fields.get(ord));
        return opened[ord];
      }
    }

    @Override
    public Map<String, Object> get() {
      Map<String, Object> d = described;
      if (d == null) {
        described = d = Map.of("reader", store == null ? MemorySegment.NULL : store.reader(), "fields", Map.copyOf(ords));
      }
      return d;
    }

    private volatile Map<String, Object> described;

    @Override
    public void checkIntegrity() throws IOException {
      CodecUtil.checksumEntireFile(data);
      if (del != null) del.checkIntegrity();
    }

    @Override
    public void close() throws IOException {
      try {
        synchronized (opened) {
          for (NativePoints p : opened) {
            if (p != null) p.free();
          }
        }
      } finally {
        IOUtils.close(store, del, data);
      }
    }
  }

  /** One field's points: the native index plus its leaf fences, shared by all threads. */
  // Supplier: the native points (lxl_points*), for native range evaluation in search.
  private static final class NativePoints extends PointValues implements java.util.function.Supplier<MemorySegment> {
    private final NativeStore.Opened store;
    private final MemorySegment points; // lxl_points*
    private final int bytes;
    private final int leafCount;
    private final long pointCount;
    private final int perLeaf;
    private final int docCount;
    private final long[] mins;
    private final long[] maxs;

    NativePoints(NativeStore.Opened store, int ord, NativeField f) {
      this.store = store;
      byte[] name = LuxirDocValuesFormat.nativeName(ord);
      long[] info = new long[3];
      try {
        points = check((MemorySegment) LxlNative.POINTS_NEW.invokeExact(store.reader(), heap(name), name.length, heap(info)));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      leafCount = (int) info[0];
      pointCount = info[1];
      perLeaf = (int) info[2];
      mins = new long[leafCount];
      maxs = new long[leafCount];
      check(NativeStore.invokeInt(() -> (int) LxlNative.POINTS_BOUNDS.invokeExact(points, heap(mins), heap(maxs))));
      bytes = f.bytesPerDim();
      docCount = f.docCount();
      if (pointCount != f.pointCount()) throw new IllegalStateException("point count mismatch for " + f.name());
    }

    @Override
    public MemorySegment get() {
      store.ensureOpen();
      return points;
    }

    void free() {
      try {
        LxlNative.POINTS_FREE.invokeExact(points);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
    }

    byte[] packed(long v) {
      byte[] b = new byte[bytes];
      toBytes(v, bytes, b);
      return b;
    }

    long leafStart(int leaf) {
      return (long) leaf * perLeaf;
    }

    long leafEnd(int leaf) {
      return Math.min((long) (leaf + 1) * perLeaf, pointCount);
    }

    @Override
    public PointTree getPointTree() {
      return new Tree(this);
    }

    @Override
    public byte[] getMinPackedValue() {
      return packed(mins[0]);
    }

    @Override
    public byte[] getMaxPackedValue() {
      return packed(maxs[leafCount - 1]);
    }

    @Override
    public int getNumDimensions() {
      return 1;
    }

    @Override
    public int getNumIndexDimensions() {
      return 1;
    }

    @Override
    public int getBytesPerDimension() {
      return bytes;
    }

    @Override
    public long size() {
      return pointCount;
    }

    @Override
    public int getDocCount() {
      return docCount;
    }
  }

  /** An implicit binary tree over leaf ranges; leaves of the tree are Luxir leaves. */
  private static final class Tree implements PointValues.PointTree {
    private final NativePoints p;
    private int lo, hi; // leaves [lo, hi)
    private int depth;
    private int[] los = new int[32], his = new int[32];
    private int[] docs;
    private long[] values;
    private byte[] scratch;

    Tree(NativePoints p) {
      this.p = p;
      this.lo = 0;
      this.hi = p.leafCount;
    }

    @Override
    public PointValues.PointTree clone() {
      Tree t = new Tree(p);
      t.lo = lo;
      t.hi = hi;
      t.depth = depth;
      t.los = los.clone();
      t.his = his.clone();
      return t;
    }

    @Override
    public boolean moveToChild() {
      if (hi - lo <= 1) return false;
      if (depth == los.length) {
        los = ArrayUtil.grow(los, depth + 1);
        his = ArrayUtil.growExact(his, los.length);
      }
      los[depth] = lo;
      his[depth] = hi;
      depth++;
      hi = (lo + hi) >>> 1;
      return true;
    }

    @Override
    public boolean moveToSibling() {
      if (depth == 0) return false;
      int plo = los[depth - 1], phi = his[depth - 1];
      int mid = (plo + phi) >>> 1;
      if (lo == plo && hi == mid) {
        lo = mid;
        hi = phi;
        return true;
      }
      return false;
    }

    @Override
    public boolean moveToParent() {
      if (depth == 0) return false;
      depth--;
      lo = los[depth];
      hi = his[depth];
      return true;
    }

    @Override
    public byte[] getMinPackedValue() {
      return p.packed(p.mins[lo]);
    }

    @Override
    public byte[] getMaxPackedValue() {
      return p.packed(p.maxs[hi - 1]);
    }

    @Override
    public long size() {
      return p.leafEnd(hi - 1) - p.leafStart(lo);
    }

    private int leaf(int leaf, boolean withValues) {
      p.store.ensureOpen();
      if (docs == null) docs = new int[p.perLeaf];
      if (withValues && values == null) values = new long[p.perLeaf];
      MemorySegment v = withValues ? heap(values) : MemorySegment.NULL;
      try {
        return check((int) LxlNative.POINTS_LEAF.invokeExact(p.points, leaf, heap(docs), v));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
    }

    @Override
    public void visitDocIDs(PointValues.IntersectVisitor visitor) throws IOException {
      visitor.grow((int) Math.min(size(), Integer.MAX_VALUE));
      IntsRef ref = new IntsRef();
      for (int l = lo; l < hi; l++) {
        int n = leaf(l, false);
        ref.ints = docs;
        ref.offset = 0;
        ref.length = n;
        visitor.visit(ref);
      }
    }

    @Override
    public void visitDocValues(PointValues.IntersectVisitor visitor) throws IOException {
      if (scratch == null) scratch = new byte[p.bytes];
      for (int l = lo; l < hi; l++) {
        int n = leaf(l, true);
        visitor.grow(n);
        long prev = 0;
        for (int i = 0; i < n; i++) {
          long v = values[i];
          if (i == 0 || v != prev) {
            toBytes(v, p.bytes, scratch);
            prev = v;
          }
          visitor.visit(docs[i], scratch);
        }
      }
    }
  }
}
