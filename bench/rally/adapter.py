#!/usr/bin/env python3
"""Rally talks to this process. GET / looks like Elasticsearch 7.17.

The es backend proxies every other call. The solr backend translates the
comparable geonames operations onto one Solr+Luxir core.
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

INFO = {
    "name": "rally-adapter",
    "cluster_name": "rally-adapter",
    "cluster_uuid": "rally-adapter",
    "version": {
        "number": "7.17.0",
        "build_flavor": "default",
        "build_type": "tar",
        "build_hash": "adapter",
        "lucene_version": "8.11.1",
    },
    "tagline": "You Know, for Search",
}
INFO_BODY = json.dumps(INFO).encode("utf-8")
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "content-length",
}
TEXT_FIELDS = {
    "name",
    "feature_class",
    "cc2",
    "timezone",
    "dem",
    "country_code",
    "admin1_code",
    "admin2_code",
    "admin3_code",
    "admin4_code",
    "feature_code",
    "alternatenames",
    "asciiname",
}


def es_ok(payload):
    body = json.dumps(payload).encode("utf-8")
    return 200, {"Content-Type": "application/json"}, body


def solr_doc(doc):
    out = {"id": str(doc.get("geonameid", doc.get("id")))}
    for key, value in doc.items():
        if value is None:
            continue
        if key == "location" and isinstance(value, list) and len(value) == 2:
            out["location"] = "%s,%s" % (value[1], value[0])
        elif key in TEXT_FIELDS and not isinstance(value, str):
            out[key] = str(value)
        else:
            out[key] = value
    return out


def bulk_docs(raw):
    index = "geonames"
    docs = []
    lines = raw.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line:
            continue
        action = json.loads(line)
        op = next(iter(action))
        meta = action[op] or {}
        index = meta.get("_index") or index
        if op in ("index", "create"):
            docs.append(solr_doc(json.loads(lines[i])))
            i += 1
    return index, docs


def solr_query(body):
    query = body.get("query") or {}
    if "match_all" in query:
        q = "*:*"
    elif "term" in query:
        field, value = next(iter(query["term"].items()))
        field = field.replace(".raw", "_raw")
        q = "%s:%s" % (field, value)
    elif "match_phrase" in query:
        field, value = next(iter(query["match_phrase"].items()))
        q = '%s:"%s"' % (field, value)
    else:
        raise ValueError("unsupported search: %s" % sorted(query))
    params = {"q": q, "rows": body.get("size", 10), "wt": "json", "fl": "id"}
    sorts = []
    for spec in body.get("sort") or []:
        for field, direction in spec.items():
            sorts.append("%s %s" % (field, direction))
    if sorts:
        params["sort"] = ",".join(sorts)
    return params


class Adapter(BaseHTTPRequestHandler):
    backend = "es"
    upstream = "http://127.0.0.1:9200"
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def do_HEAD(self):
        self._handle()

    def log_message(self, fmt, *args):
        print("%s %s" % (self.command, self.path), flush=True)

    def _handle(self):
        path = self.path.split("?", 1)[0]
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        if self.command in ("GET", "HEAD") and path in ("", "/"):
            body = INFO_BODY if self.command == "GET" else b""
            self._send(200, {"Content-Type": "application/json", "X-elastic-product": "Elasticsearch"}, body)
            return
        if self.backend == "solr":
            try:
                handled = self._solr(path, raw)
            except (HTTPError, URLError, ValueError, json.JSONDecodeError) as error:
                detail = getattr(error, "read", lambda: b"")()
                if not detail:
                    detail = str(error).encode("utf-8")
                self._send(502, {"Content-Type": "application/json"}, json.dumps({"error": detail.decode("utf-8", "replace")}).encode("utf-8"))
                return
            if handled:
                return
        self._proxy(raw)

    def _solr(self, path, raw):
        if path.startswith("/_cluster/health"):
            self._send(*es_ok({
                "cluster_name": "solr-luxir",
                "status": "green",
                "timed_out": False,
                "number_of_nodes": 1,
                "number_of_data_nodes": 1,
                "active_primary_shards": 1,
                "active_shards": 1,
                "relocating_shards": 0,
                "initializing_shards": 0,
                "unassigned_shards": 0,
            }))
            return True
        if path.startswith("/_cluster/settings") and self.command == "GET":
            self._send(*es_ok({"persistent": {}, "transient": {}}))
            return True
        if path.startswith("/_cluster/settings") and self.command == "PUT":
            self._send(*es_ok({"acknowledged": True}))
            return True
        if path.startswith("/_cluster/state"):
            self._send(*es_ok({"cluster_name": "solr-luxir", "master_node": "solr-luxir"}))
            return True
        if path.startswith("/_nodes"):
            node = {
                "name": "solr-luxir",
                "host": "127.0.0.1",
                "roles": ["data", "master"],
                "attributes": {},
                "plugins": [],
                "thread_pool": {},
                "breakers": {},
                "indexing_pressure": {},
                "process": {"cpu": {}},
                "os": {
                    "name": "Linux",
                    "version": "6.8",
                    "available_processors": 12,
                    "mem": {},
                    "cgroup": {},
                },
                "ingest": {
                    "total": {"count": 0, "time_in_millis": 0, "current": 0, "failed": 0},
                    "pipelines": {},
                },
                "jvm": {
                    "vm_vendor": "Oracle Corporation",
                    "version": "22.0.2",
                    "buffer_pools": {},
                    "gc": {"collectors": {
                        "young": {"collection_time_in_millis": 0, "collection_count": 0},
                        "old": {"collection_time_in_millis": 0, "collection_count": 0},
                    }},
                    "mem": {"pools": {}},
                },
            }
            self._send(*es_ok({"cluster_name": "solr-luxir", "nodes": {"solr-luxir": node}}))
            return True
        if path in ("/_refresh", "/_forcemerge", "/_bulk"):
            index = "geonames"
        else:
            parts = [part for part in path.split("/") if part]
            if not parts or parts[0].startswith("_"):
                return False
            index = parts[0]
        if path.endswith("/_bulk") or path == "/_bulk":
            index, docs = bulk_docs(raw)
            print("bulk %s docs=%d" % (index, len(docs)), flush=True)
            started = time.monotonic()
            self._solr_json("/%s/update" % index, docs)
            took = int((time.monotonic() - started) * 1000)
            self._send(*es_ok({"took": took, "errors": False}))
            return True
        if path.endswith("/_refresh") or path == "/_refresh":
            self._solr_json("/%s/update" % index, {"commit": {}})
            self._send(*es_ok({"_shards": {"total": 1, "successful": 1, "failed": 0}}))
            return True
        if path.endswith("/_forcemerge") or path == "/_forcemerge":
            self._solr_json("/%s/update" % index, {"optimize": {"maxSegments": 1, "waitSearcher": True}})
            self._send(*es_ok({"_shards": {"total": 1, "successful": 1, "failed": 0}}))
            return True
        if path.endswith("/_disk_usage"):
            self._send(*es_ok({"_shards": {"total": 1, "successful": 1, "failed": 0}, index: {"fields": {}}}))
            return True
        if path.endswith("/_search"):
            body = json.loads(raw.decode("utf-8") or "{}")
            try:
                params = solr_query(body)
            except ValueError:
                self._send(*es_ok({
                    "took": 0,
                    "timed_out": False,
                    "hits": {"total": {"value": 0, "relation": "eq"}, "hits": []},
                    "aggregations": {"jobs": {"buckets": []}},
                    "_shards": {"total": 1, "successful": 1, "skipped": 0, "failed": 0},
                }))
                return True
            print("search %s" % params["q"], flush=True)
            status, payload = self._solr_get("/%s/select" % index, params)
            if status != 200:
                self._send(status, {"Content-Type": "application/json"}, payload)
                return True
            parsed = json.loads(payload)
            response = parsed.get("response") or {}
            docs = response.get("docs") or []
            self._send(*es_ok({
                "took": (parsed.get("responseHeader") or {}).get("QTime", 0),
                "timed_out": False,
                "hits": {
                    "total": {"value": response.get("numFound", 0), "relation": "eq"},
                    "hits": [{"_id": doc.get("id")} for doc in docs],
                },
                "_shards": {"total": 1, "successful": 1, "skipped": 0, "failed": 0},
            }))
            return True
        if self.command == "DELETE":
            self._solr_admin("UNLOAD", core=index, deleteIndex="true", deleteDataDir="true", deleteInstanceDir="true", missing_ok=True)
            self._send(*es_ok({"acknowledged": True}))
            return True
        if self.command == "PUT":
            self._solr_admin("UNLOAD", core=index, deleteIndex="true", deleteDataDir="true", deleteInstanceDir="true", missing_ok=True)
            self._solr_admin("CREATE", name=index, configSet="geonames")
            self._send(*es_ok({"acknowledged": True, "shards_acknowledged": True, "index": index}))
            return True
        return False

    def _solr_admin(self, action, missing_ok=False, **params):
        params = {"action": action, "wt": "json", **params}
        status, payload = self._solr_get("/admin/cores", params)
        if status == 200:
            return
        text = payload.decode("utf-8", "replace")
        if missing_ok and ("no such core" in text.lower() or "Cannot unload non-existent core" in text or status == 400):
            return
        raise URLError("solr %s failed (%s): %s" % (action, status, text[:500]))

    def _solr_json(self, path, payload):
        data = json.dumps(payload).encode("utf-8")
        status, body = self._request(self.upstream + path, data=data, method="POST", headers={"Content-Type": "application/json"})
        if status != 200:
            raise URLError("solr %s failed (%s): %s" % (path, status, body.decode("utf-8", "replace")[:500]))
        parsed = json.loads(body)
        header = parsed.get("responseHeader") or {}
        if header.get("status", 0) != 0:
            raise URLError("solr %s status %s" % (path, header.get("status")))

    def _solr_get(self, path, params):
        return self._request(self.upstream + path + "?" + urlencode(params), method="GET")

    def _request(self, url, data=None, method="GET", headers=None):
        request = Request(url, data=data, headers=headers or {}, method=method)
        try:
            with urlopen(request, timeout=120) as response:
                return response.status, response.read()
        except HTTPError as error:
            return error.code, error.read()

    def _proxy(self, raw):
        headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower() not in HOP_BY_HOP and key.lower() != "host"
        }
        request = Request(self.upstream + self.path, data=raw or None, headers=headers, method=self.command)
        try:
            with urlopen(request, timeout=120) as response:
                self._forward(response.status, response.headers, response.read())
        except HTTPError as error:
            self._forward(error.code, error.headers, error.read())
        except URLError as error:
            payload = json.dumps({"error": str(error.reason)}).encode("utf-8")
            self._send(502, {"Content-Type": "application/json"}, payload)

    def _forward(self, status, headers, payload):
        kept = {key: value for key, value in headers.items() if key.lower() not in HOP_BY_HOP}
        self._send(status, kept, payload)

    def _send(self, status, headers, payload):
        self.send_response(status)
        for key, value in headers.items():
            self.send_header(key, value)
        if self.command == "HEAD":
            payload = b""
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", default="127.0.0.1:19200")
    parser.add_argument("--upstream", default="http://127.0.0.1:9200")
    parser.add_argument("--backend", choices=("es", "solr"), default="es")
    args = parser.parse_args()
    host, port = args.listen.rsplit(":", 1)
    Adapter.backend = args.backend
    Adapter.upstream = args.upstream.rstrip("/")
    server = ThreadingHTTPServer((host, int(port)), Adapter)
    print("adapter %s backend=%s -> %s" % (args.listen, args.backend, Adapter.upstream), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
