// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * A field stored in Luxir format.
 *
 * <p>{@link #get()} returns the segment's native reader. It is reachable through the JDK {@link
 * Supplier} interface because the codec may be loaded by a different classloader than the code
 * that searches with it (Solr loads module jars twice, and Lucene keeps the first codec it saw).
 */
public final class LuxirTerms extends Terms implements Supplier<MemorySegment>, Predicate<byte[]> {
  private final LuxirFieldsProducer producer;
  private final LuxirFieldsConsumer.FieldStats stats;
  private final OverflowTerms overflow;
  private final boolean docsOnly; // a Luxir value string: no freqs, no positions

  LuxirTerms(LuxirFieldsProducer producer, LuxirFieldsConsumer.FieldStats stats, OverflowTerms overflow,
      boolean docsOnly) {
    this.producer = producer;
    this.stats = stats;
    this.overflow = overflow;
    this.docsOnly = docsOnly;
  }

  /** The native reader (lxl_reader*); throws if the segment was closed. */
  @Override
  public MemorySegment get() {
    return producer.nativeReader();
  }

  /**
   * Whether an overflow term (longer than 255 bytes, stored outside Luxir's dictionary) starts
   * with {@code prefix}: native prefix expansion would not see it.
   */
  @Override
  public boolean test(byte[] prefix) {
    for (BytesRef t : overflow.terms) {
      if (t.length >= prefix.length
          && Arrays.equals(t.bytes, t.offset, t.offset + prefix.length, prefix, 0, prefix.length)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public TermsEnum iterator() throws IOException {
    producer.ensureOpen();
    LuxirTermsEnum terms = new LuxirTermsEnum(producer, stats.name(), null, docsOnly);
    return overflow.isEmpty() ? terms : new MergedTermsEnum(terms, overflow.new Enum(null));
  }

  /** Native DFA intersection for deterministic automata; Lucene's generic walk otherwise. */
  @Override
  public TermsEnum intersect(CompiledAutomaton compiled, BytesRef startTerm) throws IOException {
    if (startTerm != null
        || compiled.type != CompiledAutomaton.AUTOMATON_TYPE.NORMAL
        || compiled.runAutomaton == null
        || compiled.automaton == null
        || !compiled.automaton.isDeterministic()) {
      return super.intersect(compiled, startTerm);
    }
    producer.ensureOpen();
    LuxirTermsEnum.Dfa dfa = LuxirTermsEnum.Dfa.of(compiled);
    if (dfa == null) return super.intersect(compiled, startTerm);
    LuxirTermsEnum terms = new LuxirTermsEnum(producer, stats.name(), dfa, docsOnly);
    return overflow.isEmpty() ? terms : new MergedTermsEnum(terms, overflow.new Enum(compiled));
  }

  @Override
  public long size() {
    return stats.numTerms();
  }

  @Override
  public long getSumTotalTermFreq() {
    return stats.sumTotalTermFreq();
  }

  @Override
  public long getSumDocFreq() {
    return stats.sumDocFreq();
  }

  @Override
  public int getDocCount() {
    return stats.docCount();
  }

  @Override
  public boolean hasFreqs() {
    return !docsOnly;
  }

  @Override
  public boolean hasOffsets() {
    return false;
  }

  @Override
  public boolean hasPositions() {
    return !docsOnly;
  }

  @Override
  public boolean hasPayloads() {
    return false;
  }
}
