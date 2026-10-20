// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.codecs.NormsConsumer;
import org.apache.lucene.codecs.NormsFormat;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.IOUtils;
import org.luxir.lucene.document.LuxirTextField;

/**
 * Lucene's norms, except for Luxir-indexed fields in flushed segments: Lucene never saw their
 * tokens, so their norms (Luxir's, from the same token counts) are read from the segment's Luxir
 * postings. That keeps Lucene's own BM25 scoring of these fields (pull scorers, explain) equal to
 * the native one, and merges carry the right norms into the merged segment.
 */
final class LuxirNormsFormat extends NormsFormat {
  // Per segment: where a Luxir-indexed field's norms are. A flushed segment's are in its Luxir
  // postings (Lucene saw no tokens); a merge writes the merged norms in Lucene's format, because
  // Lucene reads them back while it writes the merged postings.
  static final String NORMS_KEY = "luxir.norms";
  static final String IN_LUXIR = "luxir";
  static final String IN_LUCENE = "lucene";

  private final NormsFormat delegate;
  private final LuxirPostingsFormat postings;

  LuxirNormsFormat(NormsFormat delegate, LuxirPostingsFormat postings) {
    this.delegate = delegate;
    this.postings = postings;
  }

  @Override
  public NormsConsumer normsConsumer(SegmentWriteState state) throws IOException {
    NormsConsumer in = delegate.normsConsumer(state);
    return new NormsConsumer() {
      @Override
      public void addNormsField(FieldInfo field, NormsProducer norms) throws IOException {
        if (LuxirTextField.isNative(field) && NativeIndexer.get(state.segmentInfo) != null) {
          // flushed segment: Lucene saw no tokens; the norms are in the Luxir segment
          field.putAttribute(NORMS_KEY, IN_LUXIR);
          return;
        }
        if (LuxirTextField.isNative(field)) field.putAttribute(NORMS_KEY, IN_LUCENE); // merged
        in.addNormsField(field, norms);
      }

      @Override
      public void merge(MergeState mergeState) throws IOException {
        super.merge(mergeState);
      }

      @Override
      public void close() throws IOException {
        in.close();
      }
    };
  }

  @Override
  public NormsProducer normsProducer(SegmentReadState state) throws IOException {
    return new Producer(delegate.normsProducer(state), state);
  }

  private final class Producer extends NormsProducer {
    private final NormsProducer in;
    private final SegmentReadState state;
    private final Map<String, byte[]> nativeNorms = new ConcurrentHashMap<>();
    private LuxirFieldsProducer luxir; // opened on first use

    Producer(NormsProducer in, SegmentReadState state) {
      this.in = in;
      this.state = state;
    }

    private synchronized LuxirFieldsProducer luxir() throws IOException {
      if (luxir == null) {
        String suffix = null;
        String prefix = state.segmentInfo.name + "_";
        // the segment's directory lists the files inside a compound file too
        for (String f : state.directory.listAll()) {
          if (f.startsWith(prefix) && f.endsWith("." + LuxirPostingsFormat.META_EXTENSION)) {
            suffix = f.substring(prefix.length(), f.length() - LuxirPostingsFormat.META_EXTENSION.length() - 1);
            break;
          }
        }
        if (suffix == null) throw new IllegalStateException("no Luxir postings in segment " + state.segmentInfo.name);
        luxir = new LuxirFieldsProducer(postings, new SegmentReadState(state, suffix));
      }
      return luxir;
    }

    @Override
    public NumericDocValues getNorms(FieldInfo field) throws IOException {
      if (!LuxirTextField.isNative(field) || !IN_LUXIR.equals(field.getAttribute(NORMS_KEY))) {
        return in.getNorms(field);
      }
      byte[] norms = nativeNorms.get(field.name);
      if (norms == null) {
        norms = luxir().norms(field.name);
        nativeNorms.put(field.name, norms);
      }
      return new ByteNorms(norms);
    }

    @Override
    public void checkIntegrity() throws IOException {
      in.checkIntegrity();
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(in, luxir);
    }
  }

  /** Dense norm bytes; a zero byte means the doc has no tokens in the field. */
  private static final class ByteNorms extends NumericDocValues {
    private final byte[] norms;
    private int doc = -1;

    ByteNorms(byte[] norms) {
      this.norms = norms;
    }

    @Override
    public long longValue() {
      return norms[doc];
    }

    @Override
    public boolean advanceExact(int target) {
      doc = target;
      return norms[target] != 0;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) {
      for (int d = target; d < norms.length; d++) {
        if (norms[d] != 0) return doc = d;
      }
      return doc = NO_MORE_DOCS;
    }

    @Override
    public long cost() {
      return norms.length;
    }
  }
}
