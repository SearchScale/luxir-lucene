// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downcall handles for {@code libluxir_lucene.so} (native/lxl.h).
 *
 * <p>The library path comes from the {@code luxir.lucene.library} system property. Short calls
 * that take Java arrays are linked {@linkplain Linker.Option#critical(boolean) critical} with heap
 * access, so term and postings iteration passes {@code byte[]}/{@code int[]} buffers without
 * copying; long-running calls (segment writing, top-k) are linked normally.
 */
public final class LxlNative {
  public static final int ABI_VERSION = 7;
  public static final int MAX_TERM = 255;
  public static final int END = Integer.MAX_VALUE;
  public static final int TERM_START = 1;
  public static final int TERM_END = 2;
  public static final int SEEK_FOUND = 0;
  public static final int SEEK_NOT_FOUND = 1;
  public static final int SEEK_END = 2;
  public static final int EXEC_SCORES = 1;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIB = load();

  private static SymbolLookup load() {
    String path = System.getProperty("luxir.lucene.library");
    if (path == null) {
      throw new IllegalStateException("set -Dluxir.lucene.library=/path/to/libluxir_lucene.so");
    }
    if (!Files.isReadable(Path.of(path))) {
      throw new IllegalStateException("luxir.lucene.library not readable: " + path);
    }
    return SymbolLookup.libraryLookup(Path.of(path), Arena.global());
  }

  private static MethodHandle fn(String name, FunctionDescriptor fd, Linker.Option... options) {
    MemorySegment sym =
        LIB.find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
    return LINKER.downcallHandle(sym, fd, options);
  }

  private static final Linker.Option CRITICAL = Linker.Option.critical(true);

  public static final MethodHandle ABI = fn("lxl_abi_version", FunctionDescriptor.of(JAVA_INT));
  public static final MethodHandle LAST_ERROR = fn("lxl_last_error", FunctionDescriptor.of(ADDRESS));

  // writer
  public static final MethodHandle WRITER_NEW = fn("lxl_writer_new", FunctionDescriptor.of(ADDRESS, JAVA_INT));
  public static final MethodHandle WRITER_START_FIELD =
      fn("lxl_writer_start_field",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
          CRITICAL);
  public static final MethodHandle WRITER_TERM =
      fn("lxl_writer_term",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
          CRITICAL);
  public static final MethodHandle WRITER_END_FIELD = fn("lxl_writer_end_field", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  public static final MethodHandle WRITER_FINISH = fn("lxl_writer_finish", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  public static final MethodHandle WRITER_FILE =
      fn("lxl_writer_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_FREE = fn("lxl_writer_free", FunctionDescriptor.ofVoid(ADDRESS));

  // writer, inverter mode (Luxir analyzes and inverts)
  public static final MethodHandle WRITER_NEW_INVERTER =
      fn("lxl_writer_new_inverter", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle WRITER_FIELD_ORD =
      fn("lxl_writer_field_ord", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle WRITER_ADD_DOCS =
      fn("lxl_writer_add_docs", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_STRING_FIELD =
      fn("lxl_writer_string_field", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle WRITER_ADD_STRINGS =
      fn("lxl_writer_add_strings", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_STRINGS_DV =
      fn("lxl_writer_strings_dv",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
              ADDRESS));
  public static final MethodHandle WRITER_STRINGS_POSTINGS =
      fn("lxl_writer_strings_postings", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  public static final MethodHandle WRITER_RAM = fn("lxl_writer_ram", FunctionDescriptor.of(JAVA_LONG, ADDRESS), CRITICAL);
  public static final MethodHandle WRITER_SET_MAX_DOC =
      fn("lxl_writer_set_max_doc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  public static final MethodHandle WRITER_FIELD_STATS =
      fn("lxl_writer_field_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL);

  public static final MethodHandle WRITER_MERGE_FIELD =
      fn("lxl_writer_merge_field",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

  // stored values, numeric columns and points
  public static final MethodHandle WRITER_STORED =
      fn("lxl_writer_stored", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
          CRITICAL);
  public static final MethodHandle WRITER_NUMERIC =
      fn("lxl_writer_numeric",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_POINTS =
      fn("lxl_writer_points", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_STORED_MERGE =
      fn("lxl_writer_stored_merge",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle WRITER_NUMERIC_MERGE =
      fn("lxl_writer_numeric_merge",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
              JAVA_INT, ADDRESS));
  public static final MethodHandle WRITER_SORTED_MERGE =
      fn("lxl_writer_sorted_merge",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
              ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
  public static final MethodHandle WRITER_POINTS_MERGE =
      fn("lxl_writer_points_merge",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
              ADDRESS));
  public static final MethodHandle WRITER_DICT =
      fn("lxl_writer_dict", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
  public static final MethodHandle DICT_NEW =
      fn("lxl_dict_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL);
  public static final MethodHandle DICT_FREE = fn("lxl_dict_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle DICT_LOOKUP =
      fn("lxl_dict_lookup", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle STORED_NEW = fn("lxl_stored_new", FunctionDescriptor.of(ADDRESS, ADDRESS));
  public static final MethodHandle STORED_FREE = fn("lxl_stored_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle STORED_DOC =
      fn("lxl_stored_doc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle NUMERIC_NEW =
      fn("lxl_numeric_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle NUMERIC_FREE = fn("lxl_numeric_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle NUMERIC_FILL =
      fn("lxl_numeric_fill",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT), CRITICAL);
  public static final MethodHandle NUMERIC_FILL_ONCE =
      fn("lxl_numeric_fill_once",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
              JAVA_INT), CRITICAL);
  public static final MethodHandle POINTS_NEW =
      fn("lxl_points_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL);
  public static final MethodHandle POINTS_FREE = fn("lxl_points_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle POINTS_BOUNDS =
      fn("lxl_points_bounds", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), CRITICAL);
  public static final MethodHandle POINTS_LEAF =
      fn("lxl_points_leaf", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), CRITICAL);
  public static final int SORT_LONG = 0, SORT_FLOAT_BITS = 1, SORT_DOUBLE_BITS = 2;
  public static final MethodHandle COLUMN_TOP_K =
      fn("lxl_column_top_k",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
              JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
  public static final MethodHandle COLUMN_ORD_COUNTS_SPARSE =
      fn("lxl_column_ord_counts_sparse",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS,
              ADDRESS, JAVA_INT, ADDRESS));
  public static final MethodHandle COLUMN_VALUE_COUNTS =
      fn("lxl_column_value_counts",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
              ADDRESS, ADDRESS));
  public static final MethodHandle VALUE_COUNTS_RELEASE =
      fn("lxl_value_counts_release", FunctionDescriptor.ofVoid());
  public static final MethodHandle COLUMN_RANGE_COUNTS =
      fn("lxl_column_range_counts",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
              ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle POINTS_RANGE =
      fn("lxl_points_range", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
  public static final MethodHandle POINTS_SET =
      fn("lxl_points_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
  public static final MethodHandle FACET_COLLECT =
      fn("lxl_facet_collect",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT,
              JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
              ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // native analysis
  public static final MethodHandle ANALYZER_NEW =
      fn("lxl_analyzer_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle ANALYZER_FREE = fn("lxl_analyzer_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle ANALYZE_START =
      fn("lxl_analyze_start", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle ANALYZE_NEXT =
      fn("lxl_analyze_next", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle NORMALIZE =
      fn("lxl_normalize", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);

  // reader
  public static final MethodHandle READER_OPEN =
      fn("lxl_reader_open", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle READER_FREE = fn("lxl_reader_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle READER_NORMS =
      fn("lxl_reader_norms", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL);
  public static final MethodHandle READER_FIELD_ALIAS =
      fn("lxl_reader_field_alias", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);

  // terms
  public static final MethodHandle TERMS_NEW =
      fn("lxl_terms_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle TERMS_FREE = fn("lxl_terms_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle TERMS_NEXT =
      fn("lxl_terms_next", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), CRITICAL);
  public static final MethodHandle TERMS_SEEK =
      fn("lxl_terms_seek",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
          CRITICAL);
  public static final MethodHandle TERMS_SEEK_ORD =
      fn("lxl_terms_seek_ord", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS), CRITICAL);

  // automaton intersection
  public static final MethodHandle INTERSECT_NEW =
      fn("lxl_intersect_new",
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
              JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
          CRITICAL);
  public static final MethodHandle INTERSECT_NEXT =
      fn("lxl_intersect_next", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), CRITICAL);
  public static final MethodHandle INTERSECT_TERMS =
      fn("lxl_intersect_terms", FunctionDescriptor.of(ADDRESS, ADDRESS));
  public static final MethodHandle INTERSECT_FREE = fn("lxl_intersect_free", FunctionDescriptor.ofVoid(ADDRESS));

  // postings
  public static final MethodHandle POSTINGS_NEW =
      fn("lxl_postings_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
  public static final MethodHandle POSTINGS_FREE = fn("lxl_postings_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle POSTINGS_FILL =
      fn("lxl_postings_fill", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
  public static final MethodHandle POSTINGS_NEXT =
      fn("lxl_postings_next", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), CRITICAL);
  public static final MethodHandle POSTINGS_POSITIONS =
      fn("lxl_postings_positions", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL);

  // query execution
  public static final MethodHandle EXEC_NEW =
      fn("lxl_exec_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
  public static final MethodHandle EXEC_FREE = fn("lxl_exec_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle EXEC_WINDOW =
      fn("lxl_exec_window",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
          CRITICAL);
  public static final MethodHandle TOP_K =
      fn("lxl_top_k",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT,
              JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // one search over many segments
  public static final MethodHandle MULTI_NEW = fn("lxl_multi_new", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
  public static final MethodHandle MULTI_FREE = fn("lxl_multi_free", FunctionDescriptor.ofVoid(ADDRESS));
  public static final MethodHandle JOIN_SORTED =
      fn("lxl_join_sorted",
          FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
              ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  public static final MethodHandle MULTI_TOP_K =
      fn("lxl_multi_top_k",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS,
              ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
              ADDRESS, ADDRESS));
  public static final MethodHandle MULTI_TOP_K_SORTED =
      fn("lxl_multi_top_k_sorted",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
              JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG,
              ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  static {
    int abi;
    try {
      abi = (int) ABI.invokeExact();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
    if (abi != ABI_VERSION) {
      throw new IllegalStateException("libluxir_lucene ABI " + abi + ", expected " + ABI_VERSION);
    }
  }

  private LxlNative() {}

  /** The message of the last failed native call on this thread. */
  public static String lastError() {
    try {
      MemorySegment p = (MemorySegment) LAST_ERROR.invokeExact();
      return p.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      return "(no message: " + t + ")";
    }
  }

  /** Throws with the native message when {@code rc} is negative. */
  public static int check(int rc) {
    if (rc < 0) throw new LxlException(lastError());
    return rc;
  }

  /** Throws with the native message when {@code p} is NULL. */
  public static MemorySegment check(MemorySegment p) {
    if (p.address() == 0) throw new LxlException(lastError());
    return p;
  }

  public static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException r) return r;
    if (t instanceof Error e) throw e;
    return new LxlException(t.toString(), t);
  }

  /** Unchecked failure of a native call. */
  public static final class LxlException extends RuntimeException {
    public LxlException(String message) {
      super(message);
    }

    LxlException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static MemorySegment heap(byte[] a) {
    return MemorySegment.ofArray(a);
  }

  public static MemorySegment heap(int[] a) {
    return MemorySegment.ofArray(a);
  }

  public static MemorySegment heap(long[] a) {
    return MemorySegment.ofArray(a);
  }

  public static MemorySegment heap(float[] a) {
    return MemorySegment.ofArray(a);
  }

  public static final MemorySegment NULL = MemorySegment.NULL;
}
