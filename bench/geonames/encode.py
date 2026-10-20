# Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
# SPDX-License-Identifier: Apache-2.0
"""Geonames NDJSON (bz2) to the batches file the benchmark client reads: [int32 BE length][JSON array] per batch.
Each doc gets id = geonameid and location as "lat,lon" (Solr's form of the [lon, lat] array)."""
import bz2, json, struct, sys
src, out, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
n = batches = 0
seen = set()
with bz2.open(src, 'rt', encoding='utf-8') as f, open(out, 'wb') as o:
    batch = []
    def flush():
        global batches
        b = json.dumps(batch, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        o.write(struct.pack('>i', len(b))); o.write(b); batches += 1
    for line in f:
        line = line.strip()
        if not line: continue
        d = json.loads(line)
        gid = d['geonameid']
        if gid in seen: raise SystemExit(f'duplicate geonameid {gid}')
        seen.add(gid)
        d['id'] = str(gid)
        loc = d.pop('location', None)
        if loc: d['location'] = f'{loc[1]},{loc[0]}'
        batch.append(d); n += 1
        if len(batch) == size: flush(); batch = []
    if batch: flush()
print(f'{n} docs in {batches} batches')
