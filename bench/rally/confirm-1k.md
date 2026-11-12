# 1,000-document confirmation

Elasticsearch and Solr+Luxir have each been raced alone. OpenSearch has not been run.

These figures are a sanity check from Rally `--test-mode` (8 iterations, 1 client). They are not the comparison.

## Engine

- Distribution: `/home/puneet/code/elasticsearch/build/distribution/local/elasticsearch-9.7.0-SNAPSHOT`
- Real version: 9.7.0-SNAPSHOT, Lucene 10.5.2, build `f6b357fbf7a0`
- Rally's own report says 7.17.0 because the adapter answers `GET /` with that version
- Security off, one node, heap 8g, bundled JDK 27.0.1
- Race id: `ef7a9afe-441c-414f-bd72-d31e7f68660d` (14 seconds, every operation error rate 0)
- Index `geonames`: 1,000 documents, 1 shard, 0 replicas, 1 segment

Disk watermarks were raised to absolute free-space floors (low 40gb, high 25gb, flood stage 15gb). The default 90% high watermark refused every shard because the 916 GiB disk is 94% full even with 53 GiB free.

## Untimed hit counts

Exact totals (`eq`), no failed shards:

| Operation | Hits |
|---|---:|
| match-all | 1000 |
| term `country_code.raw:AT` | 0 |
| phrase `name:"Sankt Georgen"` | 0 |
| population descending | 1000 |
| population ascending | 1000 |
| geonameid descending | 1000 |
| geonameid ascending | 1000 |

The term and phrase queries match nothing in this 1,000-document prefix. The file starts with Andorra. Both still returned an exact total and a success status.

## Solr+Luxir

- Distribution: `solr/solr/packaging/build/dev`, Solr 11.0.0-SNAPSHOT `d2d7cf1edd`, Lucene 10.4.0
- JDK 22.0.2, security manager off, heap 8g, `libluxir_lucene.so`, `LD_LIBRARY_PATH=/opt/gcc/lib64`
- Core `geonames` from the Luxir geonames configset. `country_code.raw` is queried as `country_code_raw`. Location is stored as `lat,lon`. Document id is `geonameid`.
- Race id: `d8ac980b-8ee2-4c7b-bf9c-4c3628901c59` (13 seconds, every operation error rate 0)
- Index: 1,000 documents, one segment. Segment files include `_0_Luxir_0.lxd` and the `.lxp` / `.lxs` / `.lxv` sidecars, plus a Lucene 10.4 postings file for fields that stay on Lucene.

Untimed exact totals (`numFoundExact`):

| Operation | Hits |
|---|---:|
| match-all | 1000 |
| term `country_code_raw:AT` | 0 |
| phrase `name:"Sankt Georgen"` | 0 |
| population and geonameid sorts | 1000 |

The node is still up on port 8983. The adapter on port 19200 is aimed at it.
