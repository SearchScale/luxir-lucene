# Geonames full-corpus comparison

All 11,396,503 documents from `documents-2.json.bz2`, one engine at a time, both
through the adapter on `127.0.0.1:19200`, heap 8g, 8 clients, bulk size 5000,
force-merge to 1 segment, no target throughput. Not `--test-mode`.

Machine: 12 cores, 31 GiB RAM, NVMe, Linux 6.8. The ES arm ran second-pass with
the corpus in page cache; the Solr arm ran with Solr translating every bulk.

OpenSearch was not run.

## Setup

| | Elasticsearch | Solr+Luxir |
|---|---|---|
| Race | `7b7ab844-dc53-4d73-9aa1-396c61be9426` (629 s) | `2854fd42-6053-4d5d-87a0-659e3c3dbe8b` (1203 s) |
| Real version | 9.5.6 (released tarball), Lucene 10.5.2, JDK 27 | 11.0.0-SNAPSHOT `d2d7cf1edd`, Lucene 10.4.0, JDK 22, `libluxir_lucene.so` |
| Index | 11,396,503 docs, 1 shard, 1 segment, 2.6 GB | 11,396,503 docs, 1 Luxir segment (`_c_Luxir_0.*` + Lucene104 sidecars), 5.0 GB |
| Error rate | 0 on every operation | 0 on every operation |

Rally reports 7.17.0 for both because the adapter spoofs `GET /`. Versions above
are from the engines' own APIs.

Force-merge runs in `polling` mode (track change): Rally's 10 s client timeout
is caught and the merge is tracked through `GET /_tasks` until done. The
adapter runs the Solr optimize in a background thread (1800 s cap) and answers
`/_tasks` for it.

## Indexing

| | Elasticsearch | Solr+Luxir |
|---|---:|---:|
| Wall-clock | ~121 s (~94,453 docs/s mean) | ~587 s (~19,426 docs/s mean) |
| Bulk p50 | 334 ms | 1,916 ms |
| Bulk p90 | 448 ms | 2,448 ms |
| Bulk p100 | 10.8 s | 32.5 s |

The Solr number is not the engine's indexing speed. Every bulk is rewritten in
the Python adapter (id, lat/lon, `.raw` to `_raw`); ES bulks are proxied
unchanged. The published direct Solr+Luxir figure on similar hardware is about
120k docs/s. The ES figure also benefits from a warm page cache on this
second race of the day (the aborted first race indexed at ~42k docs/s mean).

## Search

p50 latency and mean throughput over 30 s, 8 clients.

| Operation | ES p50 (ms) | Solr p50 (ms) | ES ops/s | Solr ops/s |
|---|---:|---:|---:|---:|
| match-all | 46.0 | 44.9 | 167 | 174 |
| term `country_code.raw:AT` | 46.1 | 45.0 | 167 | 174 |
| phrase `name:"Sankt Georgen"` | 46.7 | 45.0 | 166 | 173 |
| population desc | 52.4 | 155.4 | 145 | 50 |
| population asc | 46.0 | 152.4 | 168 | 50 |
| geonameid desc | 62.6 | 153.3 | 127 | 51 |
| geonameid asc | 51.9 | 151.2 | 151 | 51 |

Eight clients through the adapter floor at about 45 ms land near 170 ops/s
(`8 / 0.045`). Match-all, term and phrase sit on that floor for both engines.

Sorts separate the engines. Both engines used their fast path: Elasticsearch
sorted on Lucene 10.5 doc values; the Luxir core reported
`luxirCodec.path: native` for population desc (QTime 129 ms single client),
geonameid desc (93 ms) and the phrase (23 ms). The term was native too
(28 ms). Match-all reports `luxirCodec: None` (score sort path), as at 1k.
Elasticsearch sorts are 2.5-3x faster than Luxir native sorts on this machine
at 8-client concurrency through the same adapter hop.

## Hit counts (untimed, after each race)

Identical on both engines:

| Query | Hits |
|---|---:|
| match-all | 11,396,503 (`eq`, `numFoundExact`) |
| term `country_code.raw:AT` | 46,502 |
| phrase `name:"Sankt Georgen"` | 58 |
| term `country_code.raw:US` (not in the race) | 2,230,369 |

The raced term and phrase are non-empty on the full corpus (they were 0 on the
1% slice). Top population hit: Earth, 6,814,400,000 (ES id `In7Yz6EB-FvD4G0A2KkJ`).

## Post-race verification (Nov 13): what the race could and could not see

Measurements taken after the races, single client, direct to each engine,
same machine, no adapter. 15 samples, median.

| Operation | ES direct | Solr+Luxir direct |
|---|---:|---:|
| match-all | 9.1 ms | 1.6 ms |
| term `AT` | 13.3 ms | 1.6 ms |
| phrase | 13.4 ms | 1.8 ms |
| population desc | 26.9 ms | 97.8 ms |
| geonameid desc | 27.0 ms | 94.8 ms |

8-client direct sort throughput (15 s): population desc ES 816 ops/s vs Solr 64;
geonameid desc ES 686 vs Solr 65.

Two shim artifacts distorted the race numbers:

1. **The ~45 ms floor was TCP delayed-ACK, not engine time.** Python's
   http.server did not set TCP_NODELAY; every keep-alive response stalled
   ~40 ms. Fixed with `disable_nagle_algorithm = True` on the adapter
   (verified: keep-alive match-all 43.5 ms -> 1.9 ms through the shim).
   Both arms paid it equally, but it hid the direct-query differences above.
2. **Solr's 19k docs/s indexing was the adapter's GIL, not Solr.** One
   5000-doc translation costs 130 ms of Python; 8 concurrent translations
   serialize on the GIL at an aggregate 19,737 docs/s, which matches the
   race's 19,426. Direct, Solr+Luxir ingested the same batch in 191 ms
   (~26k docs/s single client; the published direct figure is ~120k docs/s
   with 12 threads).

What survives from the race: the sort comparison. Even measured directly,
Elasticsearch sorts are 3.5-3.6x faster single client and 10-12x higher
throughput at 8 clients on this machine. The Luxir core reported
`luxirCodec.path: native` on the sorts; the loss is the native path's, not a
fallback. For context, the published Luxir sort win (56 ms, 2.5-3x) is
against stock Solr, not against Elasticsearch.

What the race hid: on match-all, term and phrase, direct Luxir is 5-8x faster
than Elasticsearch (1.6-1.8 ms vs 9.1-13.4 ms).

## Re-race with the fixed adapter (Nov 13, canonical numbers)

Both adapter fixes in place (TCP_NODELAY; polling force-merge). Same corpus,
same 8 clients, same adapter hop for both engines. These replace the search
numbers above as the record.

| Operation | ES p50 (ms) | Solr p50 (ms) | ES ops/s | Solr ops/s |
|---|---:|---:|---:|---:|
| match-all | 5.38 | 5.71 | 1,209 | 1,140 |
| term `country_code.raw:AT` | 5.55 | 5.80 | 1,279 | 1,193 |
| phrase | 5.47 | 5.84 | 1,284 | 1,196 |
| population desc | 8.66 | 122.75 | 800 | 64 |
| population asc | 5.60 | 121.43 | 1,281 | 65 |
| geonameid desc | 8.13 | 123.78 | 881 | 64 |
| geonameid asc | 9.57 | 122.53 | 761 | 65 |

Indexing: ES 83,220 docs/s (race `ac423738`, 651 s, one 2.6 GB segment);
Solr 19,139 docs/s (race `ac577379`, 1,189 s, one Luxir segment, 5.0 GB) —
the Solr figure is the adapter's GIL translation ceiling (~19.7k docs/s
measured in isolation), not the engine.

Reading:

- Cheap queries (match-all, term, phrase) are a statistical tie at ~5.5 ms
  and ~1,200 ops/s. That is the 8-client concurrency ceiling of the Python
  adapter path, not the engines: direct single-client, Luxir serves these in
  1.6-1.8 ms against ES's 9.1-13.4 ms (see the direct table above). The race
  cannot show that gap at this concurrency through this shim.
- Sorts are the one clean in-race separation and they go to Elasticsearch:
  8-10 ms and 800-880 ops/s against 122-124 ms and ~64 ops/s. This survives
  direct measurement with no shim (ES 27 ms vs Luxir 98 ms single client; 816
  vs 64 ops/s at 8 clients). The Luxir sorts ran the native path.
- Indexing throughput through the shim is only meaningful for the proxied ES
  arm. For Solr+Luxir it measures the shim.

### Direct indexing comparison (Nov 13)

The race cannot rank the engines' indexing: the Solr arm's number is the
adapter's GIL ceiling. This is the direct measurement — same 1,000,000-doc
prefix of the corpus, 8 client threads, batches of 5000, pre-encoded payloads
so only the engine is timed, heap 8g, one engine running at a time:

| | Elasticsearch 9.5.6 | Solr+Luxir 11.0.0 |
|---|---:|---:|
| 1M docs, 8 clients | 12.0 s = **83,257 docs/s** | 9.8 s = **101,882 docs/s** |

Solr+Luxir indexes 1.22x faster than Elasticsearch on this machine, direct.
This matches the shape of the published direct figures (Luxir ~120k vs stock
Solr ~80k docs/s on 12 threads). The race's ES figure (83,220 docs/s) equals
this direct figure, confirming the proxied race path costs ES nothing; the
race's Solr figure (19,139 docs/s) is the shim, not the engine.

## Why the Solr+Luxir index is 5.0 GB against Elasticsearch's 2.6 GB

Per-file breakdown of each engine's single merged segment (same 11,396,503
documents, same fields, same machine):

| Component | ES | Solr+Luxir |
|---|---:|---:|
| Stored fields | 1.03 GB (`.fdt`) | 1.05 GB (`.lxs`) |
| Doc values / columns | 0.60 GB (`.dvd`) | 0.79 GB (`.lxv`) |
| Inverted index, text+keyword fields | 0.86 GB (Lucene104 `.tim/.doc/.pos`) | **2.73 GB** (`.lxd` + Luxir `.tim/.tip`) |
| Unique-key (`id`) postings | 0.09 GB (compact id format + bloom) | **0.62 GB** (Lucene104 `.tim/.doc` for the id) |
| Geo points | 0.21 GB (`.kdd`) | 0.05 GB (`.lxp`) |
| Norms | 0.07 GB | 0.08 GB |
| **Total** | **~2.86 GB** | **~5.32 GB** |

Two components explain nearly the whole 2.5 GB difference:

1. **Luxir's native inverted index (`.lxd`) is ~3x larger than Lucene's
   postings for the same fields.** The Luxir format is 8-byte aligned and read
   in place (zero-copy, no per-block deserialization) instead of Lucene's
   bit-packed, block-compressed postings. That alignment/compactness trade is
   the design choice that produces the 1.6-1.8 ms native queries; the cost is
   about +1.9 GB here. The published stock-Solr comparison shows the same
   trade: stock Solr (all-Lucene) 3.6 GB vs Luxir 5.0 GB.
2. **The `id` field costs 0.62 GB on Solr vs 0.09 GB on ES.** Luxir keeps the
   unique key on Lucene104 postings (Lucene applies deletes through its own
   postings), and 11.4M unique geonameid strings cost 499 MB of terms
   dictionary alone. Elasticsearch stores its auto-generated ids in a
   dedicated compact format plus a 14 MB bloom filter.

Everything else is close: stored fields are equal (ES's compressed `_source`
1.03 GB vs Luxir's stored fields 1.05 GB), doc values differ by 0.19 GB (same
alignment trade, smaller effect), and Luxir's geo points are actually 4x
smaller than ES's `.kdd`.

### High-client indexing and improvement levers (Nov 16)

Direct 1M-doc tests at more client counts (single runs, JDK 22 / 8g heap):

| Clients | Solr+Luxir | Elasticsearch |
|---|---:|---:|
| 8 | 101,882 docs/s | 83,257 docs/s |
| 12 | 89,792 docs/s | 85,538 docs/s |
| 16 | 131,706 docs/s | 104,796 docs/s |

Both engines scale past 8 clients; single-run variance at 12-16 clients is
large (Solr's 8/12/16 spread is 90k-132k without a clean trend), so repeated
runs are needed before ranking client counts. JDK 25 was tried (16 clients,
93,766 docs/s, queries unchanged): no measurable gain over JDK 22.

Where Luxir's index bytes go (measured, file by file): the `.lxd` postings and
`.lxv` columns are uncompressed and 8-byte aligned by design (only `.lxs`
stored fields are LZ4-compressed) — that in-place, zero-copy layout is what
produces the 1.6-1.8 ms native queries. The unique key stays on Lucene104
postings (617 MB here, because Lucene applies deletes through its own
postings), and fields with terms over 255 bytes (`alternatenames_raw`) also
stay on stock Lucene formats.

### Three-way index size comparison (Nov 16)

Stock Solr (all-Lucene codecs, `gn_stock` configset) indexed with the same
corpus on this machine, force-merged to one segment: 3.69 GB, matching the
published 3.6 GB. Decimal GB; each engine holds the same 11,396,503 documents
in one segment.

| Component | Stock Solr (Lucene 10.4) | Solr+Luxir | Elasticsearch (Lucene 10.5) |
|---|---:|---:|---:|
| Stored fields | 0.94 | 1.05 | 1.03 |
| Doc values / columns | 0.49 | 0.79 | 0.60 |
| Inverted index | 2.15 (all fields) | **3.35** (2.73 native `.lxd` + 0.62 Lucene104) | 0.95 (0.86 + 0.09 id format) |
| Geo points | 0.04 | 0.05 | 0.21 |
| Norms | 0.08 | 0.08 | 0.07 |
| **Total** | **3.69** | **5.32** | **2.86** |

Luxir is 1.44x stock Solr and 1.86x Elasticsearch; ES is 0.77x stock.

Correction to the earlier note: the Luxir index's Lucene104 fallback files
(0.62 GB) hold the `id` AND `alternatenames_raw` (terms over 255 bytes) —
the `.lxm` metadata lists every other field as native, including the
`alternatenames` text field.

Elasticsearch per-field (`_disk_usage`, top fields, MB):

| field | total | inverted | stored | doc values |
|---|---:|---:|---:|---:|
| _source | 948 | — | 948 | — |
| alternatenames.raw | 240 | 122 | — | 118 |
| location | 196 | — | — | 91 (+points) |
| name.raw | 194 | 109 | — | 85 |
| asciiname.raw | 188 | 105 | — | 83 |
| _id | 167 | 85 | 81 | — |
| alternatenames (text) | 145 | 138 | — | — |
| name (text) | 110 | 98 | — | — |
| asciiname (text) | 105 | 93 | — | — |
| geonameid | 79 | — | — | 34 |
| _seq_no (ES-internal) | 74 | — | — | 29 |

ES all-fields totals: inverted 0.94, stored 1.03, doc values 0.60, points
0.21, norms 0.07 GB.

### Config-level size dial, measured (Nov 17)

Full corpus re-indexed with 12 `*_raw` string fields and `_version_` set to
`indexed="false"` (docValues kept, so facets/sorts stay native; term queries on
those fields stop working). One segment:

| | Full schema | Slim schema |
|---|---:|---:|
| `.lxd` native postings | 2.62 GB | **2.24 GB** |
| `.lxs` stored fields | 1.05 GB | 1.03 GB |
| `.lxv` columns | 0.79 GB | 0.74 GB |
| Lucene104 fallback (`id` + `alternatenames_raw`) | 0.62 GB | 0.61 GB |
| **Total** | **5.32 GB** | **4.91 GB** |

The whole dial saves 410 MB (7.7%). The format cost is intrinsic to the native
text postings (alignment, framing, per-block impact floats, trie terms), not
to schema fat. The codec-level levers remain the real path: native docs-only /
norms-free text fields (today `nativeCandidate` requires positions+norms
exactly, so `omitPositions`/`omitNorms` push a field onto the Lucene fallback),
the DocStream TODOs, impact compaction, trie-vs-FST dictionary size, the
255-byte term limit (`alternatenames_raw`), and a native delete path for the
unique key.

## Notes

- Solr bulk and sort numbers include the adapter; the adapter is a single
  Python process on the same machine as Rally and the engine.
- ES data dir kept (distros/elasticsearch-9.5.6/data), 2.6 GB; Solr core kept
  (bench-work/solr-home/geonames), 5.0 GB.
- Force-merge needed the polling fix: Rally's ES client times out any request
  at 10 s and the default force-merge runner aborts on that timeout. Race
  history: first ES full race (`5246dcea`) aborted at force-merge after
  indexing; the fix is the `polling` mode plus the adapter's background
  optimize and `/_tasks` fake.
