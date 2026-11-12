// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.search;

import static org.luxir.lucene.LxlNative.check;
import static org.luxir.lucene.LxlNative.heap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.NativeCleaner;

/**
 * Wraps a query so that, on segments whose fields are stored in Luxir format, top-level bulk
 * scoring runs in Luxir's engine (block-max MaxScore/WAND, SIMD postings) and only the resulting
 * matches cross into Java, window by window, honouring the collector's minimum competitive score.
 *
 * <p>Everything else (pull scorers inside other queries, explain, matches, segments in another
 * format, unsupported query shapes) is delegated to the wrapped query's own Lucene weight, which
 * reads the same postings through the codec. Results are the same either way; scores use the
 * searcher's collection-wide statistics.
 */
public final class LuxirAcceleratedQuery extends Query {
  static final int WINDOW = 4096;

  private final Query in;

  public LuxirAcceleratedQuery(Query in) {
    this.in = Objects.requireNonNull(in);
  }

  @Override
  public Query rewrite(IndexSearcher searcher) throws IOException {
    Query r = in.rewrite(searcher);
    return r == in ? this : new LuxirAcceleratedQuery(r);
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
    LxlQuery.Encoded enc = LxlQuery.encode(in, searcher, boost, scoreMode.needsScores());
    if (enc == null) return in.createWeight(searcher, scoreMode, boost);
    return new NativeWeight(this, searcher, boost, enc, scoreMode);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    in.visit(visitor);
  }

  @Override
  public String toString(String field) {
    return "luxir(" + in.toString(field) + ")";
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && in.equals(((LuxirAcceleratedQuery) other).in);
  }

  @Override
  public int hashCode() {
    return classHash() * 31 + in.hashCode();
  }

  /**
   * Native bulk scoring and counting; everything else goes to the wrapped query's Lucene weight,
   * created only when first needed (for a term, creating it looks the term up in every segment).
   */
  private static final class NativeWeight extends Weight {
    final IndexSearcher searcher;
    final float boost;
    final LxlQuery.Encoded enc;
    final ScoreMode scoreMode;
    private Weight inner;

    NativeWeight(LuxirAcceleratedQuery query, IndexSearcher searcher, float boost, LxlQuery.Encoded enc,
        ScoreMode scoreMode) {
      super(query);
      this.searcher = searcher;
      this.boost = boost;
      this.enc = enc;
      this.scoreMode = scoreMode;
    }

    synchronized Weight inner() throws IOException {
      if (inner == null) inner = ((LuxirAcceleratedQuery) getQuery()).in.createWeight(searcher, scoreMode, boost);
      return inner;
    }

    @Override
    public ScorerSupplier scorerSupplier(LeafReaderContext ctx) throws IOException {
      Supplier<MemorySegment> reader = NativeLeaf.reader(ctx.reader(), enc);
      if (reader == null) return inner().scorerSupplier(ctx);
      LazySupplier lucene = new LazySupplier(this, ctx);
      return new ScorerSupplier() {
        @Override
        public Scorer get(long leadCost) throws IOException {
          ScorerSupplier s = lucene.get();
          return s == null ? new ConstantScoreScorer(0f, scoreMode, DocIdSetIterator.empty()) : s.get(leadCost);
        }

        @Override
        public BulkScorer bulkScorer() {
          return new NativeBulkScorer(reader, ctx, enc.bytes(), scoreMode, lucene);
        }

        @Override
        public long cost() {
          return lucene.cost();
        }

        @Override
        public void setTopLevelScoringClause() {
          lucene.topLevel = true;
        }
      };
    }

    @Override
    public int count(LeafReaderContext ctx) throws IOException {
      Supplier<MemorySegment> reader = NativeLeaf.reader(ctx.reader(), enc);
      if (reader == null) return inner().count(ctx);
      return (int) LuxirSearch.countLeaf(reader, ctx, enc.bytes());
    }

    @Override
    public Explanation explain(LeafReaderContext ctx, int doc) throws IOException {
      return inner().explain(ctx, doc);
    }

    @Override
    public Matches matches(LeafReaderContext ctx, int doc) throws IOException {
      return inner().matches(ctx, doc);
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      try {
        return inner().isCacheable(ctx);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** The wrapped weight's scorer supplier for one leaf, made when first asked for. */
  private static final class LazySupplier {
    private final NativeWeight weight;
    private final LeafReaderContext ctx;
    boolean topLevel;
    private ScorerSupplier supplier;
    private boolean made;

    LazySupplier(NativeWeight weight, LeafReaderContext ctx) {
      this.weight = weight;
      this.ctx = ctx;
    }

    ScorerSupplier get() throws IOException {
      if (!made) {
        supplier = weight.inner().scorerSupplier(ctx);
        if (supplier != null && topLevel) supplier.setTopLevelScoringClause();
        made = true;
      }
      return supplier;
    }

    long cost() {
      try {
        ScorerSupplier s = get();
        return s == null ? 0 : s.cost();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    BulkScorer bulkScorer() throws IOException {
      ScorerSupplier s = get();
      return s == null ? null : s.bulkScorer();
    }
  }

  /** Owns one lxl_exec, freed when exhausted or when the scorer becomes unreachable. */
  private static final class ExecHandle implements Runnable {
    MemorySegment ptr = MemorySegment.NULL;

    @Override
    public synchronized void run() {
      if (ptr.address() == 0) return;
      MemorySegment p = ptr;
      ptr = MemorySegment.NULL;
      try {
        LxlNative.EXEC_FREE.invokeExact(p);
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
    }
  }

  /**
   * A thread's buffers for one window of native matches, reused across searches (a grouping or
   * collapse request scores every segment several times); a collector searching again from inside
   * collect gets fresh ones.
   */
  private static final class Window {
    private static final ThreadLocal<Window> OF_THREAD = ThreadLocal.withInitial(Window::new);
    final int[] docs = new int[WINDOW];
    final float[] scores = new float[WINDOW];
    boolean inUse;

    static Window acquire() {
      Window w = OF_THREAD.get();
      if (w.inUse) w = new Window();
      w.inUse = true;
      return w;
    }
  }

  private static final class NativeScorable extends Scorable {
    float score;
    float minCompetitive = -Float.MAX_VALUE;

    @Override
    public float score() {
      return score;
    }

    @Override
    public void setMinCompetitiveScore(float minScore) {
      minCompetitive = minScore;
    }
  }

  private static final class NativeBulkScorer extends BulkScorer {
    private final Supplier<MemorySegment> reader;
    private final LeafReaderContext ctx;
    private final byte[] query;
    private final ScoreMode scoreMode;
    private final LazySupplier inner;
    private final ExecHandle exec = new ExecHandle();
    private BulkScorer lucene; // used instead when the collector can skip docs itself
    private final int[] n = new int[1];
    private Bits execLive;
    private boolean started;
    private int resume;

    NativeBulkScorer(Supplier<MemorySegment> reader, LeafReaderContext ctx, byte[] query, ScoreMode scoreMode,
        LazySupplier inner) {
      this.reader = reader;
      this.ctx = ctx;
      this.query = query;
      this.scoreMode = scoreMode;
      this.inner = inner;
    }

    private void start(Bits acceptDocs) {
      int maxDoc = ctx.reader().maxDoc();
      NativeLeaf.Words live = NativeLeaf.live(acceptDocs, maxDoc);
      execLive = acceptDocs;
      int flags = scoreMode.needsScores() ? LxlNative.EXEC_SCORES : 0;
      MemorySegment liveWords = live == null ? MemorySegment.NULL : live.words();
      NativeCleaner.register(this, exec); // only scorers that run hold an exec to free
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment q = arena.allocate(query.length);
        MemorySegment.copy(query, 0, q, ValueLayout.JAVA_BYTE, 0, query.length);
        exec.ptr = check((MemorySegment) LxlNative.EXEC_NEW.invokeExact(reader.get(), q, (long) query.length,
            flags, liveWords, live == null ? maxDoc : live.count()));
      } catch (Throwable t) {
        throw LxlNative.rethrow(t);
      }
      started = true;
    }

    @Override
    public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
      // A collector with a competitive iterator (e.g. a numeric sort) skips non-competitive docs
      // through Lucene's own iteration; feeding it every match natively would lose that.
      if (lucene == null && !started && collector.competitiveIterator() != null) {
        lucene = inner.bulkScorer();
        if (lucene == null) return DocIdSetIterator.NO_MORE_DOCS; // no matches here
      }
      if (lucene != null) return lucene.score(collector, acceptDocs, min, max);
      if (!started) start(acceptDocs);
      else if (acceptDocs != execLive) throw new IllegalStateException("acceptDocs changed between windows");
      if (resume == DocIdSetIterator.NO_MORE_DOCS) return resume;
      NativeScorable scorable = new NativeScorable();
      collector.setScorer(scorable);
      boolean prune = scoreMode == ScoreMode.TOP_SCORES;
      int maxDoc = ctx.reader().maxDoc();
      int cursor = Math.max(min, resume);
      int end = Math.min(max, maxDoc);
      Window w = Window.acquire();
      try {
        return resume = scoreWindows(collector, scorable, prune, maxDoc, cursor, end, w.docs, w.scores);
      } finally {
        w.inUse = false;
      }
    }

    private int scoreWindows(LeafCollector collector, NativeScorable scorable, boolean prune, int maxDoc, int cursor,
        int end, int[] docs, float[] scores) throws IOException {
      while (cursor < end) {
        int windowMax = (int) Math.min(end, (long) cursor + WINDOW);
        reader.get(); // throws if the segment was closed
        int rc;
        try {
          rc = (int) LxlNative.EXEC_WINDOW.invokeExact(exec.ptr, cursor, windowMax,
              prune ? scorable.minCompetitive : -Float.MAX_VALUE, heap(docs), heap(scores), WINDOW, heap(n));
        } catch (Throwable t) {
          throw LxlNative.rethrow(t);
        } finally {
          Reference.reachabilityFence(this); // NativeCleaner frees the exec when this is unreachable
        }
        check(rc);
        for (int i = 0; i < n[0]; i++) {
          scorable.score = scores[i];
          collector.collect(docs[i]);
        }
        if (rc == LxlNative.END) {
          // END from a window bounded below maxDoc only means the bound was reached.
          if (windowMax < maxDoc) {
            cursor = windowMax;
            continue;
          }
          exec.run();
          return DocIdSetIterator.NO_MORE_DOCS;
        }
        cursor = Math.max(rc, cursor + 1);
      }
      if (cursor >= maxDoc) {
        exec.run();
        return DocIdSetIterator.NO_MORE_DOCS;
      }
      return cursor;
    }

    @Override
    public long cost() {
      return inner.cost();
    }
  }
}
