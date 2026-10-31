package assetgen;

import static assetgen.Config.*;

import java.util.*;

public final class AnomalyInjector {

    private final World w;
    private int gtN;

    AnomalyInjector(World w) {
        this.w = w;
    }

    void gt(
        String entity,
        String id,
        String asset,
        String issue,
        String related,
        String decision,
        String desc
    ) {
        w.tGT.add(yid("GT", ++gtN).replace("-2026-", "-"), entity, id, asset, issue, related, decision, desc);
    }

    void inject() {
        Set<Object[]> used = Collections.newSetFromMap(new IdentityHashMap<>());
        int got = 0,
            tries = 0;
        while (got < w.inj && tries < w.inj * 400) {
            tries++;
            int a = w.rng.nextInt(NUM_ASSETS);
            Object[] last = w.lastEvt[a];
            if (last == null || used.contains(last) || !ARRIVAL.contains((String) last[E_TYPE])) continue;
            String other = w.locW[w.rng.nextInt(w.nW)];
            if (other.equals(last[E_TO])) continue;
            int seq = ++w.evCnt[a];
            String eid = "CUST-" + w.assetId[a] + "-" + pad(seq, 6);
            Object[] r = w.tEvt.add(
                eid,
                w.assetId[a],
                seq,
                last[E_TYPE],
                last[E_TS],
                last[E_FROM],
                other,
                last[E_SHIP],
                last[E_RMA],
                last[E_FOWN],
                last[E_TOWN],
                last[E_ACT],
                last[E_ROLE],
                last[E_COND],
                false,
                null,
                "NONE",
                null,
                (Long) last[E_REC] + 60,
                "MANUAL_ENTRY"
            );
            used.add(last);
            used.add(r);
            got++;
            gt(
                "custody_event",
                eid,
                w.assetId[a],
                "SIMULTANEOUS_LOCATIONS",
                (String) last[E_ID],
                "FLAG_AND_QUARANTINE_LOCATION",
                "Second arrival event at the same timestamp placing the asset in a different location"
            );
        }
        pick(Math.max(1, w.rcptRows.size() / 300), w.rcptRows.size(), used, w.rcptRows, (r, i) -> {
            long disp = w.rcptDisp.a[i];
            r[E_TS] = disp - 3600L * (1 + w.rng.nextInt(48));
            gt(
                "custody_event",
                (String) r[E_ID],
                (String) r[E_ASSET],
                "RETURN_BEFORE_DISPATCH",
                null,
                "REJECT_EVENT_AND_REVIEW",
                "RETURN_RECEIPT timestamp precedes the DISPATCH of the same loan"
            );
        });
        pick(Math.max(1, w.refundRows.size() / 300), w.refundRows.size(), used, w.refundRows, (r, i) -> {
            Object[] n = w.tLed.add(
                yid("LED", ++w.ledN),
                r[1],
                r[2],
                r[3],
                r[4],
                r[5],
                r[6],
                Math.min(ASOF - 1, (Long) r[L_TS] + 3600L * (24 + w.rng.nextInt(120))),
                "REFUND",
                "RETURN_REFUND",
                r[L_AMT],
                "USD"
            );
            gt(
                "deposit_ledger",
                (String) n[0],
                (String) r[1],
                "DOUBLE_REFUND",
                (String) r[0],
                "BLOCK_PAYOUT_AND_RECLAIM",
                "Second RETURN_REFUND for the same deposit account"
            );
        });
        pick(
            Math.max(1, w.closedRepairs.size() / 60),
            w.closedRepairs.size(),
            used,
            w.closedRepairs,
            (r, i) -> {
                r[R_INS] = null;
                gt(
                    "repair",
                    (String) r[R_ID],
                    (String) r[R_ASSET],
                    "REPAIR_WITHOUT_INSPECTION",
                    null,
                    "REOPEN_REPAIR_REQUIRE_INSPECTION",
                    "Repair closed without a triggering inspection reference"
                );
            }
        );
        pick(Math.max(1, NUM_EVENTS / 500), w.tEvt.rows.size(), used, w.tEvt.rows, (r, i) -> {
            r[E_ACT] = null;
            r[E_ROLE] = null;
            gt(
                "custody_event",
                (String) r[E_ID],
                (String) r[E_ASSET],
                "MISSING_ACTOR",
                null,
                "REQUEST_ACTOR_ATTRIBUTION",
                "Custody event has no actor"
            );
        });

        int[] cnt = new int[NUM_ASSETS + 1];
        for (Object[] r : w.tEvt.rows) cnt[ai((String) r[E_ASSET]) + 1]++;
        for (int i = 0; i < NUM_ASSETS; i++) cnt[i + 1] += cnt[i];
        Object[][] out = new Object[w.tEvt.rows.size()][];
        for (Object[] r : w.tEvt.rows) out[cnt[ai((String) r[E_ASSET])]++] = r;
        w.tEvt.rows = new ArrayList<>(Arrays.asList(out));
    }

    interface Mut {
        void go(Object[] r, int i);
    }

    void pick(int want, int pop, Set<Object[]> used, List<Object[]> src, Mut m) {
        int got = 0,
            tries = 0;
        while (got < want && tries < want * 400 + 1000) {
            tries++;
            int i = w.rng.nextInt(pop);
            Object[] r = src.get(i);
            if (used.contains(r)) continue;
            used.add(r);
            m.go(r, i);
            got++;
        }
    }
}
