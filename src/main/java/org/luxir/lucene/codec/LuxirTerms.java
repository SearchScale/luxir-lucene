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
import org.luxir.lucene.LxlNative;

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
    if (compiled.type != CompiledAutomaton.AUTOMATON_TYPE.NORMAL
        || compiled.runAutomaton == null
        || compiled.automaton == null
        || !compiled.automaton.isDeterministic()) {
      return super.intersect(compiled, startTerm);
    }
    producer.ensureOpen();
    LuxirDfa dfa = LuxirDfa.of(compiled, startTerm);
    if (dfa == null) return super.intersect(compiled, startTerm);
    LuxirTermsEnum terms = new LuxirTermsEnum(producer, stats.name(), dfa, docsOnly);
    return overflow.isEmpty() ? terms : new MergedTermsEnum(terms, overflow.new Enum(compiled, startTerm));
  }

  /**
   * The term's docFreq and totalTermFreq in this segment by one native lookup (zeros if absent),
   * without a terms enum; null for a term too long for the native dictionary.
   */
  public long[] termStats(BytesRef term) {
    if (term.length > LxlNative.MAX_TERM) return null;
    producer.ensureOpen();
    byte[] fieldBytes = stats.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] bytes = Arrays.copyOfRange(term.bytes, term.offset, term.offset + term.length);
    long[] out = new long[2];
    int found;
    try {
      found = (int) LxlNative.TERM_STATS.invokeExact(producer.nativeReader(), LxlNative.heap(fieldBytes),
          fieldBytes.length, LxlNative.heap(bytes), bytes.length, LxlNative.heap(out));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
    LxlNative.check(found);
    return found == 1 ? out : new long[2];
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
