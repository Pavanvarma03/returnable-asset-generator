package assetgen;

import java.util.*;
import static assetgen.Config.*;

/** Validation, derived values (status, deposit balances) and ground-truth comparison. */
public final class Validator {
  final World w; final List<Object[]> res = new ArrayList<>();
  final Table tDer, tDep, tLab;
  final Map<String, Set<String>> det = new LinkedHashMap<>();
  long[] evTypeCnt;
  Validator(World w) {
    this.w = w;
    tDer = new Table("asset_status_derived", "asset_id", "Per-asset status derived ONLY from custody events, repairs and the ledger")
      .c("asset_id","STR",true,"","","FK reusable_asset")
      .c("asset_type_id","STR",true,"","","")
      .c("current_state","ENUM",true,E_STATE,"","state after replaying events through the lifecycle state machine")
      .c("current_location_id","STR",false,"","","location after the last arrival event; NULL while in transit")
      .c("last_event_id","STR",true,"","","")
      .c("last_event_ts","TS",true,"","UTC","")
      .c("event_count","INT",true,"","count","")
      .c("loan_cycles_completed","INT",true,"","count","number of RETURN_RECEIPT events")
      .c("late_returns","INT",true,"","count","returns after max_loan_days")
      .c("repair_count","INT",true,"","count","REPAIR_SEND events")
      .c("open_cycle_dispatch_ts","TS",false,"","UTC","last DISPATCH while outstanding")
      .c("days_outstanding","INT",false,"","days","floor((as_of - last DISPATCH)/86400) while outstanding or LOST")
      .c("loss_status","ENUM",true,"LOST|OVERDUE|ON_LOAN|NOT_OUTSTANDING","","LOST = MARK_LOST; OVERDUE = outstanding > max_loan_days")
      .c("deposit_held","DEC2",true,"","USD","sum(CHARGE) - sum(REFUND) - sum(DEDUCTION) over the asset's accounts")
      .c("dq_flags","STR",false,"","","pipe-separated anomaly detectors that fired for the asset");
    tDep = new Table("deposit_account_derived", "asset_id,dispatch_shipment_id", "Deposit account balances derived from the ledger")
      .c("asset_id","STR",true,"","","").c("dispatch_shipment_id","STR",true,"","","")
      .c("charged","DEC2",true,"","USD","").c("refunded","DEC2",true,"","USD","").c("deducted","DEC2",true,"","USD","")
      .c("balance","DEC2",true,"","USD","charged - refunded - deducted")
      .c("account_status","ENUM",true,"OPEN|SETTLED|OVER_REFUNDED|UNDER_SETTLED","","");
    tLab = new Table("asset_labels", "asset_id", "Ground-truth expected asset outcomes from the generator's own rule engine")
      .c("asset_id","STR",true,"","","").c("true_final_state","ENUM",true,E_STATE,"","")
      .c("true_current_location_id","STR",false,"","","").c("true_ever_damaged","BOOL",true,"","","")
      .c("true_delayed_returns","INT",true,"","","").c("true_disputed_handoffs","INT",true,"","","")
      .c("true_loss_status","ENUM",true,"LOST|OVERDUE|ON_LOAN|NOT_OUTSTANDING","","").c("true_days_outstanding","INT",false,"","days","");
  }
  void add(String name, long checked, long viol, String detail) { res.add(new Object[]{name, checked, viol, detail}); }
  static String lossStatus(String st, long daysOut, int ty) {
    if (st.equals("LOST")) return "LOST";
    if (OUTSTANDING.contains(st)) return daysOut > T_LOAN[ty] ? "OVERDUE" : "ON_LOAN";
    return "NOT_OUTSTANDING";
  }
  void run() {
    // ---- generic: volumes, PK, FK, enum, required, id formats ----
    add("volume.assets", NA, w.tAsset.rows.size() == NA ? 0 : 1, "rows=" + w.tAsset.rows.size());
    add("volume.shipments", NS, w.tShip.rows.size() == NS ? 0 : 1, "rows=" + w.tShip.rows.size());
    add("volume.custody_events", NE, w.tEvt.rows.size() == NE ? 0 : 1, "rows=" + w.tEvt.rows.size());
    Table[] all = {w.tType, w.tOwn, w.tLoc, w.tAct, w.tAsset, w.tShip, w.tSA, w.tRma, w.tEvt, w.tIns, w.tRep, w.tLed};
    Map<String, Set<String>> pks = new HashMap<>(); Map<String, Table> byName = new HashMap<>();
    for (Table t : all) {
      byName.put(t.name, t); Set<String> s = new HashSet<>(); long dup = 0;
      int[] pi = new int[t.pk.length]; for (int i = 0; i < pi.length; i++) pi[i] = t.i(t.pk[i]);
      for (Object[] r : t.rows) { String k = pi.length == 1 ? (String) r[pi[0]] : r[pi[0]] + "|" + r[pi[1]]; if (!s.add(k)) dup++; }
      pks.put(t.name, s); add("pk_unique." + t.name, t.rows.size(), dup, "");
    }
    for (Table t : all) {
      long fkV = 0, fkN = 0, enV = 0, reqV = 0;
      for (int ci = 0; ci < t.cols.size(); ci++) {
        Col c = t.cols.get(ci); Set<String> target = c.fk.isEmpty() ? null : pks.get(c.fk.split("\\.")[0]);
        for (Object[] r : t.rows) {
          Object v = r[ci];
          if (v == null) { if (c.req) reqV++; continue; }
          if (target != null) { fkN++; if (!target.contains((String) v)) fkV++; }
          if (c.set != null && !c.set.contains(v.toString())) enV++;
        }
      }
      add("fk_integrity." + t.name, fkN, fkV, ""); add("enum_values." + t.name, t.rows.size(), enV, ""); add("required_fields." + t.name, t.rows.size(), reqV, "");
    }
    long idBad = 0;
    for (Object[] r : w.tAsset.rows) if (!((String) r[0]).matches("ASSET-\\d{7}")) idBad++;
    for (Object[] r : w.tShip.rows) if (!((String) r[0]).matches("SHP-2026-\\d{6}")) idBad++;
    for (Object[] r : w.tRma.rows) if (!((String) r[0]).matches("RMA-2026-\\d{6}")) idBad++;
    for (Object[] r : w.tRep.rows) if (!((String) r[0]).matches("REP-2026-\\d{6}")) idBad++;
    for (Object[] r : w.tEvt.rows) { String id = (String) r[E_ID]; if (!id.matches("CUST-ASSET-\\d{7}-\\d{6}") || !id.equals("CUST-" + r[E_ASSET] + "-" + pad((Integer) r[E_SEQ], 6))) idBad++; }
    add("id_format_and_consistency", w.tAsset.rows.size() + w.tShip.rows.size() + w.tRma.rows.size() + w.tRep.rows.size() + w.tEvt.rows.size(), idBad, "ASSET-/SHP-/RMA-/REP-/CUST- patterns, CUST id embeds asset id and seq");
    Set<String> ser = new HashSet<>(); long serDup = 0; for (Object[] r : w.tAsset.rows) if (!ser.add((String) r[3])) serDup++;
    add("duplicate.asset_serial_number", w.tAsset.rows.size(), serDup, "");

    // ---- group events per asset ----
    int n = w.tEvt.rows.size(); int[] start = new int[NA + 1];
    for (Object[] r : w.tEvt.rows) start[ai((String) r[E_ASSET]) + 1]++;
    for (int i = 0; i < NA; i++) start[i + 1] += start[i];
    Object[][] ord = new Object[n][]; long seqBad = 0;
    for (Object[] r : w.tEvt.rows) {
      int a = ai((String) r[E_ASSET]), s = (Integer) r[E_SEQ] - 1;
      if (s < 0 || start[a] + s >= start[a + 1] || ord[start[a] + s] != null) { seqBad++; continue; } ord[start[a] + s] = r;
    }
    add("duplicate_or_gapped.seq_no_per_asset", n, seqBad, "");
    Map<String, Object[]> insById = new HashMap<>(); Map<String, String> insByEvt = new HashMap<>();
    for (Object[] r : w.tIns.rows) { insById.put((String) r[I_ID], r); insByEvt.put((String) r[I_EVT], (String) r[I_RES]); }
    Map<String, Object[]> evById = new HashMap<>(); for (Object[] r : w.tEvt.rows) evById.put((String) r[E_ID], r);
    int[] aTy = new int[NA]; for (Object[] r : w.tAsset.rows) aTy[ai((String) r[0])] = Integer.parseInt(((String) r[1]).substring(3)) - 1;
    Set<String> dTwo = new TreeSet<>(), dRet = new TreeSet<>(), dDbl = new TreeSet<>(), dRep = new TreeSet<>(), dAct = new TreeSet<>();
    String[] flags = new String[NA];
    long trV = 0, contV = 0, ordV = 0, lossV = 0, lateRetV = 0, dmgV = 0, dupSkip = 0; Set<String> trIds = new HashSet<>();
    long[] cyc = new long[NA], late = new long[NA], reps = new long[NA];
    String[] fState = new String[NA], fLoc = new String[NA]; long[] fDisp = new long[NA];
    Map<String, Long> evTypes = new TreeMap<>();
    for (int a = 0; a < NA; a++) {
      String st = "UNCOMMISSIONED", loc = null, prevType = null, prevTo = null, expect = null; long maxTs = Long.MIN_VALUE, prevTs = Long.MIN_VALUE, lastD = -1;
      for (int k = start[a]; k < start[a + 1]; k++) {
        Object[] r = ord[k]; if (r == null) continue;
        String type = (String) r[E_TYPE], eid = (String) r[E_ID]; long ts = (Long) r[E_TS];
        evTypes.merge(type, 1L, Long::sum);
        if (r[E_ACT] == null) { dAct.add(eid); flags[a] = (flags[a] == null ? "" : flags[a] + "|") + "MISSING_ACTOR"; }
        if (prevType != null && ts == prevTs && ARRIVAL.contains(type) && ARRIVAL.contains(prevType) && !Objects.equals(r[E_TO], prevTo)) {
          dTwo.add(eid); flags[a] = (flags[a] == null ? "" : flags[a] + "|") + "SIMULTANEOUS_LOCATIONS"; dupSkip++; continue;
        }
        boolean anomalyRet = type.equals("RETURN_RECEIPT") && lastD >= 0 && ts < lastD;
        if (anomalyRet) { dRet.add(eid); flags[a] = (flags[a] == null ? "" : flags[a] + "|") + "RETURN_BEFORE_DISPATCH"; }
        else { if (ts < maxTs) ordV++; maxTs = Math.max(maxTs, ts); }
        if (!anomalyRet && r[E_REC] != null && (Long) r[E_REC] < ts) ordV++;
        String ns = TRANS.get(st + "|" + type); if (ns == null) trV++; else st = ns;
        boolean arr = ARRIVAL.contains(type);
        if (!arr && !type.equals("COMMISSION")) { if (loc == null || !loc.equals(r[E_FROM])) contV++; }
        if (arr) loc = (String) r[E_TO]; else loc = null;
        if (type.equals("DISPATCH")) lastD = ts;
        int ty = aTy[a];
        if (type.equals("MARK_LOST") && lastD >= 0 && ts - lastD < thr(ty) * DAY) lossV++;
        if (type.equals("RETURN_RECEIPT") && !anomalyRet && lastD >= 0 && ts - lastD > thr(ty) * DAY) lateRetV++;
        if (type.equals("RETURN_RECEIPT")) { cyc[a]++; if (!anomalyRet && ts - lastD > T_LOAN[ty] * DAY) late[a]++; }
        if (type.equals("REPAIR_SEND")) reps[a]++;
        if (expect != null) {
          boolean okNext = expect.equals("PASS") ? (type.equals("DISPATCH") || type.equals("TRANSFER_DISPATCH"))
              : expect.equals("FAIL_REPAIRABLE") ? (type.equals("REPAIR_SEND") || type.equals("SCRAP")) : type.equals("SCRAP");
          if (!okNext) dmgV++;
        }
        expect = insByEvt.get(eid); prevType = type; prevTo = (String) r[E_TO]; prevTs = ts;
      }
      fState[a] = st; fLoc[a] = loc; fDisp[a] = lastD;
    }
    long cont = 0;
    add("lifecycle.state_transitions", n - dupSkip, trV, "every event must be a legal transition (see lifecycle_rules.json)");
    add("lifecycle.location_continuity", n - dupSkip, contV, "departure/terminal event from_location must equal the reconstructed current location");
    add("date_ordering.custody_events", n, ordV, "event_ts non-decreasing along seq_no and recorded_ts >= event_ts (excluding detected RETURN_BEFORE_DISPATCH)");
    add("lifecycle.loss_only_after_threshold", w.tEvt.rows.size(), lossV, "MARK_LOST only when outstanding >= loss_threshold_days");
    add("lifecycle.no_return_after_loss_threshold", w.tEvt.rows.size(), lateRetV, "RETURN_RECEIPT only within loss_threshold_days");
    add("lifecycle.damage_follow_up", w.tIns.rows.size(), dmgV, "PASS -> dispatch/transfer; FAIL_REPAIRABLE -> repair or scrap; FAIL_UNREPAIRABLE -> scrap");

    // ---- shipments / bridge / rma / inspection / repair checks ----
    Map<String, Integer> bridgeCnt = new HashMap<>(); Set<String> member = new HashSet<>();
    for (Object[] r : w.tSA.rows) { bridgeCnt.merge((String) r[0], 1, Integer::sum); member.add(r[0] + "|" + r[1]); }
    long shV = 0, cntV = 0, stV = 0;
    Map<String, String> shType = new HashMap<>();
    for (Object[] r : w.tShip.rows) {
      shType.put((String) r[S_ID], (String) r[S_TYPE]);
      long plan = (Long) r[S_PLAN], exp = (Long) r[S_EXP]; Long act = (Long) r[S_ACT], ad = (Long) r[S_ACTDEL]; String s = (String) r[S_STATUS];
      if (exp <= plan) shV++; if (act != null && act < plan) shV++; if (ad != null && (act == null || ad < act)) shV++;
      if (r[S_ORIG].equals(r[S_DEST])) shV++;
      if (!Integer.valueOf(bridgeCnt.getOrDefault((String) r[S_ID], 0)).equals(r[S_CNT])) cntV++;
      if (r[S_DELAY] != null && ad != null && (Integer) r[S_DELAY] != (int) ((ad - exp) / 3600)) cntV++;
      boolean ok = s.equals("CANCELLED") ? (act == null && ad == null && (Integer) r[S_CNT] == 0)
          : s.equals("PLANNED") ? (act == null && ad == null) : s.equals("IN_TRANSIT") ? (act != null && ad == null) : (ad != null);
      if (!ok) stV++;
    }
    add("date_ordering.shipments", w.tShip.rows.size(), shV, "planned<=actual ship<=actual delivery; expected>planned; origin<>destination");
    add("arithmetic.shipment_asset_count_and_delay", w.tShip.rows.size(), cntV, "asset_count equals bridge rows; delay_hours recomputed");
    add("lifecycle.shipment_status_consistency", w.tShip.rows.size(), stV, "status matches timestamps and bridge rows");
    long evShip = 0, evShipN = 0;
    for (Object[] r : w.tEvt.rows) {
      String sid = (String) r[E_SHIP]; if (sid == null) continue; evShipN++;
      String t = shType.get(sid), e = (String) r[E_TYPE];
      String want = e.equals("DISPATCH") || e.equals("DELIVERY_RECEIPT") ? "OUTBOUND" : e.startsWith("RETURN") ? "RETURN" : "TRANSFER";
      if (!want.equals(t) || !member.contains(sid + "|" + r[E_ASSET])) evShip++;
    }
    add("join.event_shipment_membership", evShipN, evShip, "event shipment type matches event type and asset is on the shipment (bridge)");
    long rmaV = 0;
    for (Object[] r : w.tRma.rows) {
      Long cl = (Long) r[M_CLOSED]; String s = (String) r[M_STATUS];
      if (cl != null && cl < (Long) r[M_AUTH]) rmaV++;
      if ((s.equals("RECEIVED") || s.equals("EXPIRED")) != (cl != null)) rmaV++;
      if ((r[M_REASON].equals("OVERDUE_RECALL")) != (r[M_RET] == null)) rmaV++;
    }
    add("date_ordering.rma_status", w.tRma.rows.size(), rmaV, "closed_ts >= authorized_ts; closed only when RECEIVED/EXPIRED");
    long insV = 0;
    for (Object[] r : w.tIns.rows) {
      Object[] e = evById.get((String) r[I_EVT]);
      if (e == null || !e[E_ASSET].equals(r[I_ASSET]) || (Long) r[I_TS] < (Long) e[E_TS]) insV++;
      else if (!(e[E_TYPE].equals("RETURN_RECEIPT") || e[E_TYPE].equals("REPAIR_RECEIPT"))) insV++;
      double sc = (Double) r[I_SCORE]; String sv = (String) r[I_SEV], rs = (String) r[I_RES];
      if (r[I_STAGE].equals("RETURN_INTAKE")) {
        String want = sc < C_MINOR ? "NONE" : sc < C_MAJOR ? "MINOR" : sc < C_IRREP ? "MAJOR" : "IRREPARABLE"; if (!want.equals(sv)) insV++;
      }
      if ((rs.equals("PASS")) != sv.equals("NONE")) insV++;
    }
    add("rules.inspection_links_and_severity_bands", w.tIns.rows.size(), insV, "inspection after its event; severity recomputed from damage_score bands");
    long repV = 0;
    for (Object[] r : w.tRep.rows) {
      if (r[R_CLOSE] != null && (Long) r[R_CLOSE] < (Long) r[R_OPEN]) repV++;
      if (r[R_STATUS].equals("CLOSED") != (r[R_CLOSE] != null)) repV++;
      if (r[R_INS] == null) { if (r[R_STATUS].equals("CLOSED")) dRep.add((String) r[R_ID]); continue; }
      Object[] i = insById.get((String) r[R_INS]);
      if (i == null || !i[I_ASSET].equals(r[R_ASSET]) || !i[I_RES].equals("FAIL_REPAIRABLE") || !i[I_STAGE].equals("RETURN_INTAKE") || (Long) i[I_TS] > (Long) r[R_OPEN]) repV++;
    }
    add("rules.repair_requires_prior_failed_inspection", w.tRep.rows.size(), repV, "linked inspection: same asset, intake, FAIL_REPAIRABLE, earlier than repair; closed_ts>=opened_ts");

    // ---- deposit ledger reconciliation ----
    Map<String, long[]> acc = new LinkedHashMap<>(); Map<String, Integer> refCnt = new HashMap<>(); long ledV = 0;
    for (Object[] r : w.tLed.rows) {
      String key = r[L_ASSET] + "|" + r[L_SHIP]; long[] x = acc.computeIfAbsent(key, k -> new long[3]); long amt = (Long) r[L_AMT]; String t = (String) r[L_TYPE];
      if (amt <= 0) ledV++;
      if (t.equals("CHARGE")) { x[0] += amt; if (amt != T_DEP[aTy[ai((String) r[L_ASSET])]]) ledV++; }
      else if (t.equals("REFUND")) { x[1] += amt; if (refCnt.merge(key, 1, Integer::sum) == 2) { dDbl.add((String) r[L_ID]); int a = ai((String) r[L_ASSET]); flags[a] = (flags[a] == null ? "" : flags[a] + "|") + "DOUBLE_REFUND"; } }
      else x[2] += amt;
    }
    long unexplained = 0, accs = 0;
    Set<String> dblAcc = new HashSet<>(); for (Map.Entry<String, Integer> e : refCnt.entrySet()) if (e.getValue() > 1) dblAcc.add(e.getKey());
    Map<String, Long> heldByAsset = new HashMap<>();
    for (Map.Entry<String, long[]> e : acc.entrySet()) {
      long[] x = e.getValue(); long bal = x[0] - x[1] - x[2]; boolean settled = x[1] + x[2] > 0; accs++;
      String status = !settled ? "OPEN" : bal == 0 ? "SETTLED" : bal < 0 ? "OVER_REFUNDED" : "UNDER_SETTLED";
      if (x[0] == 0) unexplained++; else if (!status.equals("OPEN") && !status.equals("SETTLED") && !dblAcc.contains(e.getKey())) unexplained++;
      String[] kk = e.getKey().split("\\|"); tDep.add(kk[0], kk[1], x[0], x[1], x[2], bal, status);
      heldByAsset.merge(kk[0], bal, Long::sum);
    }
    add("reconciliation.deposit_accounts", accs, unexplained, "charge == refund + deductions for every settled account; exactly one charge; amount equals type deposit; excess refunds only where DOUBLE_REFUND detected");
    add("arithmetic.ledger_amounts", w.tLed.rows.size(), ledV, "amounts > 0 and charge equals type deposit");

    // ---- derived asset table and comparison with generator truth ----
    long recLoc = 0, recState = 0, recDays = 0, recLoss = 0, recCmp = 0;
    w.tEvt.rows.size();
    for (int a = 0; a < NA; a++) {
      int ty = aTy[a]; String st = fState[a]; Long days = null;
      if (OUTSTANDING.contains(st) && fDisp[a] >= 0) days = (ASOF - fDisp[a]) / DAY;
      String ls = lossStatus(st, days == null ? 0 : days, ty);
      Object[] last = ord[start[a + 1] - 1];
      tDer.add(w.assetId[a], id("AT-", ty + 1, 4), st, fLoc[a], last[E_ID], last[E_TS], start[a + 1] - start[a], (int) cyc[a], (int) late[a], (int) reps[a],
          days != null ? (Object) fDisp[a] : null, days == null ? null : (Object) (int) (long) days, ls, heldByAsset.getOrDefault(w.assetId[a], 0L), flags[a]);
      // truth
      String tst = w.tState[a]; Long tdays = null; if (OUTSTANDING.contains(tst) && w.lastDisp[a] >= 0) tdays = (ASOF - w.lastDisp[a]) / DAY;
      String tls = lossStatus(tst, tdays == null ? 0 : tdays, ty);
      tLab.add(w.assetId[a], tst, w.trueLoc[a], w.everDmg[a], w.delayedCnt[a], w.dispCnt[a], tls, tdays == null ? null : (Object) (int) (long) tdays);
      if (flags[a] == null) {
        recCmp++;
        if (!st.equals(tst)) recState++; if (!Objects.equals(fLoc[a], w.trueLoc[a])) recLoc++;
        if (!Objects.equals(days, tdays)) recDays++; if (!ls.equals(tls)) recLoss++;
      }
    }
    add("reconstruction.current_state_from_events", recCmp, recState, "derived state == generator truth for assets without detected anomalies");
    add("reconstruction.current_location_from_events", recCmp, recLoc, "derived location == generator truth");
    add("reconstruction.days_outstanding", recCmp, recDays, "derived outstanding age == generator truth");
    add("reconstruction.loss_status", recCmp, recLoss, "derived loss status == generator truth");

    // ---- anomaly detectors vs ground truth ----
    Map<String, Set<String>> gt = new LinkedHashMap<>();
    for (Object[] r : w.tGT.rows) gt.computeIfAbsent((String) r[4], k -> new TreeSet<>()).add((String) r[2]);
    det.put("SIMULTANEOUS_LOCATIONS", dTwo); det.put("RETURN_BEFORE_DISPATCH", dRet); det.put("DOUBLE_REFUND", dDbl);
    det.put("REPAIR_WITHOUT_INSPECTION", dRep); det.put("MISSING_ACTOR", dAct);
    for (Map.Entry<String, Set<String>> e : det.entrySet()) {
      Set<String> g = gt.getOrDefault(e.getKey(), new TreeSet<>()); long tp = e.getValue().stream().filter(g::contains).count();
      long fp = e.getValue().size() - tp, fn = g.size() - tp;
      add("anomaly_detection." + e.getKey(), g.size(), fp + fn, "injected=" + g.size() + " detected=" + e.getValue().size() + " TP=" + tp + " FP=" + fp + " FN=" + fn);
    }
    // coverage
    long delayed = 0, damaged = 0, lost = 0, disputes = 0, scrapped = 0, cancelled = 0;
    for (int a = 0; a < NA; a++) { delayed += w.delayedCnt[a]; if (w.everDmg[a]) damaged++; if (w.tState[a].equals("LOST")) lost++; if (w.tState[a].equals("SCRAPPED")) scrapped++; }
    for (Object[] r : w.tEvt.rows) if (Boolean.TRUE.equals(r[E_DISP])) disputes++;
    for (Object[] r : w.tShip.rows) if (r[S_STATUS].equals("CANCELLED")) cancelled++;
    add("coverage.delayed_returns", delayed, delayed > 0 ? 0 : 1, "late returns (> max_loan_days)");
    add("coverage.damaged_assets", damaged, damaged > 0 ? 0 : 1, "assets with a failed inspection");
    add("coverage.lost_assets", lost, lost > 0 ? 0 : 1, "assets in LOST state");
    add("coverage.disputed_handoffs", disputes, disputes > 0 ? 0 : 1, "custody events with dispute_flag");
    add("coverage.scrapped_assets", scrapped, scrapped > 0 ? 0 : 1, "assets in SCRAPPED state");
    add("coverage.cancelled_shipments", cancelled, (cancelled > 0 || NS < 5000) ? 0 : 1, "exception path: cancelled shipments");
    for (int i = 0; i < 5; i++) { String k = new ArrayList<>(det.keySet()).get(i); add("coverage.anomaly_" + k, gt.getOrDefault(k, Set.of()).size(), gt.getOrDefault(k, Set.of()).isEmpty() ? 1 : 0, ""); }
    evTypeCnt = null; this.evTypes = evTypes; this.profile = new long[]{delayed, damaged, lost, scrapped, disputes, cancelled};
  }
  Map<String, Long> evTypes; long[] profile;
}
