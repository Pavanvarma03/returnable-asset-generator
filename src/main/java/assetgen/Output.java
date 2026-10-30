package assetgen;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static assetgen.Config.*;

/** Formatting helpers: JSON strings, checksums, data dictionary and README text. */
public final class Output {
  private Output() {}

  static String jstr(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
  static String sha(Path p) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
    StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x)); return sb.toString();
  }
  static String dictionary(List<Table> ts) {
    StringBuilder sb = new StringBuilder("# Data dictionary\n\nTypes: STR text, INT integer, DEC2 money with 2 decimals (USD), DEC4 decimal with 4 places, TS UTC timestamp (ISO-8601), DATE ISO date, BOOL, ENUM closed list. NULLs are written as empty CSV fields. `Required = Y` means NOT NULL. Unique = the field is a primary key (or states `unique`).\n\n");
    for (Table t : ts) {
      sb.append("## ").append(t.name).append("\n\n").append(t.desc).append("\n\n- Primary key: `").append(String.join(", ", t.pk)).append("`\n");
      if (!t.rel.isEmpty()) sb.append("- Cardinality / relationships: ").append(t.rel).append("\n");
      sb.append("\n| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |\n|---|---|---|---|---|---|---|\n");
      for (Col c : t.cols) {
        boolean pk = Arrays.asList(t.pk).contains(c.n) && t.pk.length == 1;
        sb.append("| ").append(c.n).append(" | ").append(c.t).append(" | ").append(c.req ? "Y" : "N").append(" | ").append(c.allowed.replace("|", ", ")).append(" | ")
          .append(c.unit).append(" | ").append(pk || c.rule.contains("unique") ? "Y" : "").append(" | ").append(c.rule.isEmpty() && c.fk.isEmpty() ? "" : c.rule + (c.fk.isEmpty() ? "" : " FK -> " + c.fk)).append(" |\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }


  static String readme(World w) {
    return "# Returnable assets: synthetic custody, return and deposit data\n\n"
      + "Coordinates return merchandise, reusable packaging, pallets and equipment across locations and custody transitions.\n\n"
      + "## Run\n\n`javac -d build src/main/java/assetgen/*.java && java -cp build assetgen.Main --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --as-of 2026-10-30 --out out`\n\n"
      + "Starter set: `--assets 2000 --shipments 1000 --events 10000`. Same generator, same rules, only the scale changes. Java 17+, no dependencies. `--sql false` skips SQL.\n\n"
      + "Complexity: O(A+S+E). Pools are swap-remove arrays, asset release uses day buckets (counting sort), derivation uses counting sort and hash maps. Volume calibration repeats the O(N) simulation a small constant number of times.\n\n"
      + "## Layout\n\n- `data/` core entities (CSV): asset_type, owner, location, actor (supporting), reusable_asset, shipment, shipment_asset (bridge), return_authorization, custody_event, inspection, repair, deposit_ledger\n"
      + "- `derived/` values computed from source records only: asset_status_derived (state, location, days outstanding, loss status, deposit held), deposit_account_derived\n"
      + "- `ground_truth/` ground_truth.csv/json (injected anomalies) and asset_labels.csv (expected outcomes). Never an input feature; no data table references it.\n"
      + "- `sql/` schema.sql (PK, FK, CHECK) and seed.sql (insert order respects FKs)\n- `docs/` data_dictionary.md, lifecycle_rules.json, validation_summary.md/json, source_manifest.json, generator_command.txt\n- `checksums.sha256` SHA-256 of every file\n\n"
      + "## IDs\n\nASSET-0000001, SHP-2026-000001, RMA-2026-000001, REP-2026-000001, CUST-<asset-id>-000001 (per-asset sequence, gap-free). Supporting IDs: AT-0001, OWN-0001, LOC-0001, ACT-0001, INS-2026-000001, LED-2026-000001, GT-000001. IDs are zero-padded counters assigned once and never changed; they are assigned in generation order, not strictly by timestamp.\n\n"
      + "## Process\n\n1. Master data: asset types (lifecycle rules), owners, locations, actors, assets with COMMISSION events.\n"
      + "2. A day-by-day sweep dispatches batches from warehouse pools. Shipments are paired OUTBOUND/RETURN trips plus warehouse TRANSFERs; assets are drawn from the pool of the origin warehouse, so every event has valid parents and a continuous location chain.\n"
      + "3. Each loan: DISPATCH, DELIVERY_RECEIPT, RMA, RETURN_PICKUP, RETURN_RECEIPT, intake inspection, then release to pool, repair (REPAIR_SEND, REPAIR_RECEIPT, post-repair inspection) or SCRAP. Lost assets get MARK_LOST. Deposit ledger rows are append-only.\n"
      + "4. Outcomes follow the rules in `docs/lifecycle_rules.json` (inputs are carrier on-time rate and damage factor, site risk tier and dwell, type fragility, theft risk, loan length); noise is a latent term, outcomes are never assigned independently.\n"
      + "5. After the valid baseline, five labelled anomalies are injected (SIMULTANEOUS_LOCATIONS, RETURN_BEFORE_DISPATCH, DOUBLE_REFUND, REPAIR_WITHOUT_INSPECTION, MISSING_ACTOR), each recorded in the ground truth. Only the targeted rows change.\n"
      + "6. The validator recomputes everything from source records (state replay, locations, days outstanding, deposit balances) and compares with the ground truth.\n\n"
      + "## Cardinalities\n\nasset_type 1:N reusable_asset; owner 1:N location/actor/asset; reusable_asset 1:N custody_event, inspection, repair, return_authorization; shipment N:M reusable_asset through shipment_asset; RETURN shipment 0..1:1 paired OUTBOUND; custody_event 1:0..1 inspection; inspection 1:0..1 repair; deposit account (asset, dispatch shipment) 1:N ledger rows. Optional FKs: custody_event.shipment_id/rma_id/actor_id, repair.inspection_id, shipment.paired_shipment_id.\n\n"
      + "## Lifecycle states\n\nUNCOMMISSIONED, AT_WAREHOUSE, IN_TRANSIT_OUT, WITH_CUSTOMER, IN_TRANSIT_RETURN, IN_TRANSIT_TRANSFER, IN_REPAIR, LOST (terminal), SCRAPPED (terminal). Effective dates: asset_type.effective_from/to, asset.commissioned_date, owner/location/actor active_from.\n\n"
      + "## Reading the data\n\nLocation after an event is `to_location_id` on arrival events and unknown (in transit) otherwise. Days outstanding = floor((end - last DISPATCH)/86400), where end is the return receipt or the as-of date. The as-of date is a parameter; all timestamps are earlier than it, and later planned steps are shown as PLANNED/IN_TRANSIT.\n\n"
      + "## Provenance\n\nFully synthetic. No external dataset, no personal data. See `docs/source_manifest.json`.\n";
  }
}
