package assetgen;

import static assetgen.Config.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Report {

    private Report() {}

    static final long ON_TIME_HOURS = 6;
    static final int REMINDER_MAX = 14,
        RECALL_MAX = 39;

    static String money(long c) {
        long a = Math.abs(c);
        return (c < 0 ? "-" : "") + a / 100 + "." + pad(a % 100, 2);
    }

    static String pct(long a, long b) {
        return b == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", (100.0 * a) / b);
    }

    static String oneDecimal(double x) {
        return String.format(Locale.ROOT, "%.1f", x);
    }

    static long pctile(int[] h, long n, double p) {
        long target = (long) Math.ceil(p * n),
            c = 0;
        for (int i = 0; i < h.length; i++) {
            c += h[i];
            if (c >= target) return i;
        }
        return -1;
    }

    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static String action(String status, int daysOverdue) {
        if (status.equals("LOST")) return "CONFIRM_LOSS_AND_FILE_CLAIM";
        if (daysOverdue <= REMINDER_MAX) return "SEND_REMINDER";
        if (daysOverdue <= RECALL_MAX) return "ISSUE_RECALL_RMA";
        return "ESCALATE_BEFORE_LOSS_WRITE_OFF";
    }

    static String exceptionAction(String issue) {
        switch (issue) {
            case "SIMULTANEOUS_LOCATIONS":
                return "Quarantine the asset location and confirm physical position";
            case "RETURN_BEFORE_DISPATCH":
                return "Reject the receipt event and review scan timestamps";
            case "DOUBLE_REFUND":
                return "Block the second payout and reclaim the amount";
            case "REPAIR_WITHOUT_INSPECTION":
                return "Reopen the repair and require an inspection";
            default:
                return "Request actor attribution for the event";
        }
    }

    static void write(Path dir, World w, Validator v) throws IOException {
        Files.createDirectories(dir);
        int nt = T_CODE.length;

        Map<String, Object[]> ship = new HashMap<>();
        for (Object[] r : w.tShip.rows) ship.put((String) r[S_ID], r);

        String[] lastShip = new String[NUM_ASSETS];
        long[] lastD = new long[NUM_ASSETS];
        Arrays.fill(lastD, -1);
        long[] loans = new long[nt],
            rets = new long[nt],
            late = new long[nt],
            lost = new long[nt];
        int[] hist = new int[D + 2];
        long cycleSum = 0,
            cycleN = 0;
        for (Object[] e : w.tEvt.rows) {
            int a = ai((String) e[E_ASSET]),
                t = w.aType[a];
            long ts = (Long) e[E_TS];
            switch ((String) e[E_TYPE]) {
                case "DISPATCH":
                    loans[t]++;
                    lastD[a] = ts;
                    lastShip[a] = (String) e[E_SHIP];
                    break;
                case "RETURN_RECEIPT":
                    if (lastD[a] >= 0 && ts >= lastD[a]) {
                        rets[t]++;
                        long d = (ts - lastD[a]) / DAY;
                        if (d > T_LOAN[t]) late[t]++;
                        cycleSum += d;
                        cycleN++;
                        hist[(int) Math.min(d, D + 1)]++;
                    }
                    break;
                case "MARK_LOST":
                    lost[t]++;
                    break;
                default:
                    break;
            }
        }

        long[] intake = new long[nt],
            fails = new long[nt];
        for (Object[] r : w.tIns.rows) {
            if (!r[I_STAGE].equals("RETURN_INTAKE")) continue;
            int t = w.aType[ai((String) r[I_ASSET])];
            intake[t]++;
            if (!r[I_RES].equals("PASS")) fails[t]++;
        }
        long[] charged = new long[nt],
            refunded = new long[nt],
            deducted = new long[nt],
            forfeited = new long[nt];
        long totCharged = 0,
            totRefunded = 0,
            totDeducted = 0,
            totForfeited = 0;
        Map<String, String> ledAsset = new HashMap<>();
        for (Object[] r : w.tLed.rows) {
            int t = w.aType[ai((String) r[L_ASSET])];
            long amt = (Long) r[L_AMT];
            String et = (String) r[L_TYPE];
            ledAsset.put((String) r[L_ID], (String) r[L_ASSET]);
            if (et.equals("CHARGE")) {
                charged[t] += amt;
                totCharged += amt;
            } else if (et.equals("REFUND")) {
                refunded[t] += amt;
                totRefunded += amt;
            } else {
                deducted[t] += amt;
                totDeducted += amt;
                if (((String) r[L_REASON]).endsWith("FORFEIT")) {
                    forfeited[t] += amt;
                    totForfeited += amt;
                }
            }
        }

        List<Object[]> out = new ArrayList<>();
        int[] keys = new int[v.tDer.rows.size()];
        int m = 0;
        int onLoan = 0,
            overdue = 0,
            lostNow = 0;
        long heldOverdue = 0,
            heldOnLoan = 0,
            heldAll = 0;
        int[] age = new int[5];
        Map<String, int[]> sites = new HashMap<>();
        for (Object[] r : v.tDer.rows) {
            String st = (String) r[12];
            long held = (Long) r[13];
            heldAll += held;
            if (st.equals("ON_LOAN")) {
                onLoan++;
                heldOnLoan += held;
                continue;
            }
            if (!st.equals("OVERDUE") && !st.equals("LOST")) continue;
            int a = ai((String) r[0]),
                t = w.aType[a];
            int days = r[11] == null ? 0 : (Integer) r[11];
            int od = Math.max(0, Math.min(D, days - T_LOAN[t]));
            Object[] sh = lastShip[a] == null ? null : ship.get(lastShip[a]);
            String site = sh == null ? null : (String) sh[S_DEST];
            if (st.equals("LOST")) lostNow++;
            else {
                overdue++;
                heldOverdue += held;
                age[od <= 7 ? 0 : od <= 14 ? 1 : od <= 30 ? 2 : od <= 60 ? 3 : 4]++;
            }
            if (site != null) sites.computeIfAbsent(site, k -> new int[2])[st.equals("LOST") ? 1 : 0]++;
            out.add(new Object[] {
                r[0],
                T_CODE[t],
                st,
                r[2],
                days,
                T_LOAN[t],
                od,
                lastShip[a],
                site,
                sh == null ? null : sh[S_RECV],
                sh == null ? null : sh[S_CAR],
                held,
                action(st, od),
            });
            keys[m++] = od;
        }
        int[] order = sortByDay(keys, m);
        StringBuilder oc = new StringBuilder(
            "asset_id,asset_type_code,status,current_state,days_since_dispatch,max_loan_days,days_overdue,last_dispatch_shipment_id,customer_site_id,customer_owner_id,carrier_owner_id,deposit_held_usd,recommended_action\n"
        );
        for (int i = m - 1; i >= 0; i--) {
            Object[] r = out.get(order[i]);
            oc.append(r[0])
                .append(',')
                .append(r[1])
                .append(',')
                .append(r[2])
                .append(',')
                .append(r[3])
                .append(',')
                .append(r[4])
                .append(',')
                .append(r[5])
                .append(',')
                .append(r[6])
                .append(',')
                .append(orEmpty((String) r[7]))
                .append(',')
                .append(orEmpty((String) r[8]))
                .append(',')
                .append(orEmpty((String) r[9]))
                .append(',')
                .append(orEmpty((String) r[10]))
                .append(',')
                .append(money((Long) r[11]))
                .append(',')
                .append(r[12])
                .append('\n');
        }
        Files.writeString(dir.resolve("overdue_assets.csv"), oc.toString(), StandardCharsets.UTF_8);

        Map<String, String> repAsset = new HashMap<>();
        for (Object[] r : w.tRep.rows) repAsset.put((String) r[R_ID], (String) r[R_ASSET]);
        StringBuilder ec = new StringBuilder(
            "issue_type,entity_type,entity_id,asset_id,recommended_action\n"
        );
        int excTotal = 0;
        StringBuilder exSummary = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : v.det.entrySet()) {
            for (String id : e.getValue()) {
                String asset = id.startsWith("CUST-")
                    ? id.substring(5, 17)
                    : id.startsWith("LED-")
                      ? ledAsset.get(id)
                      : repAsset.get(id);
                String et = id.startsWith("CUST-")
                    ? "custody_event"
                    : id.startsWith("LED-")
                      ? "deposit_ledger"
                      : "repair";
                ec.append(e.getKey())
                    .append(',')
                    .append(et)
                    .append(',')
                    .append(id)
                    .append(',')
                    .append(orEmpty(asset))
                    .append(',')
                    .append(exceptionAction(e.getKey()))
                    .append('\n');
                excTotal++;
            }
            exSummary
                .append("| ")
                .append(e.getKey())
                .append(" | ")
                .append(e.getValue().size())
                .append(" | ")
                .append(exceptionAction(e.getKey()))
                .append(" |\n");
        }
        Files.writeString(dir.resolve("exceptions_queue.csv"), ec.toString(), StandardCharsets.UTF_8);

        Map<String, long[]> car = new TreeMap<>();
        for (Object[] r : w.tShip.rows) {
            if (r[S_ACTDEL] == null) continue;
            long[] x = car.computeIfAbsent((String) r[S_CAR], k -> new long[3]);
            int dh = (Integer) r[S_DELAY];
            x[0]++;
            if (dh <= ON_TIME_HOURS) x[1]++;
            x[2] += dh;
        }

        long totLoans = 0,
            totRets = 0,
            totLate = 0,
            totIntake = 0,
            totFails = 0,
            totLost = 0;
        for (int t = 0; t < nt; t++) {
            totLoans += loans[t];
            totRets += rets[t];
            totLate += late[t];
            totIntake += intake[t];
            totFails += fails[t];
            totLost += lost[t];
        }
        StringBuilder md = new StringBuilder(
            "# Operations report\n\nAs of " +
                ASOF_STR +
                ", seed " +
                SEED +
                ". Computed from custody events, shipments, inspections and the deposit ledger. Ground truth is not used.\n\n"
        );
        md.append("## Headline numbers\n\n| Measure | Value |\n|---|---:|\n")
            .append("| Assets tracked | ")
            .append(NUM_ASSETS)
            .append(" |\n")
            .append("| Loan cycles dispatched | ")
            .append(totLoans)
            .append(" |\n")
            .append("| Assets out on loan, within allowed days | ")
            .append(onLoan)
            .append(" |\n")
            .append("| Assets overdue (past max loan days) | ")
            .append(overdue)
            .append(" |\n")
            .append("| Assets lost | ")
            .append(lostNow)
            .append(" |\n")
            .append("| Loss rate (lost / loan cycles) | ")
            .append(pct(totLost, totLoans))
            .append(" |\n")
            .append("| Late return rate (returns after max loan days) | ")
            .append(pct(totLate, totRets))
            .append(" |\n")
            .append("| Damage rate at intake (failed / inspected) | ")
            .append(pct(totFails, totIntake))
            .append(" |\n")
            .append("| Loan duration, mean / median / 90th percentile (days) | ")
            .append(
                cycleN == 0
                    ? "n/a"
                    : oneDecimal((double) cycleSum / cycleN) +
                          " / " +
                          pctile(hist, cycleN, 0.5) +
                          " / " +
                          pctile(hist, cycleN, 0.9)
            )
            .append(" |\n\n");

        md.append("## Deposit position (USD)\n\n| Measure | Amount |\n|---|---:|\n")
            .append("| Deposits charged | ")
            .append(money(totCharged))
            .append(" |\n")
            .append("| Refunded | ")
            .append(money(totRefunded))
            .append(" |\n")
            .append("| Deducted (late fees, damage, forfeits) | ")
            .append(money(totDeducted))
            .append(" |\n")
            .append("| of which forfeited for loss or irreparable damage | ")
            .append(money(totForfeited))
            .append(" |\n")
            .append("| Still held, assets within loan period | ")
            .append(money(heldOnLoan))
            .append(" |\n")
            .append("| Still held, assets overdue (at risk) | ")
            .append(money(heldOverdue))
            .append(" |\n")
            .append("| Held on other assets (awaiting inspection or settlement), net of duplicate refunds | ")
            .append(money(heldAll - heldOnLoan - heldOverdue))
            .append(" |\n")
            .append("| Net balance (charged - refunded - deducted) | ")
            .append(money(totCharged - totRefunded - totDeducted))
            .append(" |\n\n")
            .append(
                heldAll == totCharged - totRefunded - totDeducted
                    ? "The held amounts add up to the net balance.\n\n"
                    : "WARNING: held amounts do not match the net balance.\n\n"
            );

        md.append("## Overdue ageing\n\n| Days past max loan | Assets |\n|---|---:|\n| 1 to 7 | ")
            .append(age[0])
            .append(" |\n| 8 to 14 | ")
            .append(age[1])
            .append(" |\n| 15 to 30 | ")
            .append(age[2])
            .append(" |\n| 31 to 60 | ")
            .append(age[3])
            .append(" |\n| over 60 | ")
            .append(age[4])
            .append(
                " |\n\nFull list, worst first, with the customer site, carrier and recommended action: `overdue_assets.csv`. Actions: up to 14 days overdue `SEND_REMINDER`; 15 to 39 `ISSUE_RECALL_RMA`; 40 or more `ESCALATE_BEFORE_LOSS_WRITE_OFF`; lost `CONFIRM_LOSS_AND_FILE_CLAIM`.\n\n"
            );

        md.append(
            "## By asset type\n\n| Type | Loans | Returns | Late | Lost | Intake damage | Charged | Forfeited |\n|---|---:|---:|---:|---:|---:|---:|---:|\n"
        );
        for (int t = 0; t < nt; t++) md.append("| ")
            .append(T_CODE[t])
            .append(" | ")
            .append(loans[t])
            .append(" | ")
            .append(rets[t])
            .append(" | ")
            .append(pct(late[t], rets[t]))
            .append(" | ")
            .append(lost[t])
            .append(" | ")
            .append(pct(fails[t], intake[t]))
            .append(" | ")
            .append(money(charged[t]))
            .append(" | ")
            .append(money(forfeited[t]))
            .append(" |\n");

        md.append("\n## Carrier performance\n\nOn time = delivered no more than ")
            .append(ON_TIME_HOURS)
            .append(
                " hours after the promised time.\n\n| Carrier | Delivered shipments | On time | Mean delay (hours) |\n|---|---:|---:|---:|\n"
            );
        for (Map.Entry<String, long[]> e : car.entrySet()) {
            String name = (String) w.tOwn.rows.get(Integer.parseInt(e.getKey().substring(4)) - 1)[1];
            long[] x = e.getValue();
            md.append("| ")
                .append(name)
                .append(" | ")
                .append(x[0])
                .append(" | ")
                .append(pct(x[1], x[0]))
                .append(" | ")
                .append(oneDecimal((double) x[2] / x[0]))
                .append(" |\n");
        }

        md.append(
            "\n## Customer sites holding the most overdue or lost assets\n\n| Site | City | Risk tier | Overdue | Lost |\n|---|---|---|---:|---:|\n"
        );
        Set<String> shown = new HashSet<>();
        for (int k = 0; k < 10; k++) {
            String best = null;
            int[] bc = null;
            for (Map.Entry<String, int[]> e : sites.entrySet()) {
                if (shown.contains(e.getKey())) continue;
                int[] c = e.getValue();
                int tot = c[0] + c[1];
                if (
                    best == null ||
                    tot > bc[0] + bc[1] ||
                    (tot == bc[0] + bc[1] && e.getKey().compareTo(best) < 0)
                ) {
                    best = e.getKey();
                    bc = c;
                }
            }
            if (best == null) break;
            shown.add(best);
            Object[] loc = w.tLoc.rows.get(Integer.parseInt(best.substring(4)) - 1);
            md.append("| ")
                .append(loc[1])
                .append(" | ")
                .append(loc[4])
                .append(" | ")
                .append(orEmpty((String) loc[8]))
                .append(" | ")
                .append(bc[0])
                .append(" | ")
                .append(bc[1])
                .append(" |\n");
        }

        md.append(
            "\n## Exception queue\n\nData problems found by the validation rules (not read from the ground truth). Total "
        )
            .append(excTotal)
            .append(
                ". Full list: `exceptions_queue.csv`.\n\n| Issue | Count | Recommended action |\n|---|---:|---|\n"
            )
            .append(exSummary);
        Files.writeString(dir.resolve("operations_report.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
