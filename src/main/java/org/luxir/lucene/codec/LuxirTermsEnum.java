// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SlowImpactsEnum;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.LxlNative;

/**
 * Lucene TermsEnum over a Luxir term dictionary: one native call per move. In intersect mode it
 * enumerates the terms a byte DFA accepts, with the whole walk (prefix seek, successor jumps,
 * suffix rejection) done natively by Luxir's DfaIntersectEnum.
 *
 * <p>Native objects are freed by NativeCleaner once this enum is unreachable, so every native call is
 * followed by a reachability fence on {@code this}.
 */
final class LuxirTermsEnum extends BaseTermsEnum {
  private final LuxirFieldsProducer producer;
  private final byte[] fieldBytes;
  private final LuxirDfa dfa; // null: a full dictionary enum
  private final NativeHandle handle;
  private final byte[] termBuf = new byte[LxlNative.MAX_TERM];
  private final BytesRef term = new BytesRef(termBuf, 0, 0);
  private final long[] stats = new long[3]; // docFreq, totalTermFreq, ord
  private final int[] lenOut = new int[1];
  private boolean positioned;
  private final boolean docsOnly; // docs-only postings: freq 1, no positions

  LuxirTermsEnum(LuxirFieldsProducer producer, String field) {
    this(producer, field, null, false);
  }

  LuxirTermsEnum(LuxirFieldsProducer producer, String field, LuxirDfa dfa, boolean docsOnly) {
    this.producer = producer;
    this.docsOnly = docsOnly;
    this.fieldBytes = field.getBytes(StandardCharsets.UTF_8);
    this.dfa = dfa;
    this.handle = NativeHandle.register(this, dfa == null ? LxlNative.TERMS_FREE : LxlNative.INTERSECT_FREE);
  }

  /** The owned native object: an lxl_terms, or an lxl_intersect in intersect mode. */
  private MemorySegment h() {
    producer.ensureOpen();
    MemorySegment p = handle.get();
    if (p.address() == 0) {
      try {
        if (dfa == null) {
          p = check((MemorySegment) LxlNative.TERMS_NEW.invokeExact(
              producer.nativeReader(), heap(fieldBytes), fieldBytes.length));
        } else {
          p = check((MemorySegment) LxlNative.INTERSECT_NEW.invokeExact(producer.nativeReader(), heap(fieldBytes),
              fieldBytes.length, dfa.accepts().length, heap(dfa.accepts()), heap(dfa.offsets()), heap(dfa.ranges()),
              heap(dfa.prefix()), dfa.prefix().length, heap(dfa.suffix()), dfa.suffix().length, dfa.initialState(),
              heap(dfa.startAfter() == null ? new byte[0] : dfa.startAfter()),
              dfa.startAfter() == null ? -1 : dfa.startAfter().length));
        }
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      handle.set(p);
    }
    return p;
  }

  @Override
  public BytesRef next() throws IOException {
    int rc;
    try {
      rc = dfa == null
          ? (int) LxlNative.TERMS_NEXT.invokeExact(h(), heap(termBuf), heap(stats))
          : (int) LxlNative.INTERSECT_NEXT.invokeExact(h(), heap(termBuf), heap(stats));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    if (rc == -2) {
      positioned = false;
      return null;
    }
    term.length = check(rc);
    positioned = true;
    return term;
  }

  private int seek(BytesRef text, int len, boolean exact) {
    if (dfa != null) throw new UnsupportedOperationException("seek on an intersect enum");
    int rc;
    try {
      rc = (int) LxlNative.TERMS_SEEK.invokeExact(h(), heap(text.bytes).asSlice(text.offset, len), len,
          exact ? 1 : 0, heap(termBuf), heap(lenOut), heap(stats));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    check(rc);
    positioned = rc != LxlNative.SEEK_END && (rc == LxlNative.SEEK_FOUND || !exact);
    if (positioned) term.length = lenOut[0];
    return rc;
  }

  @Override
  public boolean seekExact(BytesRef text) throws IOException {
    if (text.length > LxlNative.MAX_TERM) {
      positioned = false;
      return false;
    }
    return seek(text, text.length, true) == LxlNative.SEEK_FOUND;
  }

  @Override
  public SeekStatus seekCeil(BytesRef text) throws IOException {
    if (text.length > LxlNative.MAX_TERM) {
      // No stored term is this long: the ceiling is the first term after its 255-byte prefix.
      int rc = seek(text, LxlNative.MAX_TERM, false);
      if (rc == LxlNative.SEEK_END) return SeekStatus.END;
      if (rc == LxlNative.SEEK_FOUND) return next() == null ? SeekStatus.END : SeekStatus.NOT_FOUND;
      return SeekStatus.NOT_FOUND;
    }
    int rc = seek(text, text.length, false);
    return switch (rc) {
      case LxlNative.SEEK_FOUND -> SeekStatus.FOUND;
      case LxlNative.SEEK_NOT_FOUND -> SeekStatus.NOT_FOUND;
      default -> SeekStatus.END;
    };
  }

  @Override
  public void seekExact(long ord) throws IOException {
    if (dfa != null) throw new UnsupportedOperationException("seek on an intersect enum");
    int rc;
    try {
      rc = (int) LxlNative.TERMS_SEEK_ORD.invokeExact(h(), ord, heap(termBuf), heap(stats));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    term.length = check(rc);
    positioned = true;
  }

  @Override
  public BytesRef term() {
    return term;
  }

  @Override
  public long ord() {
    return stats[2];
  }

  @Override
  public int docFreq() {
    return (int) stats[0];
  }

  @Override
  public long totalTermFreq() {
    return docsOnly ? stats[0] : stats[1];
  }

  @Override
  public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
    if (!positioned) throw new IllegalStateException("terms enum is not positioned");
    boolean positions = !docsOnly && PostingsEnum.featureRequested(flags, PostingsEnum.POSITIONS);
    MemorySegment p;
    try {
      MemorySegment terms = dfa == null ? h() : (MemorySegment) LxlNative.INTERSECT_TERMS.invokeExact(h());
      p = check((MemorySegment) LxlNative.POSTINGS_NEW.invokeExact(terms, positions ? 1 : 0));
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(this);
    }
    LuxirPostingsEnum e =
        reuse instanceof LuxirPostingsEnum r && r.positions == positions ? r : new LuxirPostingsEnum(producer, positions);
    e.reset(p, docFreq());
    return e;
  }

  @Override
  public ImpactsEnum impacts(int flags) throws IOException {
    return new SlowImpactsEnum(postings(null, flags));
  }
}
