// Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
// SPDX-License-Identifier: Apache-2.0

package org.luxir.lucene.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.Transition;
import org.luxir.lucene.LxlNative;

/**
 * A deterministic byte automaton in the form Luxir's DFA walk takes (lxl_intersect_new, and the
 * native automaton query node): per-state accept flags, per-state offsets into sorted (min, max,
 * dest) ranges, the fixed prefix and the state after it, the common suffix, and an optional start
 * term the walk resumes after.
 */
public record LuxirDfa(byte[] accepts, int[] offsets, int[] ranges, byte[] prefix, int initialState, byte[] suffix,
    byte[] startAfter) {
  public static LuxirDfa of(CompiledAutomaton compiled, BytesRef startTerm) {
    Automaton a = compiled.automaton;
    int n = a.getNumStates();
    if (n == 0) return null;
    byte[] accepts = new byte[n];
    int[] offsets = new int[n + 1];
    List<int[]> all = new ArrayList<>();
    Transition t = new Transition();
    for (int s = 0; s < n; s++) {
      accepts[s] = (byte) (a.isAccept(s) ? 1 : 0);
      int count = a.initTransition(s, t);
      int[][] st = new int[count][];
      for (int i = 0; i < count; i++) {
        a.getNextTransition(t);
        if (t.min < 0 || t.max > 255) return null;
        st[i] = new int[] {t.min, t.max, t.dest};
      }
      Arrays.sort(st, (x, y) -> Integer.compare(x[0], y[0]));
      for (int[] r : st) all.add(r);
      offsets[s + 1] = all.size();
    }
    int[] ranges = new int[all.size() * 3];
    for (int i = 0; i < all.size(); i++) System.arraycopy(all.get(i), 0, ranges, 3 * i, 3);
    // Fixed prefix: follow single-byte, non-accepting states from the start.
    byte[] prefix = new byte[LxlNative.MAX_TERM];
    int plen = 0, state = 0;
    while (plen < LxlNative.MAX_TERM && accepts[state] == 0 && offsets[state + 1] - offsets[state] == 1) {
      int r = offsets[state];
      if (ranges[3 * r] != ranges[3 * r + 1]) break;
      prefix[plen++] = (byte) ranges[3 * r];
      state = ranges[3 * r + 2];
    }
    BytesRef cs = compiled.commonSuffixRef;
    byte[] suffix = cs == null ? new byte[0] : Arrays.copyOfRange(cs.bytes, cs.offset, cs.offset + cs.length);
    // The native dictionary holds terms of at most MAX_TERM bytes; for those, sorting after a longer
    // start term is sorting after its first MAX_TERM bytes (a term equal to them is a prefix of it).
    byte[] startAfter = startTerm == null ? null
        : Arrays.copyOfRange(startTerm.bytes, startTerm.offset,
            startTerm.offset + Math.min(startTerm.length, LxlNative.MAX_TERM));
    return new LuxirDfa(accepts, offsets, ranges, Arrays.copyOf(prefix, plen), state, suffix, startAfter);
  }
}
