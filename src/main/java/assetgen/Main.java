package assetgen;

import static assetgen.Config.*;
import static assetgen.Output.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class Main {

    public static void main(String[] args) throws Exception {
        Cli.parse(args);

        double scale = 1.0;
        World w = null;
        int it;
        for (it = 0; it < 40; it++) {
            w = new World(scale);
            w.run();
            boolean ok = !w.failed && w.residual >= 0 && w.residual <= 0.006 * NUM_EVENTS;
            System.out.printf(
                Locale.ROOT,
                "calibration %d: scale=%.4f failed=%b residual=%d%n",
                it,
                scale,
                w.failed,
                w.residual
            );
            if (ok) break;
            double cust = Math.max(1, w.preFinal - NUM_ASSETS - w.xferEv),
                target = NUM_EVENTS - w.inj - 0.003 * NUM_EVENTS - NUM_ASSETS - w.xferEv;
            if (w.preFinal == 0) {
                scale *= 0.9;
                continue;
            }
            scale *= Math.max(0.5, Math.min(2.0, target / cust));
        }
        if (w.failed || w.residual < 0) throw new IllegalStateException(
            "calibration failed; try larger --events relative to --assets"
        );
        new AnomalyInjector(w).inject();
        Validator v = new Validator(w);
        v.run();
        Exporter.write(w, v);
    }
}
