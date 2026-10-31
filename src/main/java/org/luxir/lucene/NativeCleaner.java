// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an action (freeing a native object) once its Java owner is unreachable, as {@link
 * java.lang.ref.Cleaner} does, for the short-lived owners searches make by the thousand (terms and
 * postings enums, doc values iterators, stored fields readers). Before JDK 24, a Cleaner keeps its
 * registrations in one linked list, which a young GC walks one object at a time: with thousands of
 * them live, every pause took about 30 times longer than without, with the other GC threads
 * spinning. Registrations here are held in a concurrent set, whose table a GC scans in parallel,
 * and run by one daemon thread.
 */
public final class NativeCleaner {
  private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
  // striped by registering thread: searches register many short-lived owners from every thread
  private static final int STRIPES = 32;
  private static final List<Set<Ref>> LIVE = new ArrayList<>(STRIPES);

  static {
    for (int i = 0; i < STRIPES; i++) LIVE.add(ConcurrentHashMap.newKeySet());
  }

  static {
    // in the root thread group, as the JDK's cleaner thread: not one of whichever group (a test's,
    // a request's) happens to load this class
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) root = root.getParent();
    Thread t = new Thread(root, NativeCleaner::drain, "luxir-native-cleaner");
    t.setDaemon(true);
    t.start();
  }

  private NativeCleaner() {}

  private static final class Ref extends PhantomReference<Object> {
    final Runnable action;
    final int stripe;

    Ref(Object owner, Runnable action, int stripe) {
      super(owner, QUEUE);
      this.action = action;
      this.stripe = stripe;
    }
  }

  /** Runs {@code action} once {@code owner} is unreachable; the action must not refer to it. */
  public static void register(Object owner, Runnable action) {
    int stripe = (int) (Thread.currentThread().threadId() & (STRIPES - 1));
    LIVE.get(stripe).add(new Ref(owner, action, stripe));
  }

  private static void drain() {
    for (;;) {
      Ref r;
      try {
        r = (Ref) QUEUE.remove();
      } catch (InterruptedException e) {
        continue; // a daemon: runs as long as the JVM
      }
      LIVE.get(r.stripe).remove(r);
      try {
        r.action.run();
      } catch (Throwable t) {
        // as Cleaner does: one failing action does not stop the others
      }
    }
  }
}
