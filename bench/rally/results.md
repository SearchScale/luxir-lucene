# Geonames 1% comparison

Same Rally challenge, one engine at a time, both through the adapter on `127.0.0.1:19200`. Not `--test-mode`.

`ingest_percentage=1` is 1% of each of the 8 file slices, rounded up to whole bulks: **120,000 documents** (bulk size 5,000). The full corpus is 11,396,503. OpenSearch was not run.

## Setup

| | Elasticsearch | Solr+Luxir |
|---|---|---|
| Race | `2f00ec27-e921-41df-8626-3df9fe89a22f` (353 s) | `ede173aa-4431-473d-9e4d-e6b02b33eb5c` (363 s) |
| Version | 9.7.0-SNAPSHOT, Lucene 10.5.2, build `f6b357fbf7a0` | 11.0.0-SNAPSHOT `d2d7cf1edd`, Lucene 10.4.0 |
| Heap | 8g, bundled JDK 27.0.1 | 8g, JDK 22.0.2, `libluxir_lucene.so` |
| Index | 120,000 docs, 1 shard, 0 replicas, 1 segment, store 37 MB | 120,000 docs, 1 segment (`_5_Luxir_0.lxd` plus Lucene 10.4 postings) |
| Error rate | 0 on every operation | 0 on every operation |

Rally's own report says cluster version 7.17.0 for both, because the adapter answers `GET /` with that version. The versions above are from the engines themselves.

Indexing used 8 clients. Each search used 8 clients, 10 seconds of warmup, then 30 seconds of measurement. No target throughput. Force-merge to 1 segment ran before the searches.

Solr bulk time includes the adapter rewriting each bulk into Solr JSON (`id` = `geonameid`, location `[lon,lat]` to `"lat,lon"`, `.raw` fields to `_raw`). Elasticsearch bulk is proxied unchanged through the same process.

## Indexing speed

Wall-clock rate is 120,000 documents divided by the time until the last of the 8 clients finished.

| | Elasticsearch | Solr+Luxir |
|---|---:|---:|
| Last client finished | 3.75 s | 6.50 s |
| Indexing speed | 32,000 docs/s | 18,500 docs/s |
| Bulk request, 50th percentile | 889 ms | 2,014 ms |
| Bulk request, 90th percentile | 2,020 ms | 2,707 ms |
| Bulk request, 100th percentile | 2,068 ms | 2,931 ms |

A bulk request is 5,000 documents. There were only 24 of them, so Rally's 1-second throughput buckets are a short, uneven sample. Rally's published mean throughput was 10,517 docs/s for Elasticsearch and 22,501 docs/s for Solr+Luxir. That mean is not the indexing speed: Elasticsearch finished the same 120,000 documents in less time. Use the wall-clock row.

## Query latency and throughput

Latency is the time for one search with 8 clients in flight. Throughput is Rally's mean over the 30-second measurement window.

| Operation | ES p50 | Solr p50 | ES p99 | Solr p99 | ES ops/s | Solr ops/s |
|---|---:|---:|---:|---:|---:|---:|
| match-all | 47.0 ms | 45.0 ms | 52.3 ms | 49.0 ms | 161 | 172 |
| term `country_code.raw:AT` | 46.0 ms | 44.9 ms | 51.1 ms | 49.9 ms | 169 | 173 |
| phrase `name:"Sankt Georgen"` | 45.8 ms | 44.7 ms | 51.1 ms | 49.6 ms | 169 | 174 |
| population desc | 47.4 ms | 47.8 ms | 52.6 ms | 51.6 ms | 163 | 164 |
| population asc | 46.2 ms | 46.9 ms | 51.9 ms | 51.2 ms | 168 | 167 |
| geonameid desc | 47.7 ms | 47.3 ms | 52.6 ms | 51.9 ms | 164 | 165 |
| geonameid asc | 46.7 ms | 47.0 ms | 51.6 ms | 51.8 ms | 167 | 166 |

Eight clients at about 46 ms land near 170 ops/s (`8 / 0.046`). Both engines sit on that line. At 120,000 documents the search numbers do not separate the engines; the adapter and the client concurrency dominate.

## Hit counts

Untimed checks after each race. Totals match.

| Query | Hits |
|---|---:|
| match-all | 120,000 |
| term `country_code.raw:AT` | 0 |
| term `country_code.raw:US` (not in the race) | 15,000 |
| term `country_code.raw:AD` (not in the race) | 3,211 |
| phrase `name:"Sankt Georgen"` | 0 |

The raced term and phrase are empty on this 1% slice. `US` and `AD` were checked only to confirm both engines indexed the same documents. On Solr, `country_code_raw:AD` and `sort=population desc` took the native Luxir path (`luxirCodec: native`). The top population hit was geonameid 1784764, population 51,200,000.

Disk stayed at about 50 GiB free.
