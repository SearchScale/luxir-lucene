// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

/*
 * lxl.h - C ABI that lets a Lucene codec store text postings in Luxir's
 * segment format and run Luxir's per-segment query execution over them.
 *
 * The Java side (org.luxir.lucene) owns all files: a writer produces Luxir
 * segment files in memory, Java copies them into Lucene segment files, and a
 * reader is later opened over those bytes in place (memory-mapped by Lucene).
 *
 * Conventions: functions returning int32_t use a negative value for failure;
 * functions returning a handle use NULL. The failure message is available
 * from lxl_last_error() on the same thread until the next failing call.
 * Term bytes are at most LXL_MAX_TERM bytes.
 *
 * Copyright 2026 luxir-lucene contributors. Apache-2.0.
 */
#ifndef LXL_H
#define LXL_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LXL_API __attribute__((visibility("default")))

#define LXL_ABI_VERSION 7
#define LXL_MAX_TERM 255
#define LXL_END INT32_MAX

LXL_API int32_t lxl_abi_version(void);
LXL_API const char* lxl_last_error(void);

/* ---- writer: one Luxir segment per Lucene segment ------------------------ */

typedef struct lxl_writer lxl_writer;
typedef struct lxl_reader lxl_reader;

LXL_API lxl_writer* lxl_writer_new(int32_t max_doc);
/* Starts a positional text field. norm_docs (ascending) and norm_values give
 * the Lucene norm byte of every doc that has the field. */
LXL_API int32_t lxl_writer_start_field(lxl_writer* w, const char* name, int32_t name_len,
                                       const int32_t* norm_docs, const uint8_t* norm_values,
                                       int32_t num_norms);
#define LXL_TERM_START 1
#define LXL_TERM_END 2
/* Adds docs to a term; terms arrive in unsigned byte order. A term may span
 * several calls (START on the first, END on the last). positions holds, for
 * each doc in order, its freqs[i] absolute positions (non-decreasing). */
LXL_API int32_t lxl_writer_term(lxl_writer* w, const uint8_t* term, int32_t term_len,
                                const int32_t* docs, const int32_t* freqs, int32_t num_docs,
                                const int32_t* positions, int32_t flags);
LXL_API int32_t lxl_writer_end_field(lxl_writer* w);
/* Finishes the segment; returns the number of files. */
LXL_API int32_t lxl_writer_finish(lxl_writer* w);
/* File i of a finished writer: name (NUL-terminated) and bytes, valid until free. */
LXL_API int32_t lxl_writer_file(lxl_writer* w, int32_t i, const char** name,
                                const uint8_t** data, int64_t* len);
LXL_API void lxl_writer_free(lxl_writer* w);

/* Inverter mode: Luxir analyzes and inverts documents itself (no Lucene
 * inversion). schema_json is a Luxir SchemaDef ({"fields":{...},"templates":{...}})
 * describing the text fields and their analyzers. Documents arrive in batches;
 * at lxl_writer_set_max_doc the segment's doc count is fixed, after which
 * Lucene-inverted fields may still be added with lxl_writer_start_field. */
LXL_API lxl_writer* lxl_writer_new_inverter(const char* schema_json, int32_t len);
/* Ordinal of a field for lxl_writer_add_docs (validated against the schema). */
LXL_API int32_t lxl_writer_field_ord(lxl_writer* w, const char* name, int32_t len);
/* Values ordered by doc: value i is field ords[i] of doc docs[i], text bytes
 * [offsets[i], offsets[i+1]). Several values of one field in one doc, adjacent
 * or not, are a multi-valued field, in their order. Docs ascend across calls. */
LXL_API int32_t lxl_writer_add_docs(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* ords,
                                    const uint8_t* text, const int64_t* offsets);
LXL_API int64_t lxl_writer_ram(lxl_writer* w);
LXL_API int32_t lxl_writer_set_max_doc(lxl_writer* w, int32_t max_doc);
/* After lxl_writer_finish: nTerms, docsWithField, sumDocFreq, sumTotalTermFreq.
 * 1 found, 0 not. */
LXL_API int32_t lxl_writer_field_stats(lxl_writer* w, const char* name, int32_t len, int64_t* stats);

/* Merges one text field from source segments straight into a plain writer
 * (maxDoc already set): doc_maps[i] maps source i's docs to merged docs (-1:
 * deleted) and must be increasing across sources (no index sorting). Postings,
 * positions and norms are copied without leaving native code. Returns the
 * number of documents with postings in the merged field. */
LXL_API int32_t lxl_writer_merge_field(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                                       lxl_reader* const* sources, const int32_t* const* doc_maps);

/* ---- native analysis (the same chains, for query-time analysis) ------------- */

typedef struct lxl_analyzer lxl_analyzer;

/* json: a Luxir AnalyzerDef. Not thread-safe; one per Lucene TokenStream. */
LXL_API lxl_analyzer* lxl_analyzer_new(const char* json, int32_t len);
LXL_API void lxl_analyzer_free(lxl_analyzer* a);
/* Starts analyzing a value (copied). */
LXL_API int32_t lxl_analyze_start(lxl_analyzer* a, const uint8_t* text, int32_t len);
/* Next tokens: term bytes appended to terms (capacity >= max_tokens * 256),
 * meta gets 4 ints per token: term length, position increment, start and end
 * byte offset. Returns the number of tokens (0 at the end). Terms longer than
 * 255 bytes are hashed exactly as the indexer does. */
LXL_API int32_t lxl_analyze_next(lxl_analyzer* a, uint8_t* terms, int32_t* meta, int32_t max_tokens);
/* Multi-term normalization (prefix/wildcard text): folds without segmenting.
 * Returns the length written to out (capacity out_cap). */
LXL_API int32_t lxl_normalize(lxl_analyzer* a, const uint8_t* text, int32_t len, uint8_t* out, int32_t out_cap);

/* ---- reader over caller-owned bytes ---------------------------------------- */

/* data[i] must be 8-byte aligned and stay valid until lxl_reader_free; each
 * region needs 16 readable bytes after its end (decoders over-read). */
LXL_API lxl_reader* lxl_reader_open(int32_t num_files, const char* const* names,
                                    const uint8_t* const* data, const int64_t* lens);
LXL_API void lxl_reader_free(lxl_reader* r);
/* A text field's norm byte per doc into out[max_doc] (0: no norm). 1 if the
 * field has norms, 0 if not. */
LXL_API int32_t lxl_reader_norms(lxl_reader* r, const char* name, int32_t name_len, uint8_t* out);
/* How the segment stores field `name`: 1 under its own name, 2 under its schema name (a native
 * text field whose name is not a valid schema name), 0 not at all. */
LXL_API int32_t lxl_reader_field_alias(lxl_reader* r, const char* name, int32_t name_len);

/* ---- terms ------------------------------------------------------------------ */

typedef struct lxl_terms lxl_terms;

LXL_API lxl_terms* lxl_terms_new(lxl_reader* r, const char* field, int32_t field_len);
LXL_API void lxl_terms_free(lxl_terms* t);
/* Moves to the next term; copies it to term_out (>= LXL_MAX_TERM bytes) and
 * fills stats (docFreq, totalTermFreq, ord). Returns its length, -2 at end. */
LXL_API int32_t lxl_terms_next(lxl_terms* t, uint8_t* term_out, int64_t* stats);
#define LXL_SEEK_FOUND 0
#define LXL_SEEK_NOT_FOUND 1
#define LXL_SEEK_END 2
/* exact=1: returns FOUND or NOT_FOUND (unpositioned). exact=0: ceil seek.
 * When positioned, term_out/stats/term_len describe the current term. */
LXL_API int32_t lxl_terms_seek(lxl_terms* t, const uint8_t* target, int32_t target_len,
                               int32_t exact, uint8_t* term_out, int32_t* term_len,
                               int64_t* stats);
LXL_API int32_t lxl_terms_seek_ord(lxl_terms* t, int64_t ord, uint8_t* term_out,
                                   int64_t* stats);

/* ---- automaton intersection (Lucene Terms.intersect) ------------------------- */

typedef struct lxl_intersect lxl_intersect;

/* A byte DFA: num_states accept flags, per-state offsets (num_states + 1) into
 * ranges (min, max, dest triples sorted by min). prefix: bytes every accepted
 * term starts with; initial_state: the DFA state after the prefix. suffix:
 * bytes every accepted term ends with (may be empty). */
LXL_API lxl_intersect* lxl_intersect_new(lxl_reader* r, const char* field, int32_t field_len,
                                         int32_t num_states, const uint8_t* accepts,
                                         const int32_t* offsets, const int32_t* ranges,
                                         const uint8_t* prefix, int32_t prefix_len,
                                         const uint8_t* suffix, int32_t suffix_len,
                                         int32_t initial_state);
/* Next accepted term, as lxl_terms_next. */
LXL_API int32_t lxl_intersect_next(lxl_intersect* it, uint8_t* term_out, int64_t* stats);
/* The terms enum positioned on the current term (for lxl_postings_new); owned by it. */
LXL_API lxl_terms* lxl_intersect_terms(lxl_intersect* it);
LXL_API void lxl_intersect_free(lxl_intersect* it);

/* ---- postings of the current term ------------------------------------------ */

typedef struct lxl_postings lxl_postings;

LXL_API lxl_postings* lxl_postings_new(lxl_terms* t, int32_t positions);
LXL_API void lxl_postings_free(lxl_postings* p);
/* Docs-and-freqs mode: next docs (target < 0) or advance to target first.
 * Returns the count filled (0 = exhausted). */
LXL_API int32_t lxl_postings_fill(lxl_postings* p, int32_t target, int32_t* docs,
                                  int32_t* freqs, int32_t cap);
/* Positions mode: next doc (target < 0) or advance; returns the doc or LXL_END. */
LXL_API int32_t lxl_postings_next(lxl_postings* p, int32_t target, int32_t* freq);
/* Positions of the current doc; returns the count. */
LXL_API int32_t lxl_postings_positions(lxl_postings* p, int32_t* out, int32_t cap);

/* ---- query execution on one segment ------------------------------------------
 * The query is a compact tree (see LxlQuery.java) carrying the caller's
 * collection-wide BM25 statistics, so scores match the caller's engine. */

typedef struct lxl_exec lxl_exec;

#define LXL_EXEC_SCORES 1   /* scored windows (else match windows) */

/* live: 64-bit words, bit set = doc may match (deletes and filters); may be
 * NULL. It must stay valid for the life of the exec. */
LXL_API lxl_exec* lxl_exec_new(lxl_reader* r, const uint8_t* query, int64_t query_len,
                               int32_t flags, const uint64_t* live, int32_t num_live);
LXL_API void lxl_exec_free(lxl_exec* e);
/* Next window of matches in [min, max) scoring >= min_competitive. Writes up
 * to cap docs/scores, *n the count; returns the resume doc or LXL_END. */
LXL_API int32_t lxl_exec_window(lxl_exec* e, int32_t min, int32_t max, float min_competitive,
                                int32_t* docs, float* scores, int32_t cap, int32_t* n);

/* Top k by score (ties by doc) and the hit count in one call.
 * theta: an initial competitive floor (e.g. from earlier segments).
 * exact_count: count every match (else total_hits is a lower bound).
 * match_bits: if not NULL, receives every match (maxDoc bits, zeroed by caller).
 * Returns the number of docs written to docs/scores. */
LXL_API int32_t lxl_top_k(lxl_reader* r, const uint8_t* query, int64_t query_len,
                          const uint64_t* live, int32_t num_live, int32_t k, float theta,
                          int32_t exact_count, int32_t* docs, float* scores,
                          int64_t* total_hits, uint64_t* match_bits);

/* ---- one search over many segments ----------------------------------------------
 * A view over the segments of one Lucene top-level reader (in leaf order), so a
 * query is planned once, term statistics are summed natively, and the ranking
 * pass shares one competitive floor across segments. */

typedef struct lxl_multi lxl_multi;
typedef struct lxl_points lxl_points;

LXL_API lxl_multi* lxl_multi_new(int32_t n, lxl_reader* const* readers);
LXL_API void lxl_multi_free(lxl_multi* m);

/* As lxl_top_k over all segments. live[i]/num_live[i]: segment i's accept set
 * (NULL = all). Hits are (segs[j], docs[j], scores[j]) best first.
 * count_limit: < 0 counts every match; > 0 counts until at least that many
 * (*count_exact = 0 if it stopped early); 0 reports the ranking pass's count.
 * match_bits[i], if match_bits is not NULL, receives segment i's matches
 * (and every match is counted).
 * Query term nodes with docFreq < 0 take their statistics from the segments.
 * Bitset clauses (query node 9, slot s) match slot_bits[s * n + i] in segment i
 * (slot_counts: its cardinality; NULL bits: no match there). Column-range clauses (node 10,
 * slot c) read segment i's column col_names[c * n + i] in the doc-values store col_stores[...]
 * (NULL: no values there), with col_points[...] (may be NULL) holding each value plus
 * col_offsets[...]. */
LXL_API int32_t lxl_multi_top_k(lxl_multi* m, const uint8_t* query, int64_t query_len,
                                const uint64_t* const* live, const int32_t* num_live, int32_t k,
                                int64_t count_limit, int32_t* segs, int32_t* docs, float* scores,
                                int64_t* total_hits, int32_t* count_exact,
                                uint64_t* const* match_bits, int32_t num_slots,
                                const uint64_t* const* slot_bits, const int32_t* slot_counts,
                                int32_t num_cols, lxl_reader* const* col_stores,
                                const char* const* col_names, lxl_points* const* col_points,
                                const int64_t* col_offsets);

/* ---- stored values, numeric columns and points ------------------------------------
 * Written into a plain writer (lxl_writer_new; lxl_writer_set_max_doc before
 * lxl_writer_finish) and read back through lxl_reader_open on its files. */

/* Stored values: value i is field fields[i] (a caller-chosen id >= 0) of doc
 * docs[i], bytes [offsets[i], offsets[i+1]). Docs never decrease, across calls
 * too; values keep their order. */
LXL_API int32_t lxl_writer_stored(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* fields,
                                  const uint8_t* data, const int64_t* offsets);
/* A numeric column: num_docs ascending docs; counts[i] values for doc i
 * (counts NULL: exactly one each), values flattened in doc order. */
LXL_API int32_t lxl_writer_numeric(lxl_writer* w, const char* name, int32_t name_len, int32_t num_docs,
                                   const int32_t* docs, const int32_t* counts, const int64_t* values);
/* A 1-D points index over n (docs[i], values[i]) pairs, in any order. */
LXL_API int32_t lxl_writer_points(lxl_writer* w, const char* name, int32_t name_len, int64_t n,
                                  const int32_t* docs, const int64_t* values);

/* Merges stored values of n source segments (in merged doc order) into a plain
 * writer: doc_maps[i] maps source i's docs to merged docs (-1: deleted), or is
 * NULL when source i has no deletions (doc d becomes bases[i] + d);
 * field_maps[i] maps source field ids (< field_map_lens[i]) to target ids (-1:
 * drop). NULL sources stored nothing. Sources without deletions or renamed
 * fields are copied as compressed chunks. Returns how many were. */
LXL_API int32_t lxl_writer_stored_merge(lxl_writer* w, int32_t n, lxl_reader* const* sources,
                                        const int32_t* bases, const int32_t* const* doc_maps,
                                        const int32_t* const* field_maps, const int32_t* field_map_lens);

/* A dictionary of n byte strings (ordinal i is bytes [offsets[i], offsets[i+1])). */
LXL_API int32_t lxl_writer_dict(lxl_writer* w, const char* name, int32_t name_len, int64_t n,
                                const uint8_t* bytes, const int64_t* offsets);

/* Native merges into a plain writer (maxDoc set). Source i (NULL: none) has
 * its column/points/dictionary under the given name (NULL: none); its doc d
 * becomes doc_maps[i][d] (-1: deleted), or bases[i] + d when doc_maps[i] is
 * NULL. Doc maps increase across sources (no index sorting).
 * stats: docs and values written. */
LXL_API int32_t lxl_writer_numeric_merge(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                                         lxl_reader* const* sources, const char* const* columns,
                                         const int32_t* bases, const int32_t* const* doc_maps, int32_t multi,
                                         int64_t* stats);
/* An ordinal column with its dictionary: the merged dictionary holds the terms
 * live docs use; stats: docs, values, terms. */
LXL_API int32_t lxl_writer_sorted_merge(lxl_writer* w, const char* column, int32_t column_len, const char* dict,
                                        int32_t dict_len, int32_t n, lxl_reader* const* sources,
                                        const char* const* columns, const char* const* dicts, const int32_t* bases,
                                        const int32_t* const* doc_maps, int32_t multi, int64_t* stats);
/* stats: points and docs with points. */
LXL_API int32_t lxl_writer_points_merge(lxl_writer* w, const char* name, int32_t name_len, int32_t n,
                                        lxl_reader* const* sources, const char* const* fields, const int32_t* bases,
                                        const int32_t* const* doc_maps, int64_t* stats);

typedef struct lxl_dict lxl_dict;
/* Dictionary reader (thread-safe); *count receives the number of entries. */
LXL_API lxl_dict* lxl_dict_new(lxl_reader* r, const char* name, int32_t name_len, int64_t* count);
LXL_API void lxl_dict_free(lxl_dict* d);
/* Copies entry ord to out if it fits in cap; returns its length. */
LXL_API int32_t lxl_dict_lookup(lxl_dict* d, int64_t ord, uint8_t* out, int32_t cap);

typedef struct lxl_stored lxl_stored;
typedef struct lxl_numeric lxl_numeric;

/* Stored-value reader (not thread-safe: one per thread). */
LXL_API lxl_stored* lxl_stored_new(lxl_reader* r);
LXL_API void lxl_stored_free(lxl_stored* s);
/* A doc's values in order, each [vint field][vint length][bytes]. Returns the
 * bytes needed; if more than cap, out is incomplete: call again with more room. */
LXL_API int32_t lxl_stored_doc(lxl_stored* s, int32_t doc, uint8_t* out, int32_t cap);

/* Numeric column iterator (single thread). */
LXL_API lxl_numeric* lxl_numeric_new(lxl_reader* r, const char* name, int32_t name_len);
LXL_API void lxl_numeric_free(lxl_numeric* c);
/* Up to cap docs >= target that have values (targets increase across calls):
 * docs[i], and for single-valued columns values[i]; for multi-valued columns
 * counts[i] and the values flattened (at most value_cap). Returns the count, 0
 * at the end. */
LXL_API int32_t lxl_numeric_fill(lxl_numeric* c, int32_t target, int32_t* docs, int32_t* counts,
                                 int64_t* values, int32_t cap, int32_t value_cap);

/* Points reader (thread-safe). info: leafCount, pointCount, maxPointsPerLeaf.
 * Leaves are in value order (then doc order). */
LXL_API lxl_points* lxl_points_new(lxl_reader* r, const char* name, int32_t name_len, int64_t* info);
LXL_API void lxl_points_free(lxl_points* p);
LXL_API int32_t lxl_points_bounds(lxl_points* p, int64_t* mins, int64_t* maxs);
/* A leaf's points (maxPointsPerLeaf room each; values may be NULL). Returns the count. */
LXL_API int32_t lxl_points_leaf(lxl_points* p, int32_t leaf, int32_t* docs, int64_t* values);
/* Top k of the matching docs (matches: max_doc bits, num_matches of them set) by a
 * single-valued column, best first, ties by doc ascending: docs_out, and keys_out (the
 * sortable keys: raw values for LXL_SORT_LONG, Float.compare/Double.compare order for
 * float/double bits). Docs without a value take missing_key. Returns how many. */
#define LXL_SORT_LONG 0
#define LXL_SORT_FLOAT_BITS 1
#define LXL_SORT_DOUBLE_BITS 2
LXL_API int32_t lxl_column_top_k(lxl_reader* r, const char* name, int32_t name_len, int32_t kind,
                                 const uint64_t* matches, int32_t num_matches, int32_t max_doc, int32_t k,
                                 int32_t reverse, int64_t missing_key, int32_t* docs_out, int64_t* keys_out);
/* Facet counting over the matching docs (max_doc bits) by a column: the touched ordinals
 * (ascending) and their counts; returns how many there are, filling the arrays only if that fits
 * in cap. *missing: docs without a value. */
LXL_API int32_t lxl_column_ord_counts_sparse(lxl_reader* r, const char* name, int32_t name_len,
                                             const uint64_t* matches, int32_t num_matches, int32_t max_doc,
                                             int64_t n_ords, int32_t* ords, int32_t* counts, int32_t cap,
                                             int32_t* missing);
/* Values with their doc counts and first doc: *out points to that many
 * {int64 value; int32 count; int32 first_doc} (16 bytes each), the calling thread's until its next
 * call or lxl_value_counts_release. Returns how many there are. *missing: docs without a value.
 * Each value is listed once, except when more than half of the first `sample` docs with a value add
 * a new one (sample <= 0: never): the values are then listed in doc order instead of hashed, and one
 * not adjacent to its earlier listing repeats (its counts add up; first_doc is its first listing's). */
LXL_API int32_t lxl_column_value_counts(lxl_reader* r, const char* name, int32_t name_len, const uint64_t* matches,
                                        int32_t num_matches, int32_t max_doc, int32_t sample, const void** out,
                                        int32_t* missing);
/* Join over single-valued sorted (string) doc-values columns of n segments: sets in out[i] (max_docs[i]
 * bits, zeroed by the caller) every doc of segment i whose to-column value is a from-column value of
 * some doc in from_matches (any segment). Per segment: stores[i] its doc-values store (NULL: no values
 * in either column), from_cols/from_dicts and to_cols/to_dicts the NUL-terminated column and
 * dictionary names (NULL: no values there), from_matches/from_counts the from docs (NULL or 0: none),
 * to_live the docs that may be output (NULL: all). *total receives the number of docs set. */
LXL_API int32_t lxl_join_sorted(int32_t n, lxl_reader* const* stores, const int32_t* max_docs,
                                const char* const* from_cols, const char* const* from_dicts,
                                const uint64_t* const* from_matches, const int32_t* from_counts,
                                const char* const* to_cols, const char* const* to_dicts,
                                const uint64_t* const* to_live, uint64_t* const* out, int64_t* total);
/* Frees the calling thread's lxl_column_value_counts result. */
LXL_API void lxl_value_counts_release(void);
/* Docs with a value whose sort key (see lxl_column_top_k) is in [los[i], his[i]]: counts[i] += 1. */
LXL_API int32_t lxl_column_range_counts(lxl_reader* r, const char* name, int32_t name_len, int32_t kind,
                                        const uint64_t* matches, int32_t num_matches, int32_t max_doc, int32_t n,
                                        const int64_t* los, const int64_t* his, int32_t* counts);
/* Sets in out (max_doc bits, zeroed) every doc with a value in [lo, hi]; returns how many. */
LXL_API int32_t lxl_points_range(lxl_points* p, int64_t lo, int64_t hi, int32_t max_doc, uint64_t* out);
/* As lxl_points_range, for the docs with any of the n values. */
LXL_API int32_t lxl_points_set(lxl_points* p, const int64_t* values, int32_t n, int32_t max_doc, uint64_t* out);
/* JSON facet collection over the matching docs (max_doc bits): each doc goes to slots by group
 * (0: slot; 1: ord_slots[ord] of each of its ordinals in column gname, skipping -1; 2: each range i
 * of n_ranges whose [los[i], his[i]] holds the sort key (range_kind, see lxl_column_top_k) of any
 * of its values in gname). Per (doc, slot), in doc order: counts[slot] += 1 (counts may be null),
 * then each aggregation a updates its per-slot accumulators (d0/d1/l0/i0/bits[a]) by op[a] from
 * the single-valued column names[name_offs[a]..name_offs[a+1]) decoded per types[a]. */
LXL_API int32_t lxl_facet_collect(lxl_reader* r, const uint64_t* matches, int32_t num_matches, int32_t max_doc,
                                  int32_t group, const char* gname, int32_t gname_len, int32_t slot,
                                  const int32_t* ord_slots, int64_t n_ords, int32_t range_kind, int32_t n_ranges,
                                  const int64_t* los, const int64_t* his, int64_t* counts, int32_t n_aggs,
                                  const char* names, const int32_t* name_offs, const int32_t* ops,
                                  const int32_t* types, double* const* d0, double* const* d1, int64_t* const* l0,
                                  int32_t* const* i0, uint64_t* const* bits);

/* ---- native string fields (inverter mode) --------------------------------------------------
 * Values of string fields that Lucene never sees: buffered with one hash per field, then
 * written at flush as doc values (ordinal column + dictionary, into another writer) and as
 * docs-only postings (into this writer, after lxl_writer_set_max_doc). */

/* Ordinal of a native string field for lxl_writer_add_strings. */
LXL_API int32_t lxl_writer_string_field(lxl_writer* w, const char* name, int32_t len);
/* Value i is field ords[i] of doc docs[i], bytes [offsets[i], offsets[i+1]); docs never
 * decrease within a field, across calls too. */
LXL_API int32_t lxl_writer_add_strings(lxl_writer* w, int32_t n, const int32_t* docs, const int32_t* ords,
                                       const uint8_t* bytes, const int64_t* offsets);
/* Writes field ord of src as a sorted ordinal column (multi: per-doc sorted unique ords; else
 * each doc's first value) with its dictionary into the plain writer dst. stats: docs, values,
 * terms. */
LXL_API int32_t lxl_writer_strings_dv(lxl_writer* dst, const char* column, int32_t column_len, const char* dict,
                                      int32_t dict_len, lxl_writer* src, int32_t ord, int32_t multi, int64_t* stats);
/* Writes field ord's docs-only postings; returns the number of docs with the field. Terms must
 * be at most LXL_MAX_TERM bytes. */
LXL_API int32_t lxl_writer_strings_postings(lxl_writer* w, int32_t ord);

#ifdef __cplusplus
}
#endif

#endif
