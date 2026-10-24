// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

/*
 * lxl.cpp - see lxl.h.
 *
 * Writing drives Luxir's PostingsWriter/TextWriter directly (the same calls
 * FullTextHandler::flushPositions makes) into a RAMDir. Reading opens a
 * PostingsReader over caller-owned regions through a read-only Directory,
 * and query execution wraps that PostingsReader in a one-segment IndexReader
 * so Luxir's own weights, bulk scorers and collectors run unchanged.
 *
 * Copyright 2026 luxir-lucene contributors. Apache-2.0.
 */
#include "lxl.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <numeric>
#include <cstring>
#include <deque>
#include <exception>
#include <limits>
#include <map>
#include <memory_resource>
#include <optional>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include <boost/unordered/unordered_flat_map.hpp>

#include "luxir/analysis/Analyzer.h"
#include "luxir/api/luxir_index.hpp"
#include "luxir/api/luxir_types.hpp"
#include "luxir/index/ColumnIndexing.h"
#include "luxir/index/CommitSnapshot.h"
#include "luxir/index/IntColWriter.h"
#include "luxir/index/Inverter.h"
#include "luxir/index/NormsWriter.h"
#include "luxir/index/PointsWriter.h"
#include "luxir/index/PostingsWriter.h"
#include "luxir/index/StoredFieldsWriter.h"
#include "luxir/query/AllQuery.h"
#include "luxir/query/BooleanQuery.h"
#include "luxir/query/BoostQuery.h"
#include "luxir/query/ConstantScoreQuery.h"
#include "luxir/query/MatchNoDocsQuery.h"
#include "luxir/query/NumericPredicateQuery.h"
#include "luxir/query/PhraseQuery.h"
#include "luxir/query/PointsMaterialize.h"
#include "luxir/query/QueryPrep.h"
#include "luxir/query/PrefixQuery.h"
#include "luxir/query/Query.h"
#include "luxir/query/TermInSetQuery.h"
#include "luxir/query/TermQuery.h"
#include "luxir/reader/DfaIntersectEnum.h"
#include "luxir/reader/DocsEnum.h"
#include "luxir/reader/IntColReader.h"
#include "luxir/reader/NormsReader.h"
#include "luxir/reader/PointsReader.h"
#include "luxir/reader/PosEnum.h"
#include "luxir/reader/PostingsReader.h"
#include "luxir/reader/StoredFieldsReader.h"
#include "luxir/reader/TermsEnum.h"
#include "luxir/schema/FieldType.h"
#include "luxir/schema/Schema.h"
#include "luxir/search/Collector.h"
#include "luxir/search/DocSet.h"
#include "luxir/search/IndexReader.h"
#include "luxir/store/Directory.h"
#include "luxir/util/automaton/ByteDfa.h"

using namespace luxir;

namespace {

thread_local std::string lastError;

int32_t fail(const char* what) {
  lastError = what;
  return -1;
}

template <typename F>
int32_t guarded(F&& f) {
  try {
    return f();
  } catch (const std::exception& e) {
    return fail(e.what());
  } catch (...) {
    return fail("unknown native exception");
  }
}

template <typename T, typename F>
T* guardedPtr(F&& f) {
  try {
    return f();
  } catch (const std::exception& e) {
    fail(e.what());
  } catch (...) {
    fail("unknown native exception");
  }
  return nullptr;
}

// ---- read-only directory over caller-owned regions ---------------------------------

class RegionFile final : public InputFile {
  const char* start;
  size_t len;

public:
  RegionFile(const char* start, size_t len) : start(start), len(len) {}
  size_t size() override { return len; }
  std::string_view read() override { return {start, len}; }
  InputStream getInputStream() override { return InputStream(start, start + len); }
};

class RegionDir final : public Directory {
  std::map<std::string, std::shared_ptr<InputFile>, std::less<>> files;

  [[noreturn]] static void readOnly() { throw std::logic_error("lxl: read-only directory"); }

public:
  void add(std::string name, const char* data, size_t len) {
    files.emplace(std::move(name), std::make_shared<RegionFile>(data, len));
  }
  void listFiles(std::vector<FileInfo>& target) override {
    for (auto& [name, f] : files) target.push_back({name, (uint64_t)f->size()});
  }
  std::shared_ptr<InputFile> openFile(std::string_view name, bool) override {
    auto it = files.find(name);
    return it == files.end() ? nullptr : it->second;
  }
  // The region's bytes, hashed as Luxir's RAM directory does (Lucene verifies its own files).
  std::shared_ptr<ChecksumReader> openChecksum(std::string_view name, bool) override {
    auto file = openFile(name, false);
    if (!file) return nullptr;
    class RegionChecksumReader final : public ChecksumReader {
      std::shared_ptr<InputFile> file;

    public:
      explicit RegionChecksumReader(std::shared_ptr<InputFile> file) : file(std::move(file)) {}
      uint64_t size() const override { return file->size(); }
      std::shared_ptr<InputFile> map() override { return file; }
      FileCheck checksum(const std::function<void(size_t)>& progress) override {
        auto data = file->read();
        if (progress) progress(data.size());
        return {(uint64_t)data.size(), XXH3_64bits(data.data(), data.size())};
      }
    };
    return std::make_shared<RegionChecksumReader>(std::move(file));
  }
  std::unique_ptr<File> createFile(const std::string_view) override { readOnly(); }
  bool deleteFile(const std::string_view) override { readOnly(); }
  void finishFile(File&) override { readOnly(); }
  void renameFile(std::string_view, std::string_view) override { readOnly(); }
  void clear() override { readOnly(); }
  void linkFile(Directory&, std::string_view) override { readOnly(); }
};

constexpr uint64_t SEG_ID = 0;

// The name Luxir's schema indexes a native text field under, as LuxirTextField.schemaName (Java)
// maps it: itself when Luxir can hold it (ASCII letters first, then letters, digits and single
// underscores, at most 127), else "lx" + its UTF-8 bytes in hex.
std::string schemaName(std::string_view field) {
  bool native = !field.empty() && field.size() <= 127 && !field.contains("__");
  for (size_t i = 0; native && i < field.size(); i++) {
    char c = field[i];
    bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    native = letter || (i > 0 && ((c >= '0' && c <= '9') || c == '_'));
  }
  if (native) return std::string(field);
  static constexpr char HEX[] = "0123456789abcdef";
  std::string name = "lx";
  for (unsigned char b : field) {
    name += HEX[b >> 4];
    name += HEX[b & 15];
  }
  return name;
}

// A field's header in `pr` (decoded once and kept by the reader), or none. Fields are found by
// their own name, else (native text fields) by their schema name.
std::optional<SegFieldInfo> findField(PostingsReader& pr, std::string_view name) {
  const PostingsReader::Field* field = pr.fieldInfo(name);
  if (field == nullptr) {
    std::string indexed = schemaName(name);
    if (indexed != name) field = pr.fieldInfo(indexed);
  }
  if (field == nullptr) return std::nullopt;
  return SegFieldInfo(*field);
}

}  // namespace

// ---- writer ---------------------------------------------------------------------------

// Values of one native string field, buffered while documents are indexed: each distinct
// term once (in blocks), and per value its doc and term id. At flush it yields both the
// doc-values ordinal column with its dictionary and the docs-only postings.
struct StrBuf {
  std::string name;
  boost::unordered_flat_map<std::string_view, int32_t> ids;
  std::vector<std::unique_ptr<char[]>> blocks;
  size_t blockUsed = 0, blockCap = 0, termBytes = 0;
  std::vector<std::string_view> terms;  // by id
  std::vector<int32_t> docs, vals;      // per value, in doc order
  std::vector<int32_t> rank;            // id -> sorted rank (after sort())
  std::vector<int32_t> byRank;          // sorted rank -> id

  void add(int32_t doc, std::string_view term) {
    if (!docs.empty() && doc < docs.back()) throw std::invalid_argument("lxl: string values out of doc order");
    auto it = ids.find(term);
    int32_t id;
    if (it != ids.end()) {
      id = it->second;
    } else {
      if (blocks.empty() || blockUsed + term.size() > blockCap) {
        blockCap = std::max<size_t>(64 << 10, term.size());
        blocks.push_back(std::make_unique<char[]>(blockCap));
        blockUsed = 0;
      }
      char* dst = blocks.back().get() + blockUsed;
      if (!term.empty()) std::memcpy(dst, term.data(), term.size());
      blockUsed += term.size();
      termBytes += term.size();
      std::string_view stored(dst, term.size());
      id = (int32_t)terms.size();
      terms.push_back(stored);
      ids.emplace(stored, id);
      rank.clear();  // a new term invalidates any earlier sort
    }
    docs.push_back(doc);
    vals.push_back(id);
  }

  void sort() {
    if (rank.size() == terms.size()) return;
    byRank.resize(terms.size());
    std::iota(byRank.begin(), byRank.end(), 0);
    // std::string_view compares as unsigned bytes: Lucene's BytesRef order
    std::sort(byRank.begin(), byRank.end(), [&](int32_t a, int32_t b) { return terms[(size_t)a] < terms[(size_t)b]; });
    rank.assign(terms.size(), 0);
    for (size_t r = 0; r < byRank.size(); r++) rank[(size_t)byRank[r]] = (int32_t)r;
  }

  int64_t ram() const {
    return (int64_t)(blocks.size() * blockCap + ids.bucket_count() * (sizeof(std::string_view) + sizeof(int32_t) + 1) +
                     terms.capacity() * sizeof(std::string_view) + (docs.capacity() + vals.capacity()) * 4 +
                     (rank.capacity() + byRank.capacity()) * 4);
  }
};

// Every Luxir segment records its documents' request (birth) versions, which order Luxir's own
// deletes against adds. Lucene applies deletes here, so all of a segment's documents share one.
constexpr uint64_t BIRTH_VERSION = 1;

struct lxl_writer {
  RAMDir dir;
  std::unique_ptr<PostingsWriter> ownPw;  // plain mode: Lucene inverts, we encode
  std::unique_ptr<Inverter> inverter;     // inverter mode: Luxir analyzes and inverts
  PostingsWriter* pw = nullptr;
  std::vector<Inverter::InputHandler*> handlers;  // by field ordinal (inverter mode)
  std::shared_ptr<PostingsReader> finished;       // the written segment, for statistics
  MemPool pool;
  PostingsWriter::IndexFieldInfo* fi = nullptr;
  std::unique_ptr<TextWriter> tw;
  NormsWriter::PreparedNorms prepared;
  std::vector<int32_t> normDocs;
  // current term, kept for endTerm
  std::string term;
  bool inTerm = false;
  // stored values (store mode), field ids rendered as names
  std::unique_ptr<StoredFieldsWriter> stored;
  std::deque<std::string> storedNames;  // deque: names are referenced while it grows
  // native string fields (inverter mode), by ordinal
  std::vector<std::unique_ptr<StrBuf>> strs;
  // finished output
  std::vector<std::string> names;
  std::vector<std::shared_ptr<InputFile>> files;

  explicit lxl_writer(int32_t maxDoc) {
    ownPw = std::make_unique<PostingsWriter>(dir, SEG_ID, maxDoc);
    pw = ownPw.get();
  }

  explicit lxl_writer(std::shared_ptr<Schema> schema) {
    inverter = std::make_unique<Inverter>(dir, SEG_ID, std::move(schema));
    inverter->updateVersions(BIRTH_VERSION);
    pw = &inverter->getPostingsWriter();
  }
};

namespace {

// NormsWriter::prepare/writeValues, fed from arrays instead of indexing streams.
void prepareNorms(lxl_writer& w, const int32_t* docs, const uint8_t* values, int32_t n) {
  auto& fi = *w.fi;
  auto& prepared = w.prepared;
  int32_t maxDoc = w.pw->getMaxDoc();
  prepared = NormsWriter::PreparedNorms();
  prepared.docsWithField = n;
  prepared.maxDoc = maxDoc;
  fi.numValues = n;
  w.normDocs.assign(docs, docs + n);
  if (n == 0) {
    fi.docsWithField = 0;
    fi.docsWithFieldEndLoc = {0, 0};
    fi.normsFormat = SegFieldInfo::NORMS_NONE;
    fi.normsLoc = {0, 0};
    fi.normsLen = 0;
    return;
  }
  prepared.flat = useDocIdIndexing(n, maxDoc);
  auto* bytes = (uint8_t*)w.pool.alloc((size_t)n);
  std::memcpy(bytes, values, (size_t)n);
  prepared.ordinalNorms = {bytes, (size_t)n};
  if (n < maxDoc) {
    RAMFile tempFile("norm-docs");
    OutputStream tempOut(&tempFile);
    MemPool scratch;
    DocsWriter dw(scratch, tempOut);
    for (int32_t i = 0; i < n; i++) dw.addDoc(docs[i]);
    dw.finish();
    tempOut.close();
    size_t bitsetLen = tempFile.size();
    char* bitsetBytes = w.pool.alloc(bitsetLen, screaming::BitSet::BLOCK_ALIGN);
    tempFile.copyTo(bitsetBytes);
    prepared.docsBitset.set(bitsetBytes + bitsetLen);
    prepared.hasBitset = true;

    auto outPtr = w.pw->getOutputStream();
    OutputStream& out = *outPtr;
    static constexpr char zeros[screaming::BitSet::BLOCK_ALIGN] = {};
    size_t pad = (screaming::BitSet::BLOCK_ALIGN - (out.size() & (screaming::BitSet::BLOCK_ALIGN - 1)))
                 & (screaming::BitSet::BLOCK_ALIGN - 1);
    if (pad > 0) out.write(zeros, pad);
    out.write(bitsetBytes, bitsetLen);
    fi.docsWithField = n;
    fi.docsWithFieldEndLoc = out.slocation();
  } else {
    fi.docsWithField = n;
    fi.docsWithFieldEndLoc = {0, 0};
  }
}

void writeNorms(lxl_writer& w) {
  auto& fi = *w.fi;
  auto& prepared = w.prepared;
  int32_t maxDoc = prepared.maxDoc;
  int32_t n = prepared.docsWithField;
  if (n == 0) return;
  auto outPtr = w.pw->getOutputStream();
  OutputStream& out = *outPtr;
  fi.normsLoc = seg_location(out.streamNumber, out.size());
  if (prepared.flat) {
    fi.normsFormat = SegFieldInfo::NORMS_FLAT;
    fi.normsLen = maxDoc;
    if (!prepared.hasBitset) {
      out.write((const char*)prepared.ordinalNorms.data(), (size_t)maxDoc);
    } else {
      static const char zeroes[4096] = {};
      auto zero = [&](int64_t len) {
        while (len > 0) {
          int64_t chunk = std::min<int64_t>(len, (int64_t)sizeof(zeroes));
          out.write(zeroes, (size_t)chunk);
          len -= chunk;
        }
      };
      int32_t next = 0;
      for (int32_t i = 0; i < n; i++) {
        int32_t doc = w.normDocs[(size_t)i];
        zero((int64_t)doc - next);
        out.write((char)prepared.ordinalNorms[(size_t)i]);
        next = doc + 1;
      }
      zero((int64_t)maxDoc - next);
    }
  } else {
    fi.normsFormat = SegFieldInfo::NORMS_SPARSE;
    fi.normsLen = n;
    out.write((const char*)prepared.ordinalNorms.data(), (size_t)n);
  }
}

}  // namespace

extern "C" {

int32_t lxl_abi_version(void) { return LXL_ABI_VERSION; }

const char* lxl_last_error(void) { return lastError.c_str(); }

lxl_writer* lxl_writer_new(int32_t max_doc) {
  return guardedPtr<lxl_writer>([&] { return new lxl_writer(max_doc); });
}

int32_t lxl_writer_start_field(lxl_writer* w, const char* name, int32_t name_len,
                               const int32_t* norm_docs, const uint8_t* norm_values,
                               int32_t num_norms) {
  return guarded([&] {
    if (w->tw) return fail("lxl: previous field not ended");
    // under the name native flush indexes text with, so every segment agrees (see schemaName)
    auto& fi = w->pw->addField(schemaName(std::string_view(name, (size_t)name_len)));
    fi.type = FieldType::TEXT;
    fi.flags = FieldType::INDEX_DOCS_FREQS_POSITIONS;
    w->fi = &fi;
    prepareNorms(*w, norm_docs, norm_values, num_norms);
    w->tw = std::make_unique<TextWriter>(*w->pw);
    w->tw->startField(&fi);
    w->tw->setNorms(w->prepared.textView());
    return 0;
  });
}

int32_t lxl_writer_term(lxl_writer* w, const uint8_t* term, int32_t term_len,
                        const int32_t* docs, const int32_t* freqs, int32_t num_docs,
                        const int32_t* positions, int32_t flags) {
  return guarded([&] {
    if (!w->tw) return fail("lxl: no field started");
    if (term_len > LXL_MAX_TERM) return fail("lxl: term longer than 255 bytes");
    TextWriter& tw = *w->tw;
    if (flags & LXL_TERM_START) {
      if (w->inTerm) return fail("lxl: term not ended");
      w->term.assign((const char*)term, (size_t)term_len);
      tw.startTerm(w->pw->copyTerm(w->term));
      w->inTerm = true;
    }
    if (!w->inTerm) return fail("lxl: term not started");
    const int32_t* pos = positions;
    for (int32_t i = 0; i < num_docs; i++) {
      int32_t doc = docs[i];
      tw.startDoc(doc);
      // Luxir stores strictly increasing positions; a repeated position (the
      // same term stacked at one position) is kept once.
      int32_t prev = -1;
      for (int32_t j = 0; j < freqs[i]; j++) {
        int32_t p = pos[j];
        if (p > prev) {
          tw.addPositionDelta(p - prev);
          prev = p;
        }
      }
      pos += freqs[i];
      tw.endDoc(doc);
    }
    if (flags & LXL_TERM_END) {
      tw.endTerm(w->pw->copyTerm(w->term));
      w->inTerm = false;
    }
    return 0;
  });
}

int32_t lxl_writer_end_field(lxl_writer* w) {
  return guarded([&] {
    if (!w->tw) return fail("lxl: no field started");
    if (w->inTerm) return fail("lxl: term not ended");
    w->tw->endField();
    w->tw.reset();
    writeNorms(*w);
    w->fi = nullptr;
    return 0;
  });
}

int32_t lxl_writer_finish(lxl_writer* w) {
  return guarded([&] {
    if (w->tw) return fail("lxl: field not ended");
    w->names.clear();
    if (w->inverter) {
      w->inverter->flush(&w->names);
    } else {
      if (w->stored) w->stored->finish(w->pw->getMaxDoc());
      VersionRunBuilder births;
      births.add(0, BIRTH_VERSION);
      w->pw->writeVersionRuns(births);
      w->pw->finish(&w->names);
    }
    // Luxir writes a segment for any documents, even with no fields; the codec reads no files as
    // "nothing stored here", so a segment without fields is dropped.
    if (!w->names.empty()) {
      w->finished = PostingsReader::create(w->dir, SEG_ID);
      if (w->finished->numFields() == 0) {
        w->finished.reset();
        w->names.clear();
      }
    }
    for (auto& name : w->names) {
      auto f = w->dir.openFile(name);
      if (!f) return fail("lxl: finished file missing");
      w->files.push_back(std::move(f));
    }
    return (int32_t)w->names.size();
  });
}

int32_t lxl_writer_file(lxl_writer* w, int32_t i, const char** name, const uint8_t** data,
                        int64_t* len) {
  if (i < 0 || (size_t)i >= w->files.size()) return fail("lxl: file index out of range");
  auto bytes = w->files[(size_t)i]->read();
  *name = w->names[(size_t)i].c_str();
  *data = (const uint8_t*)bytes.data();
  *len = (int64_t)bytes.size();
  return 0;
}

void lxl_writer_free(lxl_writer* w) { delete w; }

lxl_writer* lxl_writer_new_inverter(const char* schema_json, int32_t len) {
  return guardedPtr<lxl_writer>([&] {
    std::pmr::monotonic_buffer_resource arena;
    api::SchemaDef def;
    if (!api::read_json(def, std::string_view(schema_json, (size_t)len), arena)) {
      throw std::invalid_argument("lxl: invalid schema JSON");
    }
    return new lxl_writer(Schema::fromProto(def));
  });
}

int32_t lxl_writer_field_ord(lxl_writer* w, const char* name, int32_t len) {
  return guarded([&] {
    if (!w->inverter) return fail("lxl: not an inverter writer");
    auto& handler = w->inverter->getIndexHandler(std::string_view(name, (size_t)len));
    for (size_t i = 0; i < w->handlers.size(); i++) {
      if (w->handlers[i] == &handler) return (int32_t)i;
    }
    w->handlers.push_back(&handler);
    return (int32_t)w->handlers.size() - 1;
  });
}

int32_t lxl_writer_add_docs(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* ords,
                            const uint8_t* text, const int64_t* offsets) {
  return guarded([&] {
    if (!w->inverter) return fail("lxl: not an inverter writer");
    Inverter& inv = *w->inverter;
    std::vector<std::string_view> values;
    std::vector<int32_t> order;
    int32_t i = 0;
    while (i < n) {
      int32_t doc = docs[i];
      if (doc < inv.getMaxDoc()) return fail("lxl: documents out of order");
      while (inv.getMaxDoc() < doc) {  // docs without Luxir text
        inv.startDoc();
        inv.finishDoc();
      }
      inv.startDoc();
      int32_t end = i;
      while (end < n && docs[end] == doc) end++;
      // A field's values need not be adjacent (Solr's copyField puts each copy right after its
      // source): group them by field, in order, so each field is indexed once per doc.
      order.clear();
      for (int32_t k = i; k < end; k++) order.push_back(k);
      std::stable_sort(order.begin(), order.end(), [&](int32_t a, int32_t b) { return ords[a] < ords[b]; });
      for (size_t g = 0; g < order.size();) {
        int32_t ord = ords[order[g]];
        if (ord < 0 || (size_t)ord >= w->handlers.size()) return fail("lxl: bad field ordinal");
        values.clear();
        size_t h = g;
        for (; h < order.size() && ords[order[h]] == ord; h++) {
          int32_t k = order[h];
          values.emplace_back((const char*)text + offsets[k], (size_t)(offsets[k + 1] - offsets[k]));
        }
        w->handlers[(size_t)ord]->index(inv, std::span<const std::string_view>(values.data(), values.size()));
        g = h;
      }
      inv.finishDoc();
      i = end;
    }
    return 0;
  });
}

int64_t lxl_writer_ram(lxl_writer* w) {
  if (w->inverter) {
    int64_t ram = (int64_t)w->inverter->memSize();
    for (auto& sb : w->strs) ram += sb->ram();
    return ram;
  }
  // the chunks compressed so far (charged to the RAM directory as its buffers grow) and at most a
  // chunk pending
  return w->stored ? (int64_t)w->dir.storageBytes() + (int64_t)w->stored->format().chunkTarget : 0;
}

int32_t lxl_writer_set_max_doc(lxl_writer* w, int32_t max_doc) {
  return guarded([&] {
    if (w->inverter) {
      Inverter& inv = *w->inverter;
      if (inv.getMaxDoc() > max_doc) return fail("lxl: more documents than the segment");
      while (inv.getMaxDoc() < max_doc) {
        inv.startDoc();
        inv.finishDoc();
      }
    }
    w->pw->setMaxDoc(max_doc);
    return 0;
  });
}

int32_t lxl_writer_field_stats(lxl_writer* w, const char* name, int32_t len, int64_t* stats) {
  return guarded([&] {
    if (w->files.empty()) return fail("lxl: writer not finished");
    if (!w->finished) w->finished = PostingsReader::create(w->dir, SEG_ID);
    auto found = findField(*w->finished, std::string_view(name, (size_t)len));
    if (!found) return 0;
    const SegFieldInfo& info = *found;
    stats[0] = info.nTerms;
    stats[1] = info.docsWithField;
    stats[2] = info.sumDocFreq;
    stats[3] = info.sumTotalTermFreq;
    return 1;
  });
}

}  // extern "C"

// ---- reader ----------------------------------------------------------------------------

// A Luxir reader over segments we opened ourselves: a manifest naming them (no files, so
// nothing is opened or verified) plus the open readers it shares.
std::shared_ptr<IndexReader> openIndex(const std::vector<std::shared_ptr<PostingsReader>>& readers,
                                       const std::vector<uint64_t>& segIds) {
  static const std::shared_ptr<Schema> schema = Schema::createDefaultSchema();
  static RegionDir noFiles;
  std::pmr::monotonic_buffer_resource arena;
  std::vector<api::SegmentInfo> segs(readers.size());
  CommitSnapshot snapshot{};
  for (size_t i = 0; i < readers.size(); i++) {
    segs[i].seg_id = segIds[i];
    segs[i].max_doc = readers[i]->maxDoc();
    segs[i].live_docs = segs[i].max_doc;
    snapshot.openReaders.push_back(readers[i]);
  }
  api::IndexInfo info;
  info.schema = schema->storedInfo(arena);
  info.schema_gen = schema->gen_;
  info.incarnation = "lxl";
  info.segments = segs;
  auto bytes = std::make_shared<std::vector<std::byte>>();
  if (!api::encode(info, *bytes)) throw std::runtime_error("lxl: manifest encode failed");
  snapshot.bytes = std::move(bytes);
  snapshot.schema = schema;
  return std::make_shared<IndexReader>(noFiles, snapshot);
}

struct lxl_reader {
  RegionDir dir;
  std::shared_ptr<PostingsReader> postings;
  std::shared_ptr<IndexReader> index;  // one-segment view for query execution
};

extern "C" {

lxl_reader* lxl_reader_open(int32_t num_files, const char* const* names,
                            const uint8_t* const* data, const int64_t* lens) {
  return guardedPtr<lxl_reader>([&] {
    auto r = std::make_unique<lxl_reader>();
    for (int32_t i = 0; i < num_files; i++) {
      if (((uintptr_t)data[i] & 7) != 0) throw std::invalid_argument("lxl: region not 8-byte aligned");
      r->dir.add(names[i], (const char*)data[i], (size_t)lens[i]);
    }
    r->postings = PostingsReader::create(r->dir, SEG_ID);
    if (!r->postings) throw std::runtime_error("lxl: segment files missing");
    r->index = openIndex({r->postings}, {SEG_ID});
    return r.release();
  });
}

void lxl_reader_free(lxl_reader* r) { delete r; }

int32_t lxl_reader_norms(lxl_reader* r, const char* name, int32_t name_len, uint8_t* out) {
  return guarded([&] {
    auto found = findField(*r->postings, std::string_view(name, (size_t)name_len));
    if (!found) return 0;
    const SegFieldInfo& info = *found;
    if (info.normsFormat == SegFieldInfo::NORMS_NONE) return 0;
    NormsReader norms(*r->postings, info);
    if (const uint8_t* flat = norms.flatBase()) {
      std::memcpy(out, flat, (size_t)r->postings->maxDoc());
      return 1;
    }
    std::memset(out, 0, (size_t)r->postings->maxDoc());
    NormsReader::Iterator it(norms);
    for (int32_t d = it.next(); d != NormsReader::ENDDOC; d = it.next()) out[d] = it.value();
    return 1;
  });
}

int32_t lxl_reader_field_alias(lxl_reader* r, const char* name, int32_t name_len) {
  return guarded([&] {
    std::string_view field(name, (size_t)name_len);
    if (r->postings->findOrdinal(field) >= 0) return 1;
    std::string indexed = schemaName(field);
    return indexed != field && r->postings->findOrdinal(indexed) >= 0 ? 2 : 0;
  });
}

}  // extern "C"

// ---- terms -----------------------------------------------------------------------------

struct lxl_terms {
  MemPool pool;
  SegFieldInfo info;
  std::unique_ptr<TermsEnum> te;
  bool positioned = false;
  bool exhausted = false;
};

namespace {

int32_t current(lxl_terms* t, uint8_t* termOut, int64_t* stats) {
  PackedTerm term = t->te->term();
  std::memcpy(termOut, term.data(), term.size());
  stats[0] = t->te->docFreq();
  stats[1] = t->te->totalTermFreq();
  stats[2] = t->te->ord();
  return (int32_t)term.size();
}

}  // namespace

extern "C" {

lxl_terms* lxl_terms_new(lxl_reader* r, const char* field, int32_t field_len) {
  return guardedPtr<lxl_terms>([&]() -> lxl_terms* {
    auto found = findField(*r->postings, std::string_view(field, (size_t)field_len));
    if (!found) return nullptr;
    auto t = std::make_unique<lxl_terms>();
    t->info = *found;
    t->te = std::make_unique<TermsEnum>(t->pool, *r->postings, t->info);
    return t.release();
  });
}

void lxl_terms_free(lxl_terms* t) { delete t; }

int32_t lxl_terms_next(lxl_terms* t, uint8_t* term_out, int64_t* stats) {
  return guarded([&] {
    if (t->exhausted || !t->te->nextTerm()) {
      t->exhausted = true;
      t->positioned = false;
      return -2;
    }
    t->positioned = true;
    return current(t, term_out, stats);
  });
}

int32_t lxl_terms_seek(lxl_terms* t, const uint8_t* target, int32_t target_len, int32_t exact,
                       uint8_t* term_out, int32_t* term_len, int64_t* stats) {
  return guarded([&] {
    std::string_view key((const char*)target, (size_t)target_len);
    t->exhausted = false;
    if (exact) {
      t->positioned = t->te->seek(key);
      if (!t->positioned) return LXL_SEEK_NOT_FOUND;
      *term_len = current(t, term_out, stats);
      return LXL_SEEK_FOUND;
    }
    if (!t->te->seekCeil(key)) {
      t->positioned = false;
      t->exhausted = true;
      return LXL_SEEK_END;
    }
    t->positioned = true;
    *term_len = current(t, term_out, stats);
    return std::string_view(t->te->term()) == key ? LXL_SEEK_FOUND : LXL_SEEK_NOT_FOUND;
  });
}

int32_t lxl_terms_seek_ord(lxl_terms* t, int64_t ord, uint8_t* term_out, int64_t* stats) {
  return guarded([&] {
    if (ord < 0 || ord >= t->info.nTerms) return fail("lxl: term ordinal out of range");
    t->te->seekOrd(ord);
    t->positioned = true;
    t->exhausted = false;
    return current(t, term_out, stats);
  });
}

}  // extern "C"

// ---- automaton intersection --------------------------------------------------------------

struct lxl_intersect {
  std::unique_ptr<lxl_terms> terms;
  std::vector<automaton::ByteDfaRange> ranges;
  std::vector<uint32_t> offsets;
  std::vector<uint8_t> accepts;
  std::string prefix, suffix;
  std::unique_ptr<DfaIntersectEnum> it;
};

extern "C" {

lxl_intersect* lxl_intersect_new(lxl_reader* r, const char* field, int32_t field_len,
                                 int32_t num_states, const uint8_t* accepts,
                                 const int32_t* offsets, const int32_t* ranges,
                                 const uint8_t* prefix, int32_t prefix_len,
                                 const uint8_t* suffix, int32_t suffix_len,
                                 int32_t initial_state) {
  return guardedPtr<lxl_intersect>([&]() -> lxl_intersect* {
    std::unique_ptr<lxl_terms> terms(lxl_terms_new(r, field, field_len));
    if (!terms) throw std::invalid_argument("lxl: field not in this segment");
    if (num_states <= 0 || initial_state < 0 || initial_state >= num_states) {
      throw std::invalid_argument("lxl: bad automaton");
    }
    if (prefix_len > LXL_MAX_TERM) throw std::invalid_argument("lxl: automaton prefix too long");
    auto x = std::make_unique<lxl_intersect>();
    x->accepts.assign(accepts, accepts + num_states);
    x->offsets.assign(offsets, offsets + num_states + 1);
    int32_t numRanges = offsets[num_states];
    x->ranges.reserve((size_t)numRanges);
    for (int32_t i = 0; i < numRanges; i++) {
      int32_t dest = ranges[3 * i + 2];
      if (dest < 0 || dest >= num_states) throw std::invalid_argument("lxl: bad automaton transition");
      x->ranges.push_back({(uint8_t)ranges[3 * i], (uint8_t)ranges[3 * i + 1], dest});
    }
    x->prefix.assign((const char*)prefix, (size_t)prefix_len);
    x->suffix.assign((const char*)suffix, (size_t)suffix_len);
    automaton::ByteDfaView view(x->ranges.data(), x->offsets.data(), x->accepts.data(), nullptr,
                                (uint32_t)num_states);
    DfaScanPlan plan{x->prefix, x->suffix, initial_state};
    x->terms = std::move(terms);
    x->it = std::make_unique<DfaIntersectEnum>(x->terms->pool, *x->terms->te, view, plan);
    return x.release();
  });
}

int32_t lxl_intersect_next(lxl_intersect* x, uint8_t* term_out, int64_t* stats) {
  return guarded([&] {
    if (x->terms->exhausted || !x->it->next()) {
      x->terms->exhausted = true;
      x->terms->positioned = false;
      return -2;
    }
    x->terms->positioned = true;
    return current(x->terms.get(), term_out, stats);
  });
}

lxl_terms* lxl_intersect_terms(lxl_intersect* x) { return x->terms.get(); }

void lxl_intersect_free(lxl_intersect* x) {
  if (x == nullptr) return;
  x->it.reset();  // before the terms enum and pool it uses
  delete x;
}

}  // extern "C"

// ---- postings ----------------------------------------------------------------------------

struct lxl_postings {
  TermsEnum::PostingsState state;
  std::unique_ptr<DocsFreqEnum> freqs;
  std::unique_ptr<DocsPosEnum> docs;
  std::unique_ptr<PosEnum> pos;
  int32_t tf = 0;
  bool positionsRead = true;
};

extern "C" {

lxl_postings* lxl_postings_new(lxl_terms* t, int32_t positions) {
  return guardedPtr<lxl_postings>([&]() -> lxl_postings* {
    if (!t->positioned) throw std::logic_error("lxl: terms enum not positioned");
    auto p = std::make_unique<lxl_postings>();
    p->state = t->te->postingsState();
    // The impact frontier may point into the terms enum's block buffers, which
    // can go away before this enum does; plain iteration does not use it.
    p->state.termImpactFrontier = {};
    if (positions) {
      p->docs = std::make_unique<DocsPosEnum>(p->state);
      p->pos = std::make_unique<PosEnum>(*p->docs);
    } else {
      p->freqs = std::make_unique<DocsFreqEnum>(p->state);
    }
    return p.release();
  });
}

void lxl_postings_free(lxl_postings* p) { delete p; }

int32_t lxl_postings_fill(lxl_postings* p, int32_t target, int32_t* docs, int32_t* freqs,
                          int32_t cap) {
  return guarded([&] {
    auto& e = *p->freqs;
    int32_t d = target < 0 ? e.nextDoc() : e.advance(target);
    int32_t n = 0;
    while (d != PostingsReader::END) {
      docs[n] = d;
      freqs[n] = e.termFreq();
      if (++n == cap) break;
      d = e.nextDoc();
    }
    return n;
  });
}

int32_t lxl_postings_next(lxl_postings* p, int32_t target, int32_t* freq) {
  return guarded([&] {
    auto& e = *p->docs;
    int32_t d = target < 0 ? e.nextDoc() : e.advance(target);
    if (d == PostingsReader::END) return (int32_t)LXL_END;
    p->tf = e.termFreq();
    *freq = p->tf;
    p->positionsRead = false;
    return d;
  });
}

int32_t lxl_postings_positions(lxl_postings* p, int32_t* out, int32_t cap) {
  return guarded([&] {
    if (p->positionsRead) return fail("lxl: positions already read for this doc");
    if (p->tf > cap) return fail("lxl: positions buffer too small");
    p->pos->startPositions();
    for (int32_t i = 0; i < p->tf; i++) out[i] = p->pos->nextPosition();
    p->positionsRead = true;
    return p->tf;
  });
}

}  // extern "C"

// ---- query execution -------------------------------------------------------------------------

namespace {

// Per-segment bitsets for the query's bitset clauses (node 9), by slot and segment ord.
struct SlotTable {
  size_t numSegs = 0;
  std::vector<std::optional<BitDocSet>> sets;  // slot * numSegs + segment ord; empty: no match

  BitDocSet* get(int32_t slot, int32_t seg) {
    size_t i = (size_t)slot * numSegs + (size_t)seg;
    if (slot < 0 || (size_t)seg >= numSegs || i >= sets.size() || !sets[i]) return nullptr;
    return &*sets[i];
  }
};

// A constant-score clause over a bitset the caller computed per segment (e.g. a numeric range
// from the points index): AllQuery's shape with the bitset as its document source.
class BitsetQuery final : public luxir::Query {
public:
  int32_t slot;
  float boost;
  SlotTable* table;

  BitsetQuery(int32_t slot, float boost, SlotTable* table)
      : Query(QueryKind::TEST), slot(slot), boost(boost), table(table) {}

  bool equalsSameKind(const Query& other) const override {
    auto* b = dynamic_cast<const BitsetQuery*>(&other);
    return b != nullptr && b->slot == slot && b->boost == boost && b->table == table;
  }

  uint64_t hashImpl() const override { return Query::hashImpl() * 31 + (uint64_t)(uint32_t)slot; }

  FilterKeyScope appendFilterKey(FilterKeyBuilder& out, const FilterKeyContext& ctx) const override {
    out.appendKind(kind);
    unused(ctx);
    return FilterKeyScope::UNCACHEABLE;  // the bitset belongs to one request
  }

  // Luxir's scorer over an exact materialized bitset (stateless window fills).
  using Scorer = PointsMaterialize::PointsBitScorer;

  class Supplier final : public Query::ScorerSupplier {
    BitDocSet& set;
    int32_t maxDoc;
    float score;
    bool needsScores;

    class Plan final : public Query::ScorerPlan {
      Supplier& supplier;

    protected:
      Query::Scorer* buildScorer(MemPool& targetPool) override {
        return targetPool.make<BitsetQuery::Scorer>(supplier.set.bits(), supplier.score);
      }

      Query::Scorer* buildIndependentScorer(MemPool& targetPool) override {
        return targetPool.make<BitsetQuery::Scorer>(supplier.set.bits(), supplier.score);
      }

    public:
      [[gnu::cold]] bool supportsReset() const noexcept override {
        return unscoredResetUse() && !supplier.needsScores;
      }

      Plan(Supplier& supplier, const Query::PlanContext& planContext, const Query::ScorerShape& shape,
           int64_t cost)
          : Query::ScorerPlan(planContext, shape, cost), supplier(supplier) {}
    };

  public:
    Supplier(BitDocSet& set, int32_t maxDoc, float score, bool needsScores)
        : set(set), maxDoc(maxDoc), score(score), needsScores(needsScores) {}

    int64_t cost() override { return set.card(); }

    MembershipWork membershipWork(int32_t maxDoc) override {
      unused(maxDoc);
      double matches = (double)cost();
      return {matches, 4 * matches, 0, 32, 64};
    }

    Query::ScorerShape describeScorer(const Query::PlanContext& buildContext) const override {
      unused(buildContext);
      return {
          .matchState = Query::MatchState::NONEMPTY,
          .directKind = Query::DirectScorerKind::OTHER,
          .reportedTwoPhase = Query::ReportedTwoPhase::NO,
          .windowFillClause = Query::ClauseShape::DIRECT,
          .termDisjunctionClause = Query::ClauseShape::NONE,
          .termConjunctionClause = Query::ClauseShape::NONE,
          .independentTerm = Query::IndependentTermAccess::UNSUPPORTED,
          .docsOnly = Query::DocsOnlyAccess::UNSUPPORTED,
          .directDocSet = Query::DirectDocSetAccess::UNSUPPORTED,
      };
    }

    Query::ScorerPlan* resolve(MemPool& planPool, const Query::PlanContext& planContext) override {
      return planPool.make<Plan>(*this, planContext, describeScorer(planContext), cost());
    }

    BulkPlan planBulk(BulkUse use, const BulkScorerContext& bulkContext) override {
      if (bulkContext.requireConstantCount) {
        return {BulkAnswer::NO, BulkAnswer::NO, BulkAnswer::NO, BulkAnswer::NO};
      }
      bool available = !bulkContext.requireFilterConsumption;
      BulkPlan plan{
          available ? BulkAnswer::YES : BulkAnswer::NO,
          available ? BulkAnswer::YES : BulkAnswer::NO,
          BulkAnswer::NO,
          BulkAnswer::NO,
      };
      if (available && !needsScores && isMembershipWindowUse(use)) {
        plan.statelessReposition = BulkAnswer::YES;
      }
      return plan;
    }

    BulkScorer* buildBulk(MemPool& targetPool, const BulkPlan& plan) override {
      assert(plan.available == BulkAnswer::YES);
      unused(plan);
      return targetPool.make<DocSetBulkScorer>(targetPool, &set, maxDoc, score);
    }
  };

  class Weight final : public Query::Weight {
    BitsetQuery& query;
    float score;

  public:
    Weight(Context& context, BitsetQuery& query, int32_t flags, float score)
        : Query::Weight(context, query, flags), query(query), score(score) {
      traits |= IS_CONSTANT_SCORING;
    }

    Query::ScorerSupplier* scorerSupplierImpl(MemPool& targetPool, luxir::IndexReader::Segment& segment,
                                              Query::SupplierExecutionMode executionMode) override {
      unused(executionMode);
      BitDocSet* set = query.table->get(query.slot, segment.ord);
      if (set == nullptr || set->card() == 0) return nullptr;
      return targetPool.make<BitsetQuery::Supplier>(*set, segment.maxDoc(), score, needsScores());
    }

    Query::Scorer* createScorer(luxir::MemPool& targetPool, luxir::IndexReader::Segment& segment) override {
      BitDocSet* set = query.table->get(query.slot, segment.ord);
      if (set == nullptr || set->card() == 0) return nullptr;
      return targetPool.make<BitsetQuery::Scorer>(set->bits(), score);
    }

    CountShortcut countShortcut(IndexReader::Segment& segment, DocSet* domain) override {
      BitDocSet* set = query.table->get(query.slot, segment.ord);
      return countBound(segment, domain, set == nullptr ? 0 : (int64_t)set->card(), CountHow::METADATA);
    }
  };

  BitsetQuery::Weight* createWeight(Context& context, int32_t flags, float multiplier = 1.0f) override {
    return context.pool.make<BitsetQuery::Weight>(context, *this, flags, constantWhenScored(flags, boost * multiplier));
  }
};

// Per-segment doc-values columns (and, where values are raw int64s, points) for the query's
// column-range clauses (node 10), by slot and segment ord.
struct ColumnTable {
  struct Entry {
    PostingsReader* store = nullptr;  // the doc-values store's segment; null: no values here
    std::string_view column;
    PointsReader* points = nullptr;    // optional: the field's points
    int64_t pointsOffset = 0;          // point value = column value + pointsOffset
  };
  size_t numSegs = 0;
  std::vector<Entry> entries;  // slot * numSegs + segment ord

  const Entry* get(int32_t slot, int32_t seg) const {
    size_t i = (size_t)slot * numSegs + (size_t)seg;
    if (slot < 0 || (size_t)seg >= numSegs || i >= entries.size() || entries[i].store == nullptr) return nullptr;
    return &entries[i];
  }
};


// A numeric range over a doc-values column kept in another Luxir segment (the codec's doc-values
// store): Luxir's NumericPredicateQuery planning (sparse verification behind a selective lead,
// zone maps, scans, points materialization) on that segment's column and points. Values are raw
// int64s (ints, longs, dates), as the column and points hold them.
class ColumnRangeQuery final : public luxir::Query {
public:
  int32_t slot;
  int64_t lo, hi;
  ColumnTable* table;
  NumericPredicateQuery predicate;

  ColumnRangeQuery(int32_t slot, int64_t lo, int64_t hi, ColumnTable* table)
      : Query(QueryKind::TEST), slot(slot), lo(lo), hi(hi), table(table),
        predicate("\x01lxl-column", lo, hi) {}

  bool equalsSameKind(const Query& other) const override {
    auto* c = dynamic_cast<const ColumnRangeQuery*>(&other);
    return c != nullptr && c->slot == slot && c->lo == lo && c->hi == hi && c->table == table;
  }

  uint64_t hashImpl() const override { return Query::hashImpl() * 31 + (uint64_t)(uint32_t)slot; }

  FilterKeyScope appendFilterKey(FilterKeyBuilder& out, const FilterKeyContext& ctx) const override {
    out.appendKind(kind);
    unused(ctx);
    return FilterKeyScope::UNCACHEABLE;
  }

  class Weight final : public Query::Weight {
    ColumnRangeQuery& query;
    NumericPredicateQuery::Weight* inner;
    // By segment ord: the match count known when its supplier was made (all of the column's docs,
    // or a single-valued range counted from its points), else -1.
    std::span<int64_t> exactCounts;


    // NumericPredicateQuery::Weight's cost estimate from block relations
    static int64_t estimateCost(IntColReader& reader, std::span<const NumericPredicateQuery::BlockPlan> plans,
                                bool exactSet) {
      int64_t estimate = 0;
      for (int64_t b = 0; b < reader.numBlocks(); b++) {
        plans[(size_t)b].forEachRun((uint32_t)reader.valuesInBlock(b),
                                    [&](NumericPredicateQuery::BlockRelation relation, uint32_t begin, uint32_t end) {
                                      int64_t count = end - begin;
                                      if (relation == NumericPredicateQuery::BlockRelation::INSIDE) {
                                        estimate += count;
                                      } else if (relation == NumericPredicateQuery::BlockRelation::CROSSES) {
                                        estimate += exactSet ? count : (count + 1) / 2;
                                      }
                                    });
      }
      return std::min<int64_t>(estimate, reader.docsWithValue());
    }

  public:
    Weight(Context& context, ColumnRangeQuery& query, int32_t flags, float multiplier)
        : Query::Weight(context, query, flags), query(query) {
      traits |= IS_CONSTANT_SCORING;
      inner = static_cast<NumericPredicateQuery::Weight*>(query.predicate.createWeight(context, flags, multiplier));
      exactCounts = context.pool.make_span<int64_t>(context.topReader.segments().size());
      std::ranges::fill(exactCounts, -1);
    }

    Query::ScorerSupplier* scorerSupplierImpl(MemPool& targetPool, luxir::IndexReader::Segment& segment,
                                              Query::SupplierExecutionMode executionMode) override {
      unused(executionMode);
      const ColumnTable::Entry* e = query.table->get(query.slot, segment.ord);
      if (e == nullptr) return nullptr;
      auto info = findField(*e->store, e->column);
      if (!info) return nullptr;
      auto* segInfo = targetPool.make<SegFieldInfo>(*info);
      auto* reader = targetPool.make<IntColReader>(*e->store, *segInfo);
      if (reader->numValues() == 0) return nullptr;
      int64_t colMin = reader->getMin(), colMax = reader->getMax();
      auto& pred = query.predicate;
      if (colMax < pred.getLo() || pred.getHi() < colMin) return nullptr;
      std::span<NumericPredicateQuery::BlockPlan> plans;  // the supplier classifies on demand when points answer
      bool allMatch = pred.covers(colMin, colMax);
      int64_t cost = 0;
      PointsReader* points = e->points;
      std::span<const PointsReader::OrdinalRange> pointRanges;
      uint64_t pointValueCount = 0;
      Query::MatchState matchState = Query::MatchState::UNKNOWN;
      if (points != nullptr && points->pointCount() == (uint64_t)reader->numValues()) {
        MemPool scratch;
        auto residuals = scratch.make_span<uint32_t>(points->maxPointsPerLeaf());
        auto values = scratch.make_span<int64_t>(points->maxPointsPerLeaf());
        auto stored = targetPool.make_span<PointsReader::OrdinalRange>(pred.intervals().size());
        // the points' ordinal ranges for the range in point-value space (the planner uses only these)
        PointsReader::ValueRange shifted{pred.getLo() + e->pointsOffset, pred.getHi() + e->pointsOffset};
        size_t n = e->pointsOffset == 0
            ? points->ordinalRanges(pred.intervals(), stored, residuals, values)
            : points->ordinalRanges(std::span<const PointsReader::ValueRange>(&shifted, 1), stored, residuals, values);
        pointRanges = stored.first(n);
        for (const auto& r : pointRanges) pointValueCount += r.end - r.begin;
        uint64_t docCost = pointValueCount;
        if (reader->multiValued()) docCost = std::min<uint64_t>(docCost, reader->docsWithValue());
        cost = (int64_t)docCost;
        matchState = pointValueCount == 0 ? Query::MatchState::EMPTY : Query::MatchState::NONEMPTY;
      } else {
        points = nullptr;
        plans = NumericPredicateQuery::classifyBlocks(targetPool, *reader, pred);
        cost = estimateCost(*reader, plans, pred.isExactSet());
        bool hasInside = NumericPredicateQuery::hasInsideRun(*reader, plans), hasCrosses = false;
        for (const auto& plan : plans) hasCrosses |= plan.relation == NumericPredicateQuery::BlockRelation::CROSSES;
        matchState = hasInside ? Query::MatchState::NONEMPTY
                     : hasCrosses ? Query::MatchState::UNKNOWN
                                  : Query::MatchState::EMPTY;
      }
      exactCounts[(size_t)segment.ord] = allMatch ? reader->docsWithValue()
          : points != nullptr && !reader->multiValued() ? (int64_t)pointValueCount : -1;
      return targetPool.make<NumericPredicateQuery::Weight::Supplier>(*inner, targetPool, segment, *reader, plans,
                                                                     cost, matchState, allMatch, points, pointRanges,
                                                                     pointValueCount);
    }

    // The inner weight would look for the column in the reader's segments, where it is not (it
    // lives in the doc-values store), and count every range as empty: answer from the column's
    // header, or from what its supplier found, and never send a count to that supplier.
    CountShortcut countShortcut(IndexReader::Segment& segment, DocSet* domain) override {
      const ColumnTable::Entry* e = query.table->get(query.slot, segment.ord);
      if (e == nullptr) return {0, CountHow::METADATA};
      if (exactCounts[(size_t)segment.ord] >= 0) {
        return countBound(segment, domain, exactCounts[(size_t)segment.ord], CountHow::METADATA);
      }
      const PostingsReader::Field* field = e->store->fieldInfo(e->column);
      auto& pred = query.predicate;
      if (field == nullptr || field->numValues == 0 || pred.getHi() < pred.getLo()) return {0, CountHow::METADATA};
      if (field->columnBounds) {
        if (!pred.intersects(field->columnBounds->min, field->columnBounds->max)) return {0, CountHow::METADATA};
        if (pred.covers(field->columnBounds->min, field->columnBounds->max)) {
          return countBound(segment, domain, field->docsWithField, CountHow::METADATA);
        }
      }
      return countBound(segment, domain, field->docsWithField);
    }

    Query::Scorer* createScorer(MemPool& targetPool, luxir::IndexReader::Segment& segment) override {
      auto* supplier = scorerSupplier(targetPool, segment);
      if (supplier == nullptr) return nullptr;
      Query::Demand demand = Query::Demand::fromLeadCost(std::numeric_limits<int64_t>::max());
      return supplier->resolve(targetPool, supplier->makePlanContext(demand))->build(targetPool);
    }

  };

  ColumnRangeQuery::Weight* createWeight(Context& context, int32_t flags, float multiplier = 1.0f) override {
    return context.pool.make<ColumnRangeQuery::Weight>(context, *this, flags, multiplier);
  }
};

// Little-endian reader over the query bytes (see LxlQuery.java for the format).
struct QueryParser {
  const char* p;
  const char* end;
  MemPool& pool;
  SlotTable* slots;  // null: bitset clauses not allowed
  ColumnTable* columns = nullptr;  // null: column-range clauses not allowed

  struct TermStat {
    std::string_view field, term;
    Similarity::TermStats stats;
  };
  struct FieldStat {
    std::string_view field;
    Similarity::FieldStats stats;
  };
  std::vector<FieldStat> fields;
  std::vector<TermStat> terms;
  IndexReader* reader = nullptr;  // resolves field names; null: names as given

  void need(size_t n) {
    if ((size_t)(end - p) < n) throw std::invalid_argument("lxl: truncated query");
  }
  template <typename T>
  T get() {
    need(sizeof(T));
    T v;
    std::memcpy(&v, p, sizeof(T));
    p += sizeof(T);
    return v;
  }
  // A field name: as given if the reader has it, else its schema name (native text fields).
  std::string_view field() {
    std::string_view name = str();
    if (reader == nullptr || reader->field(name).exists()) return name;
    std::string indexed = schemaName(name);
    if (indexed == name) return name;
    auto copy = pool.make_span<char>(indexed.size());
    std::memcpy(copy.data(), indexed.data(), indexed.size());
    return {copy.data(), copy.size()};
  }

  std::string_view str() {
    int32_t n = get<int32_t>();
    if (n < 0) throw std::invalid_argument("lxl: bad string");
    need((size_t)n);
    std::string_view s(p, (size_t)n);
    p += n;
    return s;
  }

  void header() {
    if (get<uint32_t>() != 0x3151584cu) throw std::invalid_argument("lxl: bad query magic");
    int32_t n = get<int32_t>();
    for (int32_t i = 0; i < n; i++) {
      FieldStat f;
      f.field = field();
      f.stats.docsWithField = get<int64_t>();
      f.stats.sumTotalTermFreq = get<int64_t>();
      f.stats.sumDocFreq = get<int64_t>();
      fields.push_back(f);
    }
  }

  Query* node() {
    uint8_t kind = get<uint8_t>();
    switch (kind) {
      case 1: {  // term
        std::string_view field = this->field();
        std::string_view term = str();
        Similarity::TermStats s;
        s.docFreq = get<int64_t>();
        s.totalTermFreq = get<int64_t>();
        terms.push_back({field, term, s});
        return pool.make<TermQuery>(field, term);
      }
      case 2: {  // boolean
        int32_t msm = get<int32_t>();
        int32_t counts[4];
        for (auto& c : counts) {
          c = get<int32_t>();
          if (c < 0) throw std::invalid_argument("lxl: bad clause count");
        }
        std::span<Query*> groups[4];
        for (int g = 0; g < 4; g++) {
          groups[g] = {pool.make_arr<Query*>((size_t)counts[g]), (size_t)counts[g]};
          for (int32_t i = 0; i < counts[g]; i++) groups[g][(size_t)i] = node();
        }
        return pool.make<BooleanQuery>(groups[0], groups[1], groups[2], groups[3], msm);
      }
      case 3: {  // phrase
        std::string_view field = this->field();
        int32_t slop = get<int32_t>();
        int32_t n = get<int32_t>();
        if (n < 2) throw std::invalid_argument("lxl: phrase needs two terms");
        auto* words = pool.make_arr<std::string_view>((size_t)n);
        auto* positions = pool.make_arr<int32_t>((size_t)n);
        for (int32_t i = 0; i < n; i++) {
          positions[i] = get<int32_t>();
          words[i] = str();
          Similarity::TermStats s;
          s.docFreq = get<int64_t>();
          s.totalTermFreq = get<int64_t>();
          terms.push_back({field, words[i], s});
        }
        return pool.make<PhraseQuery>(field, std::span<std::string_view>(words, (size_t)n),
                                      std::span<const int32_t>(positions, (size_t)n), slop);
      }
      case 4: {  // boost
        float boost = get<float>();
        return pool.make<BoostQuery>(node(), boost);
      }
      case 5: {  // constant score
        float score = get<float>();
        return pool.make<ConstantScoreQuery>(node(), score);
      }
      case 6:
        return pool.make<MatchNoDocsQuery>();
      case 7:
        return pool.make<AllQuery>();
      case 8: {  // constant-score prefix (Lucene's constant-score multi-term rewrite)
        std::string_view field = this->field();
        std::string_view prefix = str();
        return pool.make<ConstantScoreQuery>(pool.make<PrefixQuery>(field, prefix), 1.0f);
      }
      case 11: {  // constant-score explicit term set (an expanded wildcard/regexp), sorted unique
        std::string_view field = this->field();
        int32_t n = get<int32_t>();
        if (n <= 0) throw std::invalid_argument("lxl: empty term set");
        auto* terms = pool.make_arr<std::string_view>((size_t)n);
        for (int32_t i = 0; i < n; i++) terms[i] = str();
        return pool.make<ConstantScoreQuery>(
            pool.make<TermInSetQuery>(field, std::span<const std::string_view>(terms, (size_t)n)), 1.0f);
      }
      case 9: {  // constant-score clause over the caller's per-segment bitset
        int32_t slot = get<int32_t>();
        float score = get<float>();
        if (slots == nullptr) throw std::invalid_argument("lxl: bitset clause without bitsets");
        if (slot < 0) throw std::invalid_argument("lxl: bad bitset slot");
        return pool.make<BitsetQuery>(slot, score, slots);
      }
      case 10: {  // numeric range over the caller's per-segment doc-values columns
        int32_t slot = get<int32_t>();
        int64_t lo = get<int64_t>();
        int64_t hi = get<int64_t>();
        if (columns == nullptr) throw std::invalid_argument("lxl: column range without columns");
        if (slot < 0) throw std::invalid_argument("lxl: bad column slot");
        return pool.make<ColumnRangeQuery>(slot, lo, hi, columns);
      }
      default:
        throw std::invalid_argument("lxl: unknown query node");
    }
  }

  // Overwrite the segment-local statistics with the caller's collection-wide ones
  // before any weight reads them.
  void inject(Query::Context& ctx) {
    for (auto& f : fields) {
      if (auto* cfi = ctx.getCachedFieldInfo(f.field)) cfi->fieldStats = f.stats;
    }
    for (auto& t : terms) {
      if (t.stats.docFreq < 0) continue;  // keep the statistics summed over the segments
      auto* cfi = ctx.getCachedFieldInfo(t.field);
      if (cfi == nullptr) continue;
      if (auto* cti = ctx.getCachedTerminfo(*cfi, t.term)) cti->termStats = t.stats;
    }
  }
};

// Query objects and the planning context for one execution against one segment.
struct Prepared {
  std::string bytes;  // query bytes; terms and fields point into this
  MemPool pool;
  std::unique_ptr<Query::Context> ctx;
  Query* query = nullptr;
  std::optional<BitDocSet> filter;

  Prepared(IndexReader& index, const uint8_t* q, int64_t len, SlotTable* slots = nullptr,
           ColumnTable* columns = nullptr)
      : bytes((const char*)q, (size_t)len) {
    QueryParser parser{bytes.data(), bytes.data() + bytes.size(), pool, slots, columns, {}, {}};
    parser.reader = &index;
    parser.header();
    query = parser.node();
    if (parser.p != parser.end) throw std::invalid_argument("lxl: trailing query bytes");
    ctx = std::make_unique<Query::Context>(pool, index);
    parser.inject(*ctx);
  }

  Prepared(lxl_reader* r, const uint8_t* q, int64_t len, const uint64_t* live, int32_t numLive)
      : Prepared(*r->index, q, len) {
    if (live != nullptr) {
      filter.emplace(FixedBitSet(const_cast<uint64_t*>(live), r->postings->maxDoc()), numLive);
    }
  }

  DocSet* filterSet() { return filter ? &*filter : nullptr; }

  Segment& segment(lxl_reader* r) { return r->index->segments()[0]; }

  Query::Weight* weight(int32_t flags) {
    auto* w = query->createWeight(*ctx, flags);
    if (w->needsPrepare()) throw std::invalid_argument("lxl: query needs a prepare pass");
    return w;
  }
};

Query::Scorer* pullScorer(MemPool& pool, Query::ScorerSupplier& supplier) {
  auto demand = Query::Demand::fromLeadCost(std::numeric_limits<int64_t>::max());
  return supplier.resolve(pool, supplier.makePlanContext(demand))->build(pool);
}

BulkScorer* bulkFor(MemPool& pool, Query::ScorerSupplier& supplier, Query::ExecutionUse use) {
  Query::ScorerSupplier::BulkScorerContext context;
  auto plan = supplier.planBulk(use, context);
  if (plan.available != Query::ScorerSupplier::BulkAnswer::YES || plan.hasConstantCount()) {
    if (plan.available == Query::ScorerSupplier::BulkAnswer::NO) {
      supplier.recordBulkPlanCommitment(use, context, plan);
    }
    return nullptr;
  }
  return supplier.buildBulk(pool, plan);
}

// The weight's exact count in `segment` without executing it (a range counted from its points, a
// field's docs with values), or -1: its count shortcut, resolved by a supplier where that is how
// it counts, as Luxir's own count planning does.
int64_t constantCountOf(Query::Weight& weight, IndexReader::Segment& segment, MemPool& pool) {
  auto count = QueryPrep::resolveCount(weight.countShortcut(segment, nullptr), [&](Query::Weight& resolver) {
    return resolver.scorerSupplier(pool, segment);
  });
  return count ? *count : -1;
}

}  // namespace

struct lxl_exec {
  Prepared prepared;
  Query::ScorerSupplier* supplier = nullptr;
  BulkScorer* bulk = nullptr;
  Query::Scorer* scorer = nullptr;  // pull fallback
  bool scores;

  lxl_exec(lxl_reader* r, const uint8_t* q, int64_t len, int32_t flags, const uint64_t* live,
           int32_t numLive)
      : prepared(r, q, len, live, numLive), scores((flags & LXL_EXEC_SCORES) != 0) {
    auto* w = prepared.weight(scores ? Query::NEED_SCORES | Query::ALLOW_PRUNING : 0);
    supplier = w->scorerSupplier(prepared.pool, prepared.segment(r));
    if (supplier == nullptr) return;  // nothing in this segment
    bulk = bulkFor(prepared.pool, *supplier,
                   scores ? Query::ExecutionUse::SCORED_WINDOWS : Query::ExecutionUse::MATCH_WINDOWS);
    if (bulk == nullptr) {
      scorer = pullScorer(prepared.pool, *supplier);
      if (scorer == nullptr) supplier = nullptr;  // nothing can match
    }
  }
};

extern "C" {

lxl_exec* lxl_exec_new(lxl_reader* r, const uint8_t* query, int64_t query_len, int32_t flags,
                       const uint64_t* live, int32_t num_live) {
  return guardedPtr<lxl_exec>([&] { return new lxl_exec(r, query, query_len, flags, live, num_live); });
}

void lxl_exec_free(lxl_exec* e) { delete e; }

int32_t lxl_exec_window(lxl_exec* e, int32_t min, int32_t max, float min_competitive,
                        int32_t* docs, float* scores, int32_t cap, int32_t* n) {
  return guarded([&]() -> int32_t {
    *n = 0;
    if (e->supplier == nullptr) return LXL_END;
    DocSet* filter = e->prepared.filterSet();
    if (e->bulk != nullptr) {
      ScoreWindow window;
      int32_t next = e->scores
          ? e->bulk->scoreNextWindow(window, filter, min, max, min_competitive)
          : e->bulk->matchNextWindow(window, filter, min, max);
      if (window.size > cap) return fail("lxl: window larger than buffer");
      std::memcpy(docs, window.docs.data(), sizeof(int32_t) * (size_t)window.size);
      if (e->scores) std::memcpy(scores, window.scores.data(), sizeof(float) * (size_t)window.size);
      *n = window.size;
      return next == PostingsReader::END ? (int32_t)LXL_END : next;
    }
    auto* s = e->scorer;
    if (e->scores && min_competitive > std::numeric_limits<float>::lowest()) {
      s->setMinCompetitiveScore(min_competitive);
    }
    int32_t d = s->docId();
    if (d < min) d = s->advance(min);
    int32_t count = 0;
    while (d != PostingsReader::END && d < max && count < cap) {
      if (filter == nullptr || filter->get(d)) {
        docs[count] = d;
        if (e->scores) scores[count] = s->score();
        count++;
      }
      d = s->next();
    }
    *n = count;
    return d == PostingsReader::END ? (int32_t)LXL_END : d;
  });
}

int32_t lxl_top_k(lxl_reader* r, const uint8_t* query, int64_t query_len, const uint64_t* live,
                  int32_t num_live, int32_t k, float theta, int32_t exact_count, int32_t* docs,
                  float* scores, int64_t* total_hits, uint64_t* match_bits) {
  return guarded([&]() -> int32_t {
    Prepared prepared(r, query, query_len, live, num_live);
    Segment& seg = prepared.segment(r);
    int32_t maxDoc = seg.maxDoc();
    DocSet* filter = prepared.filterSet();
    int64_t count = -1;

    // Exact count (and the match set) from a separate unscored pass, so the
    // ranking pass below can prune freely: Luxir's exact-count composition.
    if (exact_count || match_bits != nullptr) {
      count = 0;
      auto* w = prepared.weight(0);
      if (auto* supplier = w->scorerSupplier(prepared.pool, seg)) {
        auto use = match_bits != nullptr ? Query::ExecutionUse::MATCH_WINDOWS
                                         : Query::ExecutionUse::COUNT_WINDOWS;
        BulkScorer* bulk = bulkFor(prepared.pool, *supplier, use);
        if (bulk != nullptr && match_bits == nullptr) {
          count = countMatchesWindowed(bulk, filter, nullptr, maxDoc);
        } else if (bulk != nullptr) {
          int32_t cursor = 0;
          while (cursor != PostingsReader::END && cursor < maxDoc) {
            ScoreWindow window;
            int32_t next = bulk->matchNextWindow(window, filter, cursor, maxDoc);
            for (int32_t i = 0; i < window.size; i++) {
              int32_t d = window.docs[(size_t)i];
              match_bits[d >> 6] |= 1ULL << (d & 63);
            }
            count += window.size;
            if (next == PostingsReader::END) break;
            cursor = next;
          }
        } else {
          auto* s = pullScorer(prepared.pool, *supplier);  // null: nothing can match
          for (int32_t d = s ? s->next() : PostingsReader::END; d != PostingsReader::END; d = s->next()) {
            if (filter != nullptr && !filter->get(d)) continue;
            if (match_bits != nullptr) match_bits[d >> 6] |= 1ULL << (d & 63);
            count++;
          }
        }
      }
    }

    int32_t n = 0;
    int64_t ranked = 0;
    if (k > 0 && count != 0) {
      auto* w = prepared.weight(Query::NEED_SCORES | Query::ALLOW_PRUNING);
      if (auto* supplier = w->scorerSupplier(prepared.pool, seg)) {
        TopDocsCollector collector(k);
        MaxScoreAccumulator accumulator;
        if (theta > std::numeric_limits<float>::lowest()) accumulator.accumulate(theta);
        BulkScorer* bulk = bulkFor(prepared.pool, *supplier, Query::ExecutionUse::SCORED_WINDOWS);
        if (bulk != nullptr) {
          collectTopKWindowed(0, bulk, filter, collector, &accumulator, maxDoc, true);
        } else if (auto* s = pullScorer(prepared.pool, *supplier)) {
          collectTopK(0, s, filter, nullptr, collector, true, &accumulator);
        }
        ranked = collector.totalHits();
        for (auto& sd : collector.sort()) {
          docs[n] = sd.doc.docId();
          scores[n] = sd.score;
          n++;
        }
      }
    }
    *total_hits = count >= 0 ? count : ranked;
    return n;
  });
}

}  // extern "C"

// ---- one search over many segments ---------------------------------------------------------

struct lxl_multi {
  std::shared_ptr<IndexReader> index;
};

PointsReader* lxlPointsReader(lxl_points* p);

extern "C" {

lxl_multi* lxl_multi_new(int32_t n, lxl_reader* const* readers) {
  return guardedPtr<lxl_multi>([&] {
    std::vector<std::shared_ptr<PostingsReader>> prs;
    std::vector<uint64_t> segIds;
    for (int32_t i = 0; i < n; i++) {
      prs.push_back(readers[i]->postings);
      segIds.push_back((uint64_t)i);
    }
    auto m = std::make_unique<lxl_multi>();
    m->index = openIndex(prs, segIds);
    return m.release();
  });
}

void lxl_multi_free(lxl_multi* m) { delete m; }

}  // extern "C"

namespace {

// The per-call state of a search over a lxl_multi's segments: the bitsets and doc-values columns
// the query's slot and column-range clauses refer to, the prepared query, and each segment's live
// docs as its filter.
struct MultiCall {
  std::span<Segment> segments;
  size_t n;
  SlotTable slots;
  ColumnTable cols;
  std::optional<Prepared> prepared;
  std::vector<std::optional<BitDocSet>> filters;

  MultiCall(lxl_multi* m, const uint8_t* query, int64_t query_len, const uint64_t* const* live,
            const int32_t* num_live, int32_t num_slots, const uint64_t* const* slot_bits,
            const int32_t* slot_counts, int32_t num_cols, lxl_reader* const* col_stores,
            const char* const* col_names, lxl_points* const* col_points, const int64_t* col_offsets)
      : segments(m->index->segments()), n(segments.size()) {
    slots.numSegs = n;
    // Sized once: a BitDocSet cannot move.
    slots.sets = std::vector<std::optional<BitDocSet>>((size_t)std::max(0, num_slots) * n);
    for (int32_t sl = 0; sl < num_slots; sl++) {
      for (size_t i = 0; i < n; i++) {
        size_t at = (size_t)sl * n + i;
        if (slot_bits[at] == nullptr) continue;
        slots.sets[at].emplace(FixedBitSet(const_cast<uint64_t*>(slot_bits[at]), segments[i].maxDoc()), slot_counts[at]);
      }
    }
    cols.numSegs = n;
    cols.entries.resize((size_t)std::max(0, num_cols) * n);
    for (size_t at = 0; at < cols.entries.size(); at++) {
      if (col_stores[at] == nullptr) continue;
      cols.entries[at].store = col_stores[at]->postings.get();
      cols.entries[at].column = col_names[at];
      cols.entries[at].points = col_points[at] != nullptr ? lxlPointsReader(col_points[at]) : nullptr;
      cols.entries[at].pointsOffset = col_offsets[at];
    }
    prepared.emplace(*m->index, query, query_len, &slots, &cols);
    filters = std::vector<std::optional<BitDocSet>>(n);
    for (size_t i = 0; i < n; i++) {
      if (live[i] != nullptr) {
        filters[i].emplace(FixedBitSet(const_cast<uint64_t*>(live[i]), segments[i].maxDoc()), num_live[i]);
      }
    }
  }

  DocSet* filterOf(size_t i) { return filters[i] ? &*filters[i] : nullptr; }
};

}  // namespace

extern "C" {

int32_t lxl_multi_top_k(lxl_multi* m, const uint8_t* query, int64_t query_len,
                        const uint64_t* const* live, const int32_t* num_live, int32_t k,
                        int64_t count_limit, int32_t* segs, int32_t* docs, float* scores,
                        int64_t* total_hits, int32_t* count_exact,
                        uint64_t* const* match_bits, int32_t num_slots,
                        const uint64_t* const* slot_bits, const int32_t* slot_counts, int32_t num_cols,
                        lxl_reader* const* col_stores, const char* const* col_names,
                        lxl_points* const* col_points, const int64_t* col_offsets) {
  return guarded([&]() -> int32_t {
    MultiCall call(m, query, query_len, live, num_live, num_slots, slot_bits, slot_counts, num_cols, col_stores,
                   col_names, col_points, col_offsets);
    auto segments = call.segments;
    size_t n = call.n;
    Prepared& prepared = *call.prepared;
    auto filterOf = [&](size_t i) -> DocSet* { return call.filterOf(i); };

    int64_t count = -1;
    bool exact = true;
    if (match_bits != nullptr) count_limit = -1;
    if (count_limit != 0) {
      count = 0;
      auto* w = prepared.weight(0);
      for (size_t i = 0; i < n; i++) {
        if (count_limit > 0 && count >= count_limit) {
          exact = false;
          break;
        }
        if (live[i] != nullptr && num_live[i] == 0) continue;
        Segment& seg = segments[i];
        auto* supplier = w->scorerSupplier(prepared.pool, seg);
        if (supplier == nullptr) continue;
        uint64_t* bits = match_bits != nullptr ? match_bits[i] : nullptr;
        DocSet* filter = filterOf(i);
        auto use = bits != nullptr ? Query::ExecutionUse::MATCH_WINDOWS : Query::ExecutionUse::COUNT_WINDOWS;
        if (bits == nullptr && filter == nullptr) {
          int64_t constant = constantCountOf(*w, seg, prepared.pool);
          if (constant >= 0) {
            count += constant;
            continue;
          }
        }
        BulkScorer* bulk = bulkFor(prepared.pool, *supplier, use);
        if (bulk != nullptr && bits == nullptr) {
          if (count_limit < 0) {
            count += countMatchesWindowed(bulk, filter, nullptr, seg.maxDoc());
          } else {
            int32_t cursor = 0;
            while (cursor != PostingsReader::END && cursor < seg.maxDoc()) {
              int32_t next = bulk->countNextWindow(count, nullptr, filter, cursor, seg.maxDoc());
              if (count >= count_limit || next == PostingsReader::END) break;
              cursor = next;
            }
          }
        } else if (bulk != nullptr) {
          int32_t cursor = 0;
          while (cursor != PostingsReader::END && cursor < seg.maxDoc()) {
            ScoreWindow window;
            int32_t next = bulk->matchNextWindow(window, filter, cursor, seg.maxDoc());
            for (int32_t j = 0; j < window.size; j++) {
              int32_t d = window.docs[(size_t)j];
              bits[d >> 6] |= 1ULL << (d & 63);
            }
            count += window.size;
            if (next == PostingsReader::END) break;
            cursor = next;
          }
        } else if (auto* s = pullScorer(prepared.pool, *supplier)) {
          for (int32_t d = s->next(); d != PostingsReader::END; d = s->next()) {
            if (filter != nullptr && !filter->get(d)) continue;
            if (bits != nullptr) bits[d >> 6] |= 1ULL << (d & 63);
            if (++count == count_limit) break;
          }
        }
      }
    }

    int32_t got = 0;
    int64_t ranked = 0;
    bool rankedFromBits = false;
    if (k > 0 && count > 0 && match_bits != nullptr) {
      // A constant-score query's top k are its first k matches by (segment, doc), the order
      // TopDocsCollector breaks score ties in: read them from the counting pass's bits instead
      // of running the query again. One scorer, planned for a single doc, supplies the score.
      auto* w = prepared.weight(Query::NEED_SCORES | Query::ALLOW_PRUNING);
      if (w->isConstantScoring()) {
        float score = 0;
        bool scored = false;
        for (size_t i = 0; i < n && got < k; i++) {
          if (match_bits[i] == nullptr) continue;
          Segment& seg = segments[i];
          size_t words = ((size_t)seg.maxDoc() + 63) / 64;
          for (size_t wi = 0; wi < words && got < k; wi++) {
            for (uint64_t word = match_bits[i][wi]; word != 0 && got < k; word &= word - 1) {
              int32_t d = (int32_t)(wi * 64 + (size_t)std::countr_zero(word));
              if (!scored) {
                auto* supplier = w->scorerSupplier(prepared.pool, seg);
                auto* s = supplier == nullptr ? nullptr
                    : supplier->resolve(prepared.pool, supplier->makePlanContext(Query::Demand::fromLeadCost(1)))
                          ->build(prepared.pool);
                if (s == nullptr || s->advance(d) != d) throw std::logic_error("lxl: counted match not found");
                score = s->score();
                scored = true;
              }
              segs[got] = (int32_t)i;
              docs[got] = d;
              scores[got] = score;
              got++;
            }
          }
        }
        rankedFromBits = true;
      }
    }
    if (!rankedFromBits && k > 0 && count != 0) {
      auto* w = prepared.weight(Query::NEED_SCORES | Query::ALLOW_PRUNING);
      TopDocsCollector collector(k);
      MaxScoreAccumulator accumulator;
      for (size_t i = 0; i < n; i++) {
        if (live[i] != nullptr && num_live[i] == 0) continue;
        Segment& seg = segments[i];
        auto* supplier = w->scorerSupplier(prepared.pool, seg);
        if (supplier == nullptr) continue;
        DocSet* filter = filterOf(i);
        BulkScorer* bulk = bulkFor(prepared.pool, *supplier, Query::ExecutionUse::SCORED_WINDOWS);
        if (bulk != nullptr) {
          collectTopKWindowed((int32_t)i, bulk, filter, collector, &accumulator, seg.maxDoc(), true);
        } else if (auto* s = pullScorer(prepared.pool, *supplier)) {
          collectTopK((int32_t)i, s, filter, nullptr, collector, true, &accumulator);
        }
      }
      ranked = collector.totalHits();
      for (auto& sd : collector.sort()) {
        segs[got] = sd.doc.segment();
        docs[got] = sd.doc.docId();
        scores[got] = sd.score;
        got++;
      }
    }
    if (count >= 0 && count_limit > 0 && count >= count_limit) exact = false;
    *total_hits = count >= 0 ? count : ranked;
    *count_exact = count >= 0 ? (exact ? 1 : 0) : 0;
    return got;
  });
}

}  // extern "C"

// ---- native analysis ------------------------------------------------------------------------

struct lxl_analyzer {
  std::shared_ptr<const Analyzer> analyzer;
  std::unique_ptr<TokenChain> chain;
  std::string value;
  PackedTerm::TermBuffer scratch;
  bool active = false;
};

extern "C" {

lxl_analyzer* lxl_analyzer_new(const char* json, int32_t len) {
  return guardedPtr<lxl_analyzer>([&] {
    std::pmr::monotonic_buffer_resource arena;
    api::AnalyzerDef def;
    if (!api::read_json(def, std::string_view(json, (size_t)len), arena)) {
      throw std::invalid_argument("lxl: invalid analyzer JSON");
    }
    auto a = std::make_unique<lxl_analyzer>();
    a->analyzer = Analyzer::compile(def);
    a->chain = a->analyzer->createChain();
    return a.release();
  });
}

void lxl_analyzer_free(lxl_analyzer* a) { delete a; }

int32_t lxl_analyze_start(lxl_analyzer* a, const uint8_t* text, int32_t len) {
  return guarded([&] {
    a->value.assign((const char*)text, (size_t)len);
    a->chain->setValue(a->value);  // through the chain: it applies any character filters
    a->chain->reset();
    a->active = true;
    return 0;
  });
}

int32_t lxl_analyze_next(lxl_analyzer* a, uint8_t* terms, int32_t* meta, int32_t max_tokens) {
  return guarded([&] {
    if (!a->active) return 0;
    TokenStream& tail = *a->chain->tail;
    Token& tok = a->chain->getToken();
    int32_t n = 0;
    size_t used = 0;
    while (n < max_tokens) {
      if (!tail.incrementToken()) {
        a->active = false;
        break;
      }
      // the indexer's default long-term policy (FieldType::longTerms)
      auto term = PackedTerm::fitTerm(TermPolicy::HASH128, tok.text, a->scratch);
      std::memcpy(terms + used, term->data(), term->size());
      used += term->size();
      meta[4 * n] = (int32_t)term->size();
      meta[4 * n + 1] = tok.positionIncrement;
      meta[4 * n + 2] = tok.startOffset;
      meta[4 * n + 3] = tok.endOffset;
      n++;
    }
    return n;
  });
}

int32_t lxl_normalize(lxl_analyzer* a, const uint8_t* text, int32_t len, uint8_t* out, int32_t out_cap) {
  return guarded([&] {
    std::string term((const char*)text, (size_t)len);
    a->chain->normalizeTerm(term);
    if ((int32_t)term.size() > out_cap) return fail("lxl: normalized term too long");
    std::memcpy(out, term.data(), term.size());
    return (int32_t)term.size();
  });
}

}  // extern "C"

// ---- native merge of one text field --------------------------------------------------------

extern "C" {

int32_t lxl_writer_merge_field(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                               lxl_reader* const* sources, const int32_t* const* doc_maps) {
  return guarded([&]() -> int32_t {
    if (w->tw) return fail("lxl: previous field not ended");
    std::string_view field(name, (size_t)name_len);
    struct Source {
      MemPool pool;
      SegFieldInfo info;
      std::unique_ptr<TermsEnum> te;
      const int32_t* map = nullptr;
      bool live = false;
    };
    std::vector<std::unique_ptr<Source>> src;
    for (int32_t i = 0; i < n; i++) {
      auto s = std::make_unique<Source>();
      s->map = doc_maps[i];
      if (sources[i] == nullptr) {  // a source without Luxir postings: no terms in this field
        src.push_back(std::move(s));
        continue;
      }
      if (auto found = findField(*sources[i]->postings, field)) {
        s->info = *found;
        s->te = std::make_unique<TermsEnum>(s->pool, *sources[i]->postings, s->info);
      }
      src.push_back(std::move(s));
    }

    bool docsOnly = false;  // a native string field: docs-only postings, no norms
    for (auto& s : src) {
      if (s->te && !FieldType::hasPositions(s->info.flags)) docsOnly = true;
    }
    if (docsOnly) {
      auto& fi = w->pw->addField(field);
      fi.type = FieldType::STRING;
      fi.flags = FieldType::INDEX_DOCS;
      std::vector<uint64_t> withPostings((size_t)(w->pw->getMaxDoc() + 63) / 64);
      TextWriter tw(*w->pw);
      tw.startField(&fi);
      for (auto& s : src) s->live = s->te && s->te->nextTerm();
      std::string term;
      for (;;) {
        int32_t lowest = -1;
        for (int32_t i = 0; i < n; i++) {
          if (!src[i]->live) continue;
          if (lowest < 0 || std::string_view(src[i]->te->term()) < std::string_view(src[lowest]->te->term())) lowest = i;
        }
        if (lowest < 0) break;
        term.assign(std::string_view(src[lowest]->te->term()));
        std::optional<PackedTerm> started;
        int32_t last = -1;
        for (int32_t i = 0; i < n; i++) {
          auto& s = *src[i];
          if (!s.live || std::string_view(s.te->term()) != term) continue;
          auto state = s.te->postingsState();
          state.termImpactFrontier = {};
          DocsFreqEnum docs(state);
          for (int32_t d = docs.nextDoc(); d != PostingsReader::END; d = docs.nextDoc()) {
            int32_t nd = s.map[d];
            if (nd < 0) continue;
            if (nd <= last) return fail("lxl: doc maps must increase across sources");
            if (!started) {
              started = w->pw->copyTerm(term);
              tw.startTerm(*started);
            }
            tw.addDoc(nd, 1);
            last = nd;
            withPostings[(size_t)nd >> 6] |= 1ULL << (nd & 63);
          }
          s.live = s.te->nextTerm();
        }
        if (started) tw.endTerm(*started);
      }
      tw.endField();
      int64_t docCount = 0;
      for (uint64_t word : withPostings) docCount += std::popcount(word);
      return (int32_t)docCount;
    }

    // merged norms, in merged doc order
    std::vector<int32_t> normDocs;
    std::vector<uint8_t> normValues;
    for (int32_t i = 0; i < n; i++) {
      if (!src[i]->te || src[i]->info.normsFormat == SegFieldInfo::NORMS_NONE) continue;
      NormsReader norms(*sources[i]->postings, src[i]->info);
      NormsReader::Iterator it(norms);
      for (int32_t d = it.next(); d != NormsReader::ENDDOC; d = it.next()) {
        int32_t nd = src[i]->map[d];
        if (nd < 0) continue;
        if (!normDocs.empty() && nd <= normDocs.back()) return fail("lxl: doc maps must increase across sources");
        normDocs.push_back(nd);
        normValues.push_back(it.value());
      }
    }

    auto& fi = w->pw->addField(schemaName(field));  // as native flush names it
    fi.type = FieldType::TEXT;
    fi.flags = FieldType::INDEX_DOCS_FREQS_POSITIONS;
    w->fi = &fi;
    prepareNorms(*w, normDocs.data(), normValues.data(), (int32_t)normDocs.size());
    int64_t docCount = 0;  // docs with postings (norms may also cover docs with no tokens)
    {
      TextWriter tw(*w->pw);
      tw.startField(&fi);
      tw.setNorms(w->prepared.textView());
      // k-way merge of the sources' term dictionaries (unsigned byte order)
      for (auto& s : src) s->live = s->te && s->te->nextTerm();
      std::string term;
      std::vector<uint64_t> withPostings((size_t)(w->pw->getMaxDoc() + 63) / 64);
      for (;;) {
        int32_t lowest = -1;
        for (int32_t i = 0; i < n; i++) {
          if (!src[i]->live) continue;
          if (lowest < 0 || std::string_view(src[i]->te->term()) < std::string_view(src[lowest]->te->term())) lowest = i;
        }
        if (lowest < 0) break;
        term.assign(std::string_view(src[lowest]->te->term()));
        bool started = false;
        for (int32_t i = 0; i < n; i++) {
          auto& s = *src[i];
          if (!s.live || std::string_view(s.te->term()) != term) continue;
          auto state = s.te->postingsState();
          state.termImpactFrontier = {};
          DocsPosEnum docs(state);
          PosEnum pos(docs);
          for (int32_t d = docs.nextDoc(); d != PostingsReader::END; d = docs.nextDoc()) {
            int32_t nd = s.map[d];
            if (nd < 0) continue;
            if (!started) {
              tw.startTerm(w->pw->copyTerm(term));
              started = true;
            }
            tw.startDoc(nd);
            withPostings[(size_t)nd >> 6] |= 1ULL << (nd & 63);
            pos.startPositions();
            int32_t prev = -1;
            for (int32_t k = 0, tf = docs.termFreq(); k < tf; k++) {
              int32_t p = pos.nextPosition();
              tw.addPositionDelta(p - prev);
              prev = p;
            }
            tw.endDoc(nd);
          }
          s.live = s.te->nextTerm();
        }
        if (started) tw.endTerm(w->pw->copyTerm(term));
      }
      tw.endField();
      for (uint64_t word : withPostings) docCount += std::popcount(word);
    }
    writeNorms(*w);
    w->fi = nullptr;
    return (int32_t)docCount;
  });
}

}  // extern "C"

// ---- stored values, numeric columns and points -----------------------------------------------

namespace {

int32_t putVint(uint8_t* out, uint64_t v) {
  int32_t n = 0;
  while (v >= 0x80) {
    out[n++] = (uint8_t)(v | 0x80);
    v >>= 7;
  }
  out[n++] = (uint8_t)v;
  return n;
}


}  // namespace

namespace {


void writeNumeric(lxl_writer* w, std::string_view name, int32_t num_docs, const int32_t* docs,
                  const int32_t* counts, const int64_t* values) {
  if (num_docs <= 0) return;
  auto& fi = w->pw->addField(name);
  fi.type = FieldType::INT;
  fi.flags = 0;
  int32_t maxDoc = w->pw->getMaxDoc();
  auto out = w->pw->getOutputStream();
  {
    IntColWriter col(*out);
    int64_t nv = 0;
    if (counts == nullptr) {
      for (int32_t i = 0; i < num_docs; i++) col.addInt64(values[i]);
    } else {
      for (int32_t i = 0; i < num_docs; i++) {
        if (counts[i] <= 0) throw std::invalid_argument("lxl: a doc without values");
        for (int32_t k = 0; k < counts[i]; k++) col.addInt64(values[nv++]);
      }
    }
    col.finish(fi);
  }
  {
    auto& pool = MemPool::threadLocal();
    auto guard = pool.rewindScopeGuard();
    DocsWithValWriter dw(pool, *w->pw, fi);
    if (num_docs >= maxDoc) {
      dw.finishDense(num_docs);
    } else {
      for (int32_t i = 0; i < num_docs; i++) {
        if (docs[i] < 0 || docs[i] >= maxDoc || (i > 0 && docs[i] <= docs[i - 1])) {
          throw std::invalid_argument("lxl: numeric docs must ascend within the segment");
        }
        dw.startDoc(docs[i]);
      }
      dw.finish();
    }
  }
  if (counts != nullptr) {
    // value-rank end per doc, after the values in the same stream (so never at offset 0,
    // which readers take to mean single-valued)
    MonoWriter ends(MemPool::threadLocal(), *out);
    int64_t end = 0;
    for (int32_t i = 0; i < num_docs; i++) ends.addInt64(end += counts[i]);
    ends.finish();
    fi.monoLoc = ends.blockLoc;
    fi.monoMetaOff = ends.metaOff;
  }
}

struct PointRec {
  int64_t value;
  int32_t docid;
};

void writePoints(lxl_writer* w, std::string_view name, std::vector<PointRec>& pts) {
  if (pts.empty()) return;
  sortPointsByValueDocid(std::span<PointRec>(pts));
  auto& fi = w->pw->addField(name);
  fi.type = FieldType::INT;
  fi.flags = 0;
  auto out = w->pw->getOutputStream();
  PointsWriter pw(*out, PointsWriter::Options{});
  for (const auto& p : pts) pw.addPoint(p.value, p.docid);
  auto data = pw.finish();
  fi.pointsLoc = data.pointsLoc;
  fi.pointsMetaOff = data.pointsMetaOff;
}

// A dictionary: entries are bytes [offsets[i] - offsets[0], offsets[i+1] - offsets[0]).
void writeDict(lxl_writer* w, std::string_view name, int64_t n, const char* bytes, const int64_t* offsets) {
  if (n <= 0) return;
  auto& fi = w->pw->addField(name);
  fi.type = FieldType::BIN;
  fi.flags = 0;
  auto out = w->pw->getOutputStream();
  fi.columnLoc = seg_location(out->streamNumber, out->size());
  fi.columnMetaOff = offsets[n] - offsets[0];
  fi.numValues = n;
  out->write(bytes + offsets[0], (size_t)(offsets[n] - offsets[0]));
  MonoWriter ends(MemPool::threadLocal(), *out);
  for (int64_t i = 1; i <= n; i++) {
    if (offsets[i] < offsets[i - 1]) throw std::invalid_argument("lxl: dictionary offsets must not decrease");
    ends.addInt64(offsets[i] - offsets[0]);
  }
  ends.finish();
  fi.monoLoc = ends.blockLoc;
  fi.monoMetaOff = ends.metaOff;
}

// The merged doc of source doc d (-1: deleted).
inline int32_t mapDoc(const int32_t* map, int32_t base, int32_t d) {
  return map == nullptr ? base + d : map[d];
}

// A source column's docs and values, remapped (ords through ordMap when given) and appended.
void appendColumn(PostingsReader& pr, const SegFieldInfo& info, const int32_t* map, int32_t base,
                  const int64_t* ordMap, bool multiOut, std::vector<int32_t>& docs,
                  std::vector<int32_t>& counts, std::vector<int64_t>& values) {
  IntColReader col(pr, info);
  IntColReader::Iterator it(col);
  bool multi = col.multiValued();
  for (int32_t d = it.next(); d != IntColReader::ENDDOC; d = it.next()) {
    int32_t nd = mapDoc(map, base, d);
    if (nd < 0) continue;
    if (!docs.empty() && nd <= docs.back()) throw std::invalid_argument("lxl: doc maps must increase across sources");
    if (!multi) {
      int64_t v = it.value();
      values.push_back(ordMap ? ordMap[v] : v);
      if (multiOut) counts.push_back(1);
    } else {
      if (!multiOut) throw std::invalid_argument("lxl: multi-valued source for a single-valued column");
      auto [start, end] = col.getStartEndValueRank(it.rank());
      for (int64_t r = start; r < end; r++) {
        int64_t v = it.values().valueAt(r);
        values.push_back(ordMap ? ordMap[v] : v);
      }
      counts.push_back((int32_t)(end - start));
    }
    docs.push_back(nd);
  }
}

struct DictView {
  const char* bytes = nullptr;
  int64_t count = 0;
  std::unique_ptr<MonoReader> ends;

  std::string_view term(int64_t ord) const {
    auto [start, end] = ends->valuesAt(ord);
    return {bytes + start, (size_t)(end - start)};
  }
};

std::optional<DictView> openDict(PostingsReader& pr, std::string_view name) {
  auto info = findField(pr, name);
  if (!info) return std::nullopt;
  DictView d;
  d.count = info->numValues;
  d.bytes = pr.getInputStreamSeek(info->columnLoc).ptr();
  d.ends = std::make_unique<MonoReader>(pr, info->monoLoc, info->monoMetaOff, info->numValues);
  return d;
}

}  // namespace

struct lxl_stored {
  std::unique_ptr<StoredFieldsReader> sf;
  std::vector<int32_t> ids;  // segment-local stored field -> caller's field id
};

struct lxl_numeric {
  std::unique_ptr<IntColReader> col;
  std::unique_ptr<IntColReader::Iterator> it;
  int32_t cur = -1;  // the iterator's doc: the next one not yet returned
  bool multi = false;
};

struct lxl_points {
  std::unique_ptr<PointsReader> pts;
};

PointsReader* lxlPointsReader(lxl_points* p) { return p->pts.get(); }

struct lxl_dict {
  const char* bytes = nullptr;
  int64_t count = 0;
  std::unique_ptr<MonoReader> ends;
};

extern "C" {

int32_t lxl_writer_stored(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* fields,
                          const uint8_t* data, const int64_t* offsets) {
  return guarded([&] {
    if (w->inverter) return fail("lxl: stored values need a plain writer");
    if (!w->stored) w->stored = std::make_unique<StoredFieldsWriter>(*w->pw);
    for (int32_t i = 0; i < n; i++) {
      int32_t f = fields[i];
      if (f < 0) return fail("lxl: bad stored field id");
      while ((size_t)f >= w->storedNames.size()) w->storedNames.push_back(std::to_string(w->storedNames.size()));
      w->stored->addValue(docs[i], w->storedNames[(size_t)f],
                          std::string_view((const char*)data + offsets[i], (size_t)(offsets[i + 1] - offsets[i])));
    }
    return 0;
  });
}

int32_t lxl_writer_numeric(lxl_writer* w, const char* name, int32_t name_len, int32_t num_docs,
                           const int32_t* docs, const int32_t* counts, const int64_t* values) {
  return guarded([&] {
    writeNumeric(w, std::string_view(name, (size_t)name_len), num_docs, docs, counts, values);
    return 0;
  });
}

int32_t lxl_writer_points(lxl_writer* w, const char* name, int32_t name_len, int64_t n,
                          const int32_t* docs, const int64_t* values) {
  return guarded([&] {
    std::vector<PointRec> pts((size_t)std::max<int64_t>(n, 0));
    for (int64_t i = 0; i < n; i++) pts[(size_t)i] = {values[i], docs[i]};
    writePoints(w, std::string_view(name, (size_t)name_len), pts);
    return 0;
  });
}

int32_t lxl_writer_dict(lxl_writer* w, const char* name, int32_t name_len, int64_t n,
                        const uint8_t* bytes, const int64_t* offsets) {
  return guarded([&] {
    writeDict(w, std::string_view(name, (size_t)name_len), n, (const char*)bytes, offsets);
    return 0;
  });
}

int32_t lxl_writer_numeric_merge(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                                 lxl_reader* const* sources, const char* const* columns,
                                 const int32_t* bases, const int32_t* const* doc_maps, int32_t multi,
                                 int64_t* stats) {
  return guarded([&] {
    std::vector<int32_t> docs, counts;
    std::vector<int64_t> values;
    for (int32_t i = 0; i < n; i++) {
      if (sources[i] == nullptr || columns[i] == nullptr) continue;
      auto info = findField(*sources[i]->postings, columns[i]);
      if (!info) continue;
      appendColumn(*sources[i]->postings, *info, doc_maps[i], bases[i], nullptr, multi != 0, docs, counts, values);
    }
    writeNumeric(w, std::string_view(name, (size_t)name_len), (int32_t)docs.size(), docs.data(),
                 multi ? counts.data() : nullptr, values.data());
    stats[0] = (int64_t)docs.size();
    stats[1] = (int64_t)values.size();
    return 0;
  });
}

int32_t lxl_writer_sorted_merge(lxl_writer* w, const char* column, int32_t column_len, const char* dict,
                                int32_t dict_len, int32_t n, lxl_reader* const* sources,
                                const char* const* columns, const char* const* dicts, const int32_t* bases,
                                const int32_t* const* doc_maps, int32_t multi, int64_t* stats) {
  return guarded([&] {
    struct Src {
      std::optional<SegFieldInfo> col;
      std::optional<DictView> dict;
      std::vector<int64_t> ordMap;  // source ord -> merged ord (-1: no live doc uses it)
      std::vector<uint8_t> live;    // per source ord, whether a live doc uses it
      int64_t pos = 0;
    };
    std::vector<Src> src((size_t)n);
    for (int32_t i = 0; i < n; i++) {
      if (sources[i] == nullptr || columns[i] == nullptr || dicts[i] == nullptr) continue;
      PostingsReader& pr = *sources[i]->postings;
      Src& s = src[(size_t)i];
      s.col = findField(pr, columns[i]);
      s.dict = openDict(pr, dicts[i]);
      if (!s.col || !s.dict) {
        s.col.reset();
        continue;
      }
      // terms used by live docs (all of them without deletions)
      s.live.assign((size_t)s.dict->count, doc_maps[i] == nullptr ? 1 : 0);
      if (doc_maps[i] != nullptr) {
        IntColReader c(pr, *s.col);
        IntColReader::Iterator it(c);
        bool m = c.multiValued();
        for (int32_t d = it.next(); d != IntColReader::ENDDOC; d = it.next()) {
          if (doc_maps[i][d] < 0) continue;
          if (!m) {
            s.live[(size_t)it.value()] = 1;
          } else {
            auto [start, end] = c.getStartEndValueRank(it.rank());
            for (int64_t r = start; r < end; r++) s.live[(size_t)it.values().valueAt(r)] = 1;
          }
        }
      }
      s.ordMap.assign((size_t)s.dict->count, -1);
    }
    // k-way merge of the live terms (unsigned byte order)
    std::string bytes;
    std::vector<int64_t> offsets{0};
    auto skipDead = [&](Src& s) {
      while (s.pos < s.dict->count && !s.live[(size_t)s.pos]) s.pos++;
    };
    for (auto& s : src) {
      if (s.col) skipDead(s);
    }
    for (;;) {
      int32_t lowest = -1;
      std::string_view lowTerm;
      for (int32_t i = 0; i < n; i++) {
        Src& s = src[(size_t)i];
        if (!s.col || s.pos >= s.dict->count) continue;
        std::string_view t = s.dict->term(s.pos);
        if (lowest < 0 || t < lowTerm) {
          lowest = i;
          lowTerm = t;
        }
      }
      if (lowest < 0) break;
      int64_t ord = (int64_t)offsets.size() - 1;
      bytes.append(lowTerm);
      offsets.push_back((int64_t)bytes.size());
      std::string term(lowTerm);  // lowTerm may point into a source that advances below
      for (int32_t i = 0; i < n; i++) {
        Src& s = src[(size_t)i];
        if (!s.col || s.pos >= s.dict->count) continue;
        if (s.dict->term(s.pos) == term) {
          s.ordMap[(size_t)s.pos] = ord;
          s.pos++;
          skipDead(s);
        }
      }
    }
    std::vector<int32_t> docs, counts;
    std::vector<int64_t> values;
    for (int32_t i = 0; i < n; i++) {
      Src& s = src[(size_t)i];
      if (!s.col) continue;
      appendColumn(*sources[i]->postings, *s.col, doc_maps[i], bases[i], s.ordMap.data(), multi != 0, docs, counts,
                   values);
    }
    int64_t terms = (int64_t)offsets.size() - 1;
    if (!docs.empty()) {
      writeNumeric(w, std::string_view(column, (size_t)column_len), (int32_t)docs.size(), docs.data(),
                   multi ? counts.data() : nullptr, values.data());
      writeDict(w, std::string_view(dict, (size_t)dict_len), terms, bytes.data(), offsets.data());
    }
    stats[0] = (int64_t)docs.size();
    stats[1] = (int64_t)values.size();
    stats[2] = docs.empty() ? 0 : terms;
    return 0;
  });
}

int32_t lxl_writer_points_merge(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                                lxl_reader* const* sources, const char* const* fields, const int32_t* bases,
                                const int32_t* const* doc_maps, int64_t* stats) {
  return guarded([&] {
    std::vector<PointRec> pts;
    std::vector<int64_t> vals;
    std::vector<uint32_t> ids, scratch;
    int32_t maxDoc = w->pw->getMaxDoc();
    std::vector<uint64_t> withPoints((size_t)(maxDoc + 63) / 64);
    for (int32_t i = 0; i < n; i++) {
      if (sources[i] == nullptr || fields[i] == nullptr) continue;
      auto info = findField(*sources[i]->postings, fields[i]);
      if (!info || info->pointsMetaOff == 0) continue;
      PointsReader in(*sources[i]->postings, *info);
      size_t m = in.maxPointsPerLeaf();
      vals.resize(m);
      ids.resize(m);
      scratch.resize(m);
      pts.reserve(pts.size() + in.pointCount());
      for (uint32_t leaf = 0; leaf < in.leafCount(); leaf++) {
        uint16_t count = in.decodeLeafInto(leaf, std::span<int64_t>(vals.data(), m), std::span<uint32_t>(ids.data(), m),
                                           std::span<uint32_t>(scratch.data(), m));
        for (uint16_t k = 0; k < count; k++) {
          int32_t nd = mapDoc(doc_maps[i], bases[i], (int32_t)ids[k]);
          if (nd < 0) continue;
          if (nd >= maxDoc) throw std::invalid_argument("lxl: merged doc beyond the segment");
          pts.push_back({vals[k], nd});
          withPoints[(size_t)nd >> 6] |= 1ULL << (nd & 63);
        }
      }
    }
    int64_t docCount = 0;
    for (uint64_t word : withPoints) docCount += std::popcount(word);
    stats[0] = (int64_t)pts.size();
    stats[1] = docCount;
    writePoints(w, std::string_view(name, (size_t)name_len), pts);
    return 0;
  });
}

int32_t lxl_writer_stored_merge(lxl_writer* w, int32_t n, lxl_reader* const* sources, const int32_t* bases,
                                const int32_t* const* doc_maps, const int32_t* const* field_maps,
                                const int32_t* field_map_lens) {
  return guarded([&] {
    if (w->inverter) return fail("lxl: stored values need a plain writer");
    if (!w->stored) w->stored = std::make_unique<StoredFieldsWriter>(*w->pw);
    StoredFieldsWriter& out = *w->stored;
    std::vector<int64_t> firstDocs, offsets;
    int32_t raw = 0;
    for (int32_t i = 0; i < n; i++) {
      if (sources[i] == nullptr) continue;  // nothing stored: the writer pads its docs
      PostingsReader& pr = *sources[i]->postings;
      const PostingsReader::Field* info = pr.fieldInfo(Postings::STORED_DEFAULT_RESOURCE);
      if (info == nullptr) continue;
      StoredFieldsReader in(pr, *info);
      // source field (by local id) -> target name; identity when the caller's ids are unchanged
      auto names = in.fieldNames();
      std::vector<const std::string*> target(names.size(), nullptr);
      bool identity = true;
      for (size_t f = 0; f < names.size(); f++) {
        int32_t id = 0;
        auto [p, ec] = std::from_chars(names[f].data(), names[f].data() + names[f].size(), id);
        if (ec != std::errc() || id < 0) return fail("lxl: bad stored field id");
        int32_t to = id < field_map_lens[i] ? field_maps[i][id] : -1;
        if (to < 0) {
          identity = false;
          continue;
        }
        while ((size_t)to >= w->storedNames.size()) w->storedNames.push_back(std::to_string(w->storedNames.size()));
        target[f] = &w->storedNames[(size_t)to];
        if (to != id) identity = false;
      }
      // Verbatim chunk copy: no deletions maps every doc to base + doc, and compressed
      // chunks are position-independent, so they are appended without decompression.
      if (doc_maps[i] == nullptr && identity && out.canCopyChunks(in.format(), in.dictionary())
          && !StoredFieldsWriter::tooDirty(in.numChunks(), in.numDirtyChunks()) && out.adoptFieldTable(names)) {
        out.alignForRawAppend(bases[i]);
        in.readChunkDirectory(firstDocs, offsets);
        out.appendRawChunkRegion(bases[i], in.chunkRegionPtr(), in.chunkRegionBytes(), firstDocs, offsets,
                                 in.maxDoc(), in.maxChunkBytes(), in.numDirtyChunks());
        raw++;
        continue;
      }
      int32_t maxDoc = pr.maxDoc();
      for (int32_t d = 0; d < maxDoc; d++) {
        int32_t nd = doc_maps[i] == nullptr ? bases[i] + d : doc_maps[i][d];
        if (nd < 0) continue;
        in.readDoc(d, [&](std::string_view name, std::span<const std::string_view> values) {
          const std::string* t = target[(size_t)in.fieldId(name)];
          if (t == nullptr) return;
          if (values.size() == 1) out.addValue(nd, *t, values[0]);
          else out.addValues(nd, *t, values);
        });
      }
    }
    return raw;
  });
}

lxl_dict* lxl_dict_new(lxl_reader* r, const char* name, int32_t name_len, int64_t* count) {
  return guardedPtr<lxl_dict>([&]() -> lxl_dict* {
    auto info = findField(*r->postings, std::string_view(name, (size_t)name_len));
    if (!info) throw std::runtime_error("lxl: no such dictionary");
    auto d = std::make_unique<lxl_dict>();
    d->count = info->numValues;
    d->bytes = r->postings->getInputStreamSeek(info->columnLoc).ptr();
    d->ends = std::make_unique<MonoReader>(*r->postings, info->monoLoc, info->monoMetaOff, info->numValues);
    *count = d->count;
    return d.release();
  });
}

void lxl_dict_free(lxl_dict* d) { delete d; }

int32_t lxl_dict_lookup(lxl_dict* d, int64_t ord, uint8_t* out, int32_t cap) {
  return guarded([&] {
    if (ord < 0 || ord >= d->count) return fail("lxl: ordinal out of range");
    auto [start, end] = d->ends->valuesAt(ord);
    int64_t len = end - start;
    if (len <= cap) std::memcpy(out, d->bytes + start, (size_t)len);
    return (int32_t)len;
  });
}

lxl_stored* lxl_stored_new(lxl_reader* r) {
  return guardedPtr<lxl_stored>([&]() -> lxl_stored* {
    const PostingsReader::Field* info = r->postings->fieldInfo(Postings::STORED_DEFAULT_RESOURCE);
    if (info == nullptr) throw std::runtime_error("lxl: no stored values in this segment");
    auto s = std::make_unique<lxl_stored>();
    s->sf = std::make_unique<StoredFieldsReader>(*r->postings, *info);
    for (auto name : s->sf->fieldNames()) {
      int32_t id = 0;
      auto [p, ec] = std::from_chars(name.data(), name.data() + name.size(), id);
      if (ec != std::errc() || p != name.data() + name.size()) throw std::runtime_error("lxl: bad stored field id");
      s->ids.push_back(id);
    }
    return s.release();
  });
}

void lxl_stored_free(lxl_stored* s) { delete s; }

int32_t lxl_stored_doc(lxl_stored* s, int32_t doc, uint8_t* out, int32_t cap) {
  return guarded([&] {
    int64_t need = 0;
    uint8_t hdr[20];
    s->sf->readDoc(doc, [&](std::string_view name, std::span<const std::string_view> values) {
      int32_t fid = s->sf->fieldId(name);
      int32_t id = s->ids[(size_t)fid];
      for (auto v : values) {
        int32_t h = putVint(hdr, (uint64_t)id);
        h += putVint(hdr + h, v.size());
        if (need + h + (int64_t)v.size() <= cap) {
          std::memcpy(out + need, hdr, (size_t)h);
          std::memcpy(out + need + h, v.data(), v.size());
        }
        need += h + (int64_t)v.size();
      }
    });
    if (need > std::numeric_limits<int32_t>::max()) return fail("lxl: stored document too large");
    return (int32_t)need;
  });
}

}  // extern "C"

namespace {

void openNumeric(lxl_numeric& c, lxl_reader* r, const char* name, int32_t name_len) {
  auto info = findField(*r->postings, std::string_view(name, (size_t)name_len));
  if (!info) throw std::runtime_error("lxl: no such numeric column");
  c.col = std::make_unique<IntColReader>(*r->postings, *info);
  c.it = std::make_unique<IntColReader::Iterator>(*c.col);
  c.multi = c.col->multiValued();
}

int32_t fillNumeric(lxl_numeric* c, int32_t target, int32_t* docs, int32_t* counts, int64_t* values, int32_t cap,
                    int32_t value_cap);

}  // namespace

extern "C" {

lxl_numeric* lxl_numeric_new(lxl_reader* r, const char* name, int32_t name_len) {
  return guardedPtr<lxl_numeric>([&]() -> lxl_numeric* {
    auto c = std::make_unique<lxl_numeric>();
    openNumeric(*c, r, name, name_len);
    return c.release();
  });
}

void lxl_numeric_free(lxl_numeric* c) { delete c; }

int32_t lxl_numeric_fill(lxl_numeric* c, int32_t target, int32_t* docs, int32_t* counts,
                         int64_t* values, int32_t cap, int32_t value_cap) {
  return guarded([&] { return fillNumeric(c, target, docs, counts, values, cap, value_cap); });
}

int32_t lxl_numeric_fill_once(lxl_reader* r, const char* name, int32_t name_len, int32_t target, int32_t* docs,
                              int32_t* counts, int64_t* values, int32_t cap, int32_t value_cap) {
  return guarded([&] {
    lxl_numeric c;
    openNumeric(c, r, name, name_len);
    return fillNumeric(&c, target, docs, counts, values, cap, value_cap);
  });
}

}  // extern "C"

namespace {

// Up to cap docs >= target with their values, as lxl_numeric_fill.
int32_t fillNumeric(lxl_numeric* c, int32_t target, int32_t* docs, int32_t* counts, int64_t* values, int32_t cap,
                    int32_t value_cap) {
  auto& it = *c->it;
  if (c->cur < target) c->cur = it.advance(target);
  int32_t n = 0;
  int32_t nv = 0;
  while (c->cur != IntColReader::ENDDOC && n < cap) {
    if (c->multi) {
      auto [start, end] = c->col->getStartEndValueRank(it.rank());
      int32_t k = (int32_t)(end - start);
      if (nv + k > value_cap) {
        if (n == 0) return fail("lxl: value buffer too small");
        break;
      }
      for (int32_t j = 0; j < k; j++) values[nv++] = it.values().valueAt(start + j);
      counts[n] = k;
    } else {
      values[n] = it.value();
    }
    docs[n++] = c->cur;
    c->cur = it.next();
  }
  return n;
}

}  // namespace

// Sortable int64 key of a column value: raw longs (ints, longs, dates, ordinals), or float/double
// bits mapped so that signed comparison follows Float.compare / Double.compare.
static inline int64_t sortKey(int32_t kind, int64_t v) {
  if (kind == LXL_SORT_FLOAT_BITS) {
    int32_t bits = (int32_t)v;
    return (int64_t)(bits ^ ((bits >> 31) & 0x7fffffff));
  }
  if (kind == LXL_SORT_DOUBLE_BITS) return v ^ ((v >> 63) & 0x7fffffffffffffffLL);
  return v;
}

// The best sort key any value of a column block can have (its zone's max, or min when ascending),
// or nullopt when the zone cannot bound keys (float/double bits with negative values, whose raw
// order is not numeric order).
static inline std::optional<int64_t> blockBest(const NumBlockZone& z, int32_t kind, bool reverse) {
  if (kind != LXL_SORT_LONG && z.min < 0) return std::nullopt;
  return sortKey(kind, reverse ? z.max : z.min);
}

// The best k docs of a segment by a single-valued column's sort key, offered matching docs in
// increasing order. Ties go to the lower doc, whatever the direction (Lucene's). Without a column
// (no values in the segment), every doc takes the missing key.
template <typename It>
class ColumnTopK {
  IntColReader* col;
  std::optional<It> it;
  int32_t kind;
  int32_t k;
  bool reverse;
  int64_t missing;
  // As the heap's "less", the heap's top is the worst kept, and sort_heap yields best first.
  std::vector<std::pair<int64_t, int32_t>> heap;
  int32_t cur = -1;
  // zone pruning: once k docs are kept, a block whose best key cannot beat the worst kept is skipped
  int64_t zoneBlock = -1;
  std::optional<int64_t> zoneBest;

  // before(a, b): a ranks before b
  bool before(const std::pair<int64_t, int32_t>& a, const std::pair<int64_t, int32_t>& b) const {
    if (a.first != b.first) return reverse ? a.first > b.first : a.first < b.first;
    return a.second < b.second;
  }

  // strictly better key (docs come in increasing order, so a tie with the worst kept never enters)
  bool betterKey(int64_t a, int64_t b) const { return reverse ? a > b : a < b; }

public:
  ColumnTopK(IntColReader* col, int32_t kind, int32_t k, bool reverse, int64_t missing)
      : col(col), kind(kind), k(k), reverse(reverse), missing(missing) {
    if (col != nullptr) it.emplace(*col);
    heap.reserve((size_t)k);
  }

  void offer(int32_t d) {
    bool full = (int32_t)heap.size() == k;
    int64_t key = missing;
    if (it) {
      if (cur < d) cur = it->advance(d);
      if (cur == d) {
        if (full) {
          int64_t block = it->rank() / (int64_t)IntColReader::BLOCK_SIZE;
          if (block != zoneBlock) {
            zoneBlock = block;
            zoneBest = blockBest(col->blockZone(block), kind, reverse);
          }
          if (zoneBest && !betterKey(*zoneBest, heap.front().first)) return;
        }
        key = sortKey(kind, it->value());
      }
    } else if (full) {
      return;  // every key is the missing one: the lowest docs are kept
    }
    std::pair<int64_t, int32_t> e{key, d};
    auto less = [this](const auto& a, const auto& b) { return before(a, b); };
    if (!full) {
      heap.push_back(e);
      std::push_heap(heap.begin(), heap.end(), less);
    } else if (before(e, heap.front())) {
      std::pop_heap(heap.begin(), heap.end(), less);
      heap.back() = e;
      std::push_heap(heap.begin(), heap.end(), less);
    }
  }

  // Writes the kept docs best first; returns how many.
  int32_t finish(int32_t* docs_out, int64_t* keys_out) {
    std::sort_heap(heap.begin(), heap.end(), [this](const auto& a, const auto& b) { return before(a, b); });
    for (size_t i = 0; i < heap.size(); i++) {
      keys_out[i] = heap[i].first;
      docs_out[i] = heap[i].second;
    }
    return (int32_t)heap.size();
  }
};

template <typename It>
static int32_t columnTopK(IntColReader& col, int32_t kind, const uint64_t* matches, int32_t max_doc, int32_t k,
                          bool reverse, int64_t missing, int32_t* docs_out, int64_t* keys_out) {
  ColumnTopK<It> top(&col, kind, k, reverse, missing);
  size_t words = FixedBitSet::sizeInWords(max_doc);
  for (size_t w = 0; w < words; w++) {
    uint64_t word = matches[w];
    while (word != 0) {
      int32_t d = (int32_t)(w << 6) + std::countr_zero(word);
      word &= word - 1;
      if (d >= max_doc) break;
      top.offer(d);
    }
  }
  return top.finish(docs_out, keys_out);
}

extern "C" {

int32_t lxl_column_top_k(lxl_reader* r, const char* name, int32_t name_len, int32_t kind, const uint64_t* matches,
                         int32_t num_matches, int32_t max_doc, int32_t k, int32_t reverse, int64_t missing_key,
                         int32_t* docs_out, int64_t* keys_out) {
  return guarded([&]() -> int32_t {
    if (k <= 0 || num_matches == 0) return 0;
    auto info = findField(*r->postings, std::string_view(name, (size_t)name_len));
    if (!info) throw std::runtime_error("lxl: no such column");
    IntColReader col(*r->postings, *info);
    if (col.multiValued()) throw std::invalid_argument("lxl: sorting needs a single-valued column");
    // a few matches skip far between values: decode them one by one
    if ((int64_t)num_matches * 16 < max_doc) {
      return columnTopK<IntColReader::SparseIterator>(col, kind, matches, max_doc, k, reverse != 0, missing_key,
                                                     docs_out, keys_out);
    }
    return columnTopK<IntColReader::BulkIterator>(col, kind, matches, max_doc, k, reverse != 0, missing_key, docs_out,
                                                 keys_out);
  });
}

int32_t lxl_multi_top_k_sorted(lxl_multi* m, const uint8_t* query, int64_t query_len, const uint64_t* const* live,
                               const int32_t* num_live, int32_t num_slots, const uint64_t* const* slot_bits,
                               const int32_t* slot_counts, int32_t num_cols, lxl_reader* const* col_stores,
                               const char* const* col_names, lxl_points* const* col_points,
                               const int64_t* col_offsets, lxl_reader* const* sort_stores,
                               const char* const* sort_cols, int32_t kind, int32_t k, int32_t reverse,
                               int64_t missing_key, int32_t* seg_counts, int32_t* docs_out, int64_t* keys_out,
                               int64_t* total_hits) {
  return guarded([&]() -> int32_t {
    if (k <= 0) throw std::invalid_argument("lxl: k must be positive");
    MultiCall call(m, query, query_len, live, num_live, num_slots, slot_bits, slot_counts, num_cols, col_stores,
                   col_names, col_points, col_offsets);
    Prepared& prepared = *call.prepared;
    int64_t count = 0;
    auto* w = prepared.weight(0);
    for (size_t i = 0; i < call.n; i++) {
      seg_counts[i] = 0;
      if (live[i] != nullptr && num_live[i] == 0) continue;
      Segment& seg = call.segments[i];
      auto* supplier = w->scorerSupplier(prepared.pool, seg);
      if (supplier == nullptr) continue;
      int32_t maxDoc = seg.maxDoc();
      DocSet* filter = call.filterOf(i);
      std::optional<IntColReader> col;
      if (sort_stores[i] != nullptr) {
        auto info = findField(*sort_stores[i]->postings, std::string_view(sort_cols[i]));
        if (!info) throw std::runtime_error("lxl: no such column");
        col.emplace(*sort_stores[i]->postings, *info);
        if (col->multiValued()) throw std::invalid_argument("lxl: sorting needs a single-valued column");
      }
      // each segment's matches go straight to its top k, as they are found: no match bits
      auto collect = [&](auto& top) {
        if (BulkScorer* bulk = bulkFor(prepared.pool, *supplier, Query::ExecutionUse::MATCH_WINDOWS)) {
          int32_t cursor = 0;
          while (cursor != PostingsReader::END && cursor < maxDoc) {
            ScoreWindow window;
            int32_t next = bulk->matchNextWindow(window, filter, cursor, maxDoc);
            for (int32_t j = 0; j < window.size; j++) top.offer(window.docs[(size_t)j]);
            count += window.size;
            if (next == PostingsReader::END) break;
            cursor = next;
          }
        } else if (auto* s = pullScorer(prepared.pool, *supplier)) {
          for (int32_t d = s->next(); d != PostingsReader::END; d = s->next()) {
            if (filter != nullptr && !filter->get(d)) continue;
            top.offer(d);
            count++;
          }
        }
        return top.finish(docs_out + i * (size_t)k, keys_out + i * (size_t)k);
      };
      IntColReader* c = col ? &*col : nullptr;
      // a few matches skip far between values: decode them one by one
      if (std::min<int64_t>(supplier->cost(), maxDoc) * 16 < maxDoc) {
        ColumnTopK<IntColReader::SparseIterator> top(c, kind, k, reverse != 0, missing_key);
        seg_counts[i] = collect(top);
      } else {
        ColumnTopK<IntColReader::BulkIterator> top(c, kind, k, reverse != 0, missing_key);
        seg_counts[i] = collect(top);
      }
    }
    *total_hits = count;
    return 0;
  });
}

}  // extern "C"

namespace {

// Visits each matching doc (ascending) with the column's values for it (empty: no value).
template <typename It, typename F>
void forEachMatch(IntColReader& col, const uint64_t* matches, int32_t max_doc, F&& f) {
  It it(col);
  bool multi = col.multiValued();
  int32_t cur = -1;
  thread_local std::vector<int64_t> tlVals;
  auto& vals = tlVals;  // one TLS lookup per call, not per doc
  size_t words = FixedBitSet::sizeInWords(max_doc);
  if (!multi && col.docsWithValue() >= max_doc) {
    // every doc has a value, so a doc's value rank is the doc itself: read values straight from
    // the decoded frames, without stepping the docs iterator
    auto& values = it.values();
    for (size_t w = 0; w < words; w++) {
      uint64_t word = matches[w];
      while (word != 0) {
        int32_t d = (int32_t)(w << 6) + std::countr_zero(word);
        word &= word - 1;
        if (d >= max_doc) return;
        int64_t one = values.valueAt(d);
        f(d, std::span<const int64_t>(&one, 1));
      }
    }
    return;
  }
  for (size_t w = 0; w < words; w++) {
    uint64_t word = matches[w];
    while (word != 0) {
      int32_t d = (int32_t)(w << 6) + std::countr_zero(word);
      word &= word - 1;
      if (d >= max_doc) return;
      if (cur < d) cur = it.advance(d);
      if (cur != d) {
        f(d, std::span<const int64_t>());
      } else if (!multi) {
        int64_t one = it.value();
        f(d, std::span<const int64_t>(&one, 1));
      } else {
        vals.clear();
        auto [start, end] = col.getStartEndValueRank(it.rank());
        for (auto i = start; i < end; i++) vals.push_back(it.values().valueAt(i));
        f(d, std::span<const int64_t>(vals));
      }
    }
  }
}

// As forEachMatch, for the n matching docs listed (ascending) in docs. Kept apart from the bitset
// walk: sharing one loop body made the compiler's code for that walk measurably slower.
template <typename It, typename F>
void forEachListed(IntColReader& col, const int32_t* docs, int32_t n, int32_t max_doc, F&& f) {
  It it(col);
  bool multi = col.multiValued();
  int32_t cur = -1;
  thread_local std::vector<int64_t> tlVals;
  auto& vals = tlVals;  // one TLS lookup per call, not per doc
  if (!multi && col.docsWithValue() >= max_doc) {
    auto& values = it.values();
    for (int32_t i = 0; i < n; i++) {
      int32_t d = docs[i];
      int64_t one = values.valueAt(d);
      f(d, std::span<const int64_t>(&one, 1));
    }
    return;
  }
  for (int32_t i = 0; i < n; i++) {
    int32_t d = docs[i];
    if (cur < d) cur = it.advance(d);
    if (cur != d) {
      f(d, std::span<const int64_t>());
    } else if (!multi) {
      int64_t one = it.value();
      f(d, std::span<const int64_t>(&one, 1));
    } else {
      vals.clear();
      auto [start, end] = col.getStartEndValueRank(it.rank());
      for (auto v = start; v < end; v++) vals.push_back(it.values().valueAt(v));
      f(d, std::span<const int64_t>(vals));
    }
  }
}

// As forEachMatch over the column `name`; docs, when not null, lists the matches instead of matches.
template <typename F>
void scanColumn(lxl_reader* r, const char* name, int32_t name_len, const uint64_t* matches, const int32_t* docs,
                int32_t num_matches, int32_t max_doc, F&& f) {
  auto info = findField(*r->postings, std::string_view(name, (size_t)name_len));
  if (!info) throw std::runtime_error("lxl: no such column");
  IntColReader col(*r->postings, *info);
  bool sparse = (int64_t)num_matches * 16 < max_doc;
  if (docs != nullptr) {
    if (sparse) {
      forEachListed<IntColReader::SparseIterator>(col, docs, num_matches, max_doc, f);
    } else {
      forEachListed<IntColReader::BulkIterator>(col, docs, num_matches, max_doc, f);
    }
  } else if (sparse) {
    forEachMatch<IntColReader::SparseIterator>(col, matches, max_doc, f);
  } else {
    forEachMatch<IntColReader::BulkIterator>(col, matches, max_doc, f);
  }
}

template <typename F>
void scanColumn(lxl_reader* r, const char* name, int32_t name_len, const uint64_t* matches, int32_t num_matches,
                int32_t max_doc, F&& f) {
  scanColumn(r, name, name_len, matches, nullptr, num_matches, max_doc, std::forward<F>(f));
}

}  // namespace

namespace {

// Sets in out every doc with a point in [lo, hi].
void pointsFill(PointsReader& pts, int64_t lo, int64_t hi, int32_t max_doc, uint64_t* out) {
  if (lo > hi) return;
  uint32_t n = pts.leafCount();
  thread_local std::vector<int64_t> tlVals;
  thread_local std::vector<uint32_t> tlIds, tlScratch;
  auto& vals = tlVals;  // one TLS lookup per call, not per point
  auto& ids = tlIds;
  auto& scratch = tlScratch;
  size_t m = pts.maxPointsPerLeaf();
  if (vals.size() < m) {
    vals.resize(m);
    ids.resize(m);
    scratch.resize(m);
  }
  // first leaf whose max reaches lo (maxes are non-decreasing)
  uint32_t a = 0, b = n;
  while (a < b) {
    uint32_t mid = (a + b) >> 1;
    if (pts.leafMax(mid) < lo) a = mid + 1;
    else b = mid;
  }
  for (uint32_t leaf = a; leaf < n && pts.leafMin(leaf) <= hi; leaf++) {
    uint16_t count = pts.decodeLeafInto(leaf, std::span<int64_t>(vals.data(), m), std::span<uint32_t>(ids.data(), m),
                                        std::span<uint32_t>(scratch.data(), m));
    bool whole = pts.leafMin(leaf) >= lo && pts.leafMax(leaf) <= hi;
    for (uint16_t j = 0; j < count; j++) {
      if (!whole && (vals[j] < lo || vals[j] > hi)) continue;
      uint32_t d = ids[j];
      if ((int32_t)d < max_doc) out[d >> 6] |= 1ULL << (d & 63);
    }
  }
}

int32_t popcount(const uint64_t* bits, int32_t max_doc) {
  int64_t card = 0;
  size_t words = FixedBitSet::sizeInWords(max_doc);
  for (size_t w = 0; w < words; w++) card += std::popcount(bits[w]);
  return (int32_t)card;
}

}  // namespace

extern "C" {

int32_t lxl_column_ord_counts_sparse(lxl_reader* r, const char* name, int32_t name_len, const uint64_t* matches,
                                     int32_t num_matches, int32_t max_doc, int64_t n_ords, int32_t* ords,
                                     int32_t* counts, int32_t cap, int32_t* missing) {
  return guarded([&]() -> int32_t {
    *missing = 0;
    if (num_matches == 0) return 0;
    // a per-thread dense counter, cleared through the touched list after each call
    thread_local std::vector<int32_t> tlDense;
    thread_local std::vector<int32_t> tlTouched;
    auto& dense = tlDense;  // one TLS lookup per call, not per doc
    auto& touched = tlTouched;
    if ((int64_t)dense.size() < n_ords) dense.resize((size_t)n_ords, 0);
    touched.clear();
    try {
      scanColumn(r, name, name_len, matches, num_matches, max_doc, [&](int32_t, std::span<const int64_t> v) {
        if (v.empty()) {
          (*missing)++;
          return;
        }
        for (int64_t ord : v) {
          if (ord < 0 || ord >= n_ords) throw std::invalid_argument("lxl: ordinal out of range");
          if (dense[(size_t)ord]++ == 0) touched.push_back((int32_t)ord);
        }
      });
    } catch (...) {
      for (int32_t o : touched) dense[(size_t)o] = 0;
      throw;
    }
    int32_t n = (int32_t)touched.size();
    if (n <= cap) {
      std::sort(touched.begin(), touched.end());
      for (int32_t i = 0; i < n; i++) {
        ords[i] = touched[(size_t)i];
        counts[i] = dense[(size_t)touched[(size_t)i]];
      }
    }
    for (int32_t o : touched) dense[(size_t)o] = 0;
    return n;  // > cap: the caller retries with room for n
  });
}

namespace {

// A sorted dictionary read in place: term ord's bytes are [ends(ord - 1), ends(ord)).
struct JoinDict {
  const char* bytes = nullptr;
  std::optional<MonoReader> ends;
  int64_t count = 0;

  JoinDict(lxl_reader* r, const char* name) {
    auto info = findField(*r->postings, std::string_view(name));
    if (!info) throw std::runtime_error("lxl: no such dictionary");
    count = info->numValues;
    bytes = r->postings->getInputStreamSeek(info->columnLoc).ptr();
    ends.emplace(*r->postings, info->monoLoc, info->monoMetaOff, info->numValues);
  }

  std::string_view term(int64_t ord) const {
    auto [start, end] = ends->valuesAt(ord);
    return std::string_view(bytes + start, (size_t)(end - start));
  }
};

// Every doc of max_doc, as match words for forEachMatch.
const uint64_t* allDocs(int32_t max_doc) {
  thread_local std::vector<uint64_t> tlAll;
  size_t words = FixedBitSet::sizeInWords(max_doc);
  if (tlAll.size() < words) tlAll.assign(words, ~0ULL);
  return tlAll.data();
}

}  // namespace

int32_t lxl_join_sorted(int32_t n, lxl_reader* const* stores, const int32_t* max_docs,
                        const char* const* from_cols, const char* const* from_dicts,
                        const uint64_t* const* from_matches, const int32_t* from_counts,
                        const char* const* to_cols, const char* const* to_dicts,
                        const uint64_t* const* to_live, uint64_t* const* out, int64_t* total) {
  return guarded([&]() -> int32_t {
    *total = 0;
    // 1. the from docs' values, as terms (byte order, as the dictionaries sort them)
    std::vector<std::string_view> terms;
    std::vector<uint64_t> seen;
    for (int32_t i = 0; i < n; i++) {
      if (stores[i] == nullptr || from_cols[i] == nullptr || from_matches[i] == nullptr || from_counts[i] == 0) continue;
      JoinDict dict(stores[i], from_dicts[i]);
      seen.assign(FixedBitSet::sizeInWords((int32_t)std::min<int64_t>(dict.count, INT32_MAX)) + 1, 0);
      scanColumn(stores[i], from_cols[i], (int32_t)std::strlen(from_cols[i]), from_matches[i], from_counts[i],
                 max_docs[i], [&](int32_t, std::span<const int64_t> v) {
                   for (int64_t ord : v) {
                     if (ord < 0 || ord >= dict.count) throw std::invalid_argument("lxl: ordinal out of range");
                     uint64_t bit = 1ULL << (ord & 63);
                     if ((seen[(size_t)ord >> 6] & bit) == 0) {
                       seen[(size_t)ord >> 6] |= bit;
                       terms.push_back(dict.term(ord));
                     }
                   }
                 });
    }
    if (terms.empty()) return 0;
    std::sort(terms.begin(), terms.end());
    terms.erase(std::unique(terms.begin(), terms.end()), terms.end());

    // 2. per segment: those terms' ords in its to-dictionary, then every doc whose value is one
    std::vector<uint64_t> ords;
    for (int32_t i = 0; i < n; i++) {
      if (stores[i] == nullptr || to_cols[i] == nullptr) continue;
      JoinDict dict(stores[i], to_dicts[i]);
      if (dict.count == 0) continue;
      ords.assign(FixedBitSet::sizeInWords((int32_t)std::min<int64_t>(dict.count, INT32_MAX)) + 1, 0);
      bool any = false;
      double log2Count = std::log2((double)dict.count + 1);
      if ((double)terms.size() * log2Count < (double)dict.count) {
        // few terms: binary search each
        for (std::string_view t : terms) {
          int64_t lo = 0, hi = dict.count;
          while (lo < hi) {
            int64_t mid = lo + (hi - lo) / 2;
            if (dict.term(mid) < t) lo = mid + 1;
            else hi = mid;
          }
          if (lo < dict.count && dict.term(lo) == t) {
            ords[(size_t)lo >> 6] |= 1ULL << (lo & 63);
            any = true;
          }
        }
      } else {
        // many terms: one merge walk over the dictionary
        size_t ti = 0;
        for (int64_t o = 0; o < dict.count && ti < terms.size(); o++) {
          std::string_view d = dict.term(o);
          while (ti < terms.size() && terms[ti] < d) ti++;
          if (ti < terms.size() && terms[ti] == d) {
            ords[(size_t)o >> 6] |= 1ULL << (o & 63);
            any = true;
            ti++;
          }
        }
      }
      if (!any) continue;
      const uint64_t* live = to_live[i] != nullptr ? to_live[i] : allDocs(max_docs[i]);
      int32_t numLive = to_live[i] != nullptr ? popcount(live, max_docs[i]) : max_docs[i];
      uint64_t* o = out[i];
      int64_t found = 0;
      scanColumn(stores[i], to_cols[i], (int32_t)std::strlen(to_cols[i]), live, numLive, max_docs[i],
                 [&](int32_t d, std::span<const int64_t> v) {
                   for (int64_t ord : v) {
                     if (ord >= 0 && ord < dict.count && (ords[(size_t)ord >> 6] >> (ord & 63) & 1)) {
                       o[(size_t)d >> 6] |= 1ULL << (d & 63);
                       found++;
                       return;
                     }
                   }
                 });
      *total += found;
    }
    return 0;
  });
}

namespace {

// One value of lxl_column_value_counts (layout shared with the Java side).
struct ValueCount {
  int64_t value;
  int32_t count;
  int32_t first;
};
static_assert(sizeof(ValueCount) == 16);

thread_local std::vector<ValueCount> tlValueCounts;  // the last result of the calling thread

}  // namespace

int32_t lxl_column_value_counts(lxl_reader* r, const char* name, int32_t name_len, const uint64_t* matches,
                                const int32_t* docs, int32_t num_matches, int32_t max_doc, int32_t sample,
                                const void** out, int32_t* missing) {
  return guarded([&]() -> int32_t {
    auto& entries = tlValueCounts;
    entries.clear();
    *missing = 0;
    *out = entries.data();
    if (num_matches == 0) return 0;
    // Counted in a hash map, until more than half of the first `sample` docs with a value add a
    // new one: then each doc's values are listed in doc order, a repeat of the value just listed
    // counted into it. Callers merge the repeats left; sorting them out here would cost more than
    // a mostly distinct list can save.
    boost::unordered_flat_map<int64_t, std::pair<int32_t, int32_t>> byValue;  // count, first doc
    bool listing = false;
    int64_t seen = 0;
    scanColumn(r, name, name_len, matches, docs, num_matches, max_doc, [&](int32_t d, std::span<const int64_t> v) {
      if (v.empty()) {
        (*missing)++;
        return;
      }
      for (size_t i = 0; i < v.size(); i++) {
        if (i > 0 && v[i] == v[i - 1]) continue;  // a doc counts once per value (values are sorted)
        if (listing) {
          if (!entries.empty() && entries.back().value == v[i]) {
            entries.back().count++;
          } else {
            entries.push_back({v[i], 1, d});
          }
        } else {
          auto [it, inserted] = byValue.try_emplace(v[i], 0, d);
          it->second.first++;
        }
      }
      if (++seen == sample && byValue.size() * 2 > (size_t)seen) {
        listing = true;
        entries.reserve((size_t)num_matches);
        for (auto& [x, cd] : byValue) entries.push_back({x, cd.first, cd.second});
        byValue = {};
      }
    });
    if (!listing) {
      entries.reserve(byValue.size());
      for (auto& [x, cd] : byValue) entries.push_back({x, cd.first, cd.second});
    }
    *out = entries.data();
    return (int32_t)entries.size();
  });
}

void lxl_value_counts_release(void) { std::vector<ValueCount>().swap(tlValueCounts); }

int32_t lxl_column_range_counts(lxl_reader* r, const char* name, int32_t name_len, int32_t kind,
                                const uint64_t* matches, int32_t num_matches, int32_t max_doc, int32_t n,
                                const int64_t* los, const int64_t* his, int32_t* counts) {
  return guarded([&]() -> int32_t {
    if (num_matches == 0) return 0;
    scanColumn(r, name, name_len, matches, num_matches, max_doc, [&](int32_t, std::span<const int64_t> v) {
      if (v.empty()) return;
      for (int32_t i = 0; i < n; i++) {
        for (int64_t x : v) {
          int64_t key = sortKey(kind, x);
          if (key >= los[i] && key <= his[i]) {
            counts[i]++;  // a doc counts once per range
            break;
          }
        }
      }
    });
    return 0;
  });
}

lxl_points* lxl_points_new(lxl_reader* r, const char* name, int32_t name_len, int64_t* info) {
  return guardedPtr<lxl_points>([&]() -> lxl_points* {
    auto fi = findField(*r->postings, std::string_view(name, (size_t)name_len));
    if (!fi || fi->pointsMetaOff == 0) throw std::runtime_error("lxl: no such points field");
    auto p = std::make_unique<lxl_points>();
    p->pts = std::make_unique<PointsReader>(*r->postings, *fi);
    info[0] = p->pts->leafCount();
    info[1] = (int64_t)p->pts->pointCount();
    info[2] = p->pts->maxPointsPerLeaf();
    return p.release();
  });
}

void lxl_points_free(lxl_points* p) { delete p; }

int32_t lxl_points_bounds(lxl_points* p, int64_t* mins, int64_t* maxs) {
  return guarded([&] {
    uint32_t n = p->pts->leafCount();
    for (uint32_t i = 0; i < n; i++) {
      mins[i] = p->pts->leafMin(i);
      maxs[i] = p->pts->leafMax(i);
    }
    return (int32_t)n;
  });
}

int32_t lxl_points_leaf(lxl_points* p, int32_t leaf, int32_t* docs, int64_t* values) {
  return guarded([&] {
    thread_local std::vector<int64_t> tlVals;
    thread_local std::vector<uint32_t> tlIds, tlScratch;
    auto& vals = tlVals;
    auto& ids = tlIds;
    auto& scratch = tlScratch;
    size_t m = p->pts->maxPointsPerLeaf();
    if (vals.size() < m) {
      vals.resize(m);
      ids.resize(m);
      scratch.resize(m);
    }
    uint16_t count = p->pts->decodeLeafInto((uint32_t)leaf, std::span<int64_t>(vals.data(), m),
                                            std::span<uint32_t>(ids.data(), m),
                                            std::span<uint32_t>(scratch.data(), m));
    std::memcpy(docs, ids.data(), (size_t)count * sizeof(int32_t));
    if (values != nullptr) std::memcpy(values, vals.data(), (size_t)count * sizeof(int64_t));
    return (int32_t)count;
  });
}

// Sets, in out (maxDoc bits, zeroed by the caller), every doc with a value in [lo, hi]; returns
// the number of docs set. Leaves are value-sorted with min/max fences: leaves inside the range are
// taken whole, the boundary leaves value by value.
int32_t lxl_points_range(lxl_points* p, int64_t lo, int64_t hi, int32_t max_doc, uint64_t* out) {
  return guarded([&] {
    pointsFill(*p->pts, lo, hi, max_doc, out);
    return popcount(out, max_doc);
  });
}

int32_t lxl_points_set(lxl_points* p, const int64_t* values, int32_t n, int32_t max_doc, uint64_t* out) {
  return guarded([&] {
    for (int32_t i = 0; i < n; i++) pointsFill(*p->pts, values[i], values[i], max_doc, out);
    return popcount(out, max_doc);
  });
}

}  // extern "C"

// ---- native string fields (inverter mode) -----------------------------------------------------

extern "C" {

int32_t lxl_writer_string_field(lxl_writer* w, const char* name, int32_t len) {
  return guarded([&] {
    if (!w->inverter) return fail("lxl: not an inverter writer");
    std::string_view n(name, (size_t)len);
    for (size_t i = 0; i < w->strs.size(); i++) {
      if (w->strs[i]->name == n) return (int32_t)i;
    }
    auto sb = std::make_unique<StrBuf>();
    sb->name.assign(n);
    w->strs.push_back(std::move(sb));
    return (int32_t)w->strs.size() - 1;
  });
}

int32_t lxl_writer_add_strings(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* ords, const uint8_t* bytes,
                               const int64_t* offsets) {
  return guarded([&] {
    for (int32_t i = 0; i < n; i++) {
      int32_t ord = ords[i];
      if (ord < 0 || (size_t)ord >= w->strs.size()) return fail("lxl: bad string field ordinal");
      w->strs[(size_t)ord]->add(docs[i], std::string_view((const char*)bytes + offsets[i],
                                                           (size_t)(offsets[i + 1] - offsets[i])));
    }
    return 0;
  });
}

int32_t lxl_writer_strings_dv(lxl_writer* dst, const char* column, int32_t column_len, const char* dict,
                              int32_t dict_len, lxl_writer* src, int32_t ord, int32_t multi, int64_t* stats) {
  return guarded([&] {
    if (ord < 0 || (size_t)ord >= src->strs.size()) return fail("lxl: bad string field ordinal");
    StrBuf& sb = *src->strs[(size_t)ord];
    stats[0] = stats[1] = stats[2] = 0;
    if (sb.docs.empty()) return 0;
    sb.sort();
    std::vector<int32_t> docs, counts, ranks;
    std::vector<int64_t> values;
    size_t n = sb.docs.size();
    for (size_t i = 0; i < n;) {
      int32_t d = sb.docs[i];
      ranks.clear();
      for (; i < n && sb.docs[i] == d; i++) ranks.push_back(sb.rank[(size_t)sb.vals[i]]);
      if (multi) {
        std::sort(ranks.begin(), ranks.end());
        ranks.erase(std::unique(ranks.begin(), ranks.end()), ranks.end());
        counts.push_back((int32_t)ranks.size());
      } else {
        ranks.resize(1);  // single-valued: a doc's first value (callers reject documents with more)
      }
      docs.push_back(d);
      values.insert(values.end(), ranks.begin(), ranks.end());
    }
    writeNumeric(dst, std::string_view(column, (size_t)column_len), (int32_t)docs.size(), docs.data(),
                 multi ? counts.data() : nullptr, values.data());
    std::string bytes;
    bytes.reserve(sb.termBytes);
    std::vector<int64_t> offs(sb.terms.size() + 1, 0);
    for (size_t r = 0; r < sb.byRank.size(); r++) {
      bytes.append(sb.terms[(size_t)sb.byRank[r]]);
      offs[r + 1] = (int64_t)bytes.size();
    }
    writeDict(dst, std::string_view(dict, (size_t)dict_len), (int64_t)sb.terms.size(), bytes.data(), offs.data());
    stats[0] = (int64_t)docs.size();
    stats[1] = (int64_t)values.size();
    stats[2] = (int64_t)sb.terms.size();
    return 0;
  });
}

int32_t lxl_writer_strings_postings(lxl_writer* w, int32_t ord) {
  return guarded([&] {
    if (ord < 0 || (size_t)ord >= w->strs.size()) return fail("lxl: bad string field ordinal");
    if (w->tw) return fail("lxl: previous field not ended");
    StrBuf& sb = *w->strs[(size_t)ord];
    if (sb.docs.empty()) return 0;
    sb.sort();
    size_t numTerms = sb.terms.size(), n = sb.docs.size();
    for (auto t : sb.terms) {
      if (t.size() > LXL_MAX_TERM) {
        lastError = "lxl: a value of string field \"" + sb.name + "\" is longer than 255 bytes";
        return -1;
      }
    }
    // the docs of each term, in term order (counting sort; docs stay ascending)
    std::vector<int64_t> start(numTerms + 1, 0);
    for (size_t i = 0; i < n; i++) start[(size_t)sb.rank[(size_t)sb.vals[i]] + 1]++;
    for (size_t r = 0; r < numTerms; r++) start[r + 1] += start[r];
    std::vector<int64_t> fill(start.begin(), start.end() - 1);
    std::vector<int32_t> byTerm(n);
    for (size_t i = 0; i < n; i++) byTerm[(size_t)fill[(size_t)sb.rank[(size_t)sb.vals[i]]]++] = sb.docs[i];

    auto& fi = w->pw->addField(sb.name);
    fi.type = FieldType::STRING;
    fi.flags = FieldType::INDEX_DOCS;
    TextWriter tw(*w->pw);
    tw.startField(&fi);
    for (size_t r = 0; r < numTerms; r++) {
      auto term = w->pw->copyTerm(sb.terms[(size_t)sb.byRank[r]]);
      tw.startTerm(term);
      int32_t last = -1;
      for (int64_t k = start[r]; k < start[r + 1]; k++) {
        int32_t d = byTerm[(size_t)k];
        if (d != last) {
          tw.addDoc(d, 1);
          last = d;
        }
      }
      tw.endTerm(term);
    }
    tw.endField();
    int32_t docCount = 0;
    for (size_t i = 0; i < n; i++) {
      if (i == 0 || sb.docs[i] != sb.docs[i - 1]) docCount++;
    }
    return docCount;
  });
}

}  // extern "C"

// ---- JSON facet collection ---------------------------------------------------------------------
//
// Floating point here must round as Java does: no fused multiply-adds (GCC contracts a += v * v
// into one by default, which rounds once instead of twice).
#if defined(__clang__)
#pragma clang fp contract(off)
#elif defined(__GNUC__)
#pragma GCC push_options
#pragma GCC optimize("fp-contract=off")
#endif
//
// One pass over a segment's matching docs that does what Solr's JSON facet processors do per doc:
// map the doc to slots (one slot, its ordinals, or the numeric ranges holding a value), count it per
// slot, and update each aggregation's per-slot accumulators with the exact rules of the Solr slot
// accumulator it stands for, in doc order (so even double sums are bit-identical).

namespace {

enum FacetGroup : int32_t { GROUP_SLOT = 0, GROUP_ORDS = 1, GROUP_RANGES = 2 };
enum FacetValueType : int32_t { VT_INT = 0, VT_LONG = 1, VT_FLOAT = 2, VT_DOUBLE = 3 };
enum FacetOp : int32_t {
  OP_SUM = 0,       // result += doubleVal (0 when missing)
  OP_SUMSQ = 1,     // result += doubleVal * doubleVal
  OP_AVG = 2,       // if exists: result += doubleVal, counts++
  OP_MIN_D = 3,     // if exists: Double.compare min, NaN = unset
  OP_MAX_D = 4,
  OP_MIN_L = 5,     // if exists: long min, unset per the exists bits
  OP_MAX_L = 6,
  OP_MIN_DATE = 7,  // if exists: long min, Long.MIN_VALUE = unset
  OP_MAX_DATE = 8,
  OP_COUNT = 9,     // if exists: result++
  OP_MISSING = 10,  // if !exists: result++
  OP_VARIANCE = 11  // if exists: counts++, result += v * v, sum += v
};

// Java's Double.compare: numeric order, -0.0 < 0.0, NaN (any) above everything and equal to itself.
inline int javaDoubleCompare(double a, double b) {
  if (a < b) return -1;
  if (a > b) return 1;
  int64_t x = std::isnan(a) ? (int64_t)0x7ff8000000000000LL : std::bit_cast<int64_t>(a);
  int64_t y = std::isnan(b) ? (int64_t)0x7ff8000000000000LL : std::bit_cast<int64_t>(b);
  return x == y ? 0 : (x < y ? -1 : 1);
}

// The value Solr's numeric field sources read from a raw doc value.
inline double doubleValue(int32_t type, int64_t raw) {
  switch (type) {
    case VT_INT: return (double)(int32_t)raw;
    case VT_LONG: return (double)raw;
    case VT_FLOAT: return (double)std::bit_cast<float>((int32_t)raw);
    default: return std::bit_cast<double>(raw);
  }
}

inline int64_t longValue(int32_t type, int64_t raw) { return type == VT_INT ? (int64_t)(int32_t)raw : raw; }

// A single-valued column read at ascending (possibly repeated) docs, a batch at a time.
struct ValueCursor {
  virtual ~ValueCursor() = default;
  // raw[i], has[i]: the value of docs[i] and whether it has one
  virtual void fill(const std::pair<int32_t, int32_t>* pairs, size_t n, int64_t* raw, uint8_t* has) = 0;
};

template <typename It>
struct ValueCursorOf final : ValueCursor {
  SegFieldInfo info;
  IntColReader col;
  It it;
  int32_t cur = -1;

  bool dense;  // every doc has a value: a doc's value rank is the doc

  ValueCursorOf(PostingsReader& pr, const SegFieldInfo& fi, int32_t maxDoc)
      : info(fi), col(pr, info), it(col), dense(col.docsWithValue() >= maxDoc) {
    if (col.multiValued()) throw std::invalid_argument("lxl: aggregations need a single-valued column");
  }

  void fill(const std::pair<int32_t, int32_t>* pairs, size_t n, int64_t* raw, uint8_t* has) override {
    if (dense) {
      auto& values = it.values();
      for (size_t i = 0; i < n; i++) {
        raw[i] = values.valueAt(pairs[i].first);
        has[i] = 1;
      }
      return;
    }
    int32_t last = -1;
    int64_t lastRaw = 0;
    uint8_t lastHas = 0;
    for (size_t i = 0; i < n; i++) {
      int32_t d = pairs[i].first;
      if (d != last) {  // repeated docs (several slots) read once
        if (cur < d) cur = it.advance(d);
        lastHas = cur == d;
        lastRaw = lastHas ? it.value() : 0;
        last = d;
      }
      raw[i] = lastRaw;
      has[i] = lastHas;
    }
  }
};

struct FacetAgg {
  int32_t op;
  int32_t type;
  std::unique_ptr<ValueCursor> cursor;  // null: the field has no values in this segment
  double* d0;
  double* d1;
  int64_t* l0;
  int32_t* i0;
  uint64_t* bits;

  // Applies the batch's (doc, slot) pairs in order; raw/has: scratch for the batch's values.
  void apply(const std::pair<int32_t, int32_t>* pairs, size_t n, int64_t* raw, uint8_t* has) {
    if (cursor) {
      cursor->fill(pairs, n, raw, has);
    } else {
      std::fill(raw, raw + n, (int64_t)0);
      std::fill(has, has + n, (uint8_t)0);
    }
    switch (type) {
      case VT_INT: applyTyped<VT_INT>(pairs, n, raw, has); break;
      case VT_LONG: applyTyped<VT_LONG>(pairs, n, raw, has); break;
      case VT_FLOAT: applyTyped<VT_FLOAT>(pairs, n, raw, has); break;
      case VT_DOUBLE: applyTyped<VT_DOUBLE>(pairs, n, raw, has); break;
      default: throw std::invalid_argument("lxl: unknown value type");
    }
  }

  template <int32_t T>
  void applyTyped(const std::pair<int32_t, int32_t>* pairs, size_t n, const int64_t* raw, const uint8_t* has) {
    switch (op) {
      case OP_SUM:
        for (size_t i = 0; i < n; i++) d0[pairs[i].second] += has[i] ? doubleValue(T, raw[i]) : 0.0;
        break;
      case OP_SUMSQ:
        for (size_t i = 0; i < n; i++) {
          double v = has[i] ? doubleValue(T, raw[i]) : 0.0;
          d0[pairs[i].second] += v * v;
        }
        break;
      case OP_AVG:
        for (size_t i = 0; i < n; i++) {
          if (!has[i]) continue;
          int32_t s = pairs[i].second;
          d0[s] += doubleValue(T, raw[i]);
          i0[s] += 1;
        }
        break;
      case OP_MIN_D:
      case OP_MAX_D: {
        int mm = op == OP_MIN_D ? 1 : -1;
        for (size_t i = 0; i < n; i++) {
          if (!has[i]) continue;
          int32_t s = pairs[i].second;
          double v = doubleValue(T, raw[i]), cur = d0[s];
          if (javaDoubleCompare(v, cur) * mm < 0 || std::isnan(cur)) d0[s] = v;
        }
        break;
      }
      case OP_MIN_L:
      case OP_MAX_L: {
        int mm = op == OP_MIN_L ? 1 : -1;
        for (size_t i = 0; i < n; i++) {
          if (!has[i]) continue;
          int32_t s = pairs[i].second;
          int64_t v = longValue(T, raw[i]), cur = l0[s];
          uint64_t bit = 1ULL << (s & 63);
          if (cur == 0 && (bits[s >> 6] & bit) == 0) {
            bits[s >> 6] |= bit;
            l0[s] = v;
          } else if ((v < cur ? -1 : (v > cur ? 1 : 0)) * mm < 0) {
            l0[s] = v;
          }
        }
        break;
      }
      case OP_MIN_DATE:
      case OP_MAX_DATE: {
        int mm = op == OP_MIN_DATE ? 1 : -1;
        for (size_t i = 0; i < n; i++) {
          if (!has[i]) continue;
          int32_t s = pairs[i].second;
          int64_t v = longValue(T, raw[i]), cur = l0[s];
          if ((v < cur ? -1 : (v > cur ? 1 : 0)) * mm < 0 || cur == std::numeric_limits<int64_t>::min()) l0[s] = v;
        }
        break;
      }
      case OP_COUNT:
        for (size_t i = 0; i < n; i++) l0[pairs[i].second] += has[i];
        break;
      case OP_MISSING:
        for (size_t i = 0; i < n; i++) l0[pairs[i].second] += 1 - has[i];
        break;
      case OP_VARIANCE:
        for (size_t i = 0; i < n; i++) {
          if (!has[i]) continue;
          int32_t s = pairs[i].second;
          double v = doubleValue(T, raw[i]);
          i0[s]++;
          d0[s] += v * v;
          d1[s] += v;
        }
        break;
      default: throw std::invalid_argument("lxl: unknown facet aggregation");
    }
  }
};

}  // namespace

extern "C" {

int32_t lxl_facet_collect(lxl_reader* r, const uint64_t* matches, const int32_t* docs, int32_t num_matches,
                          int32_t max_doc, int32_t group, const char* gname, int32_t gname_len, int32_t slot,
                          const int32_t* ord_slots, int64_t n_ords, int32_t range_kind, int32_t n_ranges,
                          const int64_t* los, const int64_t* his, int64_t* counts, int32_t n_aggs, const char* names,
                          const int32_t* name_offs, const int32_t* ops, const int32_t* types, double* const* d0,
                          double* const* d1, int64_t* const* l0, int32_t* const* i0, uint64_t* const* bits) {
  return guarded([&]() -> int32_t {
    if (num_matches == 0) return 0;
    // decoding single values pays off below about 8 matches per 128-value frame, as for scanColumn
    bool sparse = (int64_t)num_matches * 16 < max_doc;
    std::vector<FacetAgg> aggs((size_t)n_aggs);
    for (int32_t a = 0; a < n_aggs; a++) {
      FacetAgg& g = aggs[(size_t)a];
      g.op = ops[a];
      g.type = types[a];
      g.d0 = d0[a];
      g.d1 = d1[a];
      g.l0 = l0[a];
      g.i0 = i0[a];
      g.bits = bits[a];
      auto info = findField(*r->postings,
                            std::string_view(names + name_offs[a], (size_t)(name_offs[a + 1] - name_offs[a])));
      if (!info) continue;  // no values in this segment
      if (sparse) {
        g.cursor = std::make_unique<ValueCursorOf<IntColReader::SparseIterator>>(*r->postings, *info, max_doc);
      } else {
        g.cursor = std::make_unique<ValueCursorOf<IntColReader::BulkIterator>>(*r->postings, *info, max_doc);
      }
    }
    // (doc, slot) pairs in doc order, applied in batches (thread-locals bound once: each access
    // to one costs a TLS lookup in a shared library)
    thread_local std::vector<std::pair<int32_t, int32_t>> tlPairs;
    thread_local std::vector<int64_t> tlRaw;
    thread_local std::vector<uint8_t> tlHas;
    auto& pairs = tlPairs;
    auto& raw = tlRaw;
    auto& has = tlHas;
    pairs.clear();
    bool countOnly = aggs.empty();
    auto flush = [&] {
      size_t n = pairs.size();
      if (n == 0) return;
      if (counts) {
        for (size_t i = 0; i < n; i++) counts[pairs[i].second]++;
      }
      if (!aggs.empty()) {
        if (raw.size() < n) {
          raw.resize(n);
          has.resize(n);
        }
        // each aggregation's accumulators are its own: applying one at a time keeps every
        // (aggregation, slot)'s updates in doc order
        for (FacetAgg& g : aggs) g.apply(pairs.data(), n, raw.data(), has.data());
      }
      pairs.clear();
    };
    auto add = [&](int32_t d, int32_t s) {
      if (countOnly) {  // nothing to aggregate: count straight away
        if (counts) counts[s]++;
        return;
      }
      pairs.emplace_back(d, s);
      if (pairs.size() >= 65536) flush();
    };
    if (group == GROUP_SLOT && docs != nullptr) {
      for (int32_t i = 0; i < num_matches; i++) add(docs[i], slot);
    } else if (group == GROUP_SLOT) {
      size_t words = FixedBitSet::sizeInWords(max_doc);
      for (size_t w = 0; w < words; w++) {
        uint64_t word = matches[w];
        while (word != 0) {
          int32_t d = (int32_t)(w << 6) + std::countr_zero(word);
          word &= word - 1;
          if (d >= max_doc) break;
          add(d, slot);
        }
      }
    } else if (group == GROUP_ORDS) {
      if (findField(*r->postings, std::string_view(gname, (size_t)gname_len))) {
        scanColumn(r, gname, gname_len, matches, docs, num_matches, max_doc, [&](int32_t d, std::span<const int64_t> v) {
          for (int64_t ord : v) {
            if (ord < 0 || ord >= n_ords) throw std::invalid_argument("lxl: ordinal out of range");
            int32_t s = ord_slots[ord];
            if (s >= 0) add(d, s);
          }
        });
      }
    } else if (group == GROUP_RANGES) {
      // Disjoint ranges in ascending order (a range facet's buckets): a single value's range is the
      // last one starting at or below it, found by binary search instead of testing every range.
      bool ascending = n_ranges > 0;
      for (int32_t i = 0; i < n_ranges && ascending; i++) {
        ascending = los[i] <= his[i] && (i == 0 || his[i - 1] < los[i]);
      }
      if (findField(*r->postings, std::string_view(gname, (size_t)gname_len))) {
        scanColumn(r, gname, gname_len, matches, docs, num_matches, max_doc, [&](int32_t d, std::span<const int64_t> v) {
          if (v.empty()) return;
          if (ascending && v.size() == 1) {
            int64_t key = sortKey(range_kind, v[0]);
            const int64_t* after = std::upper_bound(los, los + n_ranges, key);
            if (after != los) {
              int32_t i = (int32_t)(after - los) - 1;
              if (key <= his[i]) add(d, i);
            }
            return;
          }
          for (int32_t i = 0; i < n_ranges; i++) {
            for (int64_t x : v) {
              int64_t key = sortKey(range_kind, x);
              if (key >= los[i] && key <= his[i]) {
                add(d, i);  // a doc goes to a range once
                break;
              }
            }
          }
        });
      }
    } else {
      throw std::invalid_argument("lxl: unknown facet grouping");
    }
    flush();
    return 0;
  });
}

}  // extern "C"

#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC pop_options
#endif
