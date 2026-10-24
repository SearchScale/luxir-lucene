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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesSkipIndexType;
import org.apache.lucene.index.DocValuesSkipper;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Doc values kept by Luxir. Every field is a Luxir int64 column (block-packed, with a
 * docs-with-value set and per-doc value ranges for multi-valued fields): NUMERIC and
 * SORTED_NUMERIC fields hold their values; SORTED and SORTED_SET fields hold ordinals, with the
 * sorted terms in a Luxir dictionary beside the column. BINARY fields, and fields with a skip
 * index, go to the delegate format.
 *
 * <p>Files: {@code .lxv} (the Luxir segment) and {@code .lxvm} (fields and file table), plus the
 * delegate's files when it has fields.
 */
final class LuxirDocValuesFormat extends DocValuesFormat {
  static final String NAME = "LuxirDocValues";
  static final String DATA_EXTENSION = "lxv";
  static final String META_EXTENSION = "lxvm";
  static final String DATA_CODEC = "LuxirDocValuesData";
  static final String META_CODEC = "LuxirDocValuesMeta";
  static final int VERSION = 0;

  private final DocValuesFormat delegate;

  LuxirDocValuesFormat(DocValuesFormat delegate) {
    super(NAME);
    this.delegate = delegate;
  }

  static boolean nativeType(FieldInfo fi) {
    return fi.docValuesSkipIndexType() == DocValuesSkipIndexType.NONE
        && fi.getDocValuesType() != DocValuesType.BINARY && fi.getDocValuesType() != DocValuesType.NONE;
  }

  @Override
  public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    return new Consumer(state);
  }

  @Override
  public DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
    return new Producer(state);
  }

  /**
   * A native field: its Lucene name, docs with values, total values, whether multi-valued, and
   * (for SORTED/SORTED_SET) the number of terms.
   */
  record NativeField(String name, int docCount, long valueCount, boolean multi, long termCount) {}

  /** The native name of the i-th native field's column. */
  static byte[] nativeName(int i) {
    return Integer.toString(i).getBytes(StandardCharsets.US_ASCII);
  }

  /** The native name of the i-th native field's dictionary. */
  static byte[] dictName(int i) {
    return ("t" + i).getBytes(StandardCharsets.US_ASCII);
  }

  // ---- writing -------------------------------------------------------------------------------

  private final class Consumer extends DocValuesConsumer {
    private final SegmentWriteState state;
    private MemorySegment w; // lxl_writer*, created with the first native field
    private DocValuesConsumer del;
    private final List<NativeField> fields = new ArrayList<>();
    private boolean closed;

    Consumer(SegmentWriteState state) {
      this.state = state;
    }

    private DocValuesConsumer delegate() throws IOException {
      if (del == null) del = delegate.fieldsConsumer(state);
      return del;
    }

    @Override
    public void addNumericField(FieldInfo field, DocValuesProducer producer) throws IOException {
      if (valueField(field)) {
        writeValues(field, producer.getNumeric(field));
        return;
      }
      if (!nativeType(field)) {
        delegate().addNumericField(field, producer);
        return;
      }
      NumericDocValues values = producer.getNumeric(field);
      Columns c = new Columns(false);
      for (int d = values.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = values.nextDoc()) {
        c.add(d, values.longValue());
      }
      write(field, c, null);
    }

    @Override
    public void addSortedNumericField(FieldInfo field, DocValuesProducer producer) throws IOException {
      if (valueField(field)) {
        writeValues(field, producer.getSortedNumeric(field));
        return;
      }
      if (!nativeType(field)) {
        delegate().addSortedNumericField(field, producer);
        return;
      }
      SortedNumericDocValues values = producer.getSortedNumeric(field);
      Columns c = new Columns(true);
      for (int d = values.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = values.nextDoc()) {
        int k = values.docValueCount();
        c.startDoc(d, k);
        for (int i = 0; i < k; i++) c.value(values.nextValue());
      }
      write(field, c, null);
    }

    @Override
    public void addSortedField(FieldInfo field, DocValuesProducer producer) throws IOException {
      if (valueField(field)) {
        writeValues(field, producer.getSorted(field));
        return;
      }
      if (!nativeType(field)) {
        delegate().addSortedField(field, producer);
        return;
      }
      SortedDocValues values = producer.getSorted(field);
      Columns c = new Columns(false);
      for (int d = values.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = values.nextDoc()) {
        c.add(d, values.ordValue());
      }
      if (c.docs == 0) return;
      Dict dict = new Dict(values.getValueCount());
      for (int ord = 0; ord < values.getValueCount(); ord++) dict.add(values.lookupOrd(ord));
      write(field, c, dict);
    }

    @Override
    public void addSortedSetField(FieldInfo field, DocValuesProducer producer) throws IOException {
      if (valueField(field)) {
        writeValues(field, producer.getSortedSet(field));
        return;
      }
      if (!nativeType(field)) {
        delegate().addSortedSetField(field, producer);
        return;
      }
      SortedSetDocValues values = producer.getSortedSet(field);
      Columns c = new Columns(true);
      for (int d = values.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = values.nextDoc()) {
        int k = values.docValueCount();
        c.startDoc(d, k);
        for (int i = 0; i < k; i++) c.value(values.nextOrd());
      }
      if (c.docs == 0) return;
      long count = values.getValueCount();
      if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("too many terms in " + field.name);
      Dict dict = new Dict((int) count);
      for (long ord = 0; ord < count; ord++) dict.add(values.lookupOrd(ord));
      write(field, c, dict);
    }

    /** Whether this is a flush of a Luxir value field (whose values the codec buffered). */
    private boolean valueField(FieldInfo field) {
      return LuxirValueField.kind(field) != 0 && NativeIndexer.get(state.segmentInfo) != null;
    }

    /** Writes a Luxir value field's doc values from the codec's buffers; Lucene's must be empty. */
    private void writeValues(FieldInfo field, DocIdSetIterator lucene) throws IOException {
      if (lucene.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) throw LuxirPointsFormat.misrouted(field);
      NativeIndexer ix = NativeIndexer.get(state.segmentInfo);
      if (LuxirValueField.kind(field) == 's') {
        int ord = ix.stringOrd(field.name);
        if (ord >= 0) writeStrings(field, ix.writer(), ord);
        return;
      }
      NativeIndexer.Numbers nums = ix.numbers(field.name);
      if (nums == null || nums.numDocs == 0) return;
      boolean multi = field.getDocValuesType() == DocValuesType.SORTED_NUMERIC;
      Columns c = new Columns(multi);
      c.docs = nums.numDocs;
      c.docIds = nums.docs;
      if (multi) {
        long[] v = Arrays.copyOf(nums.docValues, nums.numValues);
        for (int i = 0, start = 0; i < nums.numDocs; start += nums.counts[i++]) {
          if (nums.counts[i] > 1) Arrays.sort(v, start, start + nums.counts[i]);
        }
        c.counts = nums.counts;
        c.values = v;
        c.numValues = nums.numValues;
      } else if (!nums.multi()) {
        c.values = nums.docValues;
        c.numValues = nums.numDocs;
      } else {
        // single-valued: a doc's first value (Solr rejects documents with more)
        long[] v = new long[nums.numDocs];
        for (int i = 0, start = 0; i < nums.numDocs; start += nums.counts[i++]) v[i] = nums.docValues[start];
        c.values = v;
        c.numValues = nums.numDocs;
      }
      write(field, c, null);
    }

    /** A native string field's ordinal column and dictionary, built natively from its hash. */
    private void writeStrings(FieldInfo field, MemorySegment src, int srcOrd) {
      if (w == null) w = NativeStore.newWriter(state.segmentInfo.maxDoc());
      int ord = fields.size();
      byte[] col = nativeName(ord);
      byte[] dict = dictName(ord);
      boolean multi = field.getDocValuesType() == DocValuesType.SORTED_SET;
      long[] st = new long[3];
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment colName = arena.allocateFrom(ValueLayout.JAVA_BYTE, col);
        MemorySegment dictNm = arena.allocateFrom(ValueLayout.JAVA_BYTE, dict);
        MemorySegment stats = arena.allocate(ValueLayout.JAVA_LONG, 3);
        MemorySegment ww = w;
        int m = multi ? 1 : 0;
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_STRINGS_DV.invokeExact(
            ww, colName, col.length, dictNm, dict.length, src, srcOrd, m, stats)));
        MemorySegment.copy(stats, ValueLayout.JAVA_LONG, 0, st, 0, 3);
      }
      if (st[0] == 0) return;
      fields.add(new NativeField(field.name, (int) st[0], st[1], multi, st[2]));
    }

    private void write(FieldInfo field, Columns c, Dict dict) {
      if (c.docs == 0) return; // no values: reads as empty
      if (w == null) w = NativeStore.newWriter(state.segmentInfo.maxDoc());
      int ord = fields.size();
      byte[] name = nativeName(ord);
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment docs = arena.allocate(ValueLayout.JAVA_INT, c.docs);
        MemorySegment.copy(c.docIds, 0, docs, ValueLayout.JAVA_INT, 0, c.docs);
        MemorySegment counts = MemorySegment.NULL;
        if (c.multi) {
          counts = arena.allocate(ValueLayout.JAVA_INT, c.docs);
          MemorySegment.copy(c.counts, 0, counts, ValueLayout.JAVA_INT, 0, c.docs);
        }
        MemorySegment vals = arena.allocate(ValueLayout.JAVA_LONG, c.numValues);
        MemorySegment.copy(c.values, 0, vals, ValueLayout.JAVA_LONG, 0, (int) c.numValues);
        MemorySegment nm = arena.allocateFrom(ValueLayout.JAVA_BYTE, name);
        MemorySegment ww = w, cs = counts;
        int nd = c.docs;
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_NUMERIC.invokeExact(
            ww, nm, name.length, nd, docs, cs, vals)));
        if (dict != null && dict.n > 0) {
          byte[] dn = dictName(ord);
          MemorySegment dnm = arena.allocateFrom(ValueLayout.JAVA_BYTE, dn);
          MemorySegment bytes = arena.allocate(Math.max(1, dict.len));
          MemorySegment.copy(dict.bytes, 0, bytes, ValueLayout.JAVA_BYTE, 0, dict.len);
          MemorySegment offs = arena.allocate(ValueLayout.JAVA_LONG, dict.n + 1);
          MemorySegment.copy(dict.offsets, 0, offs, ValueLayout.JAVA_LONG, 0, dict.n + 1);
          long terms = dict.n;
          check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_DICT.invokeExact(
              ww, dnm, dn.length, terms, bytes, offs)));
        }
      }
      fields.add(new NativeField(field.name, c.docs, c.numValues, c.multi, dict == null ? -1 : dict.n));
    }

    @Override
    public void addBinaryField(FieldInfo field, DocValuesProducer producer) throws IOException {
      delegate().addBinaryField(field, producer);
    }

    /**
     * Native fields are merged without leaving native code (columns remapped, dictionaries
     * k-way merged keeping the terms live docs use) when every source is this format; other
     * fields, and merges of other sources, take Lucene's generic path.
     */
    @Override
    public void merge(MergeState ms) throws IOException {
      for (DocValuesProducer p : ms.docValuesProducers) {
        if (p != null) p.checkIntegrity();
      }
      boolean nativeOk = !ms.needsIndexSort && NativeStore.allNative(ms.docValuesProducers, Producer.class);
      try (Arena arena = Arena.ofConfined()) {
        NativeStore.DocMaps maps = nativeOk ? NativeStore.DocMaps.of(ms, arena) : null;
        for (FieldInfo fi : ms.mergeFieldInfos) {
          DocValuesType type = fi.getDocValuesType();
          if (type == DocValuesType.NONE) continue;
          if (nativeOk && nativeType(fi)) {
            mergeNatively(fi, ms, maps, arena);
            continue;
          }
          switch (type) {
            case NUMERIC -> mergeNumericField(fi, ms);
            case BINARY -> mergeBinaryField(fi, ms);
            case SORTED -> mergeSortedField(fi, ms);
            case SORTED_SET -> mergeSortedSetField(fi, ms);
            case SORTED_NUMERIC -> mergeSortedNumericField(fi, ms);
            default -> throw new AssertionError(type);
          }
        }
      }
    }

    @SuppressWarnings("unchecked")
    private void mergeNatively(FieldInfo fi, MergeState ms, NativeStore.DocMaps maps, Arena arena) {
      int n = maps.n();
      boolean sorted = fi.getDocValuesType() == DocValuesType.SORTED || fi.getDocValuesType() == DocValuesType.SORTED_SET;
      boolean multiType =
          fi.getDocValuesType() == DocValuesType.SORTED_SET || fi.getDocValuesType() == DocValuesType.SORTED_NUMERIC;
      MemorySegment sources = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
      MemorySegment columns = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
      MemorySegment dicts = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
      boolean multi = false;
      for (int i = 0; i < n; i++) {
        DocValuesProducer p = ms.docValuesProducers[i];
        if (p == null) continue;
        Map<String, Object> src = ((java.util.function.Supplier<Map<String, Object>>) p).get();
        long[] f = ((Map<String, long[]>) src.get("fields")).get(fi.name);
        if (f == null) continue;
        sources.setAtIndex(ValueLayout.ADDRESS, i, (MemorySegment) src.get("reader"));
        int ord = (int) f[0];
        columns.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(Integer.toString(ord)));
        dicts.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom("t" + ord));
        multi |= f[3] != 0;
      }
      multi &= multiType;
      if (w == null) w = NativeStore.newWriter(state.segmentInfo.maxDoc());
      int ord = fields.size();
      byte[] col = nativeName(ord);
      byte[] dict = dictName(ord);
      MemorySegment colName = arena.allocateFrom(ValueLayout.JAVA_BYTE, col);
      MemorySegment dictName = arena.allocateFrom(ValueLayout.JAVA_BYTE, dict);
      MemorySegment stats = arena.allocate(ValueLayout.JAVA_LONG, 3);
      MemorySegment ww = w;
      int m = multi ? 1 : 0;
      if (sorted) {
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_SORTED_MERGE.invokeExact(ww, colName, col.length,
            dictName, dict.length, n, sources, columns, dicts, maps.bases(), maps.maps(), m, stats)));
      } else {
        check(NativeStore.invokeInt(() -> (int) LxlNative.WRITER_NUMERIC_MERGE.invokeExact(ww, colName, col.length, n,
            sources, columns, maps.bases(), maps.maps(), m, stats)));
      }
      long docs = stats.getAtIndex(ValueLayout.JAVA_LONG, 0);
      if (docs == 0) return; // nothing written: reads as empty
      fields.add(new NativeField(fi.name, (int) docs, stats.getAtIndex(ValueLayout.JAVA_LONG, 1), multi,
          sorted ? stats.getAtIndex(ValueLayout.JAVA_LONG, 2) : -1));
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      boolean ok = false;
      try {
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
            meta.writeVInt(f.docCount());
            meta.writeVLong(f.valueCount());
            meta.writeByte((byte) (f.multi() ? 1 : 0));
            meta.writeZLong(f.termCount());
          }
          meta.writeByte((byte) (del != null ? 1 : 0));
          NativeStore.writeEntries(meta, entries);
          CodecUtil.writeFooter(meta);
        }
        EarlyWriteback.submit(state.directory, dataName);
        ok = true;
      } finally {
        NativeStore.free(w);
        w = null;
        if (ok) IOUtils.close(del);
        else IOUtils.closeWhileHandlingException(del);
      }
    }
  }

  /** Docs and values of one field, gathered for the native writer. */
  private static final class Columns {
    final boolean multi;
    int docs;
    int[] docIds = new int[1024];
    int[] counts;
    long[] values = new long[1024];
    long numValues;

    Columns(boolean multi) {
      this.multi = multi;
      if (multi) counts = new int[1024];
    }

    void add(int doc, long value) {
      if (docs == docIds.length) docIds = ArrayUtil.grow(docIds, docs + 1);
      docIds[docs++] = doc;
      value(value);
    }

    void startDoc(int doc, int count) {
      if (docs == docIds.length) {
        docIds = ArrayUtil.grow(docIds, docs + 1);
        counts = ArrayUtil.growExact(counts, docIds.length);
      }
      docIds[docs] = doc;
      counts[docs++] = count;
    }

    void value(long v) {
      if (numValues == values.length) {
        if (numValues >= ArrayUtil.MAX_ARRAY_LENGTH) throw new IllegalArgumentException("too many doc values");
        values = ArrayUtil.grow(values, (int) numValues + 1);
      }
      values[(int) numValues++] = v;
    }
  }

  /** Sorted terms of one field, gathered for the native writer. */
  private static final class Dict {
    int n;
    byte[] bytes = new byte[1024];
    int len;
    final long[] offsets;

    Dict(int count) {
      offsets = new long[count + 1];
    }

    void add(BytesRef term) {
      if (bytes.length < len + term.length) bytes = ArrayUtil.grow(bytes, len + term.length);
      System.arraycopy(term.bytes, term.offset, bytes, len, term.length);
      len += term.length;
      offsets[++n] = len;
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  /**
   * Reader; {@link #get()} exposes the native segment and fields to merges, through JDK types
   * only (the merging writer may come from another classloader).
   */
  private final class Producer extends DocValuesProducer implements java.util.function.Supplier<Map<String, Object>> {
    private final IndexInput data;
    private final NativeStore.Opened store; // null: no native fields
    private final Map<String, Integer> ords = new HashMap<>();
    private final List<NativeField> fields = new ArrayList<>();
    private final Terms[] dicts;
    private final DocValuesProducer del;

    Producer(SegmentReadState state) throws IOException {
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
            NativeField f = new NativeField(
                meta.readString(), meta.readVInt(), meta.readVLong(), meta.readByte() != 0, meta.readZLong());
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
      dicts = new Terms[fields.size()];
      IndexInput in = null;
      NativeStore.Opened s = null;
      DocValuesProducer d = null;
      boolean ok = false;
      try {
        in = state.directory.openInput(dataName, state.context);
        CodecUtil.checkIndexHeader(in, DATA_CODEC, VERSION, VERSION, state.segmentInfo.getId(), state.segmentSuffix);
        CodecUtil.retrieveChecksum(in);
        if (!entries.isEmpty()) s = new NativeStore.Opened(in, entries);
        if (hasDelegate) d = delegate.fieldsProducer(state);
        ok = true;
      } finally {
        if (!ok) IOUtils.closeWhileHandlingException(s, d, in);
      }
      data = in;
      store = s;
      del = d;
    }

    private int nativeOrd(FieldInfo fi) {
      Integer ord = nativeType(fi) ? ords.get(fi.name) : null;
      return ord == null ? -1 : ord;
    }

    private Terms terms(int ord) {
      synchronized (dicts) {
        if (dicts[ord] == null) dicts[ord] = new Terms(store, ord, fields.get(ord).termCount());
        return dicts[ord];
      }
    }

    @Override
    public NumericDocValues getNumeric(FieldInfo field) throws IOException {
      if (!nativeType(field)) return del == null ? DocValues.emptyNumeric() : del.getNumeric(field);
      int ord = nativeOrd(field);
      if (ord < 0) return DocValues.emptyNumeric();
      return new NativeNumeric(store, nativeName(ord), fields.get(ord).docCount());
    }

    @Override
    public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
      if (!nativeType(field)) return del == null ? DocValues.emptySortedNumeric() : del.getSortedNumeric(field);
      int ord = nativeOrd(field);
      if (ord < 0) return DocValues.emptySortedNumeric();
      NativeField f = fields.get(ord);
      if (!f.multi()) return DocValues.singleton(new NativeNumeric(store, nativeName(ord), f.docCount()));
      return new NativeSortedNumeric(new Multi(store, nativeName(ord), f.docCount()));
    }

    @Override
    public SortedDocValues getSorted(FieldInfo field) throws IOException {
      if (!nativeType(field)) return del == null ? DocValues.emptySorted() : del.getSorted(field);
      int ord = nativeOrd(field);
      if (ord < 0) return DocValues.emptySorted();
      return new NativeSorted(new NativeNumeric(store, nativeName(ord), fields.get(ord).docCount()), terms(ord));
    }

    @Override
    public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
      if (!nativeType(field)) return del == null ? DocValues.emptySortedSet() : del.getSortedSet(field);
      int ord = nativeOrd(field);
      if (ord < 0) return DocValues.emptySortedSet();
      NativeField f = fields.get(ord);
      if (!f.multi()) {
        NativeNumeric ords = new NativeNumeric(store, nativeName(ord), f.docCount());
        return DocValues.singleton(new NativeSorted(ords, terms(ord)));
      }
      return new NativeSortedSet(new Multi(store, nativeName(ord), f.docCount()), terms(ord));
    }

    @Override
    public BinaryDocValues getBinary(FieldInfo field) throws IOException {
      return del == null ? DocValues.emptyBinary() : del.getBinary(field);
    }

    @Override
    public Map<String, Object> get() {
      Map<String, Object> d = described;
      if (d == null) {
        Map<String, long[]> f = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
          NativeField nf = fields.get(i);
          f.put(nf.name(), new long[] {i, nf.docCount(), nf.valueCount(), nf.multi() ? 1 : 0, nf.termCount()});
        }
        described = d = Map.of("reader", store == null ? MemorySegment.NULL : store.reader(), "fields", f);
      }
      return d;
    }

    // the native store and fields, built once (searches look them up per request)
    private volatile Map<String, Object> described;

    @Override
    public DocValuesSkipper getSkipper(FieldInfo field) throws IOException {
      return del == null || nativeType(field) ? null : del.getSkipper(field);
    }

    @Override
    public void checkIntegrity() throws IOException {
      CodecUtil.checksumEntireFile(data);
      if (del != null) del.checkIntegrity();
    }

    @Override
    public void close() throws IOException {
      try {
        synchronized (dicts) {
          for (Terms t : dicts) {
            if (t != null) t.free();
          }
        }
      } finally {
        IOUtils.close(store, del, data);
      }
    }
  }

  /** One field's sorted terms (thread-safe; lookups copy into the caller's buffer). */
  private static final class Terms {
    final NativeStore.Opened store;
    final MemorySegment dict; // lxl_dict*, NULL with no terms
    final long count;

    Terms(NativeStore.Opened store, int ord, long count) {
      this.store = store;
      if (count <= 0) {
        dict = MemorySegment.NULL;
        this.count = 0;
        return;
      }
      byte[] name = dictName(ord);
      long[] got = new long[1];
      try {
        dict = check((MemorySegment) LxlNative.DICT_NEW.invokeExact(store.reader(), heap(name), name.length, heap(got)));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      if (got[0] != count) throw new IllegalStateException("term count mismatch: " + got[0] + " != " + count);
      this.count = count;
    }

    /** Term {@code ord} into {@code out}, whose bytes array may be replaced. */
    void lookup(long ord, BytesRef out) {
      store.ensureOpen();
      for (;;) {
        int len;
        try {
          len = check((int) LxlNative.DICT_LOOKUP.invokeExact(dict, ord, heap(out.bytes), out.bytes.length));
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
        if (len <= out.bytes.length) {
          out.offset = 0;
          out.length = len;
          return;
        }
        out.bytes = new byte[ArrayUtil.oversize(len, 1)];
      }
    }

    void free() {
      if (dict.address() == 0) return;
      try {
        LxlNative.DICT_FREE.invokeExact(dict);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
    }
  }

  // ---- iterators -----------------------------------------------------------------------------

  /** Docs with values, fetched from the native column a block at a time. */
  private abstract static class Blocks {
    static final int BLOCK = 256;
    final NativeStore.Opened store;
    final byte[] name; // the column's name in the store
    final Object owner;
    // The column's native iterator, opened by the second fill: most iterators (a page of results'
    // values) read one block, which a one-shot call reads without a native object to clean up.
    private NativeHandle handle;
    private int fills;
    final long cost;
    int[] docs = new int[BLOCK];
    int n; // docs in the block
    int i = -1; // current index in the block
    int doc = -1;
    boolean exhausted;

    Blocks(NativeStore.Opened store, byte[] name, long cost, Object owner) {
      this.store = store;
      this.name = name;
      this.owner = owner;
      this.cost = cost;
    }

    /** Fills the block with docs >= target; returns the count. */
    abstract int fill(int target);

    /** lxl_numeric_fill of docs >= target into the buffers; the first by a one-shot call. */
    int nativeFill(int target, MemorySegment docs, MemorySegment counts, MemorySegment values, int cap, int valueCap) {
      try {
        if (fills++ == 0) {
          return (int) LxlNative.NUMERIC_FILL_ONCE.invokeExact(store.reader(), heap(name), name.length, target, docs,
              counts, values, cap, valueCap);
        }
        if (handle == null) {
          handle = NativeHandle.register(owner, LxlNative.NUMERIC_FREE);
          handle.set(check((MemorySegment) LxlNative.NUMERIC_NEW.invokeExact(store.reader(), heap(name), name.length)));
        }
        return (int) LxlNative.NUMERIC_FILL.invokeExact(handle.get(), target, docs, counts, values, cap, valueCap);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      } finally {
        Reference.reachabilityFence(owner);
      }
    }

    private boolean refill(int target) {
      store.ensureOpen();
      n = exhausted ? 0 : fill(target);
      i = 0;
      if (n == 0) exhausted = true;
      return n > 0;
    }

    int nextDoc() {
      if (doc == DocIdSetIterator.NO_MORE_DOCS) return doc;
      if (i + 1 < n) return doc = docs[++i];
      if (!refill(doc + 1)) return doc = DocIdSetIterator.NO_MORE_DOCS;
      return doc = docs[0];
    }

    int advance(int target) {
      if (n > 0 && i < n && docs[n - 1] >= target) {
        int j = Math.max(i, 0);
        while (docs[j] < target) j++;
        i = j;
        return doc = docs[j];
      }
      if (!refill(target)) return doc = DocIdSetIterator.NO_MORE_DOCS;
      return doc = docs[0];
    }

    boolean advanceExact(int target) {
      int d = advance(target);
      if (d == target) return true;
      // stay before the next doc with a value, so nextDoc() still returns it
      if (d != DocIdSetIterator.NO_MORE_DOCS) {
        i--;
      } else {
        n = 0;
        i = -1;
      }
      doc = target;
      return false;
    }
  }

  /** A single-valued column (values or ordinals). */
  private static final class NativeNumeric extends NumericDocValues {
    private final Blocks blocks;
    private final long[] values = new long[Blocks.BLOCK];

    NativeNumeric(NativeStore.Opened store, byte[] name, long cost) {
      blocks = new Blocks(store, name, cost, this) {
        @Override
        int fill(int target) {
          return check(nativeFill(target, heap(docs), MemorySegment.NULL, heap(values), BLOCK, BLOCK));
        }
      };
    }

    @Override
    public long longValue() {
      return values[blocks.i];
    }

    @Override
    public boolean advanceExact(int target) {
      return blocks.advanceExact(target);
    }

    @Override
    public int docID() {
      return blocks.doc;
    }

    @Override
    public int nextDoc() {
      return blocks.nextDoc();
    }

    @Override
    public int advance(int target) {
      return blocks.advance(target);
    }

    @Override
    public long cost() {
      return blocks.cost;
    }
  }

  /** A multi-valued column (values or ordinals): per doc, a run of values. */
  private static final class Multi {
    final Blocks blocks;
    final int[] counts = new int[Blocks.BLOCK];
    final int[] starts = new int[Blocks.BLOCK];
    long[] values = new long[Blocks.BLOCK * 4];
    int next; // next value index of the current doc

    Multi(NativeStore.Opened store, byte[] name, long cost) {
      blocks = new Blocks(store, name, cost, this) {
        @Override
        int fill(int target) {
          int got;
          for (;;) {
            got = nativeFill(target, heap(docs), heap(counts), heap(values), BLOCK, values.length);
            if (got >= 0) break;
            String err = LxlNative.lastError();
            if (!err.contains("too small")) throw new LxlNative.LxlException(err);
            values = new long[values.length * 4]; // one doc has more values than the buffer holds
          }
          int s = 0;
          for (int j = 0; j < got; j++) {
            starts[j] = s;
            s += counts[j];
          }
          return got;
        }
      };
    }

    private void position() {
      if (blocks.doc != DocIdSetIterator.NO_MORE_DOCS && blocks.i >= 0 && blocks.i < blocks.n
          && blocks.docs[blocks.i] == blocks.doc) {
        next = starts[blocks.i];
      }
    }

    long nextValue() {
      return values[next++];
    }

    int count() {
      return counts[blocks.i];
    }

    boolean advanceExact(int target) {
      boolean found = blocks.advanceExact(target);
      if (found) position();
      return found;
    }

    int nextDoc() {
      int d = blocks.nextDoc();
      position();
      return d;
    }

    int advance(int target) {
      int d = blocks.advance(target);
      position();
      return d;
    }
  }

  private static final class NativeSortedNumeric extends SortedNumericDocValues {
    private final Multi m;

    NativeSortedNumeric(Multi m) {
      this.m = m;
    }

    @Override
    public long nextValue() {
      return m.nextValue();
    }

    @Override
    public int docValueCount() {
      return m.count();
    }

    @Override
    public boolean advanceExact(int target) {
      return m.advanceExact(target);
    }

    @Override
    public int docID() {
      return m.blocks.doc;
    }

    @Override
    public int nextDoc() {
      return m.nextDoc();
    }

    @Override
    public int advance(int target) {
      return m.advance(target);
    }

    @Override
    public long cost() {
      return m.blocks.cost;
    }
  }

  private static final class NativeSorted extends SortedDocValues {
    private final NativeNumeric ords;
    private final Terms terms;
    private final BytesRef term = new BytesRef(new byte[64]);

    NativeSorted(NativeNumeric ords, Terms terms) {
      this.ords = ords;
      this.terms = terms;
    }

    @Override
    public int ordValue() {
      return (int) ords.longValue();
    }

    @Override
    public BytesRef lookupOrd(int ord) {
      terms.lookup(ord, term);
      return term;
    }

    @Override
    public int getValueCount() {
      return (int) terms.count;
    }

    @Override
    public boolean advanceExact(int target) {
      return ords.advanceExact(target);
    }

    @Override
    public int docID() {
      return ords.docID();
    }

    @Override
    public int nextDoc() {
      return ords.nextDoc();
    }

    @Override
    public int advance(int target) {
      return ords.advance(target);
    }

    @Override
    public long cost() {
      return ords.cost();
    }
  }

  private static final class NativeSortedSet extends SortedSetDocValues {
    private final Multi m;
    private final Terms terms;
    private final BytesRef term = new BytesRef(new byte[64]);

    NativeSortedSet(Multi m, Terms terms) {
      this.m = m;
      this.terms = terms;
    }

    @Override
    public long nextOrd() {
      return m.nextValue();
    }

    @Override
    public int docValueCount() {
      return m.count();
    }

    @Override
    public BytesRef lookupOrd(long ord) {
      terms.lookup(ord, term);
      return term;
    }

    @Override
    public long getValueCount() {
      return terms.count;
    }

    @Override
    public boolean advanceExact(int target) {
      return m.advanceExact(target);
    }

    @Override
    public int docID() {
      return m.blocks.doc;
    }

    @Override
    public int nextDoc() {
      return m.nextDoc();
    }

    @Override
    public int advance(int target) {
      return m.advance(target);
    }

    @Override
    public long cost() {
      return m.blocks.cost;
    }
  }
}
