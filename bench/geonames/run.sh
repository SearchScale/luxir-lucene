#!/bin/bash
# Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
# SPDX-License-Identifier: Apache-2.0
#
# Geonames on one standalone Solr: a stock core (gn_stock) against a Luxir core (gn_luxir).
#   1. indexing: 12 client threads, 1000-doc batches, two rounds per core, alternating;
#   2. both cores merged to one segment;
#   3. tasks.tsv: verification (numFound, ids, facets), median latency from one client
#      thread, per-category and mixed throughput from several.
#
# Required:
#   SOLR_DIST  a Solr distribution with the luxir-codec module (modules/luxir-codec/lib holds
#              solr-luxir-codec and luxir-lucene jars)
#   LUXIR_LIB  libluxir_lucene.so
#   BATCHES    the batches file written by encode.py
# Optional: WORK (Solr home and logs, default ./work), PORT (8983), HEAP (12g), THREADS (12),
#   QUERY_THREADS (8), JAVA_HOME (JDK 22 or later).
set -euo pipefail
: "${SOLR_DIST:?set SOLR_DIST}" "${LUXIR_LIB:?set LUXIR_LIB}" "${BATCHES:?set BATCHES}"
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${WORK:-$PWD/work}; PORT=${PORT:-8983}; HEAP=${HEAP:-12g}
THREADS=${THREADS:-12}; QUERY_THREADS=${QUERY_THREADS:-8}
URL=http://localhost:$PORT/solr

mkdir -p "$WORK/home/configsets" "$WORK/logs"
cp "$SOLR_DIST/server/solr/solr.xml" "$WORK/home/"
rm -rf "$WORK/home/configsets/gn_stock" "$WORK/home/configsets/gn_luxir"
cp -r "$HERE/configsets/gn_stock" "$HERE/configsets/gn_luxir" "$WORK/home/configsets/"

export SOLR_MODULES=luxir-codec SOLR_SECURITY_MANAGER_ENABLED=false SOLR_LOGS_DIR="$WORK/logs"
export SOLR_OPTS="--enable-native-access=ALL-UNNAMED -Dluxir.lucene.library=$LUXIR_LIB"
"$SOLR_DIST/bin/solr" start --user-managed -p "$PORT" --solr-home "$WORK/home" -m "$HEAP" > /dev/null
trap '"$SOLR_DIST/bin/solr" stop -p "$PORT" > /dev/null 2>&1 || true' EXIT
for _ in $(seq 120); do curl -sf "$URL/admin/info/system" > /dev/null && break; sleep 2; done
SPID=$(cat "$SOLR_DIST/bin/solr-$PORT.pid" 2>/dev/null || pgrep -f "jetty.port=$PORT" | head -1)
cpu() { awk '{print $14 + $15}' "/proc/$SPID/stat"; }
bench() { (cd "$ROOT" && ./gradlew -q solrBench -PsolrBenchArgs="$*"); }

echo "== indexing ($(date +%H:%M:%S))"
for round in 1 2; do
  for c in gn_stock gn_luxir; do
    curl -s "$URL/admin/cores?action=UNLOAD&core=$c&deleteDataDir=true&deleteInstanceDir=true" > /dev/null
    sleep 2; rm -rf "${WORK:?}/home/$c"
    curl -s "$URL/admin/cores?action=CREATE&name=$c&configSet=$c&wt=json" | grep -q '"status":0' \
      || { echo "creating $c failed"; exit 1; }
    sync; c0=$(cpu)
    echo -n "round $round: "
    bench lu-index solr "$URL" "$c" "$BATCHES" "$THREADS"
    segments=$(curl -s "$URL/$c/admin/segments?wt=json" \
      | python3 -c "import json,sys; print(len(json.load(sys.stdin)['segments']))")
    echo "  server CPU seconds: $(( ($(cpu) - c0) / 100 )); segments: $segments;" \
      "index $(du -sh "$WORK/home/$c/data/index" | cut -f1)"
    sleep 20
  done
done

echo "== merge to 1 segment ($(date +%H:%M:%S))"
for c in gn_stock gn_luxir; do
  t0=$(date +%s); c0=$(cpu)
  curl -s -m 3600 "$URL/$c/update?optimize=true&maxSegments=1&waitSearcher=true" > /dev/null
  echo "$c: $(( $(date +%s) - t0 ))s, server CPU seconds $(( ($(cpu) - c0) / 100 )),"\
    "index $(du -sh "$WORK/home/$c/data/index" | cut -f1)"
done

echo "== queries ($(date +%H:%M:%S))"
WSEARCH_PERCAT_SECS=10 bench gn-search "$URL" "$HERE/tasks.tsv" "$QUERY_THREADS" 30 gn_stock gn_luxir
echo "#### done $(date +%H:%M:%S)"
