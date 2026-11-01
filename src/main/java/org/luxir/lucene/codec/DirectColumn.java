// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * A single-valued column with a value in every doc, decoded in place from the store's memory as
 * Luxir's NumColumn::valueAt decodes it, without a native call per read: blocks of 4096 values,
 * each a NumBlockInfo (payload offset and bit width, base, gcd, scaled slope) and its residuals,
 * bit-packed (or raw longs past 57 bits). Reads go through a slice of the store's segment, so a
 * read after the store is closed throws rather than touching unmapped memory.
 */
final class DirectColumn {
  private static final ValueLayout.OfLong LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  private static final int BLOCK_SHIFT = 12; // NumColumnFormat::BLOCK_SIZE = 4096
  private static final int SLOPE_SHIFT = 14; // NumColumnFormat::SLOPE_SHIFT
  private static final long OFFSET_MASK = (1L << 56) - 1;
  private static final int MAX_PACKED_BITS = 57;

  private final MemorySegment blocks; // from the column's value blocks to the end of the store
  private final long meta; // offset of the block descriptors in blocks
  final long numValues;

  DirectColumn(MemorySegment blocks, long meta, long numValues) {
    this.blocks = blocks;
    this.meta = meta;
    this.numValues = numValues;
  }

  /** The value at rank (here, the doc), 0 <= rank < numValues. */
  long get(long rank) {
    long info = meta + (rank >>> BLOCK_SHIFT) * 32;
    long offsetAndBits = blocks.get(LONG, info);
    long inBlock = rank & ((1 << BLOCK_SHIFT) - 1);
    int bits = (int) (offsetAndBits >>> 56);
    long payload = offsetAndBits & OFFSET_MASK;
    if (bits > MAX_PACKED_BITS) return blocks.get(LONG, payload + inBlock * Long.BYTES);
    long residual = 0;
    if (bits != 0) {
      long bitPos = inBlock * bits;
      residual = (blocks.get(LONG, payload + (bitPos >>> 3)) >>> (bitPos & 7)) & ((1L << bits) - 1);
    }
    long slope = blocks.get(LONG, info + 24);
    if (slope != 0) {
      // the low 64 bits of (int128) inBlock * slope >> SLOPE_SHIFT
      residual += (inBlock * slope >>> SLOPE_SHIFT) | (Math.multiplyHigh(inBlock, slope) << (64 - SLOPE_SHIFT));
    }
    return blocks.get(LONG, info + 8) + blocks.get(LONG, info + 16) * residual;
  }
}
