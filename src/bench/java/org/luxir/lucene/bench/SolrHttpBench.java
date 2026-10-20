// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.bench;

import java.io.BufferedReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

/**
 * End-to-end benchmark of two Solr cores over HTTP: one stock (Lucene), one served by Luxir.
 * Both use the same schema and the same request; only the core name differs.
 *
 * <pre>
 * index  solrUrl core docsFile numDocs threads batchSize [updateParams|-] [fields, e.g. id,ts,month]
 * luxir-index luxirUrl collection docsFile numDocs threads batchSize [fields]
 * search solrUrl luceneIndexDir threads seconds core...
 * </pre>
 */
public final class SolrHttpBench {
  static final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "index" ->
          index(args[1], args[2], Path.of(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
              Integer.parseInt(args[6]), args.length > 7 && !args[7].equals("-") ? args[7] : "",
              args.length > 8 ? java.util.Set.of(args[8].split(",")) : null);
      case "luxir-index" ->
          // Plain Luxir (its own server), the same documents and request shape:
          // luxir-index luxirUrl collection docsFile numDocs threads batchSize
          luxirIndex(args[1], args[2], Path.of(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
              Integer.parseInt(args[6]), args.length > 7 ? java.util.Set.of(args[7].split(",")) : null);
      case "search" ->
          search(args[1], Path.of(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]),
              Arrays.copyOfRange(args, 5, args.length));
      case "wsearch" ->
          WideSearchBench.run(args[1], args[2], args[3], Path.of(args[4]), Integer.parseInt(args[5]),
              Integer.parseInt(args[6]), Arrays.copyOfRange(args, 7, args.length));
      case "lu-encode" -> LuceneUtilBench.encode(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]),
          args.length > 4 ? Integer.parseInt(args[4]) : 1000);
      case "lu-tasks" -> {
        java.util.Set<String> skipped = new java.util.TreeSet<>();
        for (WideSearchBench.WReq r : LuceneUtilBench.tasks(Path.of(args[1]), Integer.parseInt(args[2]), skipped)) {
          System.out.println(r.category() + "\n   solr: " + java.net.URLDecoder.decode(r.solr(), StandardCharsets.UTF_8)
              + "\n   luxir: " + r.luxir());
        }
        System.out.println("skipped: " + skipped);
      }
      case "lu-jfacet" -> LuceneUtilBench.jsonFacets(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
          Arrays.copyOfRange(args, 4, args.length));
      case "lu-index" -> LuceneUtilBench.index(args[1], args[2], args[3], Path.of(args[4]), Integer.parseInt(args[5]),
          args.length > 6 ? args[6] : "");
      case "gn-search" -> WideSearchBench.run(args[1], "-", "-", WideSearchBench.taskFile(Path.of(args[2])),
          Integer.parseInt(args[3]), Integer.parseInt(args[4]), Arrays.copyOfRange(args, 5, args.length));
      case "lu-search" -> LuceneUtilBench.search(args[1], args[2], args[3], Path.of(args[4]), Integer.parseInt(args[5]),
          Integer.parseInt(args[6]), Integer.parseInt(args[7]), Arrays.copyOfRange(args, 8, args.length));
      case "ceiling" -> {
        // Fixed cost of the HTTP + Solr request path: a query that matches nothing.
        List<Req> none = List.of(new Req("None", "q=" + enc("id:does-not-exist") + "&rows=10&fl=id"));
        int threads = Integer.parseInt(args[2]), seconds = Integer.parseInt(args[3]);
        for (String core : Arrays.copyOfRange(args, 4, args.length)) {
          double lat = medianLatency(none, r -> get(args[1], core, r.params()));
          double qps = throughput(none, threads, seconds, r -> get(args[1], core, r.params()));
          System.out.printf(Locale.ROOT, "%-12s match-nothing: %.3f ms median, %.0f QPS with %d threads%n", core, lat, qps, threads);
        }
      }
      default -> throw new IllegalArgumentException(args[0]);
    }
  }

  // ---- indexing -------------------------------------------------------------------------

  static void index(String solr, String core, Path docs, int numDocs, int threads, int batchSize, String updateParams,
      java.util.Set<String> only) throws Exception {
    // Pre-encode every request body so the client costs nothing during the timed phase.
    List<byte[]> batches = new ArrayList<>();
    long textBytes = 0;
    int n = 0;
    try (Lines r = new Lines(docs)) {
      StringBuilder sb = new StringBuilder("[");
      int inBatch = 0;
      String line;
      while (n < numDocs && (line = r.readLine()) != null) {
        String[] p = line.split("\t", 3);
        if (p.length != 3) continue;
        LocalDateTime dt = LocalDateTime.parse(p[1], BenchTasks.DATE);
        String month = String.format(Locale.ROOT, "%04d-%02d", dt.getYear(), dt.getMonthValue());
        if (inBatch > 0) sb.append(',');
        doc(sb, n, p, dt, month, only);
        textBytes += p[0].length() + p[2].length();
        n++;
        if (++inBatch == batchSize) {
          batches.add(sb.append(']').toString().getBytes(StandardCharsets.UTF_8));
          sb.setLength(0);
          sb.append('[');
          inBatch = 0;
        }
      }
      if (inBatch > 0) batches.add(sb.append(']').toString().getBytes(StandardCharsets.UTF_8));
    }
    System.out.printf(Locale.ROOT, "core %s: %d docs (%.0f MB text) in %d requests of %d, %d client threads%n",
        core, n, textBytes / 1e6, batches.size(), batchSize, threads);

    post(solr, core, "/update?commit=true", "[]".getBytes(StandardCharsets.UTF_8)); // core warm, empty commit
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger next = new AtomicInteger();
    long t0 = System.nanoTime();
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      fs.add(pool.submit(() -> {
        for (int i = next.getAndIncrement(); i < batches.size(); i = next.getAndIncrement()) {
          post(solr, core, updateParams.isEmpty() ? "/update" : "/update?" + updateParams, batches.get(i));
        }
        return null;
      }));
    }
    for (Future<?> f : fs) f.get();
    pool.shutdown();
    double add = (System.nanoTime() - t0) / 1e9;
    long t1 = System.nanoTime();
    post(solr, core, "/update?commit=true&waitSearcher=true", "[]".getBytes(StandardCharsets.UTF_8));
    double commit = (System.nanoTime() - t1) / 1e9;
    long found = numFound(get(solr, core, "q=*:*&rows=0"));
    System.out.printf(Locale.ROOT, "INDEX %s: %.1fs add + %.1fs commit = %.0f docs/s; numFound=%d%n",
        core, add, commit, n / (add + commit), found);
  }

  static void luxirIndex(String luxir, String collection, Path docs, int numDocs, int threads, int batchSize,
      java.util.Set<String> only) throws Exception {
    List<byte[]> batches = new ArrayList<>();
    int n = 0;
    try (Lines r = new Lines(docs)) {
      StringBuilder sb = new StringBuilder("{\"docs\":[");
      int inBatch = 0;
      String line;
      while (n < numDocs && (line = r.readLine()) != null) {
        String[] p = line.split("\t", 3);
        if (p.length != 3) continue;
        LocalDateTime dt = LocalDateTime.parse(p[1], BenchTasks.DATE);
        String month = String.format(Locale.ROOT, "%04d-%02d", dt.getYear(), dt.getMonthValue());
        if (inBatch > 0) sb.append(',');
        doc(sb, n, p, dt, month, only);
        n++;
        if (++inBatch == batchSize) {
          batches.add(sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
          sb.setLength(0);
          sb.append("{\"docs\":[");
          inBatch = 0;
        }
      }
      if (inBatch > 0) batches.add(sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }
    System.out.printf(Locale.ROOT, "luxir %s: %d docs in %d requests of %d, %d client threads%n",
        collection, n, batches.size(), batchSize, threads);
    String update = luxir + "/collections/" + collection + "/_update";
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger next = new AtomicInteger();
    long t0 = System.nanoTime();
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      fs.add(pool.submit(() -> {
        for (int i = next.getAndIncrement(); i < batches.size(); i = next.getAndIncrement()) {
          postUrl(update, batches.get(i));
        }
        return null;
      }));
    }
    for (Future<?> f : fs) f.get();
    pool.shutdown();
    double add = (System.nanoTime() - t0) / 1e9;
    long t1 = System.nanoTime();
    postUrl(update, "{\"commit\":{}}".getBytes(StandardCharsets.UTF_8));
    double commit = (System.nanoTime() - t1) / 1e9;
    System.out.printf(Locale.ROOT, "INDEX luxir/%s: %.1fs add + %.1fs commit = %.0f docs/s%n",
        collection, add, commit, n / (add + commit));
  }

  static String postUrl(String url, byte[] body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<String> rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (rsp.statusCode() != 200) throw new IllegalStateException(url + " -> " + rsp.statusCode() + " " + rsp.body());
    return rsp.body();
  }

  /** One benchmark document as JSON; {@code only} restricts the fields sent (id always). */
  static void doc(StringBuilder sb, int n, String[] p, LocalDateTime dt, String month, java.util.Set<String> only) {
    if (only != null && only.contains("wide")) {
      wideDoc(sb, n, p, dt, month);
      return;
    }
    sb.append("{\"id\":\"d").append(n).append('"');
    if (only == null || only.contains("title")) {
      sb.append(",\"title\":");
      json(sb, p[0]);
    }
    if (only == null || only.contains("body")) {
      sb.append(",\"body\":");
      json(sb, p[2]);
    }
    if (only == null || only.contains("ts")) sb.append(",\"ts\":\"").append(dt.toInstant(ZoneOffset.UTC)).append('"');
    if (only == null || only.contains("month")) sb.append(",\"month\":\"").append(month).append('"');
    sb.append('}');
  }

  /**
   * The "wide" document: three text fields (title and summary stored), a date, three strings
   * (one multi-valued), five ints, a long, a float and a double. Everything is derived from the
   * article, so every system receives identical values.
   */
  static void wideDoc(StringBuilder sb, int n, String[] p, LocalDateTime dt, String month) {
    String body = p[2];
    sb.append("{\"id\":\"d").append(n).append("\",\"title\":");
    json(sb, p[0]);
    sb.append(",\"body\":");
    json(sb, body);
    int cut = Math.min(body.length(), 400);
    int sp = body.lastIndexOf(' ', cut);
    if (cut == 400 && sp > 200) cut = sp;
    sb.append(",\"summary\":");
    json(sb, body.substring(0, cut));
    sb.append(",\"ts\":\"").append(dt.toInstant(ZoneOffset.UTC)).append('"');
    sb.append(",\"month\":\"").append(month).append('"');
    String kind = body.startsWith("#REDIRECT") || body.startsWith("#redirect") ? "redirect"
        : p[0].contains("(disambiguation)") ? "disambiguation"
        : p[0].startsWith("List of") ? "list" : "article";
    sb.append(",\"kind\":\"").append(kind).append('"');
    sb.append(",\"cats\":[");
    int cats = 0;
    for (int i = body.indexOf("[[Category:"); i >= 0 && cats < 20; i = body.indexOf("[[Category:", i + 11)) {
      int end = body.indexOf("]]", i + 11);
      if (end < 0) break;
      String c = body.substring(i + 11, end);
      int bar = c.indexOf('|');
      if (bar >= 0) c = c.substring(0, bar);
      c = c.strip();
      if (c.isEmpty() || c.length() > 200) continue;
      if (cats++ > 0) sb.append(',');
      json(sb, c);
    }
    sb.append(']');
    int words = 0, links = 0, refs = 0;
    boolean inWord = false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      boolean w = Character.isLetterOrDigit(c);
      if (w && !inWord) words++;
      inWord = w;
      if (c == '[' && i + 1 < body.length() && body.charAt(i + 1) == '[') links++;
      else if (c == '<' && body.startsWith("<ref", i)) refs++;
    }
    long h = mix(p[0].hashCode() * 0x9E3779B97F4A7C15L + n);
    sb.append(",\"year\":").append(dt.getYear());
    sb.append(",\"body_len\":").append(body.length());
    sb.append(",\"words\":").append(words);
    sb.append(",\"links\":").append(links);
    sb.append(",\"refs\":").append(refs);
    sb.append(",\"rev\":").append(400_000_000_000L + (h >>> 24));
    sb.append(",\"popularity\":").append((float) ((h & 0xFFFFFF) / (double) 0x1000000 * 1000));
    sb.append(",\"quality\":").append(Math.log1p(links) + Math.log1p(refs) * 0.5 + (h >>> 40) / 1e9);
    sb.append('}');
  }

  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Corpus lines, starting over from the top at the end so numDocs can exceed the file. */
  static final class Lines implements AutoCloseable {
    private final Path file;
    private BufferedReader r;
    private boolean any;

    Lines(Path file) throws java.io.IOException {
      this.file = file;
      this.r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    String readLine() throws java.io.IOException {
      String line = r.readLine();
      if (line == null && any) {
        r.close();
        r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        any = false;
        line = r.readLine();
      }
      if (line != null) any = true;
      return line;
    }

    @Override
    public void close() throws java.io.IOException {
      r.close();
    }
  }

  static void json(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    sb.append('"');
  }

  static String post(String solr, String core, String path, byte[] body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(solr + "/" + core + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<String> rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (rsp.statusCode() != 200) throw new IllegalStateException(core + path + " -> " + rsp.statusCode() + " " + rsp.body());
    return rsp.body();
  }

  static String get(String solr, String core, String query) throws Exception {
    // long queries (term sets of thousands of values) go as a form body: a URL that long is refused
    HttpRequest req = query.length() > 4096
        ? HttpRequest.newBuilder(URI.create(solr + "/" + core + "/select"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(query)).build()
        : HttpRequest.newBuilder(URI.create(solr + "/" + core + "/select?" + query)).GET().build();
    HttpResponse<String> rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (rsp.statusCode() != 200) throw new IllegalStateException(core + " " + query + " -> " + rsp.statusCode() + " " + rsp.body());
    return rsp.body();
  }

  static final Pattern NUM_FOUND = Pattern.compile("\"numFound\":(\\d+)");

  static long numFound(String body) {
    Matcher m = NUM_FOUND.matcher(body);
    if (!m.find()) throw new IllegalStateException("no numFound in " + body);
    return Long.parseLong(m.group(1));
  }

  // ---- search ---------------------------------------------------------------------------

  /** One request: a category and its query string (without rows/fl/minExactCount). */
  record Req(String category, String params) {}

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static List<Req> buildRequests(Path luceneIndex) throws Exception {
    List<Req> out = new ArrayList<>();
    try (FSDirectory dir = FSDirectory.open(luceneIndex);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      for (BenchTasks.Task t : BenchTasks.build(reader, new IndexSearcher(reader))) {
        String text = t.text();
        String q = switch (t.category()) {
          case "AndHighHigh", "AndHighMed" -> {
            String[] ab = text.split(" AND ");
            yield "q=" + enc("+body:" + ab[0] + " +body:" + ab[1]);
          }
          case "OrHighHigh", "OrHighMed" -> {
            String[] ab = text.split(" OR ");
            yield "q=" + enc("body:" + ab[0] + " body:" + ab[1]);
          }
          case "TermSortTs" -> "q=" + enc("body:" + text.substring(0, text.indexOf(' '))) + "&sort=" + enc("ts desc");
          default -> "q=" + enc("body:" + text); // Term*, Prefix3 (x*), Fuzzy1 (x~1), Phrase ("a b")
        };
        out.add(new Req(t.category(), q));
        // Solr-shaped variants of the same terms: a facet and a range filter.
        if (t.category().equals("TermMed")) {
          out.add(new Req("TermFacetMonth", q + "&facet=true&facet.field=month&facet.limit=10"));
        }
        if (t.category().equals("TermHigh")) {
          out.add(new Req("TermFqDate", q + "&fq=" + enc("ts:[2012-05-01T00:00:00Z TO *]")));
        }
      }
    }
    return out;
  }

  static void search(String solr, Path luceneIndex, int threads, int seconds, String[] cores) throws Exception {
    List<Req> reqs = buildRequests(luceneIndex);
    Map<String, List<Req>> byCat = new LinkedHashMap<>();
    for (Req r : reqs) byCat.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
    System.out.printf(Locale.ROOT, "%d requests in %d categories; cores %s%n", reqs.size(), byCat.size(), Arrays.toString(cores));

    // Same answers? Compare numFound across cores, and the returned ids for sorted requests.
    int mismatches = 0, maxShown = seconds == 0 ? Integer.MAX_VALUE : 10;
    double worst = 0;
    for (Req r : reqs) {
      long first = -1;
      for (String core : cores) {
        long nf = numFound(get(solr, core, r.params() + "&rows=0"));
        if (first < 0) first = nf;
        else if (nf != first) {
          mismatches++;
          worst = Math.max(worst, Math.abs(nf - first) / (double) Math.max(1, first));
          if (mismatches <= maxShown) System.out.printf(Locale.ROOT, "  numFound mismatch %s %s: %d vs %d%n", r.category(), r.params(), first, nf);
        }
      }
    }
    System.out.printf(Locale.ROOT, "verified %d requests: %d numFound mismatches (worst %.3f%%)%n", reqs.size(), mismatches, worst * 100);
    if (seconds == 0) return;

    for (boolean exact : new boolean[] {true, false}) {
      String extra = "&rows=10&fl=id" + (exact ? "" : "&minExactCount=1000");
      System.out.printf(Locale.ROOT, "%n== /select rows=10 fl=id, %s, 1 client thread: median latency (ms) ==%n",
          exact ? "exact numFound" : "minExactCount=1000");
      StringBuilder hdr = new StringBuilder(String.format(Locale.ROOT, "%-15s", "task"));
      for (String c : cores) hdr.append(String.format(Locale.ROOT, " %12s", c));
      System.out.println(hdr + "  speedup");
      double[] logSum = new double[cores.length];
      for (Map.Entry<String, List<Req>> e : byCat.entrySet()) {
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-15s", e.getKey()));
        double[] lat = new double[cores.length];
        for (int i = 0; i < cores.length; i++) {
          String core = cores[i];
          lat[i] = medianLatency(e.getValue(), r -> get(solr, core, r.params() + extra));
          logSum[i] += Math.log(lat[i]);
          row.append(String.format(Locale.ROOT, " %12.2f", lat[i]));
        }
        System.out.println(row + String.format(Locale.ROOT, "  %6.2fx", lat[0] / lat[cores.length - 1]));
      }
      StringBuilder gm = new StringBuilder(String.format(Locale.ROOT, "%-15s", "geomean"));
      for (double s : logSum) gm.append(String.format(Locale.ROOT, " %12.2f", Math.exp(s / byCat.size())));
      System.out.println(gm + String.format(Locale.ROOT, "  %6.2fx", Math.exp((logSum[0] - logSum[cores.length - 1]) / byCat.size())));
    }

    for (boolean exact : new boolean[] {true, false}) {
      String extra = "&rows=10&fl=id" + (exact ? "" : "&minExactCount=1000");
      System.out.printf(Locale.ROOT, "%n== throughput, %d client threads, %ds, all tasks mixed, %s (QPS) ==%n",
          threads, seconds, exact ? "exact numFound" : "minExactCount=1000");
      double base = 0;
      for (String core : cores) {
        double qps = throughput(reqs, threads, seconds, r -> get(solr, core, r.params() + extra));
        if (base == 0) base = qps;
        System.out.printf(Locale.ROOT, "%-12s %8.0f QPS  %5.2fx%n", core, qps, qps / base);
      }
    }
  }

  interface Runner {
    String run(Req r) throws Exception;
  }

  static double medianLatency(List<Req> reqs, Runner r) throws Exception {
    for (int w = 0; w < 3; w++) for (Req q : reqs) r.run(q);
    List<Double> per = new ArrayList<>();
    for (Req q : reqs) {
      double[] lat = new double[7];
      for (int i = 0; i < lat.length; i++) {
        long t0 = System.nanoTime();
        r.run(q);
        lat[i] = (System.nanoTime() - t0) / 1e6;
      }
      Arrays.sort(lat);
      per.add(lat[lat.length / 2]);
    }
    per.sort(Double::compare);
    return per.get(per.size() / 2);
  }

  static double throughput(List<Req> reqs, int threads, int seconds, Runner r) throws Exception {
    for (Req q : reqs) r.run(q);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong done = new AtomicLong();
    List<Future<?>> fs = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      int offset = i * 7;
      fs.add(pool.submit(() -> {
        int k = offset;
        while (!stop.get()) {
          r.run(reqs.get(k++ % reqs.size()));
          done.incrementAndGet();
        }
        return null;
      }));
    }
    long t0 = System.nanoTime();
    Thread.sleep(seconds * 1000L);
    stop.set(true);
    for (Future<?> f : fs) f.get();
    double secs = (System.nanoTime() - t0) / 1e9;
    pool.shutdown();
    pool.awaitTermination(1, TimeUnit.MINUTES);
    return done.get() / secs;
  }
}
