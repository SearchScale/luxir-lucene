// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import org.luxir.lucene.LxlNative;

/**
 * Owns one native object (a terms or postings enum). Lucene enums have no close(), so the object
 * is freed when it is replaced, or by a {@link Cleaner} once its Java owner is unreachable.
 */
final class NativeHandle implements Runnable {
  private static final Cleaner CLEANER = Cleaner.create();

  private final MethodHandle free;
  private MemorySegment ptr = MemorySegment.NULL;

  private NativeHandle(MethodHandle free) {
    this.free = free;
  }

  /** A handle freed by {@code free} when {@code owner} becomes unreachable. */
  static NativeHandle register(Object owner, MethodHandle free) {
    NativeHandle h = new NativeHandle(free);
    CLEANER.register(owner, h);
    return h;
  }

  MemorySegment get() {
    return ptr;
  }

  /** Replaces the owned object, freeing the previous one. */
  synchronized void set(MemorySegment p) {
    run();
    ptr = p;
  }

  @Override
  public synchronized void run() {
    if (ptr.address() == 0) return;
    MemorySegment p = ptr;
    ptr = MemorySegment.NULL;
    try {
      free.invokeExact(p);
    } catch (Throwable t) {
      throw LxlNative.rethrow(t);
    }
  }
}
