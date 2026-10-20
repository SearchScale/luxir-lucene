// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.luxir.lucene.LxlNative;

/**
 * Writes one segment's postings: native fields through Luxir's segment writer into {@code .lxd},
 * the rest through the embedded Lucene postings format.
 */
final class LuxirFieldsConsumer extends FieldsConsumer {
  /** Docs per native call for very frequent terms. */
  private static final int CHUNK_DOCS = 1 << 16;

  private final LuxirPostingsFormat format;
  private final SegmentWriteState state;
  private FieldsConsumer delegateConsumer;
  private boolean written;

  LuxirFieldsConsumer(LuxirPostingsFormat format, SegmentWriteState state) {
    this.format = format;
    this.state = state;
  }

  // During a merge: fields every source holds in Luxir format, merged natively.
  private MergeState mergeState;
  private Set<String> nativeMerge = Set.of();
  private final java.util.Map<String, Integer> mergedDocCounts = new java.util.HashMap<>();

  @Override
  public void merge(MergeState mergeState, NormsProducer norms) throws IOException {
    Set<String> eligible = new java.util.LinkedHashSet<>();
    if (mergeState.segmentInfo.getIndexSort() == null) {
      for (FieldInfo fi : mergeState.mergeFieldInfos) {
        if (!LuxirPostingsFormat.nativeCandidate(fi) && !LuxirPostingsFormat.nativeString(fi)) continue;
        boolean all = true, any = false;
        for (FieldsProducer p : mergeState.fieldsProducers) {
          Terms t = p == null ? null : p.terms(fi.name);
          if (t == null) continue;
          any = true;
          if (nativeReaderOf(t) == null) {
            all = false;
            break;
          }
        }
        if (all && any) eligible.add(fi.name);
      }
    }
    this.mergeState = mergeState;
    this.nativeMerge = eligible;
    super.merge(mergeState, norms);
  }

  @Override
  public void write(Fields fields, NormsProducer norms) throws IOException {
    if (written) throw new IllegalStateException("write called twice");
    written = true;
    // Fields Luxir analyzed and inverted itself (their terms are already in the native writer).
    NativeIndexer indexer = NativeIndexer.get(state.segmentInfo);
    Set<String> indexed = indexer == null ? Set.of() : indexer.postingsFields();
    List<String> nativeFields = new ArrayList<>();
    List<String> delegated = new ArrayList<>();
    for (String field : fields) {
      if (indexed.contains(field) || nativeMerge.contains(field)) continue;
      Terms terms = fields.terms(field);
      if (terms == null) continue;
      FieldInfo fi = state.fieldInfos.fieldInfo(field);
      if (norms != null && LuxirPostingsFormat.nativeCandidate(fi)) {
        nativeFields.add(field);
      } else {
        delegated.add(field);
      }
    }
    writeNative(fields, nativeFields, norms, !delegated.isEmpty(), indexer);
    if (!delegated.isEmpty()) {
      delegateConsumer = format.delegate.fieldsConsumer(state);
      delegateConsumer.write(only(fields, delegated), norms);
    }
  }

  private static Fields only(Fields fields, List<String> names) {
    Set<String> set = Set.copyOf(names);
    return new FilterLeafReader.FilterFields(fields) {
      @Override
      public Iterator<String> iterator() {
        return names.iterator();
      }

      @Override
      public Terms terms(String field) throws IOException {
        return set.contains(field) ? in.terms(field) : null;
      }

      @Override
      public int size() {
        return names.size();
      }
    };
  }

  /** Per-field statistics recorded in the metadata file (they include overflow terms). */
  record FieldStats(String name, long numTerms, int docCount, long sumDocFreq, long sumTotalTermFreq) {}

  private record Written(FieldStats stats, OverflowTerms.Writer overflow) {}

  private void writeNative(Fields fields, List<String> names, NormsProducer norms, boolean hasDelegate,
      NativeIndexer indexer) throws IOException {
    String metaName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, LuxirPostingsFormat.META_EXTENSION);
    String dataName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, LuxirPostingsFormat.DATA_EXTENSION);
    int maxDoc = state.segmentInfo.maxDoc();
    List<Written> stats = new ArrayList<>();
    try (IndexOutput meta = state.directory.createOutput(metaName, state.context);
        IndexOutput data = state.directory.createOutput(dataName, state.context);
        Arena arena = Arena.ofConfined()) {
      CodecUtil.writeIndexHeader(meta, LuxirPostingsFormat.META_CODEC, LuxirPostingsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(), state.segmentSuffix);
      CodecUtil.writeIndexHeader(data, LuxirPostingsFormat.DATA_CODEC, LuxirPostingsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(), state.segmentSuffix);
      List<String> fileNames = new ArrayList<>();
      List<long[]> fileLocs = new ArrayList<>();
      if (!names.isEmpty() || indexer != null || !nativeMerge.isEmpty()) {
        MemorySegment w;
        if (indexer != null) {
          w = indexer.take();
          check((int) invoke(() -> (int) LxlNative.WRITER_SET_MAX_DOC.invokeExact(w, maxDoc)));
        } else {
          w = check((MemorySegment) invoke(() -> (MemorySegment) LxlNative.WRITER_NEW.invokeExact(maxDoc)));
        }
        try {
          for (String name : names) {
            stats.add(writeField(w, name, fields.terms(name), norms.getNorms(state.fieldInfos.fieldInfo(name)), maxDoc));
          }
          if (indexer != null) {
            // Luxir value strings: docs-only postings from the native hash
            for (String name : indexer.searchableStrings()) {
              int ord = indexer.stringOrd(name);
              int docCount = check((int) invoke(() -> (int) LxlNative.WRITER_STRINGS_POSTINGS.invokeExact(w, ord)));
              if (docCount > 0) mergedDocCounts.put(name, docCount);
            }
          }
          if (!nativeMerge.isEmpty()) mergeNatively(w, arena);
          int numFiles = check((int) invoke(() -> (int) LxlNative.WRITER_FINISH.invokeExact(w)));
          Set<String> nativeStats = new java.util.LinkedHashSet<>(nativeMerge);
          if (indexer != null) nativeStats.addAll(indexer.postingsFields());
          if (!nativeStats.isEmpty()) {
            long[] st = new long[4];
            for (String name : nativeStats) {
              byte[] nb = name.getBytes(StandardCharsets.UTF_8);
              int found = check((int) invoke(() -> (int) LxlNative.WRITER_FIELD_STATS.invokeExact(w, heap(nb), nb.length, heap(st))));
              if (found == 0) continue; // no tokens in this segment
              int docCount = mergedDocCounts.getOrDefault(name, (int) st[1]);
              // docs-only fields: every term's total frequency is its doc frequency
              long sumTtf = LuxirPostingsFormat.nativeString(state.fieldInfos.fieldInfo(name)) ? st[2] : st[3];
              stats.add(new Written(new FieldStats(name, st[0], docCount, st[2], sumTtf), new OverflowTerms.Writer()));
            }
          }
          MemorySegment namePtr = arena.allocate(ValueLayout.ADDRESS);
          MemorySegment dataPtr = arena.allocate(ValueLayout.ADDRESS);
          MemorySegment lenPtr = arena.allocate(ValueLayout.JAVA_LONG);
          byte[] buf = new byte[1 << 16];
          for (int i = 0; i < numFiles; i++) {
            int fi = i;
            check((int) invoke(() -> (int) LxlNative.WRITER_FILE.invokeExact(w, fi, namePtr, dataPtr, lenPtr)));
            String fileName = namePtr.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0);
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
            fileNames.add(fileName);
            fileLocs.add(new long[] {offset, len});
          }
        } finally {
          invoke(() -> {
            LxlNative.WRITER_FREE.invokeExact(w);
            return null;
          });
        }
      }
      // Luxir's SIMD decoders read up to 16 bytes past a region.
      for (int i = 0; i < 16; i++) data.writeByte((byte) 0);
      CodecUtil.writeFooter(data);

      meta.writeVInt(stats.size());
      for (Written wr : stats) {
        FieldStats s = wr.stats();
        meta.writeString(s.name());
        meta.writeVLong(s.numTerms());
        meta.writeVInt(s.docCount());
        meta.writeVLong(s.sumDocFreq());
        meta.writeVLong(s.sumTotalTermFreq());
        wr.overflow().writeTo(meta);
      }
      meta.writeVInt(fileNames.size());
      for (int i = 0; i < fileNames.size(); i++) {
        meta.writeString(fileNames.get(i));
        meta.writeVLong(fileLocs.get(i)[0]);
        meta.writeVLong(fileLocs.get(i)[1]);
      }
      meta.writeByte((byte) (hasDelegate ? 1 : 0));
      CodecUtil.writeFooter(meta);
    }
    EarlyWriteback.submit(state.directory, dataName);
  }

  /**
   * The native reader of a Luxir-format field with no overflow terms, or null. Checked through
   * JDK interfaces, not instanceof: the merge's source segments may have been opened by a codec
   * from another classloader (Solr loads module jars twice; Lucene's SPI keeps the first).
   */
  @SuppressWarnings("unchecked")
  static MemorySegment nativeReaderOf(Terms t) {
    if (t == null || !t.getClass().getName().equals(LuxirTerms.class.getName())) return null;
    if (!(t instanceof java.util.function.Supplier<?> reader) || !(t instanceof java.util.function.Predicate<?> overflow)) {
      return null;
    }
    if (((java.util.function.Predicate<byte[]>) overflow).test(new byte[0])) return null; // has overflow terms
    return (MemorySegment) reader.get();
  }

  /** Merges {@link #nativeMerge} fields without leaving native code: postings, positions, norms. */
  private void mergeNatively(MemorySegment w, Arena arena) throws IOException {
    int n = mergeState.fieldsProducers.length;
    for (String field : nativeMerge) {
      MemorySegment sources = arena.allocate(ValueLayout.ADDRESS, n);
      MemorySegment maps = arena.allocate(ValueLayout.ADDRESS, n);
      for (int i = 0; i < n; i++) {
        FieldsProducer p = mergeState.fieldsProducers[i];
        Terms t = p == null ? null : p.terms(field);
        MemorySegment reader = nativeReaderOf(t);
        sources.setAtIndex(ValueLayout.ADDRESS, i, reader == null ? MemorySegment.NULL : reader);
        int maxDoc = mergeState.maxDocs[i];
        MemorySegment map = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, maxDoc));
        for (int d = 0; d < maxDoc; d++) map.setAtIndex(ValueLayout.JAVA_INT, d, mergeState.docMaps[i].get(d));
        maps.setAtIndex(ValueLayout.ADDRESS, i, map);
      }
      byte[] name = field.getBytes(StandardCharsets.UTF_8);
      MemorySegment nameSeg = arena.allocate(name.length);
      MemorySegment.copy(name, 0, nameSeg, ValueLayout.JAVA_BYTE, 0, name.length);
      int docCount = check((int) invoke(() -> (int) LxlNative.WRITER_MERGE_FIELD.invokeExact(w, nameSeg, name.length, n, sources, maps)));
      mergedDocCounts.put(field, docCount);
    }
  }

  private static Written writeField(MemorySegment w, String name, Terms terms, NumericDocValues norms, int maxDoc)
      throws IOException {
    // Norms: the Lucene-encoded byte of every doc with the field.
    int[] normDocs = new int[64];
    byte[] normValues = new byte[64];
    int numNorms = 0;
    for (int d = norms.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = norms.nextDoc()) {
      if (numNorms == normDocs.length) {
        int size = ArrayUtil.oversize(numNorms + 1, Integer.BYTES);
        normDocs = ArrayUtil.growExact(normDocs, size);
        normValues = ArrayUtil.growExact(normValues, size);
      }
      normDocs[numNorms] = d;
      normValues[numNorms] = (byte) norms.longValue();
      numNorms++;
    }
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    int n0 = numNorms;
    int[] nd = normDocs;
    byte[] nv = normValues;
    check((int) invoke(() -> (int) LxlNative.WRITER_START_FIELD.invokeExact(
        w, heap(nameBytes), nameBytes.length, heap(nd), heap(nv), n0)));

    FixedBitSet seen = new FixedBitSet(Math.max(1, maxDoc));
    long numTerms = 0, sumDocFreq = 0, sumTtf = 0;
    int[] docs = new int[256];
    int[] freqs = new int[256];
    int[] positions = new int[1024];
    OverflowTerms.Writer overflow = new OverflowTerms.Writer();
    TermsEnum te = terms.iterator();
    PostingsEnum pe = null;
    for (BytesRef term = te.next(); term != null; term = te.next()) {
      boolean tooLong = term.length > LxlNative.MAX_TERM; // kept as an overflow term
      MemorySegment termSeg = tooLong ? null : heap(term.bytes).asSlice(term.offset, term.length);
      int termLen = term.length;
      pe = te.postings(pe, PostingsEnum.POSITIONS);
      int n = 0, np = 0;
      long df = 0;
      boolean started = false;
      for (int d = pe.nextDoc(); d != DocIdSetIterator.NO_MORE_DOCS; d = pe.nextDoc()) {
        int f = pe.freq();
        if (n == docs.length) {
          docs = ArrayUtil.grow(docs, n + 1);
          freqs = ArrayUtil.grow(freqs, n + 1);
        }
        if (np + f > positions.length) positions = ArrayUtil.grow(positions, np + f);
        docs[n] = d;
        freqs[n] = f;
        for (int j = 0; j < f; j++) positions[np++] = pe.nextPosition();
        n++;
        df++;
        sumTtf += f;
        seen.set(d);
        if (n == CHUNK_DOCS && !tooLong) {
          sendTerm(w, termSeg, termLen, docs, freqs, n, positions, started ? 0 : LxlNative.TERM_START);
          started = true;
          n = 0;
          np = 0;
        }
      }
      if (df == 0) continue; // every doc of this term was deleted (merge)
      if (tooLong) {
        overflow.add(term, docs, freqs, n, positions);
      } else {
        sendTerm(w, termSeg, termLen, docs, freqs, n, positions,
            (started ? 0 : LxlNative.TERM_START) | LxlNative.TERM_END);
      }
      numTerms++;
      sumDocFreq += df;
    }
    check((int) invoke(() -> (int) LxlNative.WRITER_END_FIELD.invokeExact(w)));
    return new Written(new FieldStats(name, numTerms, seen.cardinality(), sumDocFreq, sumTtf), overflow);
  }

  private static void sendTerm(MemorySegment w, MemorySegment term, int termLen, int[] docs, int[] freqs, int n,
      int[] positions, int flags) {
    check((int) invoke(() -> (int) LxlNative.WRITER_TERM.invokeExact(
        w, term, termLen, heap(docs), heap(freqs), n, heap(positions), flags)));
  }

  interface NativeCall {
    Object call() throws Throwable;
  }

  static Object invoke(NativeCall c) {
    try {
      return c.call();
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(delegateConsumer);
  }
}
