// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.MemorySegmentAccessInput;
import org.apache.lucene.util.IOUtils;
import org.luxir.lucene.LxlNative;

/**
 * A Luxir segment (the files of one {@code lxl_writer}) kept inside a single Lucene file: each
 * native file 8-byte aligned, after a codec header and before a footer, with the table of
 * contents in the format's metadata file. Reading maps the Lucene file in place where it can
 * (MMapDirectory, compound files included) and otherwise copies it once into native memory.
 */
final class NativeStore {
  private NativeStore() {}

  /** Where one native file lies in the Lucene file. */
  record Entry(String name, long offset, long length) {}

  /** Finishes {@code w} (a plain writer, max doc set) and appends its files to {@code data}. */
  static List<Entry> writeFiles(MemorySegment w, IndexOutput data) throws IOException {
    List<Entry> entries = new ArrayList<>();
    try (Arena arena = Arena.ofConfined()) {
      int numFiles = check(invokeInt(() -> (int) LxlNative.WRITER_FINISH.invokeExact(w)));
      MemorySegment namePtr = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment dataPtr = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment lenPtr = arena.allocate(ValueLayout.JAVA_LONG);
      byte[] buf = new byte[1 << 16];
      for (int i = 0; i < numFiles; i++) {
        int fi = i;
        check(invokeInt(() -> (int) LxlNative.WRITER_FILE.invokeExact(w, fi, namePtr, dataPtr, lenPtr)));
        String name = namePtr.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0);
        long len = lenPtr.get(ValueLayout.JAVA_LONG, 0);
        MemorySegment bytes = dataPtr.get(ValueLayout.ADDRESS, 0).reinterpret(len);
        data.alignFilePointer(Long.BYTES);
        long offset = data.getFilePointer();
        for (long pos = 0; pos < len; ) {
          int n = (int) Math.min(buf.length, len - pos);
          MemorySegment.copy(bytes, ValueLayout.JAVA_BYTE, pos, buf, 0, n);
          data.writeBytes(buf, 0, n);
          pos += n;
        }
        entries.add(new Entry(name, offset, len));
      }
    }
    return entries;
  }

  /** Ends a data file: Luxir's SIMD decoders may read up to 16 bytes past a region. */
  static void finishData(IndexOutput data) throws IOException {
    for (int i = 0; i < 16; i++) data.writeByte((byte) 0);
    CodecUtil.writeFooter(data);
  }

  static void writeEntries(DataOutput meta, List<Entry> entries) throws IOException {
    meta.writeVInt(entries.size());
    for (Entry e : entries) {
      meta.writeString(e.name());
      meta.writeVLong(e.offset());
      meta.writeVLong(e.length());
    }
  }

  static List<Entry> readEntries(DataInput meta) throws IOException {
    int n = meta.readVInt();
    List<Entry> entries = new ArrayList<>(n);
    for (int i = 0; i < n; i++) entries.add(new Entry(meta.readString(), meta.readVLong(), meta.readVLong()));
    return entries;
  }

  /** A finished lxl_writer's segment opened for reading (lxl_reader*), owning its memory. */
  static final class Opened implements Closeable {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment reader;
    private volatile boolean closed;

    /** {@code in} has had its header checked; {@code entries} must not be empty. */
    Opened(IndexInput in, List<Entry> entries) throws IOException {
      boolean ok = false;
      try {
        MemorySegment whole = null;
        if (in instanceof MemorySegmentAccessInput msa) whole = msa.segmentSliceOrNull(0, in.length());
        if (whole == null || !aligned(whole, entries)) {
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
        int n = entries.size();
        MemorySegment names = arena.allocate(ValueLayout.ADDRESS, n);
        MemorySegment ptrs = arena.allocate(ValueLayout.ADDRESS, n);
        MemorySegment lens = arena.allocate(ValueLayout.JAVA_LONG, n);
        for (int i = 0; i < n; i++) {
          Entry e = entries.get(i);
          names.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(e.name(), StandardCharsets.UTF_8));
          ptrs.setAtIndex(ValueLayout.ADDRESS, i, whole.asSlice(e.offset(), e.length()));
          lens.setAtIndex(ValueLayout.JAVA_LONG, i, e.length());
        }
        try {
          reader = check((MemorySegment) LxlNative.READER_OPEN.invokeExact(n, names, ptrs, lens));
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
        ok = true;
      } finally {
        if (!ok) arena.close();
      }
    }

    private static boolean aligned(MemorySegment whole, List<Entry> entries) {
      for (Entry e : entries) {
        if (((whole.address() + e.offset()) & 7) != 0) return false;
      }
      return true;
    }

    /** The native reader; throws once closed. */
    MemorySegment reader() {
      if (closed) throw new AlreadyClosedException("Luxir segment closed");
      return reader;
    }

    void ensureOpen() {
      if (closed) throw new AlreadyClosedException("Luxir segment closed");
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      try {
        LxlNative.READER_FREE.invokeExact(reader);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      } finally {
        arena.close();
      }
    }
  }

  /**
   * Doc maps of a merge for the native merges: {@code maps[i]} maps source i's docs to merged docs
   * (-1: deleted), or is NULL when source i has no deletions and its doc d becomes {@code
   * bases[i] + d}.
   */
  record DocMaps(int n, MemorySegment bases, MemorySegment maps) {
    static DocMaps of(org.apache.lucene.index.MergeState ms, Arena arena) {
      int n = ms.maxDocs.length;
      MemorySegment bases = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
      MemorySegment maps = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
      for (int i = 0; i < n; i++) {
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
        maps.setAtIndex(ValueLayout.ADDRESS, i, map);
      }
      return new DocMaps(n, bases, maps);
    }
  }

  /**
   * Whether every non-null source is {@code type} (by class name, so sources opened by another
   * classloader's codec count) and exposes its native segment through a JDK {@link
   * java.util.function.Supplier}.
   */
  static boolean allNative(Object[] sources, Class<?> type) {
    for (Object s : sources) {
      if (s != null && (!s.getClass().getName().equals(type.getName()) || !(s instanceof java.util.function.Supplier<?>))) {
        return false;
      }
    }
    return true;
  }

  /** Frees a writer. */
  static void free(MemorySegment w) {
    if (w == null || w.address() == 0) return;
    try {
      LxlNative.WRITER_FREE.invokeExact(w);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  static MemorySegment newWriter(int maxDoc) {
    try {
      return check((MemorySegment) LxlNative.WRITER_NEW.invokeExact(maxDoc));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  static void setMaxDoc(MemorySegment w, int maxDoc) {
    check(invokeInt(() -> (int) LxlNative.WRITER_SET_MAX_DOC.invokeExact(w, maxDoc)));
  }

  interface IntCall {
    int call() throws Throwable;
  }

  static int invokeInt(IntCall c) {
    try {
      return c.call();
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  static void closeQuietly(Closeable... cs) {
    IOUtils.closeWhileHandlingException(cs);
  }
}
