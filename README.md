# luxir-lucene

luxir-lucene runs [Luxir](https://github.com/luxir-search/luxir), a C++ search
engine, inside Apache Lucene and Apache Solr at the codec level. Lucene and Solr
stay in charge of documents, analysis, segments, deletes, merges, commits, NRT,
the update log, SolrCloud replication, faceting and highlighting. Luxir takes
over two things:

- **Storage:** postings, stored fields, doc values, norms and points are written
  in Luxir's segment format, inside Lucene's segment files.
- **Execution:** indexing of Luxir-typed fields, and the queries, sorts and
  facets that read them, run natively in one call per request.

Java talks to the native library through the Foreign Function and Memory API
(JDK 22 or later); there is no JNI.

**Status:** experimental. File formats can change between versions, and the
native library currently needs Luxir with the patches in
[`luxir-patches/`](luxir-patches) (proposed upstream).

```
Solr (update log, NRT, SolrCloud, faceting, highlighting, atomic updates ...)
  Lucene IndexWriter / IndexReader (segments, deletes, merges, commits)
    LuxirCodec (this library): Luxir segment files inside each Lucene segment, read in place via mmap
      Java TermsEnum / PostingsEnum / doc values / points over them: every Lucene query and Solr feature works
  Solr plugin (luxir-codec module): requests answered by LuxirSearch in one native call over all
    segments, with Solr's filters passed as bitsets; JSON and classic facets counted natively
libluxir_lucene.so (native/lxl.{h,cpp}): a C ABI over Luxir's writers, readers, inverter, queries and collectors
```

## Contents

| Path | What |
|---|---|
| `native/` | `lxl.h` / `lxl.cpp`: the C ABI, built as `libluxir_lucene.so` inside a Luxir checkout (`lxl.cmake`) |
| `src/main/java/org/luxir/lucene/codec` | `LuxirCodec`, `LuxirPostingsFormat` and the other formats; native merges; `NativeIndexer` |
| `src/main/java/org/luxir/lucene/document` | `LuxirTextField` and `LuxirValueField`: fields that Luxir indexes itself |
| `src/main/java/org/luxir/lucene/search` | `LuxirSearch` (native top-k and counts), `LuxirAcceleratedQuery`, native sorts and facets |
| `src/main/java/org/luxir/lucene/analysis` | `LuxirAnalyzer`: Luxir's analysis chain as a Lucene `Analyzer` |
| `src/bench` | HTTP benchmark clients: stock Solr against Solr with Luxir |
| `bench/geonames` | The geonames benchmark: data encoder, configsets, tasks and run script |
| `luxir-patches/` | The Luxir changes this library needs, as `git format-patch` files |

The Solr integration (field types, query and facet components, update handler
and codec factory) is a Solr module, `solr/modules/luxir-codec`, kept on a
Solr branch together with four small Solr core changes proposed upstream (see
[Solr](#solr)).

## How it works

### The codec

- **Writing.** `LuxirFieldsConsumer.write` iterates Lucene's inverted postings
  (terms in byte order, then docs, then positions) and pushes each term in one
  native call into Luxir's `TextWriter`, with Lucene's own norms. Luxir builds
  its block-max impacts from those norms. The resulting Luxir segment files are
  copied, 8-byte aligned, into the Lucene file `<seg>.lxd`; their layout and
  per-field statistics go into `.lxm`.
- **What is stored natively.** Positional text fields with norms and no
  payloads or offsets. Every other field the postings format is handed (string
  ids, docs-only fields, offsets, payloads) goes to an embedded
  `Lucene104PostingsFormat`.
- **Long terms.** Terms over Luxir's 255-byte limit are kept as "overflow
  terms" in `.lxm`, and the Terms API serves the merged view, so a field never
  falls back to Lucene's format because of a few long terms.
- **Reading.** Lucene's mmap of `.lxd` is handed to Luxir's `PostingsReader` in
  place; other directories get one copy. `TermsEnum` and `PostingsEnum` make
  one native call per move (128 docs per call for docs-and-freqs iteration).
  `Terms.intersect`, which backs fuzzy, prefix, wildcard and regexp expansion,
  exports Lucene's DFA and runs Luxir's `DfaIntersectEnum`.
- **Stored fields, doc values and points** (`.lxs`, `.lxv`, `.lxp` and their
  metadata files): Luxir's LZ4 document chunks; numeric, sorted-numeric, sorted
  and sorted-set columns with a term dictionary for the sorted types; and
  one-dimensional points of up to 8 bytes. Binary doc values, fields with a skip
  index and other point shapes go to the delegate formats.
- **Merges** run natively when every source segment is in Luxir format and
  there is no index sort: doc maps are applied in C++, and sorted dictionaries
  are k-way merged keeping only the terms live docs use. Stored-field chunks are
  copied compressed when there are no deletions.

Lucene's randomized format suites (`BasePostingsFormatTestCase`,
`BaseStoredFieldsFormatTestCase`, `BaseDocValuesFormatTestCase`,
`BasePointsFormatTestCase`) pass against these formats.

### Luxir doing the indexing

With the formats alone, Lucene still analyzes and inverts every document, which
is most of the indexing CPU. Two field types hand that work to Luxir:

- **`LuxirTextField`** gives Lucene's indexing chain no tokens. Its text travels
  to the codec through the stored-fields writer, the one codec hook Lucene calls
  per document on the indexing thread, where Luxir's own `Inverter`
  (`NativeIndexer`, batched over FFM) tokenizes and inverts it. Native memory is
  reported to Lucene, so RAM-based flushing still works. Queries, highlighting
  and prefix or wildcard expansion use `LuxirAnalyzer`, the same native chain,
  so query and index terms always agree.
- **`LuxirValueField`** does the same for strings and numbers. Strings are
  buffered in one native hash per field, which at flush yields both the sorted
  doc values and docs-only postings; numbers are buffered as primitive arrays
  and written as columns and points. Indexed strings are limited to 255 UTF-8
  bytes; a longer value fails the document.

A value field reports its full schema the first time Lucene asks within a
document, so the segment's `FieldInfo` declares postings, doc values and points,
and a stored-only type afterwards, so the value reaches the codec alone. Without
the codec's per-document mark (another codec, or index sorting) it always
reports its full schema and fails loudly instead of being indexed twice or not
at all. The unique key and `_root_` stay on Lucene's path, because Lucene
applies deletes through its own postings.

`TestNativeIndexing` checks that Luxir's inverter produces the same terms,
postings, positions, norms and statistics as Lucene inverting the same tokens;
`TestLuxirValueField` checks doc values, stored values, terms, queries and
sorts against Lucene's own fields, with CheckIndex.

### Queries run by Luxir

`LuxirSearch.topK` encodes the rewritten Lucene query, and Solr's filters as
non-scoring clauses, into one native query run over a cached multi-segment view
of the reader. It first counts (stopping at `max(minExactCount, k)` when
allowed), then ranks with block-max pruning and one competitive floor shared
across segments. Collection statistics come from the searcher, so scores equal
Lucene's BM25 (a near-tie can differ in the last bit).

| Lucene query | Native form |
|---|---|
| Term, boolean (any occurs, `minimumShouldMatch`), boost, constant score, exact phrase, constant-score prefix, match-all/none | Luxir's own queries; a fuzzy query runs as the boolean of terms Lucene's rewrite picked |
| Term on a docs-only string field | Luxir's term query, scored as Lucene scores frequency 1 and norm 1 |
| Int, long or date range (`IndexOrDocValuesQuery`) | Luxir's numeric-range planning over the doc-values column and points |
| Other 1-D point ranges, `FieldExistsQuery` on points, point sets | Bitsets built natively from Luxir's points per segment |
| Constant-score wildcard and regexp | Luxir's term-set query over the terms the automaton matches (at most 10,000 per segment) |

Sloppy phrases stay with Lucene, because Lucene's sloppy frequency is a
heuristic Luxir does not reproduce bit for bit. `LuxirAcceleratedQuery` still
makes top-level bulk scoring native under any Lucene collector, window by
window, honoring `setMinCompetitiveScore`.

Single-field sorts (numbers, dates, strings on single-valued doc values) take
each segment's best k from a native scan of the column, skipping 4096-value
blocks whose min/max cannot compete; Java merges the segments. Field and range
facets count natively over the columns.

`TestLuxirSearch` indexes the same random corpus with Lucene's codec and fields
and with Luxir's, and checks hundreds of random queries, filters, sorts and
facets for identical hits, scores and counts.

## Solr

The Solr module (`solr/modules/luxir-codec` on the Solr branch) provides:

- **`LuxirCodecFactory`**, and field types `LuxirTextField`, `LuxirStrField`,
  `LuxirIntPointField`, `LuxirLongPointField`, `LuxirFloatPointField`,
  `LuxirDoublePointField` and `LuxirDatePointField`;
- **`LuxirCodecQueryComponent`**: the query, its filters and a single-field
  sort in one native call, including Solr's query result cache, paging,
  `minExactCount` and `rows=0`;
- **`LuxirFacetComponent`** (classic field and range facets) and
  **`LuxirFacetModule`** (JSON facets: terms, ranges, query facets and stats
  collected natively through Solr's `FacetAccelerator` hook, results
  bit-identical to Java's);
- **`LuxirJoinQParserPlugin`** (registered as `join`): same-core joins between
  single-valued string fields, computed natively over the fields' doc-values
  columns and dictionaries (no terms or postings iteration); other joins go to
  Solr's join unchanged;
- **`LuxirUpdateRequestHandler`**: a JSON loader that parses flat documents
  straight from the request bytes and, where the update chain allows it, builds
  Lucene documents without a `SolrInputDocument`;
- **`ParallelFlushUpdateProcessorFactory`**: flushes buffered segments on all
  cores before a hard commit.

It relies on four Solr core changes, each a separate branch proposed upstream:

| Change | Why |
|---|---|
| `FacetAccelerator`: a hook that lets a `FacetModule` collect JSON facet documents itself | native JSON facets |
| New ids skip the delete in `DirectUpdateHandler2` (no update log, not SolrCloud) | 20-25% faster indexing, with any codec |
| A fix for `stddev()` in a growing numeric terms facet | a crash in stock Solr |
| `AddUpdateCommand.makeLuceneDocs()` protected; `SolrIndexSearcher.getQueryResultCache()` | direct document building; the query result cache |

Example configuration:

```xml
<!-- solrconfig.xml -->
<codecFactory class="org.apache.solr.luxir.codec.LuxirCodecFactory"/>
<searchComponent name="query" class="org.apache.solr.luxir.codec.LuxirCodecQueryComponent"/>
<searchComponent name="facet" class="org.apache.solr.luxir.codec.LuxirFacetComponent"/>
<searchComponent name="facet_module" class="org.apache.solr.luxir.codec.LuxirFacetModule"/>
<indexConfig><ramBufferSizeMB>4096</ramBufferSizeMB></indexConfig>
<requestHandler name="/update" class="org.apache.solr.luxir.codec.LuxirUpdateRequestHandler"/>
<updateRequestProcessorChain name="parallel-flush" default="true">
  <processor class="org.apache.solr.luxir.codec.ParallelFlushUpdateProcessorFactory"/>
  <processor class="solr.LogUpdateProcessorFactory"/>
  <processor class="solr.DistributedUpdateProcessorFactory"/>
  <processor class="solr.RunUpdateProcessorFactory"/>
</updateRequestProcessorChain>

<!-- schema -->
<fieldType name="text_lx" class="org.apache.solr.luxir.codec.LuxirTextField"
           luxirAnalyzer='{"tokenizer":"unicode_word","filters":["nfkc_cf","fold","kstem"]}'/>
<fieldType name="string" class="org.apache.solr.luxir.codec.LuxirStrField" docValues="true"/>
<fieldType name="plong" class="org.apache.solr.luxir.codec.LuxirLongPointField" docValues="true"/>
```

Start Solr with `SOLR_MODULES=luxir-codec`,
`-Dluxir.lucene.library=/path/to/libluxir_lucene.so` and
`--enable-native-access=ALL-UNNAMED`. With `debug=true`, every response reports
`luxirCodec: {path: native|lucene, reason: ...}`; `luxirCodec=false` on a request
turns the native paths off. Requests the native path does not cover (multi-field
sorts, grouping, cursors, post filters, rank queries, time limits, distributed
IDF) run through Solr's normal code over the Luxir formats. The module's tests
compare every response with stock Solr's on the same index.

## Building

Requirements: Linux x86-64, the C++26 toolchain and dependencies that Luxir's
build needs (see Luxir's `docs/dev/build-setup.md`), and JDK 22 or later.

1. **Luxir with the patches.** Check out Luxir at `8cb622ba` (or later) and
   apply the patches:

   ```bash
   git clone https://github.com/luxir-search/luxir.git && cd luxir
   git am /path/to/luxir-lucene/luxir-patches/*.patch
   ```

2. **The native library**, built inside the Luxir tree through its untracked
   `LocalTargets.cmake` hook. jemalloc must be off (it would replace the JVM's
   malloc) and code must be position independent:

   ```bash
   echo 'include(/path/to/luxir-lucene/native/lxl.cmake)' > LocalTargets.cmake
   cmake --preset gcc-release -B build/release \
     -DLUXIR_JEMALLOC=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DLUXIR_LOCAL_TARGETS=ON
   cmake --build build/release --target luxir_lucene
   ```

   Add the vcpkg toolchain options your Luxir build uses. The library is
   `build/release/bin/libluxir_lucene.so`.

3. **The Java library and its tests:**

   ```bash
   ./gradlew test jar -Pluxir.lucene.library=/path/to/luxir/build/release/bin/libluxir_lucene.so
   ```

   `-Ptests.iters=N` runs the randomized tests more times. Without
   `-Pluxir.lucene.library`, the build looks for the library at
   `../luxir/build/lux4j-release/bin/libluxir_lucene.so`.

4. **The Solr module**, from the Solr branch:

   ```bash
   ./gradlew :solr:modules:luxir-codec:test \
     -Pluxir.lucene.jar=/path/to/luxir-lucene/build/libs/luxir-lucene-0.1.0-SNAPSHOT.jar \
     -Pluxir.lucene.library=/path/to/libluxir_lucene.so
   ```

## Benchmark

Geonames (11.4M documents, 18 source fields: text, strings, numbers, a location),
stock Solr against Solr with Luxir in one standalone Solr 11 instance, JDK 22,
12 GB heap, a 12-core machine with a SATA SSD. Both cores have the same fields
and no update log; the Luxir core uses Luxir field types and components, a 4 GB
RAM buffer and parallel flush. The client runs on the same machine. Everything
needed to repeat it is in [`bench/geonames`](bench/geonames).

Before measuring, every one of the 212 requests is checked: numFound is equal on
both cores, and on the Luxir core the native path returns the same ids and facet
counts as Lucene's path over the same index.

**Indexing**, 12 client threads, batches of 1000, two rounds per core:

| | docs/s | server CPU-seconds | segments | merged to 1 segment |
|---|---|---|---|---|
| stock Solr | 79.2k, 83.5k | 1528, 1489 | 46, 44 | 101 s, 3.6 GB |
| Solr + Luxir | 120.2k, 95.4k* (1.33x) | 967, 949 (0.64x) | 26, 27 | 139 s, 5.0 GB |

\* That round's commit took 35.9 s instead of the usual 9 s (SATA disk); its add
phase was 83.6 s, as in round 1. Without the commit, both rounds are about 1.5x.

**Queries**, on the merged single-segment indexes: median latency from one
client thread (rows=10, exact counts), and throughput from 8 client threads,
10 s per category, then 30 s with all categories mixed:

| category | requests | latency, stock (ms) | latency, Luxir (ms) | stock / Luxir | QPS, stock | QPS, Luxir | Luxir / stock |
|---|---|---|---|---|---|---|---|
| MatchAll | 1 | 0.69 | 0.53 | 1.31x | 20855 | 25809 | 1.24x |
| StringTerm | 20 | 0.67 | 0.47 | 1.42x | 10301 | 21846 | 2.12x |
| TextTerm | 30 | 0.57 | 0.45 | 1.29x | 15030 | 19939 | 1.33x |
| TextPhrase | 30 | 0.47 | 0.39 | 1.20x | 13079 | 12108 | 0.93x |
| TextOr | 20 | 0.89 | 0.62 | 1.43x | 6831 | 11096 | 1.62x |
| TextAnd | 20 | 0.49 | 0.40 | 1.21x | 17955 | 17539 | 0.98x |
| CatchAllText | 20 | 0.43 | 0.36 | 1.18x | 14273 | 18286 | 1.28x |
| TextFiltered | 20 | 0.45 | 0.39 | 1.15x | 9568 | 14061 | 1.47x |
| NumRange | 10 | 1.93 | 0.41 | 4.65x | 1946 | 8122 | 4.17x |
| SortPopulation | 2 | 147.9 | 56.5 | 2.62x | 33 | 101 | 3.04x |
| SortGeonameid | 2 | 128.8 | 56.6 | 2.27x | 40 | 101 | 2.54x |
| TermSortPop | 10 | 0.60 | 0.64 | 0.94x | 6426 | 5664 | 0.88x |
| CountryFacet | 9 | 65.1 | 38.1 | 1.71x | 69 | 121 | 1.76x |
| HiCardFacet | 3 | 517.7 | 347.9 | 1.49x | 5 | 13 | 2.35x |
| TermsPure | 5 | 77.2 | 62.4 | 1.24x | 72 | 79 | 1.09x |
| TermsFiltered | 5 | 30.5 | 36.0 | 0.85x | 156 | 139 | 0.89x |
| TermsProhibited | 5 | 75.9 | 64.6 | 1.17x | 73 | 77 | 1.05x |
| **geomean** | | **5.18** | **3.57** | **1.45x** | **1079** | **1634** | **1.51x** |
| **mixed, 30 s** | | | | | **251** | **435** | **1.73x** |

Single-thread latency ratios move by about 10% between runs, mostly in the
sub-millisecond categories. Slower than stock: a term query sorted by a field
(TermSortPop), large term sets combined with a filter (TermsFiltered), and
phrase and conjunction throughput, which run within 10% of stock.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
