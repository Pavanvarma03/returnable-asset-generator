package assetgen;

import java.time.*;
import static assetgen.Config.*;

/** Command-line option parsing; writes the run parameters into Config. */
public final class Cli {
  private Cli() {}

  static void parse(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String k = args[i].replaceFirst("^--", ""), v = null;
      if (k.contains("=")) { v = k.substring(k.indexOf('=') + 1); k = k.substring(0, k.indexOf('=')); } else if (i + 1 < args.length) v = args[++i];
      switch (k) {
        case "seed": SEED = Long.parseLong(v); break; case "assets": NA = Integer.parseInt(v); break; case "shipments": NS = Integer.parseInt(v); break;
        case "events": NE = Integer.parseInt(v); break; case "out": OUT = v; break; case "as-of": ASOF_STR = v; break; case "sql": SQL = !"false".equals(v); break;
        default: throw new IllegalArgumentException("unknown option " + k);
      }
    }
    ASOF = LocalDate.parse(ASOF_STR).atStartOfDay(ZoneOffset.UTC).toEpochSecond(); D = (int) ((ASOF - BASE) / DAY);
  }
}
