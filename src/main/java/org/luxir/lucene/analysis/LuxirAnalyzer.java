// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.analysis;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.UnicodeUtil;
import org.luxir.lucene.LxlNative;

/**
 * A Lucene Analyzer that runs a Luxir analysis chain natively. It is the query-time (and
 * highlighting) counterpart of {@link org.luxir.lucene.document.LuxirTextField}, whose text Luxir
 * analyzes at index time with the same chain, so query terms and indexed terms always agree.
 *
 * <p>The chain is a Luxir AnalyzerDef in JSON, e.g. {@code
 * {"tokenizer":"unicode_word","filters":["nfkc_cf","fold",{"name":"kstem","params":{"possessive":false}}]}}.
 * Like Luxir's indexer, values of a multi-valued field are 100 positions apart.
 */
public class LuxirAnalyzer extends Analyzer {
  /** Luxir's default English chain, close to Standard + LowerCase + ASCIIFolding + KStem. */
  public static final String ENGLISH =
      "{\"tokenizer\":\"unicode_word\",\"filters\":[\"nfkc_cf\",\"fold\",{\"name\":\"kstem\",\"params\":{\"possessive\":false}}]}";

  private static final Cleaner CLEANER = Cleaner.create();
  private static final int BATCH = 64;

  private final String json;
  private final byte[] jsonBytes;
  private NativeAnalyzer normalizer; // multi-term normalization, guarded by this

  /** The {@link #ENGLISH} chain (usable as {@code <analyzer class="...LuxirAnalyzer"/>} in Solr). */
  public LuxirAnalyzer() {
    this(ENGLISH);
  }

  public LuxirAnalyzer(String json) {
    super(PER_FIELD_REUSE_STRATEGY);
    this.json = json;
    this.jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    new NativeAnalyzer(jsonBytes).close(); // validate now: a bad definition fails at configuration time
  }

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    return new TokenStreamComponents(new LuxirTokenizer(new NativeAnalyzer(jsonBytes)));
  }

  @Override
  public int getPositionIncrementGap(String fieldName) {
    return 100;
  }

  @Override
  protected TokenStream normalize(String fieldName, TokenStream in) {
    return new TokenFilter(in) {
      private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

      @Override
      public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) return false;
        String normalized = normalizeTerm(term.toString());
        term.setEmpty().append(normalized);
        return true;
      }
    };
  }

  /** Folds a multi-term query's text (prefix, wildcard) the way the chain folds tokens. */
  public synchronized String normalizeTerm(String text) {
    if (normalizer == null) normalizer = new NativeAnalyzer(jsonBytes);
    byte[] in = text.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[Math.max(64, in.length * 4 + 16)];
    int n;
    try {
      n = (int) LxlNative.NORMALIZE.invokeExact(normalizer.ptr, heap(in), in.length, heap(out), out.length);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    } finally {
      Reference.reachabilityFence(normalizer);
    }
    return new String(out, 0, check(n), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    super.close();
    synchronized (this) {
      if (normalizer != null) normalizer.close();
    }
  }

  @Override
  public String toString() {
    return "LuxirAnalyzer(" + json + ")";
  }

  /** One native analysis chain (not thread-safe), freed when unreachable. */
  static final class NativeAnalyzer implements AutoCloseable {
    final MemorySegment ptr;
    private final Cleaner.Cleanable cleanable;

    NativeAnalyzer(byte[] json) {
      MemorySegment p;
      try {
        p = check((MemorySegment) LxlNative.ANALYZER_NEW.invokeExact(heap(json), json.length));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      ptr = p;
      MemorySegment owned = p;
      cleanable = CLEANER.register(this, () -> {
        try {
          LxlNative.ANALYZER_FREE.invokeExact(owned);
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        }
      });
    }

    @Override
    public void close() {
      cleanable.clean();
    }
  }

  /** Pulls tokens from the native chain in batches. */
  static final class LuxirTokenizer extends Tokenizer {
    private final NativeAnalyzer chain;
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final byte[] terms = new byte[BATCH * 256];
    private final int[] meta = new int[BATCH * 4];
    private final char[] chars = new char[256];
    private int count, idx, termPos;
    private boolean done;
    private int[] byteToChar = new int[0];
    private int charLength;

    LuxirTokenizer(NativeAnalyzer chain) {
      this.chain = chain;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      StringBuilder sb = new StringBuilder();
      char[] buf = new char[4096];
      for (int n; (n = input.read(buf)) != -1; ) sb.append(buf, 0, n);
      String text = sb.toString();
      byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
      mapOffsets(text, utf8.length);
      try {
        check((int) LxlNative.ANALYZE_START.invokeExact(chain.ptr, heap(utf8), utf8.length));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      } finally {
        Reference.reachabilityFence(chain);
      }
      count = idx = termPos = 0;
      done = false;
    }

    // UTF-8 byte offset -> UTF-16 char offset, for OffsetAttribute.
    private void mapOffsets(String text, int byteLength) {
      if (byteToChar.length < byteLength + 1) byteToChar = new int[byteLength + 1];
      int b = 0;
      for (int c = 0; c < text.length(); c++) {
        int cp = text.codePointAt(c);
        int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        for (int i = 0; i < len; i++) byteToChar[b + i] = c;
        b += len;
        if (cp >= 0x10000) c++;
      }
      byteToChar[byteLength] = text.length();
      charLength = text.length();
    }

    @Override
    public boolean incrementToken() throws IOException {
      clearAttributes();
      if (idx == count) {
        if (done) return false;
        int n;
        try {
          n = (int) LxlNative.ANALYZE_NEXT.invokeExact(chain.ptr, heap(terms), heap(meta), BATCH);
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        } finally {
          Reference.reachabilityFence(chain);
        }
        count = check(n);
        idx = 0;
        termPos = 0;
        if (count < BATCH) done = true;
        if (count == 0) return false;
      }
      int len = meta[4 * idx];
      int nchars = UnicodeUtil.UTF8toUTF16(terms, termPos, len, chars);
      termAtt.copyBuffer(chars, 0, nchars);
      termPos += len;
      posIncAtt.setPositionIncrement(meta[4 * idx + 1]);
      offsetAtt.setOffset(correctOffset(byteToChar[meta[4 * idx + 2]]), correctOffset(byteToChar[meta[4 * idx + 3]]));
      idx++;
      return true;
    }

    @Override
    public void end() throws IOException {
      super.end();
      int finalOffset = correctOffset(charLength);
      offsetAtt.setOffset(finalOffset, finalOffset);
    }
  }
}
