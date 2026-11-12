// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.index;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.FilterMergePolicy;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergePolicy.MergeContext;
import org.apache.lucene.index.MergePolicy.MergeSpecification;
import org.apache.lucene.index.MergePolicy.OneMerge;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.codec.LuxirCodec;
import org.luxir.lucene.codec.LuxirTerms;

/**
 * Copies a Lucene index and rewrites it as one {@link LuxirCodec} segment.
 *
 * <p>The terms already stored in the source are kept. Source segments are not Luxir, so the merge
 * takes {@code LuxirFieldsConsumer}'s non-native path: {@code write} walks Lucene postings. Text is
 * not analyzed again.
 */
public final class LuceneToLuxir {
  private LuceneToLuxir() {}

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("usage: LuceneToLuxir <source-index> <destination-index>");
      System.exit(1);
      return;
    }
    try {
      convert(Path.of(args[0]), Path.of(args[1]));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  /**
   * Copies {@code source} to {@code destination}, rewrites the copy with {@link LuxirCodec}, and
   * prints, per indexed field, whether its postings are Luxir or stayed on the embedded Lucene104
   * postings format.
   */
  public static void convert(Path source, Path destination) throws IOException {
    Path src = source.toAbsolutePath().normalize();
    Path dst = destination.toAbsolutePath().normalize();
    checkDestination(src, dst);
    try (FSDirectory dir = FSDirectory.open(src)) {
      if (!DirectoryReader.indexExists(dir)) {
        throw new IllegalArgumentException("source is not a Lucene index: " + source);
      }
    }
    copyDirectory(src, dst);
    rewrite(dst);
    CheckIndex.Status status = check(dst);
    if (!status.clean) {
      throw new IllegalStateException(problems(status));
    }
    System.out.println("CheckIndex: clean (" + status.numSegments + (status.numSegments == 1 ? " segment)" : " segments)"));
    try (FSDirectory dir = FSDirectory.open(dst); DirectoryReader reader = DirectoryReader.open(dir)) {
      for (LeafReaderContext ctx : reader.leaves()) {
        String codec = segmentCodec(ctx.reader());
        if (!LuxirCodec.NAME.equals(codec)) {
          throw new IllegalStateException("segment is still " + codec + " after rewrite");
        }
      }
      for (String line : describeFields(reader)) System.out.println(line);
    }
  }

  /**
   * One line per indexed field. A field whose {@link Terms} are {@link LuxirTerms} is Luxir;
   * anything else this codec wrote stayed on the embedded Lucene104 postings format. Reasons come
   * from the field info and, for a long term, from the Luxir reader's overflow predicate.
   */
  public static List<String> describeFields(IndexReader reader) throws IOException {
    List<String> lines = new ArrayList<>();
    boolean several = reader.leaves().size() > 1;
    for (LeafReaderContext ctx : reader.leaves()) {
      CodecReader codec = codecReader(ctx.reader());
      FieldsProducer postings = codec.getPostingsReader();
      if (postings == null) continue;
      String segment = codec instanceof SegmentReader sr ? sr.getSegmentName() : "leaf" + ctx.ord;
      List<FieldInfo> infos = new ArrayList<>();
      for (FieldInfo fi : codec.getFieldInfos()) infos.add(fi);
      infos.sort(Comparator.comparing(fi -> fi.name));
      for (FieldInfo fi : infos) {
        if (fi.getIndexOptions() == IndexOptions.NONE) continue;
        Terms terms = postings.terms(fi.name);
        if (terms == null) continue;
        String line = describe(fi, terms);
        lines.add(several ? segment + " " + line : line);
      }
    }
    return lines;
  }

  private static String describe(FieldInfo fi, Terms terms) throws IOException {
    if (terms instanceof LuxirTerms luxir) {
      // An empty prefix matches every overflow term, which is a term longer than 255 bytes.
      if (luxir.test(new byte[0])) return fi.name + ": Luxir (term longer than 255 bytes)";
      return fi.name + ": Luxir";
    }
    List<String> why = new ArrayList<>();
    IndexOptions opts = fi.getIndexOptions();
    if (opts.compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) > 0) why.add("offsets");
    else if (opts != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) why.add("index options " + opts);
    if (fi.hasPayloads()) why.add("payloads");
    if (fi.omitsNorms()) why.add("missing norms");
    if (why.isEmpty() && hasLongTerm(terms)) why.add("term longer than 255 bytes");
    if (why.isEmpty()) return fi.name + ": Lucene104";
    return fi.name + ": Lucene104 (" + String.join(", ", why) + ")";
  }

  private static boolean hasLongTerm(Terms terms) throws IOException {
    TermsEnum te = terms.iterator();
    for (BytesRef term = te.next(); term != null; term = te.next()) {
      if (term.length > LxlNative.MAX_TERM) return true;
    }
    return false;
  }

  private static void checkDestination(Path src, Path dst) throws IOException {
    if (!Files.exists(src)) throw new IllegalArgumentException("source does not exist: " + src);
    if (!Files.isDirectory(src)) throw new IllegalArgumentException("source is not a directory: " + src);
    Path srcReal = src.toRealPath();
    Path dstReal = Files.exists(dst) ? dst.toRealPath() : realParent(dst);
    if (src.equals(dst) || srcReal.equals(dstReal)) {
      throw new IllegalArgumentException("refusing to rewrite in place: source and destination are the same path");
    }
    if (dstReal.startsWith(srcReal)) {
      throw new IllegalArgumentException("destination is inside the source index: " + dst);
    }
    if (Files.exists(dst)) throw new IllegalArgumentException("destination already exists: " + dst);
  }

  private static Path realParent(Path dst) throws IOException {
    Path parent = dst.getParent();
    if (parent != null && Files.exists(parent)) return parent.toRealPath().resolve(dst.getFileName()).normalize();
    return dst;
  }

  private static void copyDirectory(Path source, Path dest) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(dest.resolve(source.relativize(dir)));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, dest.resolve(source.relativize(file)));
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void rewrite(Path dest) throws IOException {
    // Merges do not analyze. The analyzer is only there because IndexWriter requires one.
    IndexWriterConfig cfg = new IndexWriterConfig(new MergeOnlyAnalyzer())
        .setOpenMode(OpenMode.APPEND)
        .setCodec(new LuxirCodec())
        .setMergeScheduler(new SerialMergeScheduler());
    // forceMerge(1) does not rewrite a segment that is already alone. A Lucene segment still has
    // to be merged once so this writer's codec stores it.
    cfg.setMergePolicy(new RewriteForeignSegments(cfg.getMergePolicy()));
    try (FSDirectory dir = FSDirectory.open(dest); IndexWriter writer = new IndexWriter(dir, cfg)) {
      writer.forceMerge(1);
      writer.commit();
    }
  }

  private static CheckIndex.Status check(Path dest) throws IOException {
    try (FSDirectory dir = FSDirectory.open(dest); CheckIndex checker = new CheckIndex(dir)) {
      checker.setLevel(CheckIndex.Level.MIN_LEVEL_FOR_INTEGRITY_CHECKS);
      return checker.checkIndex();
    }
  }

  private static String problems(CheckIndex.Status status) {
    StringBuilder sb = new StringBuilder("CheckIndex reported problems");
    if (status.segmentInfos != null) {
      for (CheckIndex.Status.SegmentInfoStatus seg : status.segmentInfos) {
        if (seg.error != null) sb.append('\n').append(seg.name).append(": ").append(seg.error.getMessage());
      }
    }
    return sb.toString();
  }

  private static CodecReader codecReader(LeafReader leaf) {
    LeafReader unwrapped = FilterLeafReader.unwrap(leaf);
    if (unwrapped instanceof CodecReader cr) return cr;
    throw new IllegalStateException("segment is not a codec reader: " + leaf);
  }

  private static String segmentCodec(LeafReader leaf) {
    CodecReader codec = codecReader(leaf);
    if (codec instanceof SegmentReader sr) return sr.getSegmentInfo().info.getCodec().getName();
    return codec.getMetaData().toString();
  }

  /** Never tokenizes: this rewrite keeps the terms already in the index. */
  private static final class MergeOnlyAnalyzer extends Analyzer {
    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
      throw new IllegalStateException("conversion does not reanalyze text (" + fieldName + ")");
    }
  }

  /**
   * {@code findForcedMerges} of the wrapped policy returns nothing when the index already has few
   * enough segments. Segments whose codec is not Luxir are merged anyway, including a single
   * segment, so the writer's codec rewrites them.
   */
  private static final class RewriteForeignSegments extends FilterMergePolicy {
    RewriteForeignSegments(MergePolicy in) {
      super(in);
    }

    @Override
    public MergeSpecification findForcedMerges(SegmentInfos segmentInfos, int maxSegmentCount,
        Map<SegmentCommitInfo, Boolean> segmentsToMerge, MergeContext mergeContext) throws IOException {
      MergeSpecification spec = in.findForcedMerges(segmentInfos, maxSegmentCount, segmentsToMerge, mergeContext);
      if (spec != null) return spec;
      List<SegmentCommitInfo> foreign = new ArrayList<>();
      for (SegmentCommitInfo sci : segmentInfos) {
        if (!LuxirCodec.NAME.equals(sci.info.getCodec().getName())) foreign.add(sci);
      }
      if (foreign.isEmpty()) return null;
      MergeSpecification rewrite = new MergeSpecification();
      rewrite.add(new OneMerge(List.copyOf(foreign)));
      return rewrite;
    }
  }
}
