// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.document;

import java.io.Reader;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.InvertableType;
import org.apache.lucene.document.StoredValue;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.util.BytesRef;

/**
 * A string or numeric value that Luxir indexes: the codec builds its postings (strings: one term,
 * docs only), doc values and points natively from the value, which reaches it through the
 * stored-fields channel on the indexing thread. Lucene's indexing chain hashes, buffers and sorts
 * nothing for it. One instance replaces the separate indexed, doc-values and stored fields of a
 * value.
 *
 * <p>Lucene builds each document's field schema from the fields' types, then indexes each field
 * by its type. The codec marks the start of every document on the indexing thread ({@link
 * #startDocument()}); in the first document started after this field was created, it reports its
 * full schema the first time Lucene asks (so the segment's {@link FieldInfo} declares the
 * postings, doc values and points that readers use) and a stored-only type afterwards, so its
 * value goes to the codec alone. Without that mark (another codec, index sorting) it always
 * reports its full schema, and the value fails loudly in Lucene's inverter or points buffer
 * rather than be indexed twice or not at all.
 *
 * <p>Doc values follow Solr's point-field convention: ints and longs as is; floats and doubles as
 * {@link Float#floatToIntBits} / {@link Double#doubleToLongBits} when single-valued ({@link
 * DocValuesType#NUMERIC}) and as sortable bits when multi-valued ({@link
 * DocValuesType#SORTED_NUMERIC}). Strings indexed for search are limited to 255 UTF-8 bytes.
 *
 * <p>Requires {@link org.luxir.lucene.codec.LuxirCodec}.
 */
public final class LuxirValueField implements IndexableField {
  /** FieldInfo attribute marking a Luxir value field: {@code s} (string) or {@code n} (number). */
  public static final String VALUE = "luxir.value";
  /** Longest string, in UTF-8 bytes, that can be indexed for search. */
  public static final int MAX_TERM_BYTES = 255;

  private static final ThreadLocal<long[]> DOC = ThreadLocal.withInitial(() -> new long[1]);

  /** Called by the codec as Lucene starts each document on this thread. */
  public static void startDocument() {
    DOC.get()[0]++;
  }

  /** Whether a field holds Luxir values: 's', 'n' or 0 (from its FieldInfo attributes). */
  public static char kind(FieldInfo fi) {
    String v = fi.getAttribute(VALUE);
    return v == null || v.isEmpty() ? 0 : v.charAt(0);
  }

  /** The types of one kind of field: its full schema and the stored-only view. */
  public static final class Type {
    final FieldType full, light;
    final boolean string;
    final boolean searchable;
    final int pointBytes;

    private Type(boolean string, IndexOptions index, int pointBytes, DocValuesType dv, boolean stored) {
      this.string = string;
      this.searchable = index != IndexOptions.NONE;
      this.pointBytes = pointBytes;
      String kind = string ? "s" : "n";
      String st = stored ? "1" : "0";
      FieldType f = new FieldType();
      f.setStored(true); // the value always travels to the codec; STORED decides whether it is kept
      f.setTokenized(false);
      f.setOmitNorms(true);
      f.setIndexOptions(index);
      if (pointBytes > 0) f.setDimensions(1, pointBytes);
      f.setDocValuesType(dv);
      f.putAttribute(VALUE, kind);
      f.putAttribute(LuxirTextField.STORED, st);
      f.freeze();
      full = f;
      FieldType l = new FieldType();
      l.setStored(true);
      l.setTokenized(false);
      l.setOmitNorms(true);
      l.setIndexOptions(IndexOptions.NONE);
      l.putAttribute(VALUE, kind);
      l.putAttribute(LuxirTextField.STORED, st);
      l.freeze();
      light = l;
    }

    /**
     * A string field: {@code indexed} gives docs-only postings (term queries); {@code docValues}
     * is {@link DocValuesType#SORTED}, {@link DocValuesType#SORTED_SET} or NONE.
     */
    public static Type string(boolean indexed, DocValuesType docValues, boolean stored) {
      if (docValues != DocValuesType.NONE && docValues != DocValuesType.SORTED && docValues != DocValuesType.SORTED_SET) {
        throw new IllegalArgumentException("string doc values must be SORTED or SORTED_SET: " + docValues);
      }
      return new Type(true, indexed ? IndexOptions.DOCS : IndexOptions.NONE, 0, docValues, stored);
    }

    /**
     * A numeric field: {@code pointBytes} 4 (int, float) or 8 (long, double) gives a 1-D point
     * (range queries), 0 none; {@code docValues} is {@link DocValuesType#NUMERIC}, {@link
     * DocValuesType#SORTED_NUMERIC} or NONE.
     */
    public static Type number(int pointBytes, DocValuesType docValues, boolean stored) {
      if (pointBytes != 0 && pointBytes != 4 && pointBytes != 8) {
        throw new IllegalArgumentException("point bytes must be 0, 4 or 8: " + pointBytes);
      }
      if (docValues != DocValuesType.NONE
          && docValues != DocValuesType.NUMERIC
          && docValues != DocValuesType.SORTED_NUMERIC) {
        throw new IllegalArgumentException("numeric doc values must be NUMERIC or SORTED_NUMERIC: " + docValues);
      }
      return new Type(false, IndexOptions.NONE, pointBytes, docValues, stored);
    }
  }

  private final String name;
  private final Type type;
  private final BytesRef utf8; // strings
  private final Number number; // numbers: Integer, Long, Float or Double
  // The creating thread's document counter (one thread-local read per field) and its value then.
  // Indexed on another thread, the field keeps reporting its full schema: Lucene then fails on it.
  private final long[] doc = DOC.get();
  private final long born = doc[0];
  private long seen = -1; // the document in which the full schema was reported

  /** A string value given as UTF-8 bytes (not copied). */
  public LuxirValueField(String name, Type type, BytesRef utf8) {
    if (!type.string) throw new IllegalArgumentException("field " + name + " is numeric");
    if (type.searchable && utf8.length > MAX_TERM_BYTES) {
      throw new IllegalArgumentException(
          "value of field " + name + " is " + utf8.length + " UTF-8 bytes; at most " + MAX_TERM_BYTES + " can be indexed");
    }
    this.name = name;
    this.type = type;
    this.utf8 = utf8;
    this.number = null;
  }

  public LuxirValueField(String name, Type type, String value) {
    this(name, type, new BytesRef(value));
  }

  /** A numeric value: an Integer or Float (4-byte point) or a Long or Double (8-byte point). */
  public LuxirValueField(String name, Type type, Number value) {
    if (type.string) throw new IllegalArgumentException("field " + name + " is a string field");
    int bytes =
        switch (value) {
          case Integer i -> 4;
          case Float f -> 4;
          case Long l -> 8;
          case Double d -> 8;
          default -> throw new IllegalArgumentException("unsupported number " + value.getClass());
        };
    if (type.pointBytes != 0 && type.pointBytes != bytes) {
      throw new IllegalArgumentException("field " + name + " has " + type.pointBytes + "-byte points, value " + value);
    }
    this.name = name;
    this.type = type;
    this.utf8 = null;
    this.number = value;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public IndexableFieldType fieldType() {
    long current = doc[0];
    // Before Lucene starts the document (e.g. the application inspecting it), and always when no
    // Luxir codec marks documents, the full schema: Lucene then fails on the value, loudly.
    if (current == born) return type.full;
    if (current != seen) {
      seen = current;
      return type.full;
    }
    return type.light;
  }

  @Override
  public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
    throw misrouted();
  }

  @Override
  public BytesRef binaryValue() {
    if (utf8 == null) throw misrouted(); // only Lucene's points buffer asks a number for bytes
    return utf8;
  }

  @Override
  public String stringValue() {
    return null;
  }

  @Override
  public Reader readerValue() {
    return null;
  }

  @Override
  public Number numericValue() {
    return number;
  }

  @Override
  public StoredValue storedValue() {
    return switch (number) {
      case null -> new StoredValue(utf8);
      case Integer i -> new StoredValue(i.intValue());
      case Long l -> new StoredValue(l.longValue());
      case Float f -> new StoredValue(f.floatValue());
      case Double d -> new StoredValue(d.doubleValue());
      default -> throw new AssertionError(number);
    };
  }

  @Override
  public InvertableType invertableType() {
    return InvertableType.TOKEN_STREAM;
  }

  private IllegalStateException misrouted() {
    return new IllegalStateException(
        "Luxir value field \""
            + name
            + "\" reached Lucene's indexing chain: index it with LuxirCodec, without index sorting");
  }

  @Override
  public String toString() {
    return "LuxirValueField<" + name + ":" + (utf8 != null ? utf8.utf8ToString() : number) + ">";
  }
}
