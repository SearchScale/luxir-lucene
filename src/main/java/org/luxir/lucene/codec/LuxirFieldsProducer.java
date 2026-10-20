// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MemorySegmentAccessInput;
import org.apache.lucene.util.IOUtils;
import org.luxir.lucene.LxlNative;

/**
 * Opens a segment's Luxir postings in place: when the data file is memory-mapped (MMapDirectory,
 * including compound files) the native reader points straight into Lucene's mapping; otherwise the
 * file is copied once into native memory.
 */
public final class LuxirFieldsProducer extends FieldsProducer {
  private final IndexInput data;
  private final Arena arena = Arena.ofShared();
  private final MemorySegment reader; // lxl_reader*, or NULL with no native fields
  private final FieldsProducer delegate;
  private final Map<String, Terms> fields = new TreeMap<>();
  private final List<String> nativeFields = new ArrayList<>();
  private final int maxDoc;
  private volatile boolean closed;

  LuxirFieldsProducer(LuxirPostingsFormat format, SegmentReadState state) throws IOException {
    String metaName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, LuxirPostingsFormat.META_EXTENSION);
    String dataName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, LuxirPostingsFormat.DATA_EXTENSION);
    List<LuxirFieldsConsumer.FieldStats> stats = new ArrayList<>();
    List<OverflowTerms> overflows = new ArrayList<>();
    List<String> fileNames = new ArrayList<>();
    List<long[]> fileLocs = new ArrayList<>();
    boolean hasDelegate;
    try (ChecksumIndexInput meta = state.directory.openChecksumInput(metaName)) {
      Throwable failure = null;
      boolean delegateFlag = false;
      try {
        CodecUtil.checkIndexHeader(meta, LuxirPostingsFormat.META_CODEC, LuxirPostingsFormat.VERSION_START,
            LuxirPostingsFormat.VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);
        int n = meta.readVInt();
        for (int i = 0; i < n; i++) {
          stats.add(new LuxirFieldsConsumer.FieldStats(
              meta.readString(), meta.readVLong(), meta.readVInt(), meta.readVLong(), meta.readVLong()));
          overflows.add(OverflowTerms.read(meta));
        }
        int nf = meta.readVInt();
        for (int i = 0; i < nf; i++) {
          fileNames.add(meta.readString());
          fileLocs.add(new long[] {meta.readVLong(), meta.readVLong()});
        }
        delegateFlag = meta.readByte() != 0;
      } catch (Throwable t) {
        failure = t;
      } finally {
        CodecUtil.checkFooter(meta, failure);
      }
      hasDelegate = delegateFlag;
    }

    boolean success = false;
    IndexInput in = null;
    FieldsProducer del = null;
    MemorySegment r = MemorySegment.NULL;
    try {
      in = state.directory.openInput(dataName, state.context);
      CodecUtil.checkIndexHeader(in, LuxirPostingsFormat.DATA_CODEC, LuxirPostingsFormat.VERSION_START,
          LuxirPostingsFormat.VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);
      CodecUtil.retrieveChecksum(in);
      if (!fileNames.isEmpty()) {
        MemorySegment whole = null;
        if (in instanceof MemorySegmentAccessInput msa) {
          whole = msa.segmentSliceOrNull(0, in.length());
        }
        if (whole == null || !aligned(whole, fileLocs)) {
          // Not mapped (or mapped at an unaligned offset): one copy into native memory.
          MemorySegment copy = arena.allocate(in.length() + 16, Long.BYTES);
          byte[] buf = new byte[1 << 16];
          IndexInput c = in.clone();
          c.seek(0);
          for (long pos = 0; pos < in.length(); ) {
            int n = (int) Math.min(buf.length, in.length() - pos);
            c.readBytes(buf, 0, n);
            MemorySegment.copy(buf, 0, copy, ValueLayout.JAVA_BYTE, pos, n);
            pos += n;
          }
          whole = copy;
        }
        r = openNative(whole, fileNames, fileLocs);
      }
      for (int i = 0; i < stats.size(); i++) {
        LuxirFieldsConsumer.FieldStats s = stats.get(i);
        nativeFields.add(s.name());
        FieldInfo fi = state.fieldInfos.fieldInfo(s.name());
        boolean docsOnly = fi != null && fi.getIndexOptions() == IndexOptions.DOCS;
        fields.put(s.name(), new LuxirTerms(this, s, overflows.get(i), docsOnly));
      }
      if (hasDelegate) {
        del = format.delegate.fieldsProducer(state);
        for (String f : del) {
          Terms t = del.terms(f);
          if (t != null) fields.put(f, t);
        }
      }
      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(in, del);
        freeNative(r);
        arena.close();
      }
    }
    this.data = in;
    this.delegate = del;
    this.reader = r;
    this.maxDoc = state.segmentInfo.maxDoc();
  }

  private static boolean aligned(MemorySegment whole, List<long[]> locs) {
    for (long[] loc : locs) {
      if (((whole.address() + loc[0]) & 7) != 0) return false;
    }
    return true;
  }

  private MemorySegment openNative(MemorySegment whole, List<String> names, List<long[]> locs) {
    int n = names.size();
    MemorySegment namePtrs = arena.allocate(ValueLayout.ADDRESS, n);
    MemorySegment dataPtrs = arena.allocate(ValueLayout.ADDRESS, n);
    MemorySegment lens = arena.allocate(ValueLayout.JAVA_LONG, n);
    for (int i = 0; i < n; i++) {
      namePtrs.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(names.get(i), StandardCharsets.UTF_8));
      dataPtrs.setAtIndex(ValueLayout.ADDRESS, i, whole.asSlice(locs.get(i)[0], locs.get(i)[1]));
      lens.setAtIndex(ValueLayout.JAVA_LONG, i, locs.get(i)[1]);
    }
    try {
      return check((MemorySegment) LxlNative.READER_OPEN.invokeExact(n, namePtrs, dataPtrs, lens));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  private static void freeNative(MemorySegment r) {
    if (r.address() == 0) return;
    try {
      LxlNative.READER_FREE.invokeExact(r);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  /** The native reader (lxl_reader*); valid until this producer is closed. */
  public MemorySegment nativeReader() {
    ensureOpen();
    return reader;
  }

  /** A field's norm byte per document (0: no norm), read from the Luxir segment. */
  public byte[] norms(String field) {
    ensureOpen();
    byte[] out = new byte[Math.max(1, maxDoc)];
    byte[] name = field.getBytes(StandardCharsets.UTF_8);
    if (reader.address() == 0) return out;
    try {
      LxlNative.check((int) LxlNative.READER_NORMS.invokeExact(reader, LxlNative.heap(name), name.length, LxlNative.heap(out)));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
    return out;
  }

  public void ensureOpen() {
    if (closed) throw new AlreadyClosedException("this LuxirFieldsProducer is closed");
  }

  @Override
  public Iterator<String> iterator() {
    return Collections.unmodifiableSet(new TreeSet<>(fields.keySet())).iterator();
  }

  @Override
  public Terms terms(String field) {
    return fields.get(field);
  }

  @Override
  public int size() {
    return fields.size();
  }

  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(data);
    if (delegate != null) delegate.checkIntegrity();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      freeNative(reader); // before the mapping it points into goes away
    } finally {
      try {
        IOUtils.close(delegate, data);
      } finally {
        arena.close();
      }
    }
  }

  @Override
  public String toString() {
    return "LuxirFieldsProducer(native=" + nativeFields + ", delegate=" + (delegate != null) + ")";
  }
}
