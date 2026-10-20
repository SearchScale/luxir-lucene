// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.bench;

import static org.luxir.lucene.bench.SolrHttpBench.enc;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

/**
 * Query benchmark over the wide documents: text queries (luceneutil-style terms picked from the
 * stock index), string and numeric queries, filters, field sorts and facets. Each request is sent
 * to Solr cores as /select parameters and to plain Luxir as an equivalent _search body.
 *
 * <pre>
 * wsearch solrUrl luxirUrl|- luxirCollection luceneIndexDir threads seconds core...
 * </pre>
 *
 * The last core is the one whose execution path (native or Lucene, from debug output) is
 * reported; plain Luxir, when given, is measured after the cores.
 */
final class WideSearchBench {
  /** One request: its category, Solr /select parameters and plain-Luxir body (null: none). */
  record WReq(String category, String solr, String luxir) {}

  private static final String[] KINDS = {"list", "disambiguation"};
  private static final String[] CATS = {
    "Living people", "Greek mythology", "English-language films", "Days of the year",
    "American film actors", "American films"
  };

  static List<WReq> build(Path luceneIndex) throws Exception {
    List<WReq> out = new ArrayList<>();
    List<String> med = new ArrayList<>();
    try (FSDirectory dir = FSDirectory.open(luceneIndex);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      for (BenchTasks.Task t : BenchTasks.build(reader, new IndexSearcher(reader))) {
        String x = t.text();
        switch (t.category()) {
          case "AndHighHigh", "AndHighMed" -> {
            String[] ab = x.split(" AND ");
            out.add(req(t.category(), "q=" + enc("+body:" + ab[0] + " +body:" + ab[1]),
                q("body:" + ab[0] + " AND body:" + ab[1])));
          }
          case "OrHighHigh", "OrHighMed" -> {
            String[] ab = x.split(" OR ");
            out.add(req(t.category(), "q=" + enc("body:" + ab[0] + " body:" + ab[1]),
                q("body:" + ab[0] + " OR body:" + ab[1])));
          }
          case "TermSortTs" -> {
            String term = x.substring(0, x.indexOf(' '));
            out.add(req("TermSortDate", "q=" + enc("body:" + term) + "&sort=" + enc("ts desc"),
                q("body:" + term, "\"sort\":\"ts desc\"")));
          }
          default -> { // Term*, Prefix3 (x*), Fuzzy1 (x~1), Phrase ("a b")
            if (t.category().equals("TermMed")) med.add(x);
            out.add(req(t.category(), "q=" + enc("body:" + x), q("body:" + x)));
          }
        }
      }
    }
    Random r = new Random(42);
    for (int i = 0; i < med.size(); i++) {
      String t = med.get(i);
      String body = "body:" + t;
      String bq = "q=" + enc(body);
      // a query on a string field alone
      String sv = i % 2 == 0 ? KINDS[i / 2 % KINDS.length] : CATS[i % CATS.length];
      String sf = i % 2 == 0 ? "kind" : "cats";
      out.add(req("StrTermQ", "q=" + enc(sf + ":\"" + sv + "\""), q(sf + ":=\"" + sv + "\"")));
      // a numeric range as the query
      String range = switch (i % 3) {
        case 0 -> { int lo = 200 + r.nextInt(2000); yield "words:[" + lo + " TO " + (lo + 300) + "]"; }
        case 1 -> { int lo = 20 + r.nextInt(60); yield "links:[" + lo + " TO " + (lo + 10) + "]"; }
        default -> { int lo = r.nextInt(900); yield "popularity:[" + lo + " TO " + (lo + 20) + "]"; }
      };
      out.add(req("RangeQ", "q=" + enc(range), q(range)));
      // a text query with a filter
      out.add(req("TermFqStr", bq + "&fq=" + enc("kind:article"), q(body, filter("kind:article"))));
      out.add(req("TermFqRange", bq + "&fq=" + enc("words:[100 TO *]"), q(body, filter("words:[100 TO *]"))));
      out.add(req("TermFqDate", bq + "&fq=" + enc("ts:[2012-04-01T00:00:00Z TO *]"),
          q(body, filter("ts:[2012-04-01T00:00:00Z TO *]"))));
      // match all, filtered
      int links = 10 + r.nextInt(40);
      String kind = KINDS[i % KINDS.length];
      out.add(req("AllFq", "q=*:*&fq=" + enc("kind:" + kind) + "&fq=" + enc("links:[" + links + " TO *]"),
          "{\"query\":\"*:*\"," + filter("kind:" + kind, "links:[" + links + " TO *]") + "}"));
      // text and string clauses in one scored query; two text fields
      out.add(req("BoolStr", "q=" + enc("+" + body + " +kind:article"), q(body + " AND kind:article")));
      out.add(req("TwoFields", "q=" + enc("title:" + t + " " + body), q("title:" + t + " OR " + body)));
      // field sorts
      out.add(req("TermSortNum", bq + "&sort=" + enc("popularity desc"), q(body, "\"sort\":\"popularity desc\"")));
      out.add(req("TermSortStr", bq + "&sort=" + enc("month desc"), q(body, "\"sort\":\"month desc\"")));
      // facets over the full match set
      out.add(req("TermFacetStr", bq + "&facet=true&facet.field=kind&facet.field=cats&facet.limit=10",
          q(body, "\"ops\":{\"kind\":{\"field_facet\":{\"field\":\"kind\",\"limit\":10}},"
              + "\"cats\":{\"field_facet\":{\"field\":\"cats\",\"limit\":10}}}")));
      out.add(req("TermFacetNum", bq + "&facet=true&facet.field=year&facet.limit=10",
          q(body, "\"ops\":{\"year\":{\"field_facet\":{\"field\":\"year\",\"limit\":10}}}")));
      out.add(req("TermFacetRange", bq + "&facet=true&facet.range=ts&facet.range.start="
              + enc("2004-01-01T00:00:00Z") + "&facet.range.end=" + enc("2013-01-01T00:00:00Z")
              + "&facet.range.gap=" + enc("+1YEAR"),
          q(body, "\"ops\":{\"ts\":{\"range_facet\":{\"field\":\"ts\",\"start\":\"2004-01-01T00:00:00Z\","
              + "\"end\":\"2013-01-01T00:00:00Z\",\"calendar_gap\":{\"n\":1,\"unit\":\"year\"}}}}")));
    }
    return out;
  }

  private static WReq req(String cat, String solr, String luxir) {
    return new WReq(cat, solr, luxir);
  }

  private static String q(String query, String... extra) {
    StringBuilder sb = new StringBuilder("{\"query\":");
    SolrHttpBench.json(sb, query);
    for (String e : extra) sb.append(',').append(e);
    return sb.append('}').toString();
  }

  private static String filter(String... fs) {
    StringBuilder sb = new StringBuilder("\"filter\":[");
    for (int i = 0; i < fs.length; i++) {
      if (i > 0) sb.append(',');
      SolrHttpBench.json(sb, fs[i]);
    }
    return sb.append(']').toString();
  }

  static String luxir(String url, String collection, String body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/collections/" + collection + "/_search"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> rsp = SolrHttpBench.http.send(req, HttpResponse.BodyHandlers.ofString());
    if (rsp.statusCode() != 200) throw new IllegalStateException("luxir " + body + " -> " + rsp.statusCode() + " " + rsp.body());
    return rsp.body();
  }

  private static final Pattern FOUND = Pattern.compile("\"found\":(\\d+)");

  static long found(String body) {
    Matcher m = FOUND.matcher(body);
    if (!m.find()) throw new IllegalStateException("no found in " + body);
    return Long.parseLong(m.group(1));
  }

  // The Luxir body with the result shape the Solr requests use: 10 docs, exact count, the id field.
  private static String luxirBody(String body, int limit) {
    // a request that sets its own limit (count-only) keeps it
    String lim = body.contains("\"limit\":") ? "" : ",\"limit\":" + limit;
    return body.substring(0, body.length() - 1) + lim + ",\"get_number\":true,\"fields\":[\"id\"]}";
  }

  private static final Pattern ID = Pattern.compile("\"id\":\\s*\"([^\"]*)\"");

  static String facets(String body) {
    int i = body.indexOf("\"facet_counts\"");
    if (i < 0) i = body.indexOf("\"facets\""); // JSON facets
    return i < 0 ? "" : body.substring(i);
  }

  static List<String> ids(String body) {
    List<String> out = new ArrayList<>();
    Matcher m = ID.matcher(body);
    while (m.find()) out.add(m.group(1));
    return out;
  }

  private static final Pattern PATH = Pattern.compile("\"luxirCodec\":\\s*\\{\\s*\"path\":\\s*\"(\\w+)\"(?:\\s*,\\s*\"reason\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?");

  /** Requests from a file of "category TAB url-encoded params" lines. */
  static List<WReq> taskFile(Path file) throws IOException {
    List<WReq> out = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      int tab = line.indexOf('\t');
      if (line.isBlank() || line.startsWith("#") || tab < 0) continue;
      out.add(new WReq(line.substring(0, tab), line.substring(tab + 1), null));
    }
    return out;
  }

  static void run(String solr, String luxirUrl, String collection, Path luceneIndex, int threads, int seconds,
      String[] cores) throws Exception {
    run(solr, luxirUrl, collection, build(luceneIndex), threads, seconds, cores);
  }

  static void run(String solr, String luxirUrl, String collection, List<WReq> reqs, int threads, int seconds,
      String[] cores) throws Exception {
    Map<String, List<WReq>> byCat = new LinkedHashMap<>();
    for (WReq r : reqs) byCat.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
    boolean withLuxir = !luxirUrl.equals("-");
    System.out.printf(Locale.ROOT, "%d requests in %d categories; cores %s%s%n", reqs.size(), byCat.size(),
        Arrays.toString(cores), withLuxir ? " + plain luxir" : "");

    // Same answers, and which path each request takes on the last core.
    System.out.println("\n== verification (numFound per core; path on " + cores[cores.length - 1] + ") ==");
    for (Map.Entry<String, List<WReq>> e : byCat.entrySet()) {
      int mismatch = 0, luxMismatch = 0, nativeCount = 0, idMismatch = 0, facetMismatch = 0, javaMismatch = 0;
      double worstLux = 0;
      Map<String, Integer> reasons = new TreeMap<>();
      for (WReq r : e.getValue()) {
        long first = -1;
        for (String core : cores) {
          long nf = SolrHttpBench.numFound(SolrHttpBench.get(solr, core, r.solr() + "&rows=0"));
          if (first < 0) first = nf;
          else if (nf != first) mismatch++;
        }
        if (withLuxir && r.luxir() != null) {
          long lf = found(luxir(luxirUrl, collection, luxirBody(r.luxir(), 0)));
          if (lf != first) {
            luxMismatch++;
            worstLux = Math.max(worstLux, Math.abs(lf - first) / (double) Math.max(1, first));
          }
        }
        String last = cores[cores.length - 1];
        if (r.solr().contains("json.facet=")) {
          // collected natively and in Java on the same core: identical
          String fn = facets(SolrHttpBench.get(solr, last, r.solr() + "&rows=0&wt=json&indent=false"));
          String fj = facets(SolrHttpBench.get(solr, last, r.solr() + "&rows=0&wt=json&indent=false&luxirCodec=false"));
          if (!fn.equals(fj) && javaMismatch++ == 0) System.out.println("  JSON facets native != Java for " + r.solr() + ":\n    " + fn + "\n    " + fj);
        }
        if (r.solr().contains("facet=true") || r.solr().contains("json.facet=")) {
          // facet counts: every core the same (the stock core is the reference)
          String f0 = facets(SolrHttpBench.get(solr, cores[0], r.solr() + "&rows=0&wt=json&indent=false"));
          String f1 = facets(SolrHttpBench.get(solr, last, r.solr() + "&rows=0&wt=json&indent=false"));
          if (!f0.equals(f1) && facetMismatch++ == 0) System.out.println("  facets differ for " + r.solr() + ":\n    " + f0 + "\n    " + f1);
        }
        // native and Lucene execution on the same core: same ids, in the same order
        String nat = SolrHttpBench.get(solr, last, r.solr() + "&rows=10&fl=id&wt=json");
        String luc = SolrHttpBench.get(solr, last, r.solr() + "&rows=10&fl=id&wt=json&luxirCodec=false");
        if (!ids(nat).equals(ids(luc))) {
          if (idMismatch++ == 0) System.out.println("  ids differ for " + r.solr() + ": " + ids(nat) + " vs " + ids(luc));
        }
        String dbg = SolrHttpBench.get(solr, last, r.solr() + "&rows=10&fl=id&debug=timing&wt=json&json.nl=map");
        Matcher m = PATH.matcher(dbg);
        if (m.find() && m.group(1).equals("native")) {
          nativeCount++;
        } else {
          String why = m.find(0) ? (m.group(2) == null ? m.group(1) : m.group(2)) : "no luxirCodec debug";
          reasons.merge(why.length() > 90 ? why.substring(0, 90) : why, 1, Integer::sum);
        }
      }
      System.out.printf(Locale.ROOT,
          "%-15s %2d reqs  numFound mismatches: cores %d, luxir %d (worst %.2f%%)  ids native!=lucene %d  facets %d"
              + "  json facets native!=java %d  native %d/%d %s%n",
          e.getKey(), e.getValue().size(), mismatch, luxMismatch, worstLux * 100, idMismatch, facetMismatch, javaMismatch,
          nativeCount, e.getValue().size(), reasons.isEmpty() ? "" : reasons);
    }
    if (seconds == 0) return;

    List<String> targets = new ArrayList<>(Arrays.asList(cores));
    if (withLuxir) targets.add("luxir");
    SolrHttpBench.Runner[] runners = new SolrHttpBench.Runner[targets.size()];
    String extra = "&rows=10&fl=id";
    for (int i = 0; i < targets.size(); i++) {
      String target = targets.get(i);
      runners[i] = target.equals("luxir")
          ? rq -> luxir(luxirUrl, collection, luxirBody(luxirOf(rq), 10))
          : rq -> SolrHttpBench.get(solr, target, rq.params() + extra);
    }

    System.out.printf(Locale.ROOT, "%n== median latency (ms), rows=10, exact counts, 1 client thread ==%n");
    StringBuilder hdr = new StringBuilder(String.format(Locale.ROOT, "%-24s", "task"));
    for (String t : targets) hdr.append(String.format(Locale.ROOT, " %14s", t));
    System.out.println(hdr + "  " + targets.get(0) + "/" + cores[cores.length - 1]);
    // categories plain Luxir has no equivalent for are measured on the Solr cores only ("-");
    // geomeans are over the categories every target ran
    double[] logSum = new double[targets.size()];
    int common = 0;
    for (Map.Entry<String, List<WReq>> e : byCat.entrySet()) {
      List<SolrHttpBench.Req> list = asReqs(e.getValue());
      boolean all = !withLuxir || e.getValue().stream().allMatch(r -> r.luxir() != null);
      StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-24s", e.getKey()));
      double[] lat = new double[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        if (!all && targets.get(i).equals("luxir")) {
          row.append(String.format(Locale.ROOT, " %14s", "-"));
          continue;
        }
        lat[i] = SolrHttpBench.medianLatency(list, runners[i]);
        row.append(String.format(Locale.ROOT, " %14.2f", lat[i]));
      }
      if (all) {
        common++;
        for (int i = 0; i < targets.size(); i++) logSum[i] += Math.log(lat[i]);
      }
      System.out.println(row + String.format(Locale.ROOT, "  %6.2fx", lat[0] / lat[cores.length - 1]));
    }
    StringBuilder gm = new StringBuilder(String.format(Locale.ROOT, "%-24s", "geomean (" + common + " common)"));
    for (double s : logSum) gm.append(String.format(Locale.ROOT, " %14.2f", Math.exp(s / common)));
    System.out.println(gm + String.format(Locale.ROOT, "  %6.2fx",
        Math.exp((logSum[0] - logSum[cores.length - 1]) / common)));

    String perCat = System.getenv("WSEARCH_PERCAT_SECS");
    if (perCat != null) {
      int secs = Integer.parseInt(perCat);
      System.out.printf(Locale.ROOT, "%n== throughput per category (QPS), %d client threads, %ds each ==%n", threads, secs);
      StringBuilder h = new StringBuilder(String.format(Locale.ROOT, "%-24s", "task"));
      for (String t : targets) h.append(String.format(Locale.ROOT, " %14s", t));
      System.out.println(h + "  " + cores[cores.length - 1] + "/" + targets.get(0));
      double[] logQ = new double[targets.size()];
      int commonQ = 0;
      for (Map.Entry<String, List<WReq>> e : byCat.entrySet()) {
        List<SolrHttpBench.Req> list = asReqs(e.getValue());
        boolean all = !withLuxir || e.getValue().stream().allMatch(r -> r.luxir() != null);
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-24s", e.getKey()));
        double[] q = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
          if (!all && targets.get(i).equals("luxir")) {
            row.append(String.format(Locale.ROOT, " %14s", "-"));
            continue;
          }
          q[i] = SolrHttpBench.throughput(list, threads, secs, runners[i]);
          row.append(String.format(Locale.ROOT, " %14.0f", q[i]));
        }
        if (all) {
          commonQ++;
          for (int i = 0; i < targets.size(); i++) logQ[i] += Math.log(q[i]);
        }
        System.out.println(row + String.format(Locale.ROOT, "  %6.2fx", q[cores.length - 1] / q[0]));
      }
      StringBuilder gq = new StringBuilder(String.format(Locale.ROOT, "%-24s", "geomean (" + commonQ + " common)"));
      for (double v : logQ) gq.append(String.format(Locale.ROOT, " %14.0f", Math.exp(v / commonQ)));
      System.out.println(gq + String.format(Locale.ROOT, "  %6.2fx",
          Math.exp((logQ[cores.length - 1] - logQ[0]) / commonQ)));
    }

    System.out.printf(Locale.ROOT, "%n== throughput, %d client threads, %ds, all categories mixed (QPS) ==%n", threads, seconds);
    // the mix: requests every target runs
    List<SolrHttpBench.Req> all = asReqs(withLuxir ? reqs.stream().filter(r -> r.luxir() != null).toList() : reqs);
    double base = 0;
    for (int i = 0; i < targets.size(); i++) {
      double qps = SolrHttpBench.throughput(all, threads, seconds, runners[i]);
      if (base == 0) base = qps;
      System.out.printf(Locale.ROOT, "%-18s %8.0f QPS  %5.2fx%n", targets.get(i), qps, qps / base);
    }
  }

  // Luxir bodies by request object (the shared helpers take Reqs, which are records).
  private static final Map<SolrHttpBench.Req, String> LUXIR = new IdentityHashMap<>();

  private static String luxirOf(SolrHttpBench.Req q) {
    synchronized (LUXIR) {
      return LUXIR.get(q);
    }
  }

  private static List<SolrHttpBench.Req> asReqs(List<WReq> reqs) {
    List<SolrHttpBench.Req> out = new ArrayList<>();
    for (WReq r : reqs) {
      SolrHttpBench.Req q = new SolrHttpBench.Req(r.category(), r.solr());
      synchronized (LUXIR) {
        LUXIR.put(q, r.luxir());
      }
      out.add(q);
    }
    return out;
  }
}
