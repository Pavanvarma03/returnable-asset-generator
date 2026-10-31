package assetgen;

import static assetgen.Config.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class Output {

    private Output() {}

    static String jstr(String s) {
        return s == null
            ? "null"
            : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static String sha(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String dictionary(List<Table> ts) {
        StringBuilder sb = new StringBuilder(
            "# Data dictionary\n\nTypes: STR text, INT integer, DEC2 money with 2 decimals (USD), DEC4 decimal with 4 places, TS UTC timestamp (ISO-8601), DATE ISO date, BOOL, ENUM closed list. NULLs are written as empty CSV fields. `Required = Y` means NOT NULL. Unique = the field is a primary key (or states `unique`).\n\n"
        );
        for (Table t : ts) {
            sb.append("## ")
                .append(t.name)
                .append("\n\n")
                .append(t.desc)
                .append("\n\n- Primary key: `")
                .append(String.join(", ", t.pk))
                .append("`\n");
            if (!t.rel.isEmpty()) sb.append("- Cardinality / relationships: ").append(t.rel).append("\n");
            sb.append(
                "\n| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |\n|---|---|---|---|---|---|---|\n"
            );
            for (Col c : t.cols) {
                boolean pk = Arrays.asList(t.pk).contains(c.n) && t.pk.length == 1;
                sb.append("| ")
                    .append(c.n)
                    .append(" | ")
                    .append(c.t)
                    .append(" | ")
                    .append(c.req ? "Y" : "N")
                    .append(" | ")
                    .append(c.allowed.replace("|", ", "))
                    .append(" | ")
                    .append(c.unit)
                    .append(" | ")
                    .append(pk || c.rule.contains("unique") ? "Y" : "")
                    .append(" | ")
                    .append(
                        c.rule.isEmpty() && c.fk.isEmpty()
                            ? ""
                            : c.rule + (c.fk.isEmpty() ? "" : " FK -> " + c.fk)
                    )
                    .append(" |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    static String readme(World w) {
        return "# Returnable asset data\n\n"
            + "Test data for tracking returnable assets (pallets, crates, roll cages, kegs, scanners) between warehouses, customers and repair shops.\n\n"
            + "## How to run\n\n"
            + "    javac -d build src/main/java/assetgen/*.java\n"
            + "    java -cp build assetgen.Main --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --out out\n\n"
            + "Use --assets 2000 --shipments 1000 --events 10000 for the small set. Use --sql false to skip the SQL files.\n\n"
            + "## Folders\n\n"
            + "- data: the 12 tables as CSV\n"
            + "- derived: asset status and deposit balances worked out from the events\n"
            + "- ground_truth: the injected errors and the expected result for each asset (not used by anything else)\n"
            + "- reports: operations report, overdue and lost asset list, exception list\n"
            + "- sql: schema.sql and seed.sql\n"
            + "- docs: data dictionary, lifecycle rules, validation summary, manifest\n"
            + "- checksums.sha256: SHA-256 of every file\n\n"
            + "## IDs\n\n"
            + "ASSET-0000001, SHP-2026-000001, RMA-2026-000001, REP-2026-000001, CUST-ASSET-0000001-000001 (asset id plus event number).\n\n"
            + "## How the data is made\n\n"
            + "1. Asset types, owners, locations, actors and assets are created first.\n"
            + "2. Each day, batches of assets are dispatched from warehouses to customers and later returned. Some are late, damaged, repaired, scrapped or lost.\n"
            + "3. Damage, loss, late fees and refunds follow the rules in docs/lifecycle_rules.json.\n"
            + "4. Five kinds of errors are added afterwards and listed in ground_truth: two locations at once, return before dispatch, double refund, repair without inspection, event without actor.\n"
            + "5. The validator works out each asset's state, location, days outstanding and deposit balance from the events and checks them against the generator.\n\n"
            + "All data is made up. No outside data is used.\n";
    }
}
