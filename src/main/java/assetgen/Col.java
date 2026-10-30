package assetgen;

import java.util.*;

/** Column definition used for the data dictionary, DDL and validation. */
public final class Col {
  final String n, t, allowed, unit, rule; final boolean req; String fk = ""; Set<String> set;
  Col(String n, String t, boolean req, String allowed, String unit, String rule) {
    this.n = n; this.t = t; this.req = req; this.allowed = allowed; this.unit = unit; this.rule = rule;
    if (!allowed.isEmpty()) set = new HashSet<>(Arrays.asList(allowed.split("\\|")));
  }
}
