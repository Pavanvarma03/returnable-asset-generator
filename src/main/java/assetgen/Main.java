package assetgen;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static assetgen.Config.*;
import static assetgen.Output.*;

/**
 * Synthetic data generator: returnable assets (pallets, reusable packaging, equipment, return containers).
 *
 * Complexity: O(A + S + E) time and memory (A assets, S shipments, E custody events).
 *  - every record is produced in O(1); pools are swap-remove arrays; releases use day buckets (counting sort);
 *  - validation/derivation groups events with a counting sort and hash maps (expected O(1) per lookup).
 *  - volume calibration repeats the whole O(N) simulation a small constant number of times.
 *
 * Run: java -cp build assetgen.Main --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --out out
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    Cli.parse(args);
    // ---- calibrate batch size so the baseline lands just below the event target, final transfer absorbs the exact remainder ----
    double scale = 1.0; World w = null; int it;
    for (it = 0; it < 40; it++) {
      w = new World(scale); w.run();
      boolean ok = !w.failed && w.residual >= 0 && w.residual <= 0.006 * NE;
      System.out.printf(Locale.ROOT, "calibration %d: scale=%.4f failed=%b residual=%d%n", it, scale, w.failed, w.residual);
      if (ok) break;
      double cust = Math.max(1, w.preFinal - NA - w.xferEv), target = NE - w.inj - 0.003 * NE - NA - w.xferEv;
      if (w.preFinal == 0) { scale *= 0.9; continue; }
      scale *= Math.max(0.5, Math.min(2.0, target / cust));
    }
    if (w.failed || w.residual < 0) throw new IllegalStateException("calibration failed; try larger --events relative to --assets");
    new AnomalyInjector(w).inject();
    Validator v = new Validator(w); v.run();
    Exporter.write(w, v);
  }
}
