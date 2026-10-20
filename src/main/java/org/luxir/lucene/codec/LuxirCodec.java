// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.codecs.FieldInfosFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.NormsFormat;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;

/**
 * Lucene's codec with Luxir doing the text indexing:
 *
 * <ul>
 *   <li>postings: per field, {@link LuxirPostingsFormat} unless the selector names another;
 *   <li>{@link org.luxir.lucene.document.LuxirTextField}s are analyzed and inverted by Luxir as
 *       they are indexed (fed through the stored-fields channel), and their norms are read from
 *       the Luxir segment;
 *   <li>stored fields are Luxir's ({@link LuxirStoredFieldsFormat});
 *   <li>numeric doc values are Luxir columns ({@link LuxirDocValuesFormat});
 *   <li>one-dimensional points are Luxir's points index ({@link LuxirPointsFormat});
 *   <li>everything else (other doc values and points, vectors) is the delegate codec's.
 * </ul>
 *
 * The no-argument constructor (used when reading, through SPI) cannot index Luxir text; writers
 * pass the native schema: a Luxir SchemaDef naming the Luxir-indexed fields and their analyzers.
 */
public final class LuxirCodec extends FilterCodec {
  public static final String NAME = "Luxir104";

  private final LuxirPostingsFormat luxir = new LuxirPostingsFormat();
  private final PostingsFormat postings;
  private final StoredFieldsFormat stored;
  private final NormsFormat norms;
  private final DocValuesFormat docValues;
  private final PointsFormat points;

  public LuxirCodec() {
    this(new Lucene104Codec(), field -> null, null);
  }

  /** Every field's postings in Luxir format; {@code nativeSchema} for Luxir-indexed fields. */
  public LuxirCodec(Supplier<String> nativeSchema) {
    this(new Lucene104Codec(), field -> null, nativeSchema);
  }

  /**
   * @param delegate formats Luxir does not provide come from here
   * @param postingsFormatForField a field's postings format, or null for Luxir
   * @param nativeSchema the Luxir SchemaDef of Luxir-indexed fields (null: none)
   */
  public LuxirCodec(Codec delegate, Function<String, PostingsFormat> postingsFormatForField,
      Supplier<String> nativeSchema) {
    super(NAME, delegate);
    PostingsFormat perField = new PerFieldPostingsFormat() {
      @Override
      public PostingsFormat getPostingsFormatForField(String field) {
        PostingsFormat p = postingsFormatForField.apply(field);
        return p == null ? luxir : p;
      }
    };
    this.postings = new NativeFieldsPostingsFormat(perField);
    this.stored = new LuxirStoredFieldsFormat(nativeSchema);
    this.norms = new LuxirNormsFormat(delegate.normsFormat(), luxir);
    this.docValues = new LuxirDocValuesFormat(delegate.docValuesFormat());
    this.points = new LuxirPointsFormat(delegate.pointsFormat());
  }

  @Override
  public PostingsFormat postingsFormat() {
    return postings;
  }

  @Override
  public FieldInfosFormat fieldInfosFormat() {
    return fieldInfos;
  }

  /**
   * Field infos are written last in a flush: a segment without postings never had its native
   * indexer taken by the postings writer, so it is released here.
   */
  private final FieldInfosFormat fieldInfos = new FieldInfosFormat() {
    @Override
    public FieldInfos read(Directory directory, SegmentInfo segmentInfo, String segmentSuffix, IOContext iocontext)
        throws IOException {
      return delegate.fieldInfosFormat().read(directory, segmentInfo, segmentSuffix, iocontext);
    }

    @Override
    public void write(Directory directory, SegmentInfo segmentInfo, String segmentSuffix, FieldInfos infos,
        IOContext context) throws IOException {
      NativeIndexer leftover = NativeIndexer.get(segmentInfo);
      if (leftover != null) leftover.free();
      delegate.fieldInfosFormat().write(directory, segmentInfo, segmentSuffix, infos, context);
    }
  };

  @Override
  public StoredFieldsFormat storedFieldsFormat() {
    return stored;
  }

  @Override
  public NormsFormat normsFormat() {
    return norms;
  }

  @Override
  public DocValuesFormat docValuesFormat() {
    return docValues;
  }

  @Override
  public PointsFormat pointsFormat() {
    return points;
  }

  /**
   * Per-field postings, where the fields Luxir indexed itself (which have no Lucene postings) are
   * still handed to their format, so the Luxir postings writer writes them out.
   */
  private static final class NativeFieldsPostingsFormat extends PostingsFormat {
    private final PostingsFormat perField;

    NativeFieldsPostingsFormat(PostingsFormat perField) {
      super("LuxirPerField");
      this.perField = perField;
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
      FieldsConsumer in = perField.fieldsConsumer(state);
      return new FieldsConsumer() {
        @Override
        public void write(Fields fields, NormsProducer norms) throws IOException {
          NativeIndexer indexer = NativeIndexer.get(state.segmentInfo);
          Set<String> nativeFields = indexer == null ? Set.of() : indexer.postingsFields();
          in.write(nativeFields.isEmpty() ? fields : withNative(fields, nativeFields), norms);
        }

        @Override
        public void merge(MergeState mergeState, NormsProducer norms) throws IOException {
          in.merge(mergeState, norms);
        }

        @Override
        public void close() throws IOException {
          in.close();
        }
      };
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
      return perField.fieldsProducer(state);
    }
  }

  private static Fields withNative(Fields fields, Set<String> nativeFields) {
    Set<String> all = new TreeSet<>(nativeFields);
    for (String f : fields) all.add(f);
    return new Fields() {
      @Override
      public Iterator<String> iterator() {
        return all.iterator();
      }

      @Override
      public Terms terms(String field) throws IOException {
        return nativeFields.contains(field) ? NATIVE_STUB : fields.terms(field);
      }

      @Override
      public int size() {
        return all.size();
      }
    };
  }

  /** Placeholder for a field whose postings the native writer already holds. */
  private static final Terms NATIVE_STUB = new Terms() {
    @Override
    public TermsEnum iterator() {
      return TermsEnum.EMPTY;
    }

    @Override
    public long size() {
      return 0;
    }

    @Override
    public long getSumTotalTermFreq() {
      return 0;
    }

    @Override
    public long getSumDocFreq() {
      return 0;
    }

    @Override
    public int getDocCount() {
      return 0;
    }

    @Override
    public boolean hasFreqs() {
      return true;
    }

    @Override
    public boolean hasOffsets() {
      return false;
    }

    @Override
    public boolean hasPositions() {
      return true;
    }

    @Override
    public boolean hasPayloads() {
      return false;
    }
  };
}
