// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.bench;

import static org.luxir.lucene.bench.SolrHttpBench.enc;
import static org.luxir.lucene.bench.SolrHttpBench.json;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DateFormatSymbols;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * luceneutil's datasets and tasks (the Lucene nightly benchmarks) against Solr and plain Luxir.
 *
 * <pre>
 * lu-encode linesFile numDocs batchesFile          the wikimedium docs as JSON batches, once
 * lu-index solr|luxir url core|collection batchesFile threads [updateParams, e.g. commitWithin=1000]
 * lu-search solrUrl luxirUrl|- luxirCollection tasksFile perCategory threads seconds core...
 * </pre>
 *
 * Documents carry luceneutil's fields: body and titleTokenized (text), title (sorted), lastMod
 * (epoch ms, long), month (English month name), dayOfYear and timesecnum (ints), randomLabel, and
 * rnd100 (0-99 from the doc number: the stand-in for luceneutil's random "+filter=N%").
 */
final class LuceneUtilBench {
  private static final String[] MONTHS = DateFormatSymbols.getInstance(Locale.ROOT).getMonths();

  // ---- documents -------------------------------------------------------------------------

  static void encode(Path lines, int numDocs, Path out, int batchSize) throws Exception {
    int n = 0, batches = 0;
    try (SolrHttpBench.Lines r = new SolrHttpBench.Lines(lines);
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
      StringBuilder sb = new StringBuilder("[");
      int inBatch = 0;
      String line;
      while (n < numDocs && (line = r.readLine()) != null) {
        if (line.startsWith("FIELDS_HEADER_INDICATOR")) continue;
        String[] p = line.split("\t", 5);
        if (p.length < 4) continue;
        if (inBatch > 0) sb.append(',');
        doc(sb, n, p);
        n++;
        if (++inBatch == batchSize) {
          write(o, sb.append(']'));
          batches++;
          sb.setLength(0);
          sb.append('[');
          inBatch = 0;
        }
      }
      if (inBatch > 0) {
        write(o, sb.append(']'));
        batches++;
      }
    }
    System.out.printf(Locale.ROOT, "encoded %d docs in %d batches of %d to %s (%d MB)%n", n, batches, batchSize, out,
        Files.size(out) >> 20);
  }

  private static void write(DataOutputStream o, StringBuilder sb) throws IOException {
    byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
    o.writeInt(b.length);
    o.write(b);
  }

  static void doc(StringBuilder sb, int n, String[] p) {
    LocalDateTime dt = LocalDateTime.parse(p[1], BenchTasks.DATE);
    sb.append("{\"id\":\"w").append(n).append("\",\"title\":");
    json(sb, p[0]);
    sb.append(",\"titleTokenized\":");
    json(sb, p[0]);
    sb.append(",\"body\":");
    json(sb, p[2]);
    sb.append(",\"lastMod\":").append(dt.toInstant(ZoneOffset.UTC).toEpochMilli());
    sb.append(",\"month\":\"").append(MONTHS[dt.getMonthValue() - 1]).append('"');
    sb.append(",\"dayOfYear\":").append(dt.getDayOfYear());
    sb.append(",\"timesecnum\":").append(dt.getHour() * 3600 + dt.getMinute() * 60 + dt.getSecond());
    sb.append(",\"randomLabel\":");
    json(sb, label(p[3].strip()));
    sb.append(",\"rnd100\":").append(Math.floorMod(SolrHttpBench.mix(n), 100));
    sb.append('}');
  }

  /**
   * The label, cut to 255 UTF-8 bytes at a character boundary: a few of the file's labels are
   * longer, and Luxir indexes value strings of at most 255 bytes (every system gets the same data).
   */
  static String label(String s) {
    if (s.length() <= 63) return s;
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length <= 255) return s;
    int end = 255;
    while ((b[end] & 0xC0) == 0x80) end--; // b[end] starts the first character that does not fit
    return new String(b, 0, end, StandardCharsets.UTF_8);
  }

  // ---- indexing --------------------------------------------------------------------------

  /** The batches file, mapped: offsets and lengths of each JSON array. */
  private record Batches(List<MappedByteBuffer> maps, long[] offsets, int[] lengths) {
    byte[] get(int i, byte[] prefix, byte[] suffix) {
      long off = offsets[i];
      int len = lengths[i];
      byte[] b = new byte[prefix.length + len + suffix.length];
      System.arraycopy(prefix, 0, b, 0, prefix.length);
      MappedByteBuffer m = maps.get((int) (off >>> 30));
      int at = (int) (off & ((1 << 30) - 1));
      if (at + len <= m.capacity()) {
        m.duplicate().position(at).get(b, prefix.length, len);
      } else { // spans two maps
        int first = m.capacity() - at;
        m.duplicate().position(at).get(b, prefix.length, first);
        maps.get((int) (off >>> 30) + 1).duplicate().position(0).get(b, prefix.length + first, len - first);
      }
      System.arraycopy(suffix, 0, b, prefix.length + len, suffix.length);
      return b;
    }
  }

  private static Batches open(Path file) throws IOException {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = ch.size();
      List<MappedByteBuffer> maps = new ArrayList<>();
      for (long pos = 0; pos < size; pos += 1L << 30) {
        maps.add(ch.map(FileChannel.MapMode.READ_ONLY, pos, Math.min(1L << 30, size - pos)));
      }
      List<Long> offs = new ArrayList<>();
      List<Integer> lens = new ArrayList<>();
      ByteBuffer hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
      for (long pos = 0; pos < size; ) {
        hdr.clear();
        ch.read(hdr, pos);
        int len = hdr.getInt(0);
        offs.add(pos + 4);
        lens.add(len);
        pos += 4 + len;
      }
      return new Batches(maps, offs.stream().mapToLong(Long::longValue).toArray(),
          lens.stream().mapToInt(Integer::intValue).toArray());
    }
  }

  static void index(String target, String url, String name, Path file, int threads, String updateParams)
      throws Exception {
    Batches b = open(file);
    boolean luxir = target.equals("luxir");
    String update = luxir ? url + "/collections/" + name + "/_update" : url + "/" + name + "/update";
    String addUrl = updateParams.isEmpty() ? update : update + "?" + updateParams;
    byte[] pre = (luxir ? "{\"docs\":" : "").getBytes(StandardCharsets.UTF_8);
    byte[] post = (luxir ? "}" : "").getBytes(StandardCharsets.UTF_8);
    if (!luxir) SolrHttpBench.postUrl(update + "?commit=true", "[]".getBytes(StandardCharsets.UTF_8));
    // touch every page first, so the timed run reads from memory, not the disk
    long sum = 0;
    for (int i = 0; i < b.offsets().length; i++) sum += b.get(i, new byte[0], new byte[0]).length;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger next = new AtomicInteger();
    long t0 = System.nanoTime();
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      fs.add(pool.submit(() -> {
        for (int i = next.getAndIncrement(); i < b.offsets().length; i = next.getAndIncrement()) {
          SolrHttpBench.postUrl(addUrl, b.get(i, pre, post));
        }
        return null;
      }));
    }
    try {
      for (Future<?> f : fs) f.get();
    } finally {
      pool.shutdownNow();
    }
    double add = (System.nanoTime() - t0) / 1e9;
    long t1 = System.nanoTime();
    if (luxir) {
      SolrHttpBench.postUrl(update, "{\"commit\":{}}".getBytes(StandardCharsets.UTF_8));
    } else {
      SolrHttpBench.postUrl(update + "?commit=true&waitSearcher=true", "[]".getBytes(StandardCharsets.UTF_8));
    }
    double commit = (System.nanoTime() - t1) / 1e9;
    long docs = luxir ? -1 : SolrHttpBench.numFound(SolrHttpBench.get(url, name, "q=*:*&rows=0"));
    System.out.printf(Locale.ROOT, "INDEX %s/%s: %.1fs add + %.1fs commit = %.0f docs/s (%d batches, %d MB)%s%n",
        target, name, add, commit, b.offsets().length * 1000.0 / (add + commit), b.offsets().length, sum >> 20,
        docs >= 0 ? "; numFound=" + docs : "");
  }

  // ---- tasks -----------------------------------------------------------------------------

  // luceneutil facet dimensions and sort types, as our fields
  private static final Map<String, String> FACET_DIMS =
      Map.of("Month", "month", "DayOfYear", "dayOfYear", "RandomLabel", "randomLabel");
  private static final Map<String, String> SORTS = Map.of(
      "monthdvsort", "month", "dayofyeardvsort", "dayOfYear", "titledvsort", "title", "titlebdvsort", "title",
      "lastmodndvsort", "lastMod");

  /**
   * The tasks of {@code file} as requests, at most {@code perCategory} distinct ones per
   * category. Tasks with no Solr equivalent (spans, intervals, respell, grouping, vectors,
   * taxonomy-only browsing, date hierarchies, combined fields) are skipped; a task without a plain
   * Luxir equivalent runs on Solr only.
   */
  static List<WideSearchBench.WReq> tasks(Path file, int perCategory, Set<String> skipped) throws IOException {
    Map<String, List<WideSearchBench.WReq>> byCat = new LinkedHashMap<>();
    Map<String, Set<String>> seen = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String cat = line.substring(0, colon).strip();
      String text = line.substring(colon + 1);
      int hash = text.indexOf(" #");
      if (hash >= 0) text = text.substring(0, hash);
      text = text.strip();
      if (skipped.contains(cat)) continue;
      List<WideSearchBench.WReq> list = byCat.computeIfAbsent(cat, k -> new ArrayList<>());
      if (list.size() >= perCategory || !seen.computeIfAbsent(cat, k -> new java.util.HashSet<>()).add(text)) continue;
      WideSearchBench.WReq r = convert(cat, text);
      if (r == null) {
        skipped.add(cat);
        byCat.remove(cat);
        continue;
      }
      list.add(r);
    }
    List<WideSearchBench.WReq> out = new ArrayList<>();
    for (List<WideSearchBench.WReq> l : byCat.values()) out.addAll(l);
    return out;
  }

  /** One task as Solr parameters and a plain Luxir request; null if it has no Solr equivalent. */
  static WideSearchBench.WReq convert(String cat, String text) {
    if (cat.equals("range") || cat.equals("Respell") || cat.contains("Taxo") && cat.startsWith("Browse")
        || cat.contains("BDV") || cat.startsWith("Combined") || cat.contains("Vector") || cat.contains("Group")
        || cat.contains("SearchAfter") || cat.contains("Skipper")) {
      return null;
    }
    StringBuilder solr = new StringBuilder();
    List<String> luxirExtra = new ArrayList<>();
    List<String> luxFilters = new ArrayList<>();
    boolean luxirOk = true;
    // count(...): the number of hits only
    boolean count = false;
    if (text.startsWith("count(") && text.endsWith(")")) {
      count = true;
      text = text.substring(6, text.length() - 1).strip();
    }
    if (count) {
      solr.append("rows=0&");
      luxirExtra.add("\"limit\":0");
    }
    // +filter=N%: luceneutil's random N% of the docs; here docs whose rnd100 is below N
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*\\+filter=([0-9.]+)%").matcher(text);
    if (m.find()) {
      int pct = (int) Math.round(Double.parseDouble(m.group(1)));
      String f = "rnd100:[0 TO " + (pct - 1) + "]";
      solr.append("fq=").append(enc(f)).append('&');
      luxFilters.add(f);
      text = (text.substring(0, m.start()) + text.substring(m.end())).strip();
    }
    m = java.util.regex.Pattern.compile("\\s*\\+dvRangeFilter=([A-Za-z_]+),(-?\\d+),(-?\\d+)").matcher(text);
    if (m.find()) {
      String f = field(m.group(1)) + ":[" + m.group(2) + " TO " + m.group(3) + "]";
      solr.append("fq=").append(enc(f)).append('&');
      luxFilters.add(f);
      text = (text.substring(0, m.start()) + text.substring(m.end())).strip();
    }
    String mustNot = null;
    m = java.util.regex.Pattern.compile("\\s*\\+iodvRangeNot=([A-Za-z_]+),(-?\\d+),(-?\\d+)").matcher(text);
    if (m.find()) {
      mustNot = field(m.group(1)) + ":[" + m.group(2) + " TO " + m.group(3) + "]";
      text = (text.substring(0, m.start()) + text.substring(m.end())).strip();
    }
    int msm = 0;
    m = java.util.regex.Pattern.compile("\\s*\\+minShouldMatch=(\\d+)").matcher(text);
    if (m.find()) {
      msm = Integer.parseInt(m.group(1));
      text = (text.substring(0, m.start()) + text.substring(m.end())).strip();
    }
    if (text.contains("+constantScore") || text.contains("+drillDown") || text.contains("hilite//")) return null;
    String dismax = null;
    int dm = text.indexOf("+dismaxFields=");
    if (dm >= 0) {
      dismax = text.substring(dm + "+dismaxFields=".length()).strip().replace(',', ' ');
      text = text.substring(0, dm).strip();
    }
    // +facets:TYPE:DIM: a field facet (top 10)
    m = java.util.regex.Pattern.compile("\\s*\\+facets:(taxonomy|sortedset):([A-Za-z]+)").matcher(text);
    while (m.find()) {
      String dim = FACET_DIMS.get(m.group(2));
      if (dim == null) return null;
      solr.append("facet=true&facet.limit=10&facet.field=").append(dim).append('&');
      luxirExtra.add("\"ops\":{\"" + dim + "\":{\"field_facet\":{\"field\":\"" + dim + "\",\"limit\":10}}}");
      text = (text.substring(0, m.start()) + text.substring(m.end())).strip();
      m = java.util.regex.Pattern.compile("\\s*\\+facets:(taxonomy|sortedset):([A-Za-z]+)").matcher(text);
    }
    if (text.contains("+facets:")) return null;

    // typed tasks: type//rest
    String q, lux;
    int slashes = text.indexOf("//");
    String type = slashes > 0 && !text.substring(0, slashes).contains(" ") ? text.substring(0, slashes) : null;
    String rest = type == null ? text : text.substring(slashes + 2).strip();
    if (type != null && SORTS.containsKey(type)) {
      solr.append("sort=").append(enc(SORTS.get(type) + " asc")).append('&');
      luxirExtra.add("\"sort\":\"" + SORTS.get(type) + " asc\"");
      type = null;
    }
    if (type == null) {
      q = rest;
      lux = luxirExpr(rest);
      if (lux == null) luxirOk = false;
    } else {
      String[] w = rest.split("\\s+");
      switch (type) {
        case "nrq" -> {
          q = w[0] + ":[" + w[1] + " TO " + w[2] + "]";
          lux = q;
        }
        case "dvrange" -> {
          q = field(w[0]) + ":[" + w[1] + " TO " + w[2] + "]";
          lux = q;
        }
        case "intSet" -> {
          StringBuilder vals = new StringBuilder();
          for (int i = 1; i < w.length; i++) vals.append(i > 1 ? "," : "").append(w[i]);
          q = "{!terms f=" + w[0] + "}" + vals;
          lux = w[0] + ":=(" + vals.toString().replace(",", ", ") + ")";
        }
        default -> {
          return null; // spans, intervals, grouping, vectors...
        }
      }
    }
    if (mustNot != null) {
      q = q + " -" + mustNot;
      if (lux != null) lux = "(" + lux + ") AND NOT " + mustNot;
    }
    if (msm > 0) {
      q = "{!edismax qf=body mm=" + msm + "}" + q;
      luxirOk = false; // min_match on an expression is not equivalent here
    } else if (dismax != null) {
      q = "{!dismax qf='" + dismax + "' tie=0}" + q;
      luxirOk = false;
    }
    solr.insert(0, "q=" + enc(q) + "&df=body&");
    solr.setLength(solr.length() - 1);
    String luxBody = null;
    if (luxirOk && lux != null) {
      StringBuilder b = new StringBuilder("{\"query\":");
      json(b, lux);
      if (!luxFilters.isEmpty()) {
        b.append(",\"filter\":[");
        for (int i = 0; i < luxFilters.size(); i++) {
          if (i > 0) b.append(',');
          json(b, luxFilters.get(i));
        }
        b.append(']');
      }
      for (String e : luxirExtra) b.append(',').append(e);
      luxBody = b.append('}').toString();
    }
    return new WideSearchBench.WReq(cat, solr.toString(), luxBody);
  }

  private static String field(String luceneutilField) {
    return luceneutilField.replace("_skipper", "");
  }

  /**
   * luceneutil's query text (Lucene syntax over the default field body) in Luxir's expression
   * language, which has no default field; null where there is no direct equivalent.
   */
  static String luxirExpr(String text) {
    if (text.equals("*:*")) return "*:*";
    StringBuilder out = new StringBuilder();
    int i = 0, n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == ' ') {
        out.append(c);
        i++;
        continue;
      }
      String sign = "";
      if (c == '+' || c == '-') {
        sign = String.valueOf(c);
        i++;
        if (i >= n) return null;
        c = text.charAt(i);
      }
      if (c == '"') {
        int end = text.indexOf('"', i + 1);
        if (end < 0) return null;
        int j = end + 1;
        while (j < n && text.charAt(j) != ' ') j++;
        out.append(sign).append("body:").append(text, i, j);
        i = j;
      } else if (c == '(') {
        int end = text.indexOf(')', i);
        if (end < 0) return null;
        out.append(sign).append("body:").append(text, i, end + 1);
        i = end + 1;
      } else {
        int j = i;
        while (j < n && text.charAt(j) != ' ') j++;
        String tok = text.substring(i, j);
        i = j;
        if (tok.contains(":")) {
          out.append(sign).append(tok);
        } else if (tok.matches(".*~\\d+")) {
          int t = tok.lastIndexOf('~');
          out.append(sign).append("fuzzy(").append(tok, 0, t).append(", field=body, max_edits=")
              .append(tok.substring(t + 1)).append(", prefix_length=0)");
        } else if (tok.endsWith("*") && tok.indexOf('*') == tok.length() - 1 && !tok.contains("?")) {
          out.append(sign).append("body:").append(tok);
        } else if (tok.contains("*") || tok.contains("?")) {
          out.append(sign).append("wildcard(").append(tok).append(", field=body)");
        } else {
          out.append(sign).append("body:").append(tok);
        }
      }
    }
    return out.toString();
  }

  // ---- JSON facets -----------------------------------------------------------------------

  /** Base queries each JSON facet task runs under: all docs, terms, a conjunction, a 10% filter. */
  private static final String[] JSON_FACET_BASES = {
    "q=" + enc("*:*"), "q=ref&df=body", "q=team&df=body", "q=" + enc("+class +june") + "&df=body",
    "q=" + enc("*:*") + "&fq=" + enc("rnd100:[0 TO 9]")
  };

  /** JSON facet tasks over luceneutil's fields (Solr only: the JSON Facet API is Solr's). */
  static List<WideSearchBench.WReq> jsonFacetTasks() {
    long y2001 = 978307200000L, year = 31536000000L;
    String[][] tasks = {
      {"JTermsMonth", "{m:{type:terms,field:month,limit:12}}"},
      {"JTermsLabel", "{l:{type:terms,field:randomLabel,limit:10}}"},
      {"JTermsStats", "{m:{type:terms,field:month,limit:12,facet:{s:'sum(timesecnum)',a:'avg(dayOfYear)',"
          + "mn:'min(lastMod)',mx:'max(lastMod)'}}}"},
      {"JTermsSortStat", "{m:{type:terms,field:month,limit:5,sort:'a desc',facet:{a:'avg(timesecnum)'}}}"},
      {"JNumTerms", "{d:{type:terms,field:dayOfYear,limit:10}}"},
      {"JRange", "{r:{type:range,field:dayOfYear,start:1,end:367,gap:30,other:all}}"},
      {"JRangeStats", "{r:{type:range,field:dayOfYear,start:1,end:367,gap:30,facet:{s:'sum(timesecnum)',"
          + "x:'max(lastMod)'}}}"},
      {"JRangeLong", "{r:{type:range,field:lastMod,start:" + y2001 + ",end:" + (y2001 + 12 * year) + ",gap:"
          + year + "}}"},
      {"JStats", "{s:'sum(timesecnum)',a:'avg(dayOfYear)',mn:'min(lastMod)',mx:'max(lastMod)',"
          + "sd:'stddev(timesecnum)'}"},
      {"JQueryFacets", "{a:{type:query,q:'dayOfYear:[1 TO 90]',facet:{s:'sum(timesecnum)'}},"
          + "b:{type:query,q:'dayOfYear:[91 TO 366]',facet:{s:'sum(timesecnum)'}}}"},
      {"JNested", "{m:{type:terms,field:month,limit:3,facet:{l:{type:terms,field:randomLabel,limit:3}}}}"},
      {"JTermsPrefix", "{l:{type:terms,field:randomLabel,prefix:a,limit:10,facet:{a:'avg(timesecnum)'}}}"},
    };
    List<WideSearchBench.WReq> out = new ArrayList<>();
    for (String[] t : tasks) {
      for (String base : JSON_FACET_BASES) {
        out.add(new WideSearchBench.WReq(t[0], base + "&json.facet=" + enc(t[1]), null));
      }
    }
    return out;
  }

  static void jsonFacets(String solr, int threads, int seconds, String[] cores) throws Exception {
    WideSearchBench.run(solr, "-", "-", jsonFacetTasks(), threads, seconds, cores);
  }

  static void search(String solr, String luxirUrl, String collection, Path tasks, int perCategory, int threads,
      int seconds, String[] cores) throws Exception {
    Set<String> skipped = new java.util.TreeSet<>();
    List<WideSearchBench.WReq> reqs = tasks(tasks, perCategory, skipped);
    System.out.println("tasks " + tasks.getFileName() + ": skipped categories (no Solr equivalent): " + skipped);
    WideSearchBench.run(solr, luxirUrl, collection, reqs, threads, seconds, cores);
  }
}
