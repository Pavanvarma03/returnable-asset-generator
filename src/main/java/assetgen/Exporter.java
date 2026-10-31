package assetgen;

import static assetgen.Config.*;
import static assetgen.Output.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Exporter {

    private Exporter() {}

    static void write(World w, Validator v) throws Exception {
        Path out = Paths.get(OUT);
        Files.createDirectories(out);
        for (String d : new String[] { "data", "derived", "ground_truth", "sql", "docs" })
            Files.createDirectories(out.resolve(d));
        List<Table> core = List.of(
            w.tType,
            w.tOwn,
            w.tLoc,
            w.tAct,
            w.tAsset,
            w.tShip,
            w.tSA,
            w.tRma,
            w.tEvt,
            w.tIns,
            w.tRep,
            w.tLed
        );
        for (Table t : core) t.csv(out.resolve("data/" + t.name + ".csv"));
        v.tDer.csv(out.resolve("derived/asset_status_derived.csv"));
        v.tDep.csv(out.resolve("derived/deposit_account_derived.csv"));
        w.tGT.csv(out.resolve("ground_truth/ground_truth.csv"));
        v.tLab.csv(out.resolve("ground_truth/asset_labels.csv"));
        StringBuilder gj = new StringBuilder("[\n");
        for (int i = 0; i < w.tGT.rows.size(); i++) {
            Object[] r = w.tGT.rows.get(i);
            gj.append("  {\"gt_id\":")
                .append(jstr((String) r[0]))
                .append(",\"entity_type\":")
                .append(jstr((String) r[1]))
                .append(",\"entity_id\":")
                .append(jstr((String) r[2]))
                .append(",\"asset_id\":")
                .append(jstr((String) r[3]))
                .append(",\"issue_type\":")
                .append(jstr((String) r[4]))
                .append(",\"related_entity_id\":")
                .append(jstr((String) r[5]))
                .append(",\"expected_decision\":")
                .append(jstr((String) r[6]))
                .append(",\"description\":")
                .append(jstr((String) r[7]))
                .append("}")
                .append(i < w.tGT.rows.size() - 1 ? ",\n" : "\n");
        }
        Files.writeString(out.resolve("ground_truth/ground_truth.json"), gj.append("]\n").toString());
        StringBuilder lj = new StringBuilder("{\n  \"states\": [");
        String[] sts = Validator.class.getName().isEmpty() ? null : E_STATE.split("\\|");
        for (int i = 0; i < sts.length; i++) lj.append(i > 0 ? ", " : "").append(jstr(sts[i]));
        lj.append("],\n  \"transitions\": [\n");
        int ti = 0;
        for (Map.Entry<String, String> e : TRANS.entrySet()) {
            String[] p = e.getKey().split("\\|");
            lj.append("    {\"from\":")
                .append(jstr(p[0]))
                .append(",\"event\":")
                .append(jstr(p[1]))
                .append(",\"to\":")
                .append(jstr(e.getValue()))
                .append("}")
                .append(++ti < TRANS.size() ? ",\n" : "\n");
        }
        lj.append("  ],\n  \"rules\": {\n")
            .append(
                "    \"loss\": \"asset outstanding (dispatch to receipt, or to as-of date) longer than loss_threshold_days is LOST (MARK_LOST at dispatch+threshold, deposit forfeited)\",\n"
            )
            .append(
                "    \"non_return\": \"theft_risk_index*(0.5+site_risk)+noise > " +
                    LOSS_CUT +
                    " or return would arrive after loss threshold\",\n"
            )
            .append(
                "    \"damage\": \"score=fragility*(0.6+0.4*min(2,days/max_loan))+0.6*carrier_damage_factor+noise; PASS<" +
                    C_MINOR +
                    "; MINOR<" +
                    C_MAJOR +
                    "; MAJOR<" +
                    C_IRREP +
                    "; else IRREPARABLE\",\n"
            )
            .append(
                "    \"repair\": \"FAIL_REPAIRABLE -> REPAIR_SEND unless repairs >= max_repair_cycles (then SCRAP); post-repair score=0.45*score+noise must be < " +
                    POST_CUT +
                    " else SCRAP\",\n"
            )
            .append(
                "    \"deposit\": \"CHARGE at dispatch; at intake inspection: LATE_FEE 2%/day of deposit capped at 50%, DAMAGE_DEDUCTION 15%(MINOR)/40%(MAJOR), DAMAGE_FORFEIT 100%(IRREPARABLE), remainder REFUNDED; LOSS_FORFEIT 100% on MARK_LOST\",\n"
            )
            .append(
                "    \"dispute\": \"delivery late > 72h => LATE_DELIVERY; transit damage score > " +
                    TCUT +
                    " => CONDITION_MISMATCH; resolved after 3-30 days when before as-of date\"\n  }\n}\n"
            );
        Files.writeString(out.resolve("docs/lifecycle_rules.json"), lj.toString());
        if (SQL) {
            try (BufferedWriter sw = Files.newBufferedWriter(out.resolve("sql/schema.sql"))) {
                for (Table t : core) sw.write(t.ddl());
            }
            try (BufferedWriter sw = Files.newBufferedWriter(out.resolve("sql/seed.sql"))) {
                sw.write("-- load order respects foreign keys\n");
                for (Table t : core) t.sql(sw);
            }
        }
        List<Table> dd = new ArrayList<>(core);
        dd.add(v.tDer);
        dd.add(v.tDep);
        dd.add(w.tGT);
        dd.add(v.tLab);
        Files.writeString(out.resolve("docs/data_dictionary.md"), dictionary(dd));

        long fails = 0;
        StringBuilder vs = new StringBuilder(
            "# Validation summary\n\nSeed " +
                SEED +
                ", as-of " +
                ASOF_STR +
                ". Each row: check, items examined, violations, detail. PASS = 0 violations.\n\n| Check | Examined | Violations | Result | Detail |\n|---|---:|---:|---|---|\n"
        );
        StringBuilder vj = new StringBuilder("[\n");
        for (int i = 0; i < v.res.size(); i++) {
            Object[] r = v.res.get(i);
            boolean pass = (Long) r[2] == 0;
            if (!pass) fails++;
            vs.append("| ")
                .append(r[0])
                .append(" | ")
                .append(r[1])
                .append(" | ")
                .append(r[2])
                .append(" | ")
                .append(pass ? "PASS" : "FAIL")
                .append(" | ")
                .append(r[3])
                .append(" |\n");
            vj.append("  {\"check\":")
                .append(jstr((String) r[0]))
                .append(",\"examined\":")
                .append(r[1])
                .append(",\"violations\":")
                .append(r[2])
                .append(",\"pass\":")
                .append(pass)
                .append(",\"detail\":")
                .append(jstr((String) r[3]))
                .append("}")
                .append(i < v.res.size() - 1 ? ",\n" : "\n");
        }
        vs.append(
            "\n**" +
                (v.res.size() - fails) +
                " of " +
                v.res.size() +
                " checks pass.**\n\n## Row counts\n\n| Table | Rows |\n|---|---:|\n"
        );
        for (Table t : core)
            vs.append("| ").append(t.name).append(" | ").append(t.rows.size()).append(" |\n");
        vs.append(
            "| ground_truth (anomalies) | " +
                w.tGT.rows.size() +
                " |\n\n## Distribution profile\n\n| Metric | Value |\n|---|---:|\n"
        );
        Map<String, Integer> shipSt = new TreeMap<>();
        for (Object[] r : w.tShip.rows) shipSt.merge((String) r[S_STATUS], 1, Integer::sum);
        Map<String, Integer> insRes = new TreeMap<>();
        for (Object[] r : w.tIns.rows)
            if (r[I_STAGE].equals("RETURN_INTAKE")) insRes.merge((String) r[I_RES], 1, Integer::sum);
        Map<String, Integer> fin = new TreeMap<>();
        for (int a = 0; a < NUM_ASSETS; a++) fin.merge(w.tState[a], 1, Integer::sum);
        Map<String, Integer> rmaSt = new TreeMap<>();
        for (Object[] r : w.tRma.rows) rmaSt.merge((String) r[M_STATUS], 1, Integer::sum);
        for (Map.Entry<String, Long> e : v.evTypes.entrySet())
            vs.append("| events: " + e.getKey() + " | " + e.getValue() + " |\n");
        for (Map.Entry<String, Integer> e : fin.entrySet())
            vs.append("| assets in state " + e.getKey() + " | " + e.getValue() + " |\n");
        for (Map.Entry<String, Integer> e : shipSt.entrySet())
            vs.append("| shipments " + e.getKey() + " | " + e.getValue() + " |\n");
        for (Map.Entry<String, Integer> e : insRes.entrySet())
            vs.append("| intake inspections " + e.getKey() + " | " + e.getValue() + " |\n");
        for (Map.Entry<String, Integer> e : rmaSt.entrySet())
            vs.append("| RMA " + e.getKey() + " | " + e.getValue() + " |\n");
        vs.append(
            "| late returns (cycles) | " +
                v.profile[0] +
                " |\n| assets ever damaged | " +
                v.profile[1] +
                " |\n| assets lost | " +
                v.profile[2] +
                " |\n| assets scrapped | " +
                v.profile[3] +
                " |\n| disputed handoff events | " +
                v.profile[4] +
                " |\n"
        );
        Files.writeString(out.resolve("docs/validation_summary.md"), vs.toString());
        Files.writeString(out.resolve("docs/validation_summary.json"), vj.append("]\n").toString());
        Files.writeString(
            out.resolve("docs/generator_command.txt"),
            "java -cp build assetgen.Main --seed " +
                SEED +
                " --assets " +
                NUM_ASSETS +
                " --shipments " +
                NUM_SHIPMENTS +
                " --events " +
                NUM_EVENTS +
                " --as-of " +
                ASOF_STR +
                " --out <dir>\n"
        );
        Files.writeString(out.resolve("docs/README.md"), readme(w));
        Report.write(out.resolve("reports"), w, v);

        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(out)) {
            s.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        StringBuilder man = new StringBuilder(
            "{\n  \"generator\": \"assetgen (src/main/java/assetgen)\",\n  \"seed\": " +
                SEED +
                ",\n  \"as_of\": \"" +
                ASOF_STR +
                "\",\n  \"external_sources\": [],\n  \"provenance\": \"All data is synthetic and generated from the documented rules; no external or personal data was used. City names and coordinates are public geographic facts used only as realistic anchors; organisation and actor names are fabricated labels.\",\n  \"files\": [\n"
        );
        StringBuilder chk = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            Path f = files.get(i);
            String rel = out.relativize(f).toString().replace('\\', '/');
            String h = sha(f);
            chk.append(h).append("  ").append(rel).append("\n");
            man.append("    {\"path\":")
                .append(jstr(rel))
                .append(",\"bytes\":")
                .append(Files.size(f))
                .append(",\"sha256\":\"")
                .append(h)
                .append("\"}")
                .append(i < files.size() - 1 ? ",\n" : "\n");
        }
        Files.writeString(out.resolve("docs/source_manifest.json"), man.append("  ]\n}\n").toString());
        Files.writeString(out.resolve("checksums.sha256"), chk.toString());
        System.out.println(
            "done: " +
                (v.res.size() - fails) +
                "/" +
                v.res.size() +
                " checks pass; events=" +
                w.tEvt.rows.size() +
                " anomalies=" +
                w.tGT.rows.size()
        );
        for (Object[] r : v.res)
            if ((Long) r[2] != 0) System.out.println("FAIL " + r[0] + " viol=" + r[2] + " " + r[3]);
    }
}
