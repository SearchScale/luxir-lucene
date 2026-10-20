// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;

/**
 * Writes large files the codec has finished to disk in the background, so the commit that later
 * fsyncs them finds little left to write. Lucene fsyncs a commit's files one at a time, after
 * every segment is flushed; without this, the disk idles while segments are built and then
 * writes them all while the commit waits. Extra syncs are always safe. Files below {@code
 * luxir.writeback.minBytes} (default 8 MiB; negative disables) are left to the commit, as are
 * files not on a file system directory (e.g. cached in RAM by NRTCachingDirectory).
 */
final class EarlyWriteback {
  static final long MIN_BYTES = Long.getLong("luxir.writeback.minBytes", 8L << 20);

  // One worker while files are queued (one disk takes them in turn), gone soon after.
  private static final ThreadPoolExecutor POOL =
      new ThreadPoolExecutor(
          0,
          1,
          50,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<>(),
          r -> {
            Thread t = new Thread(r, "luxir-writeback");
            t.setDaemon(true);
            return t;
          });

  private EarlyWriteback() {}

  /** Queues the file for writeback if it is large and on disk. Never throws. */
  static void submit(Directory dir, String name) {
    if (MIN_BYTES < 0) return;
    if (!(FilterDirectory.unwrap(dir) instanceof FSDirectory fs)) return;
    Path path = fs.getDirectory().resolve(name);
    try {
      if (Files.size(path) < MIN_BYTES) return;
    } catch (IOException e) {
      return; // not on disk (yet, or any more)
    }
    POOL.execute(
        () -> {
          try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.force(false);
          } catch (IOException | RuntimeException e) {
            // deleted meanwhile, or not syncable: the commit syncs what it needs
          }
        });
  }
}
