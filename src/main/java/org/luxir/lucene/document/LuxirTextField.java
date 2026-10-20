// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.document;

import java.nio.charset.StandardCharsets;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

/**
 * A text field that Luxir indexes: its text reaches the codec (through the stored-fields channel,
 * on the indexing thread), where Luxir's own analyzer and inverter build the postings and norms.
 * Lucene's indexing chain sees no tokens for it, so its analysis, term hashing and in-memory
 * postings are skipped entirely.
 *
 * <p>Requires an index written with {@link org.luxir.lucene.codec.LuxirCodec} configured with a
 * native schema naming this field (or a suffix template matching it). Search with {@link
 * org.luxir.lucene.analysis.LuxirAnalyzer} over the same chain.
 */
public final class LuxirTextField extends Field {
  /** FieldInfo attribute marking a Luxir-indexed field. */
  public static final String NATIVE = "luxir.native";
  /** FieldInfo attribute: whether the text is also stored. */
  public static final String STORED = "luxir.stored";

  public static final FieldType TYPE_STORED = type(true);
  public static final FieldType TYPE_NOT_STORED = type(false);

  private static FieldType type(boolean stored) {
    FieldType t = new FieldType();
    t.setTokenized(true);
    t.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    t.setOmitNorms(false);
    t.setStored(true); // the text always travels to the codec; STORED decides whether it is kept
    t.putAttribute(NATIVE, "1");
    t.putAttribute(STORED, stored ? "1" : "0");
    t.freeze();
    return t;
  }

  public LuxirTextField(String name, String value, boolean stored) {
    super(name, value, stored ? TYPE_STORED : TYPE_NOT_STORED);
  }

  /**
   * Text given as UTF-8 bytes (not copied): it reaches Luxir without ever becoming a Java String,
   * e.g. straight from a request body.
   */
  public LuxirTextField(String name, byte[] utf8, int offset, int length, boolean stored) {
    super(name, "", stored ? TYPE_STORED : TYPE_NOT_STORED);
    fieldsData = new org.apache.lucene.util.BytesRef(utf8, offset, length);
  }

  /**
   * The field's name in the native schema. Luxir's schema holds names of ASCII letters, digits and
   * single underscores that start with a letter (at most 127 bytes); any other name (such as Solr's
   * {@code _text_}) is indexed as {@code lx} followed by its UTF-8 bytes in hex, and the segment
   * records it under its own name.
   */
  public static String schemaName(String field) {
    if (nativeName(field)) return field;
    byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
    if (2 + 2 * utf8.length > MAX_SCHEMA_NAME) {
      throw new IllegalArgumentException("Luxir text field name too long: " + field);
    }
    StringBuilder sb = new StringBuilder(2 + 2 * utf8.length).append("lx");
    for (byte b : utf8) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  private static final int MAX_SCHEMA_NAME = 127;

  private static boolean nativeName(String name) {
    if (name.isEmpty() || name.length() > MAX_SCHEMA_NAME || name.contains("__")) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
      if (!(letter || (i > 0 && ((c >= '0' && c <= '9') || c == '_')))) return false;
    }
    return true;
  }

  /** Whether a field is Luxir-indexed (from its FieldInfo attributes). */
  public static boolean isNative(org.apache.lucene.index.FieldInfo fi) {
    return "1".equals(fi.getAttribute(NATIVE));
  }

  @Override
  public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
    return reuse instanceof NoTokens ? reuse : new NoTokens();
  }

  /** Lucene inverts nothing for this field. */
  private static final class NoTokens extends TokenStream {
    NoTokens() {
      addAttribute(CharTermAttribute.class);
    }

    @Override
    public boolean incrementToken() {
      return false;
    }
  }
}
