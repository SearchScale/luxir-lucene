// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Stores positional text postings in Luxir's native segment format, inside ordinary Lucene segment
 * files, so Lucene keeps owning commits, deletes, merges, NRT reopen and replication.
 *
 * <p>A field is stored natively when it indexes docs, freqs and positions without offsets or
 * payloads, has norms, and all its terms are at most 255 bytes (Luxir's term limit). Every other
 * field this format is asked to handle is written by an embedded {@link Lucene104PostingsFormat},
 * so the format accepts any field Lucene can index.
 *
 * <p>Files per segment: {@code .lxm} (metadata: native fields, their statistics and the location
 * of each Luxir file) and {@code .lxd} (the Luxir segment files, 8-byte aligned, read in place
 * through Lucene's memory mapping).
 */
public final class LuxirPostingsFormat extends PostingsFormat {
  public static final String NAME = "Luxir";

  static final String META_CODEC = "LuxirPostingsMeta";
  static final String DATA_CODEC = "LuxirPostingsData";
  static final String META_EXTENSION = "lxm";
  static final String DATA_EXTENSION = "lxd";
  static final int VERSION_START = 0;
  static final int VERSION_CURRENT = VERSION_START;

  final PostingsFormat delegate = new Lucene104PostingsFormat();

  public LuxirPostingsFormat() {
    super(NAME);
  }

  /** Whether a field's index options allow Luxir storage (term lengths are checked at write). */
  static boolean nativeCandidate(FieldInfo fi) {
    return fi.getIndexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
        && !fi.hasPayloads()
        && !fi.omitsNorms();
  }

  /** Whether a field is a Luxir value string with docs-only postings (written natively). */
  static boolean nativeString(FieldInfo fi) {
    return fi != null && fi.getIndexOptions() == IndexOptions.DOCS && LuxirValueField.kind(fi) == 's';
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    return new LuxirFieldsConsumer(this, state);
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    return new LuxirFieldsProducer(this, state);
  }
}
