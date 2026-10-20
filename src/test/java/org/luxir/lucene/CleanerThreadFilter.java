// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene;

import com.carrotsearch.randomizedtesting.ThreadFilter;

/** The JDK Cleaner thread that frees native enums is shared and outlives tests by design. */
public final class CleanerThreadFilter implements ThreadFilter {
  @Override
  public boolean reject(Thread t) {
    return t.getName().startsWith("Cleaner-");
  }
}
