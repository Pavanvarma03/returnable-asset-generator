package assetgen;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static assetgen.Config.*;

/** In-memory table with schema metadata; exports CSV, DDL and INSERT statements. */
public final class Table {
  final String name, desc; final String[] pk; String rel = "";
  final List<Col> cols = new ArrayList<>(); final Map<String, Integer> ix = new HashMap<>();
  List<Object[]> rows = new ArrayList<>();
  Table(String name, String pk, String desc) { this.name = name; this.pk = pk.split(","); this.desc = desc; }
  Table c(String n, String t, boolean req, String allowed, String unit, String rule) { ix.put(n, cols.size()); cols.add(new Col(n, t, req, allowed, unit, rule)); return this; }
  Table fk(String col, String ref) { cols.get(ix.get(col)).fk = ref; return this; }
  Table rel(String s) { rel = s; return this; }
  Object[] add(Object... v) {
    if (v.length != cols.size()) throw new IllegalStateException(name + " expects " + cols.size() + " values, got " + v.length);
    rows.add(v); return v;
  }
  int i(String c) { Integer k = ix.get(c); if (k == null) throw new IllegalArgumentException(name + "." + c); return k; }
  static String fmt(Col c, Object v) {
    if (v == null) return "";
    switch (c.t) {
      case "TS": return Instant.ofEpochSecond((Long) v).toString();
      case "DATE": return LocalDate.ofEpochDay((Long) v).toString();
      case "DEC2": { long x = (Long) v, ax = Math.abs(x); return (x < 0 ? "-" : "") + ax / 100 + "." + pad(ax % 100, 2); }
      case "DEC4": return String.format(Locale.ROOT, "%.4f", (Double) v);
      default: return v.toString();
    }
  }
  void csv(Path p) throws IOException {
    try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cols.size(); i++) { if (i > 0) sb.append(','); sb.append(cols.get(i).n); }
      sb.append('\n'); w.write(sb.toString());
      for (Object[] r : rows) {
        sb.setLength(0);
        for (int i = 0; i < r.length; i++) {
          if (i > 0) sb.append(',');
          String s = fmt(cols.get(i), r[i]);
          if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) s = '"' + s.replace("\"", "\"\"") + '"';
          sb.append(s);
        }
        sb.append('\n'); w.write(sb.toString());
      }
    }
  }
  String sqlType(Col c) {
    switch (c.t) {
      case "STR": return "VARCHAR(64)"; case "ENUM": return "VARCHAR(32)"; case "INT": return "INTEGER"; case "TS": return "TIMESTAMP";
      case "DATE": return "DATE"; case "BOOL": return "BOOLEAN"; case "DEC2": return "DECIMAL(14,2)"; default: return "DECIMAL(10,4)";
    }
  }
  String ddl() {
    StringBuilder sb = new StringBuilder("CREATE TABLE " + name + " (\n");
    for (Col c : cols) {
      sb.append("  ").append(c.n).append(' ').append(sqlType(c)).append(c.req ? " NOT NULL" : "");
      if (c.set != null) { sb.append(" CHECK (").append(c.n).append(" IN ("); boolean f = true; for (String s : c.allowed.split("\\|")) { if (!f) sb.append(','); f = false; sb.append('\'').append(s).append('\''); } sb.append("))"); }
      sb.append(",\n");
    }
    sb.append("  PRIMARY KEY (").append(String.join(", ", pk)).append(")");
    for (Col c : cols) if (!c.fk.isEmpty()) { String[] f = c.fk.split("\\."); sb.append(",\n  FOREIGN KEY (").append(c.n).append(") REFERENCES ").append(f[0]).append('(').append(f[1]).append(')'); }
    return sb.append("\n);\n\n").toString();
  }
  void sql(Writer w) throws IOException {
    StringBuilder head = new StringBuilder("INSERT INTO " + name + " (");
    for (int i = 0; i < cols.size(); i++) { if (i > 0) head.append(", "); head.append(cols.get(i).n); }
    head.append(") VALUES\n");
    int n = rows.size();
    for (int s = 0; s < n; s += 1000) {
      StringBuilder sb = new StringBuilder(head);
      int e = Math.min(n, s + 1000);
      for (int k = s; k < e; k++) {
        Object[] r = rows.get(k); sb.append('(');
        for (int i = 0; i < r.length; i++) {
          if (i > 0) sb.append(',');
          Col c = cols.get(i); String v = fmt(c, r[i]);
          if (r[i] == null) sb.append("NULL");
          else if (c.t.equals("STR") || c.t.equals("ENUM")) sb.append('\'').append(v.replace("'", "''")).append('\'');
          else if (c.t.equals("TS")) sb.append('\'').append(v.replace('T', ' ').replace("Z", "")).append('\'');
          else if (c.t.equals("DATE")) sb.append('\'').append(v).append('\'');
          else if (c.t.equals("BOOL")) sb.append(v.toUpperCase());
          else sb.append(v);
        }
        sb.append(k < e - 1 ? "),\n" : ");\n");
      }
      w.write(sb.toString());
    }
    w.write("\n");
  }
}
