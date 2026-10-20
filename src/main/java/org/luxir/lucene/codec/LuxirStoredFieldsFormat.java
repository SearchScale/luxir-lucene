// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.StoredFieldDataInput;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.UnicodeUtil;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.document.LuxirTextField;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Stored fields kept by Luxir: every stored value goes to the segment's Luxir stored-field
 * resource (LZ4-compressed chunks of whole documents), tagged with its type. This is also the
 * channel through which Luxir-indexed text reaches Luxir: values of {@link LuxirTextField}s go to
 * the segment's {@link NativeIndexer} as each document is indexed, and are stored only if the
 * field asks for it.
 *
 * <p>Files: {@code .lxs} (the Luxir segment) and {@code .lxsm} (field ids and file table).
 */
final class LuxirStoredFieldsFormat extends StoredFieldsFormat {
  static final String DATA_EXTENSION = "lxs";
  static final String META_EXTENSION = "lxsm";
  static final String DATA_CODEC = "LuxirStoredData";
  static final String META_CODEC = "LuxirStoredMeta";
  static final int VERSION = 0;

  // value tags (first byte of each stored value)
  static final byte STRING = 0, BINARY = 1, INT = 2, LONG = 3, FLOAT = 4, DOUBLE = 5;

  private final Supplier<String> nativeSchema; // null: this codec cannot index Luxir text
  private static final String EMPTY_SCHEMA = "{\"fields\":{}}";

  LuxirStoredFieldsFormat(Supplier<String> nativeSchema) {
    this.nativeSchema = nativeSchema;
  }

  @Override
  public StoredFieldsReader fieldsReader(Directory dir, SegmentInfo si, FieldInfos fn, IOContext context)
      throws IOException {
    return new Reader(new Core(dir, si, fn, context));
  }

  @Override
  public StoredFieldsWriter fieldsWriter(Directory dir, SegmentInfo si, IOContext context) throws IOException {
    return new Writer(dir, si, context);
  }

  // ---- writing -------------------------------------------------------------------------------

  private final class Writer extends StoredFieldsWriter {
    private static final int BATCH_BYTES = 1 << 20;

    private final Directory dir;
    private final SegmentInfo si;
    private final IOContext context;
    private NativeIndexer indexer;
    private MemorySegment w; // lxl_writer*, created with the first stored value
    private final Map<Integer, String> fieldNames = new TreeMap<>();
    private int doc = -1;
    private boolean merging;
    private boolean finished;
    // pending values, sent to native in batches
    private int n;
    private int[] docs = new int[1024];
    private int[] fields = new int[1024];
    private long[] offsets = new long[1025];
    private byte[] data = new byte[1 << 16];
    private int dataLen;
    private long nativeRam;

    Writer(Directory dir, SegmentInfo si, IOContext context) {
      this.dir = dir;
      this.si = si;
      this.context = context;
    }

    @Override
    public void startDocument() {
      doc++;
      if (!merging) LuxirValueField.startDocument();
    }

    @Override
    public void finishDocument() throws IOException {
      if (dataLen >= BATCH_BYTES) sendBatch();
      if (indexer != null) indexer.maybeFlush();
    }

    private NativeIndexer indexer(FieldInfo info) {
      if (indexer == null) {
        boolean text = LuxirTextField.isNative(info);
        if (text && nativeSchema == null) {
          throw new IllegalStateException("field " + info.name + " is Luxir-indexed but the codec has no native schema");
        }
        if (si.getIndexSort() != null) {
          throw new IllegalArgumentException("Luxir-indexed fields do not support index sorting");
        }
        // value fields need no schema; a later text field needs the codec's
        indexer = NativeIndexer.create(si, nativeSchema != null ? nativeSchema.get() : EMPTY_SCHEMA);
      }
      return indexer;
    }

    /** Starts a value of {@code size} bytes after its tag; returns where the bytes go. */
    private int start(FieldInfo info, byte tag, int size) {
      if (n == docs.length) {
        docs = ArrayUtil.grow(docs, n + 1);
        fields = ArrayUtil.growExact(fields, docs.length);
        offsets = ArrayUtil.growExact(offsets, docs.length + 1);
      }
      if (data.length < dataLen + 1 + size) data = ArrayUtil.grow(data, dataLen + 1 + size);
      docs[n] = doc;
      fields[n] = info.number;
      offsets[n] = dataLen;
      fieldNames.putIfAbsent(info.number, info.name);
      data[dataLen++] = tag;
      return dataLen;
    }

    private void end(int end) {
      dataLen = end;
      offsets[++n] = dataLen;
    }

    private void storeUtf8(FieldInfo info, byte[] b, int off, int len) {
      int p = start(info, STRING, len);
      System.arraycopy(b, off, data, p, len);
      end(p + len);
    }

    private static boolean stored(FieldInfo info) {
      return "1".equals(info.getAttribute(LuxirTextField.STORED));
    }

    /** Whether a value of this field is a Luxir value (indexed natively), outside merges. */
    private boolean value(FieldInfo info, char kind) {
      return !merging && LuxirValueField.kind(info) == kind;
    }

    private static long point32(int sortable) {
      return (sortable ^ 0x80000000) & 0xFFFFFFFFL;
    }

    private static boolean multi(FieldInfo info) {
      return info.getDocValuesType() == DocValuesType.SORTED_NUMERIC;
    }

    @Override
    public void writeField(FieldInfo info, String value) {
      if (value(info, 's')) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        indexer(info).addString(doc, info, utf8, 0, utf8.length);
        if (stored(info)) storeUtf8(info, utf8, 0, utf8.length);
        return;
      }
      if (!merging && LuxirTextField.isNative(info)) {
        indexer(info).add(doc, info, value);
        if (!stored(info)) return;
      }
      int p = start(info, STRING, UnicodeUtil.maxUTF8Length(value.length()));
      end(UnicodeUtil.UTF16toUTF8(value, 0, value.length(), data, p));
    }

    @Override
    public void writeField(FieldInfo info, BytesRef value) {
      if (value(info, 's')) {
        indexer(info).addString(doc, info, value.bytes, value.offset, value.length);
        if (stored(info)) storeUtf8(info, value.bytes, value.offset, value.length);
        return;
      }
      if (!merging && LuxirTextField.isNative(info)) {
        // Luxir text given as UTF-8 bytes: straight into the native batch
        indexer(info).add(doc, info, value.bytes, value.offset, value.length);
        if (stored(info)) storeUtf8(info, value.bytes, value.offset, value.length);
        return;
      }
      int p = start(info, BINARY, value.length);
      System.arraycopy(value.bytes, value.offset, data, p, value.length);
      end(p + value.length);
    }

    @Override
    public void writeField(FieldInfo info, int value) {
      if (value(info, 'n')) {
        indexer(info).addNumber(doc, info, value, point32(value));
        if (!stored(info)) return;
      }
      int p = start(info, INT, Integer.BYTES);
      BitUtil.VH_LE_INT.set(data, p, value);
      end(p + Integer.BYTES);
    }

    @Override
    public void writeField(FieldInfo info, long value) {
      if (value(info, 'n')) {
        indexer(info).addNumber(doc, info, value, value);
        if (!stored(info)) return;
      }
      int p = start(info, LONG, Long.BYTES);
      BitUtil.VH_LE_LONG.set(data, p, value);
      end(p + Long.BYTES);
    }

    @Override
    public void writeField(FieldInfo info, float value) {
      if (value(info, 'n')) {
        int sortable = NumericUtils.floatToSortableInt(value);
        indexer(info).addNumber(doc, info, multi(info) ? sortable : Float.floatToIntBits(value), point32(sortable));
        if (!stored(info)) return;
      }
      int p = start(info, FLOAT, Integer.BYTES);
      BitUtil.VH_LE_INT.set(data, p, Float.floatToRawIntBits(value));
      end(p + Integer.BYTES);
    }

    @Override
    public void writeField(FieldInfo info, double value) {
      if (value(info, 'n')) {
        long sortable = NumericUtils.doubleToSortableLong(value);
        indexer(info).addNumber(doc, info, multi(info) ? sortable : Double.doubleToLongBits(value), sortable);
        if (!stored(info)) return;
      }
      int p = start(info, DOUBLE, Long.BYTES);
      BitUtil.VH_LE_LONG.set(data, p, Double.doubleToRawLongBits(value));
      end(p + Long.BYTES);
    }

    private void sendBatch() {
      if (n == 0) return;
      if (w == null) w = NativeStore.newWriter(0);
      MemorySegment ww = w;
      int count = n;
      check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_STORED.invokeExact(
          ww, count, heap(docs), heap(fields), heap(data), heap(offsets))));
      try {
        nativeRam = (long) LxlNative.WRITER_RAM.invokeExact(ww);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      n = 0;
      dataLen = 0;
      offsets[0] = 0;
    }

    @Override
    public void finish(int numDocs) throws IOException {
      if (doc + 1 != numDocs) {
        throw new IllegalStateException("stored fields for " + (doc + 1) + " docs, segment has " + numDocs);
      }
      sendBatch();
      if (indexer != null) indexer.flushBatch();
      String dataName = IndexFileNames.segmentFileName(si.name, "", DATA_EXTENSION);
      String metaName = IndexFileNames.segmentFileName(si.name, "", META_EXTENSION);
      try (IndexOutput dataOut = dir.createOutput(dataName, context);
          IndexOutput meta = dir.createOutput(metaName, context)) {
        CodecUtil.writeIndexHeader(dataOut, DATA_CODEC, VERSION, si.getId(), "");
        CodecUtil.writeIndexHeader(meta, META_CODEC, VERSION, si.getId(), "");
        List<NativeStore.Entry> entries = List.of();
        if (w != null) {
          NativeStore.setMaxDoc(w, numDocs);
          entries = NativeStore.writeFiles(w, dataOut);
        }
        NativeStore.finishData(dataOut);
        meta.writeVInt(numDocs);
        meta.writeVInt(fieldNames.size());
        for (Map.Entry<Integer, String> e : fieldNames.entrySet()) {
          meta.writeVInt(e.getKey());
          meta.writeString(e.getValue());
        }
        NativeStore.writeEntries(meta, entries);
        CodecUtil.writeFooter(meta);
      } finally {
        NativeStore.free(w);
        w = null;
      }
      EarlyWriteback.submit(dir, dataName);
      finished = true; // the postings writer takes the indexer next
    }

    @Override
    public int merge(MergeState mergeState) throws IOException {
      if (!mergeState.needsIndexSort && allNative(mergeState)) return mergeNatively(mergeState);
      // Merged Luxir postings come from the source segments: stored values are only copied.
      merging = true;
      try {
        return super.merge(mergeState);
      } finally {
        merging = false;
      }
    }

    /**
     * Whether every source is this format's reader. Checked by class name and JDK interface: the
     * sources may have been opened by a codec from another classloader.
     */
    private static boolean allNative(MergeState ms) {
      for (StoredFieldsReader r : ms.storedFieldsReaders) {
        if (r == null || !r.getClass().getName().equals(Reader.class.getName()) || !(r instanceof Supplier<?>)) {
          return false;
        }
      }
      return true;
    }

    /** Native merge: compressed chunks copied as they are where a source has no deletions. */
    @SuppressWarnings("unchecked")
    private int mergeNatively(MergeState ms) throws IOException {
      int n = ms.storedFieldsReaders.length;
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment sources = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
        MemorySegment bases = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
        MemorySegment docMaps = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
        MemorySegment fieldMaps = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
        MemorySegment fieldMapLens = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
        for (int i = 0; i < n; i++) {
          ms.storedFieldsReaders[i].checkIntegrity();
          Map<String, Object> src = ((Supplier<Map<String, Object>>) ms.storedFieldsReaders[i]).get();
          sources.setAtIndex(ValueLayout.ADDRESS, i, (MemorySegment) src.get("reader"));
          int maxDoc = ms.maxDocs[i];
          int base = 0;
          MemorySegment map = MemorySegment.NULL;
          if (ms.liveDocs[i] == null) {
            if (maxDoc > 0) base = ms.docMaps[i].get(0);
          } else {
            map = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, maxDoc));
            for (int d = 0; d < maxDoc; d++) {
              map.setAtIndex(ValueLayout.JAVA_INT, d, ms.liveDocs[i].get(d) ? ms.docMaps[i].get(d) : -1);
            }
          }
          bases.setAtIndex(ValueLayout.JAVA_INT, i, base);
          docMaps.setAtIndex(ValueLayout.ADDRESS, i, map);
          Map<Integer, String> names = (Map<Integer, String>) src.get("fields");
          int len = 0;
          for (int id : names.keySet()) len = Math.max(len, id + 1);
          MemorySegment fm = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, len));
          fm.fill((byte) -1);
          for (Map.Entry<Integer, String> e : names.entrySet()) {
            FieldInfo fi = ms.mergeFieldInfos.fieldInfo(e.getValue());
            if (fi == null) continue;
            fm.setAtIndex(ValueLayout.JAVA_INT, e.getKey(), fi.number);
            fieldNames.putIfAbsent(fi.number, fi.name);
          }
          fieldMaps.setAtIndex(ValueLayout.ADDRESS, i, fm);
          fieldMapLens.setAtIndex(ValueLayout.JAVA_INT, i, len);
        }
        if (w == null) w = NativeStore.newWriter(0);
        MemorySegment ww = w;
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_STORED_MERGE.invokeExact(
            ww, n, sources, bases, docMaps, fieldMaps, fieldMapLens)));
      }
      int docCount = ms.segmentInfo.maxDoc();
      doc = docCount - 1;
      finish(docCount);
      return docCount;
    }

    @Override
    public long ramBytesUsed() {
      // Counted towards Lucene's RAM buffer, so segments flush by total memory as usual.
      return data.length + 20L * docs.length + nativeRam + (indexer == null ? 0 : indexer.ramBytesUsed());
    }

    @Override
    public void close() {
      NativeStore.free(w);
      w = null;
      if (indexer != null && !finished) indexer.free(); // aborted segment
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  /** The open segment, shared by a reader and its clones. */
  private static final class Core {
    final IndexInput data;
    final NativeStore.Opened store; // null: nothing stored
    final FieldInfo[] byId;
    final Map<Integer, String> names;
    final int maxDoc;

    Core(Directory dir, SegmentInfo si, FieldInfos fieldInfos, IOContext context) throws IOException {
      String dataName = IndexFileNames.segmentFileName(si.name, "", DATA_EXTENSION);
      String metaName = IndexFileNames.segmentFileName(si.name, "", META_EXTENSION);
      Map<Integer, String> names = new TreeMap<>();
      List<NativeStore.Entry> entries;
      int numDocs;
      try (ChecksumIndexInput meta = dir.openChecksumInput(metaName)) {
        Throwable failure = null;
        List<NativeStore.Entry> read = List.of();
        int nd = 0;
        try {
          CodecUtil.checkIndexHeader(meta, META_CODEC, VERSION, VERSION, si.getId(), "");
          nd = meta.readVInt();
          int nf = meta.readVInt();
          for (int i = 0; i < nf; i++) names.put(meta.readVInt(), meta.readString());
          read = NativeStore.readEntries(meta);
        } catch (Throwable t) {
          failure = t;
        } finally {
          CodecUtil.checkFooter(meta, failure);
        }
        entries = read;
        numDocs = nd;
      }
      if (numDocs != si.maxDoc()) throw new CorruptIndexException("stored docs " + numDocs + " != " + si.maxDoc(), metaName);
      int maxId = names.isEmpty() ? -1 : ((TreeMap<Integer, String>) names).lastKey();
      byId = new FieldInfo[maxId + 1];
      for (Map.Entry<Integer, String> e : names.entrySet()) {
        FieldInfo fi = fieldInfos.fieldInfo(e.getValue());
        if (fi == null) throw new CorruptIndexException("stored field " + e.getValue() + " has no field info", metaName);
        byId[e.getKey()] = fi;
      }
      this.names = java.util.Collections.unmodifiableMap(names);
      maxDoc = si.maxDoc();
      IndexInput in = dir.openInput(dataName, context);
      boolean ok = false;
      try {
        CodecUtil.checkIndexHeader(in, DATA_CODEC, VERSION, VERSION, si.getId(), "");
        CodecUtil.retrieveChecksum(in);
        store = entries.isEmpty() ? null : new NativeStore.Opened(in, entries);
        ok = true;
      } finally {
        if (!ok) NativeStore.closeQuietly(in);
      }
      data = in;
    }
  }

  /**
   * Reader; {@link #get()} exposes the native segment and field ids to merges, through JDK types
   * only (the merging writer may come from another classloader).
   */
  private static final class Reader extends StoredFieldsReader implements Supplier<Map<String, Object>> {
    private final Core core;
    private final boolean clone;
    private NativeHandle handle; // lxl_stored*, per instance (not thread-safe)
    private byte[] buf = new byte[1 << 12];

    Reader(Core core) {
      this(core, false);
    }

    private Reader(Core core, boolean clone) {
      this.core = core;
      this.clone = clone;
    }

    @Override
    public void document(int docID, StoredFieldVisitor visitor) throws IOException {
      if (core.store == null) return;
      MemorySegment reader = core.store.reader();
      if (handle == null) {
        handle = NativeHandle.register(this, LxlNative.STORED_FREE);
        try {
          handle.set(check((MemorySegment) LxlNative.STORED_NEW.invokeExact(reader)));
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
      }
      int len;
      try {
        for (;;) {
          int cap = buf.length;
          len = check((int) LxlNative.STORED_DOC.invokeExact(handle.get(), docID, heap(buf), cap));
          if (len <= cap) break;
          buf = new byte[ArrayUtil.oversize(len, 1)];
        }
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      } finally {
        Reference.reachabilityFence(this);
      }
      ByteArrayDataInput in = new ByteArrayDataInput(buf, 0, len);
      while (in.getPosition() < len) {
        int id = in.readVInt();
        int size = in.readVInt();
        int p = in.getPosition();
        FieldInfo fi = id < core.byId.length ? core.byId[id] : null;
        if (fi == null) throw new CorruptIndexException("unknown stored field id " + id, core.data);
        switch (visitor.needsField(fi)) {
          case YES -> visit(visitor, fi, p, size);
          case NO -> {}
          case STOP -> {
            return;
          }
        }
        in.setPosition(p + size);
      }
    }

    private void visit(StoredFieldVisitor visitor, FieldInfo fi, int p, int size) throws IOException {
      int v = p + 1;
      switch (buf[p]) {
        case STRING -> visitor.stringField(fi, new String(buf, v, size - 1, StandardCharsets.UTF_8));
        case BINARY ->
            visitor.binaryField(fi, new StoredFieldDataInput(new ByteArrayDataInput(buf, v, size - 1), size - 1));
        case INT -> visitor.intField(fi, (int) BitUtil.VH_LE_INT.get(buf, v));
        case LONG -> visitor.longField(fi, (long) BitUtil.VH_LE_LONG.get(buf, v));
        case FLOAT -> visitor.floatField(fi, Float.intBitsToFloat((int) BitUtil.VH_LE_INT.get(buf, v)));
        case DOUBLE -> visitor.doubleField(fi, Double.longBitsToDouble((long) BitUtil.VH_LE_LONG.get(buf, v)));
        default -> throw new CorruptIndexException("bad stored value tag " + buf[p], core.data);
      }
    }

    @Override
    public Map<String, Object> get() {
      MemorySegment r = core.store == null ? MemorySegment.NULL : core.store.reader();
      return Map.of("reader", r, "fields", core.names);
    }

    @Override
    public StoredFieldsReader clone() {
      if (core.store != null) core.store.ensureOpen();
      return new Reader(core, true);
    }

    @Override
    public void checkIntegrity() throws IOException {
      CodecUtil.checksumEntireFile(core.data);
    }

    @Override
    public void close() throws IOException {
      if (clone) return;
      try {
        if (core.store != null) core.store.close();
      } finally {
        core.data.close();
      }
    }
  }
}
