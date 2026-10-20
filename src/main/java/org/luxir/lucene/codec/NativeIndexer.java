// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.StringHelper;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.document.LuxirTextField;

/**
 * Luxir's analyzer and inverter for one Lucene segment being written: the stored-fields writer
 * feeds it each document's Luxir text and Luxir values as Lucene indexes the document (so analysis
 * runs on the indexing threads, in parallel across segments). At flush the doc-values and points
 * writers take the values' columns and points, and the postings writer finishes it. Text and
 * strings are passed in batches to keep native calls few; numbers stay in Java arrays until flush.
 */
final class NativeIndexer {
  private static final Map<String, NativeIndexer> SEGMENTS = new ConcurrentHashMap<>();
  private static final int BATCH_BYTES = 1 << 20;

  static String key(SegmentInfo si) {
    return si.name + "/" + StringHelper.idToString(si.getId());
  }

  static NativeIndexer get(SegmentInfo si) {
    return SEGMENTS.get(key(si));
  }

  private final String key;
  private MemorySegment writer; // lxl_writer* (inverter mode)
  private final Map<String, Integer> ords = new HashMap<>();
  private final Set<String> fields = new LinkedHashSet<>();
  // pending batch
  private int n;
  private int[] docs = new int[256];
  private int[] fieldOrds = new int[256];
  private long[] offsets = new long[257];
  private byte[] text = new byte[1 << 16];
  private int textLen;
  private long nativeRam;
  // native string fields: ordinals, and the pending batch
  private final Map<String, Integer> stringOrds = new HashMap<>();
  private final Set<String> searchableStrings = new LinkedHashSet<>();
  private int sn;
  private int[] sDocs = new int[256];
  private int[] sOrds = new int[256];
  private long[] sOffsets = new long[257];
  private byte[] sBytes = new byte[1 << 16];
  private int sLen;
  // numeric fields, buffered until flush
  private final Map<String, Numbers> numbers = new HashMap<>();
  private long numberRam;

  private NativeIndexer(String key, String schemaJson) {
    this.key = key;
    byte[] json = schemaJson.getBytes(StandardCharsets.UTF_8);
    try {
      writer = check((MemorySegment) LxlNative.WRITER_NEW_INVERTER.invokeExact(heap(json), json.length));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  /** Creates and registers the indexer for a segment. */
  static NativeIndexer create(SegmentInfo si, String schemaJson) {
    NativeIndexer ix = new NativeIndexer(key(si), schemaJson);
    if (SEGMENTS.putIfAbsent(ix.key, ix) != null) {
      ix.free();
      throw new IllegalStateException("segment " + ix.key + " already has a native indexer");
    }
    return ix;
  }

  // native ordinals by FieldInfo number (+1; 0: not looked up yet), so values need no map lookup
  private int[] textOrdByNumber = new int[0];
  private int[] stringOrdByNumber = new int[0];
  private Numbers[] numbersByNumber = new Numbers[0];

  private static int[] fit(int[] a, int i) {
    return i < a.length ? a : Arrays.copyOf(a, ArrayUtil.oversize(i + 1, Integer.BYTES));
  }

  /** Adds a text value given as UTF-8 bytes (copied into the batch). */
  void add(int doc, FieldInfo fi, byte[] utf8, int offset, int length) {
    int i = fi.number;
    textOrdByNumber = fit(textOrdByNumber, i);
    int ord = textOrdByNumber[i] - 1;
    if (ord < 0) {
      ord = textOrd(fi.name);
      textOrdByNumber[i] = ord + 1;
    }
    append(doc, ord, utf8, offset, length);
  }

  void add(int doc, FieldInfo fi, String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    add(doc, fi, utf8, 0, utf8.length);
  }

  private int textOrd(String field) {
    Integer ord = ords.get(field);
    if (ord == null) {
      String schemaName = LuxirTextField.schemaName(field);
      byte[] name = schemaName.getBytes(StandardCharsets.UTF_8);
      try {
        ord = check((int) LxlNative.WRITER_FIELD_ORD.invokeExact(writer, heap(name), name.length));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      ords.put(field, ord);
      fields.add(field);
    }
    return ord;
  }

  private void append(int doc, int ord, byte[] utf8, int offset, int length) {
    if (n == docs.length) {
      int size = ArrayUtil.oversize(n + 1, Integer.BYTES);
      docs = Arrays.copyOf(docs, size);
      fieldOrds = Arrays.copyOf(fieldOrds, size);
      offsets = Arrays.copyOf(offsets, size + 1);
    }
    int need = textLen + length;
    if (need > text.length) text = Arrays.copyOf(text, ArrayUtil.oversize(need, 1));
    System.arraycopy(utf8, offset, text, textLen, length);
    docs[n] = doc;
    fieldOrds[n] = ord;
    offsets[n] = textLen;
    textLen += length;
    n++;
    offsets[n] = textLen;
  }

  /** Adds a string value given as UTF-8 bytes (copied into the batch). */
  void addString(int doc, FieldInfo fi, byte[] utf8, int offset, int length) {
    stringOrdByNumber = fit(stringOrdByNumber, fi.number);
    int ord = stringOrdByNumber[fi.number] - 1;
    if (ord < 0) {
      byte[] name = fi.name.getBytes(StandardCharsets.UTF_8);
      try {
        ord = check((int) LxlNative.WRITER_STRING_FIELD.invokeExact(writer, heap(name), name.length));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      stringOrds.put(fi.name, ord);
      stringOrdByNumber[fi.number] = ord + 1;
      if (fi.getIndexOptions() != IndexOptions.NONE) searchableStrings.add(fi.name);
    }
    if (sn == sDocs.length) {
      int size = ArrayUtil.oversize(sn + 1, Integer.BYTES);
      sDocs = Arrays.copyOf(sDocs, size);
      sOrds = Arrays.copyOf(sOrds, size);
      sOffsets = Arrays.copyOf(sOffsets, size + 1);
    }
    int need = sLen + length;
    if (need > sBytes.length) sBytes = Arrays.copyOf(sBytes, ArrayUtil.oversize(need, 1));
    System.arraycopy(utf8, offset, sBytes, sLen, length);
    sDocs[sn] = doc;
    sOrds[sn] = ord;
    sOffsets[sn] = sLen;
    sLen += length;
    sn++;
    sOffsets[sn] = sLen;
  }

  /** Adds a numeric value: its doc value and its point (as {@link LuxirPointsFormat} longs). */
  void addNumber(int doc, FieldInfo fi, long docValue, long point) {
    if (fi.number >= numbersByNumber.length) {
      numbersByNumber = Arrays.copyOf(numbersByNumber, ArrayUtil.oversize(fi.number + 1, 8));
    }
    Numbers nums = numbersByNumber[fi.number];
    if (nums == null) {
      numbers.put(fi.name, nums = new Numbers());
      numbersByNumber[fi.number] = nums;
    }
    long before = nums.ramBytesUsed();
    nums.add(doc, docValue, point);
    numberRam += nums.ramBytesUsed() - before;
  }

  /** A numeric field's buffered values, or null. */
  Numbers numbers(String field) {
    return numbers.get(field);
  }

  /** A native string field's ordinal in {@link #writer()}, or -1. */
  int stringOrd(String field) {
    Integer ord = stringOrds.get(field);
    return ord == null ? -1 : ord;
  }

  /** Fields whose postings the native writer builds: Luxir text and searchable strings. */
  Set<String> postingsFields() {
    if (searchableStrings.isEmpty()) return fields;
    Set<String> all = new LinkedHashSet<>(fields);
    all.addAll(searchableStrings);
    return all;
  }

  /** String fields with postings (indexed for search). */
  Set<String> searchableStrings() {
    return searchableStrings;
  }

  /** The native writer, with every pending value sent. */
  MemorySegment writer() {
    flushBatch();
    return writer;
  }

  /** Called at the end of each document: ships a batch once it is large enough. */
  void maybeFlush() {
    if (textLen >= BATCH_BYTES || sLen >= BATCH_BYTES) flushBatch();
  }

  void flushBatch() {
    flushStrings();
    if (n == 0) return;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment d = arena.allocate(ValueLayout.JAVA_INT, n);
      MemorySegment o = arena.allocate(ValueLayout.JAVA_INT, n);
      MemorySegment off = arena.allocate(ValueLayout.JAVA_LONG, n + 1);
      MemorySegment t = arena.allocate(Math.max(1, textLen));
      MemorySegment.copy(docs, 0, d, ValueLayout.JAVA_INT, 0, n);
      MemorySegment.copy(fieldOrds, 0, o, ValueLayout.JAVA_INT, 0, n);
      MemorySegment.copy(offsets, 0, off, ValueLayout.JAVA_LONG, 0, n + 1);
      MemorySegment.copy(text, 0, t, ValueLayout.JAVA_BYTE, 0, textLen);
      check((int) LxlNative.WRITER_ADD_DOCS.invokeExact(writer, n, d, o, t, off));
      nativeRam = (long) LxlNative.WRITER_RAM.invokeExact(writer);
    } catch (Throwable th) {
      throw LxlNative.rethrow(th);
    }
    n = 0;
    textLen = 0;
    offsets[0] = 0;
  }

  private void flushStrings() {
    if (sn == 0) return;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment d = arena.allocate(ValueLayout.JAVA_INT, sn);
      MemorySegment o = arena.allocate(ValueLayout.JAVA_INT, sn);
      MemorySegment off = arena.allocate(ValueLayout.JAVA_LONG, sn + 1);
      MemorySegment b = arena.allocate(Math.max(1, sLen));
      MemorySegment.copy(sDocs, 0, d, ValueLayout.JAVA_INT, 0, sn);
      MemorySegment.copy(sOrds, 0, o, ValueLayout.JAVA_INT, 0, sn);
      MemorySegment.copy(sOffsets, 0, off, ValueLayout.JAVA_LONG, 0, sn + 1);
      MemorySegment.copy(sBytes, 0, b, ValueLayout.JAVA_BYTE, 0, sLen);
      check((int) LxlNative.WRITER_ADD_STRINGS.invokeExact(writer, sn, d, o, b, off));
      nativeRam = (long) LxlNative.WRITER_RAM.invokeExact(writer);
    } catch (Throwable th) {
      throw LxlNative.rethrow(th);
    }
    sn = 0;
    sLen = 0;
    sOffsets[0] = 0;
  }

  long ramBytesUsed() {
    return nativeRam + text.length + (long) docs.length * 16 + sBytes.length + (long) sDocs.length * 16 + numberRam;
  }

  /**
   * One numeric field's values in doc order: per doc with values its count, and per value the doc
   * value and the point.
   */
  static final class Numbers {
    int numDocs;
    int[] docs = new int[256];
    int[] counts = new int[256];
    int numValues;
    long[] docValues = new long[256];
    long[] points = new long[256];

    void add(int doc, long docValue, long point) {
      if (numDocs > 0 && docs[numDocs - 1] == doc) {
        counts[numDocs - 1]++;
      } else {
        if (numDocs > 0 && doc < docs[numDocs - 1]) throw new IllegalStateException("numeric values out of doc order");
        if (numDocs == docs.length) {
          docs = ArrayUtil.grow(docs, numDocs + 1);
          counts = ArrayUtil.growExact(counts, docs.length);
        }
        docs[numDocs] = doc;
        counts[numDocs++] = 1;
      }
      if (numValues == docValues.length) {
        docValues = ArrayUtil.grow(docValues, numValues + 1);
        points = ArrayUtil.growExact(points, docValues.length);
      }
      docValues[numValues] = docValue;
      points[numValues++] = point;
    }

    /** Whether some doc has more than one value. */
    boolean multi() {
      return numValues > numDocs;
    }

    long ramBytesUsed() {
      return (long) docs.length * 8 + (long) docValues.length * 16;
    }
  }

  /**
   * Hands the native writer to the postings writer at flush and unregisters this indexer. The
   * caller owns (and frees) the returned lxl_writer.
   */
  MemorySegment take() {
    flushBatch();
    SEGMENTS.remove(key, this);
    MemorySegment w = writer;
    writer = MemorySegment.NULL;
    return w;
  }

  /** Discards everything (the segment was aborted). */
  void free() {
    SEGMENTS.remove(key, this);
    if (writer.address() == 0) return;
    try {
      LxlNative.WRITER_FREE.invokeExact(writer);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
    writer = MemorySegment.NULL;
  }
}
