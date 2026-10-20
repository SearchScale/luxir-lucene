# Geonames benchmark

Stock Solr against Solr with Luxir on the 11.4M-document geonames corpus, both
cores in one standalone Solr instance.

- `encode.py`: turns the corpus into the batches file the benchmark client
  sends (1000 documents per JSON array; `id` = `geonameid`, `location` as
  `"lat,lon"`).
- `configsets/gn_stock`, `configsets/gn_luxir`: the same fields; the Luxir
  core uses Luxir field types, codec and components, a 4 GB RAM buffer and the
  parallel-flush update chain. Neither has an update log.
- `tasks.tsv`: 212 requests in 17 categories (category, then the URL
  parameters).
- `run.sh`: indexing (two rounds per core, alternating), a merge of each core
  to one segment, then verification, median latency (one client thread),
  per-category throughput and mixed throughput.

## Data

The corpus is `documents-2.json.bz2` from the geonames track of Rally and
OpenSearch Benchmark (<https://rally-tracks.elastic.co/geonames/documents-2.json.bz2>),
derived from [GeoNames](https://www.geonames.org/) data (CC BY 4.0).

```bash
python3 encode.py documents-2.json.bz2 geonames.batches 1000
```

## Running

```bash
SOLR_DIST=/path/to/solr-dist LUXIR_LIB=/path/to/libluxir_lucene.so \
  BATCHES=/path/to/geonames.batches ./run.sh | tee run.log
```

`SOLR_DIST` is a Solr distribution built from the Solr branch with the
luxir-codec module: `./gradlew :solr:packaging:devFull` gives
`solr/packaging/build/dev`, whose module `lib` already holds the luxir-lucene
jar. The run needs about 20 GB of disk and takes about 45 minutes on 12 cores.
The verification step checks every request: numFound on both cores, the Luxir
core's native ids against its own Lucene path (`luxirCodec=false`), and facet
counts.
