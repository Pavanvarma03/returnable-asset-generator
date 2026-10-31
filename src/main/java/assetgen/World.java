package assetgen;

import java.util.*;
import static assetgen.Config.*;

/** Master data and the day-by-day simulation of dispatch, return, inspection, repair and deposit events. */
public final class World {
  final Random rng = new Random(SEED); final double scale;
  boolean failed; long residual, xferEv, preFinal; int inj;
  final Schema schema = new Schema();
  final Table tType = schema.tType, tOwn = schema.tOwn, tLoc = schema.tLoc, tAct = schema.tAct, tAsset = schema.tAsset, tShip = schema.tShip,
      tSA = schema.tSA, tRma = schema.tRma, tEvt = schema.tEvt, tIns = schema.tIns, tRep = schema.tRep, tLed = schema.tLed, tGT = schema.tGT;
  final List<String> ownIds = new ArrayList<>();
  final List<Integer> sup = new ArrayList<>(), car = new ArrayList<>(), wop = new ArrayList<>(), ven = new ArrayList<>(), cus = new ArrayList<>();
  int netOp; final double[] ownOnTime = new double[1000], ownDmg = new double[1000];
  final Map<String, List<String>> actBy = new HashMap<>(); final Map<String, String> actRole = new HashMap<>();
  int nW, nC, nR, nP, nT, locN, actN, ledN, insN, repN, rmaN;
  String[] locW, locC, locR; int[] ownW, ownC, ownR; double[] wLat, wLon, cLat, cLon, cDwell, cRisk;
  String[] assetId; int[] aType, aOwn, evCnt, repCnt, delayedCnt, dispCnt; Object[][] lastEvt;
  String[] tState, trueLoc; boolean[] everDmg; long[] lastDisp;
  // pairs / transfers master
  int[] pDay, pCust, pWh, pCar1, pCar2, pOrd, tDay, tW1, tW2, tCar, tOrd;
  Object[][] shipRows; int[] shipCnt;
  int[][] pool; int[] psz; int[] relHead; IntList relA = new IntList(), relW = new IntList(), relNext = new IntList();
  final List<Object[]> rcptRows = new ArrayList<>(), refundRows = new ArrayList<>(), closedRepairs = new ArrayList<>(); final LongList rcptDisp = new LongList();
  double meanBatch; int lineNoSeq;
  long stDelayed, stLostNonRet;

  World(double scale) { this.scale = scale; }

  String oid(int i) { return ownIds.get(i); }
  String act(int owner, String role) { List<String> l = actBy.get(owner + "|" + role); return l.get(rng.nextInt(l.size())); }
  int addOwner(String type, String name, Double ot, Double df) {
    int i = ownIds.size(); String id = id("OWN-", i + 1, 4); ownIds.add(id);
    if (ot != null) ownOnTime[i] = ot; if (df != null) ownDmg[i] = df;
    tOwn.add(id, name, type, "US", ot, df, D2025); return i;
  }
  void addActors(int o, String role, int n, String label) {
    for (int k = 0; k < n; k++) {
      String id = id("ACT-", ++actN, 4);
      tAct.add(id, oid(o), role, label + " " + role + " " + (k + 1), D2025);
      actBy.computeIfAbsent(o + "|" + role, x -> new ArrayList<>()).add(id); actRole.put(id, role);
    }
  }
  String addLoc(String name, String type, int owner, int city, double jitLat, double jitLon, String risk, Integer dwell) {
    String id = id("LOC-", ++locN, 4);
    tLoc.add(id, name, type, oid(owner), CITY[city], "US", CLAT[city] + jitLat, CLON[city] + jitLon, risk, dwell, D2025); return id;
  }

  // ---- custody event / ledger / inspection helpers ----
  Object[] ev(int a, String type, long ts, String fromLoc, String toLoc, String ship, String rma, String fromOwn, String toOwn,
              String actor, String cond, String dr, String ds, Long dres) {
    String ns = TRANS.get(tState[a] + "|" + type);
    if (ns == null) throw new IllegalStateException("generator bad transition " + assetId[a] + " " + tState[a] + " " + type);
    tState[a] = ns; trueLoc[a] = ARRIVAL.contains(type) ? toLoc : null; if (type.equals("DISPATCH")) lastDisp[a] = ts;
    int seq = ++evCnt[a]; String eid = "CUST-" + assetId[a] + "-" + pad(seq, 6);
    String role = actor == null ? null : actRole.get(actor);
    long rec = ts + 30 + rng.nextInt(3600);
    int q = rng.nextInt(100); String src = q < 85 ? "WMS_SCAN" : (q < 95 ? "TMS_FEED" : "MANUAL_ENTRY");
    Object[] r = tEvt.add(eid, assetId[a], seq, type, ts, fromLoc, toLoc, ship, rma, fromOwn, toOwn, actor, role, cond,
        dr != null, dr, dr == null ? "NONE" : ds, dres, rec, src);
    lastEvt[a] = r; return r;
  }
  Object[] led(int a, String ship, String rma, String depositor, String srcEvt, String insId, long ts, String type, String reason, long cents) {
    Object[] r = tLed.add(yid("LED", ++ledN), assetId[a], ship, rma, depositor, srcEvt, insId, ts, type, reason, cents, "USD");
    if (type.equals("REFUND")) refundRows.add(r); return r;
  }
  Object[] ins(int a, String evId, String rma, String stage, long ts, String inspector, String loc, String res, String sev, double score) {
    return tIns.add(yid("INS", ++insN), assetId[a], evId, rma, stage, ts, inspector, loc, res, sev, score);
  }
  void release(int a, int w, long ts) {
    int d = (int) ((ts - BASE) / DAY) + 1; if (d >= D) return;
    int e = relA.n; relA.add(a); relW.add(w); relNext.add(relHead[d]); relHead[d] = e;
  }
  long delayH(int carOwner) {
    return rng.nextDouble() < ownOnTime[carOwner] ? rng.nextInt(7) - 3 : 6 + (long) (-StrictMath.log(1 - rng.nextDouble()) * 48);
  }
  int sampleDay(int lo) {
    while (true) { int d = lo + rng.nextInt(D - lo); boolean we = ((d + 3) % 7) >= 5; if (!we || rng.nextDouble() > 0.75) return d; }
  }

  // ---- master data ----
  void buildMasters() {
    for (int t = 0; t < T_CODE.length; t++)
      tType.add(id("AT-", t + 1, 4), T_CODE[t], T_NAME[t], T_CAT[t], (long) T_DEP[t], T_LOAN[t], thr(t), T_REP[t], T_FRAG[t], T_THEFT[t], D2025, null);
    nW = Math.max(2, Math.min(20, NA / 1000)); nC = Math.max(4, Math.min(120, NA / 170)); nR = Math.max(2, Math.min(6, NA / 3000));
    netOp = addOwner("NETWORK_OPERATOR", "Network Operations", null, null);
    for (int i = 0; i < 6; i++) sup.add(addOwner("SUPPLIER", "Supplier " + pad(i + 1, 2), null, null));
    for (int i = 0; i < 5; i++) car.add(addOwner("CARRIER", "Carrier " + pad(i + 1, 2), 0.80 + 0.04 * i, 0.08 - 0.0175 * i));
    int nWop = Math.min(8, nW);
    for (int i = 0; i < nWop; i++) wop.add(addOwner("WAREHOUSE_OPERATOR", "Warehouse Operator " + pad(i + 1, 2), null, null));
    for (int i = 0; i < nR; i++) ven.add(addOwner("REPAIR_VENDOR", "Repair Vendor " + pad(i + 1, 2), null, null));
    int nCus = Math.max(2, Math.min(40, nC / 3));
    for (int i = 0; i < nCus; i++) cus.add(addOwner("CUSTOMER", "Customer " + pad(i + 1, 2), null, null));
    // actors
    addActors(netOp, "PLANNER", 2, "Network"); addActors(netOp, "LOGISTICS_ANALYST", 2, "Network"); addActors(netOp, "OPERATIONS_MANAGER", 2, "Network");
    for (int s : sup) addActors(s, "SUPPLIER", 2, oid(s));
    for (int c : car) addActors(c, "CARRIER", 3, oid(c));
    for (int w : wop) { addActors(w, "WAREHOUSE_OPERATOR", 3, oid(w)); addActors(w, "PLANNER", 1, oid(w)); }
    // locations
    locW = new String[nW]; ownW = new int[nW]; wLat = new double[nW]; wLon = new double[nW];
    for (int w = 0; w < nW; w++) {
      int city = w % CITY.length; double ja = (rng.nextDouble() - 0.5) * 0.3, jo = (rng.nextDouble() - 0.5) * 0.3; ownW[w] = wop.get(w % nWop);
      locW[w] = addLoc("Warehouse " + CITY[city] + " " + (w + 1), "WAREHOUSE", ownW[w], city, ja, jo, null, null);
      wLat[w] = CLAT[city] + ja; wLon[w] = CLON[city] + jo;
    }
    locC = new String[nC]; ownC = new int[nC]; cLat = new double[nC]; cLon = new double[nC]; cDwell = new double[nC]; cRisk = new double[nC];
    for (int c = 0; c < nC; c++) {
      int city = rng.nextInt(CITY.length); double ja = (rng.nextDouble() - 0.5) * 0.6, jo = (rng.nextDouble() - 0.5) * 0.6;
      int q = rng.nextInt(100); String tier = q < 60 ? "LOW" : (q < 90 ? "MEDIUM" : "HIGH"); cRisk[c] = q < 60 ? 0.3 : (q < 90 ? 0.6 : 1.0);
      int dw = 4 + rng.nextInt(18); cDwell[c] = dw; ownC[c] = cus.get(c % nCus);
      locC[c] = addLoc("Customer Site " + CITY[city] + " " + (c + 1), "CUSTOMER_SITE", ownC[c], city, ja, jo, tier, dw);
      cLat[c] = CLAT[city] + ja; cLon[c] = CLON[city] + jo;
    }
    locR = new String[nR]; ownR = new int[nR];
    for (int r = 0; r < nR; r++) { int city = rng.nextInt(CITY.length); ownR[r] = ven.get(r); locR[r] = addLoc("Repair Shop " + CITY[city] + " " + (r + 1), "REPAIR_SHOP", ownR[r], city, 0, 0, null, null); }
  }

  void run() {
    inj = Math.max(1, NA / 500);
    buildMasters();
    nT = Math.max(1, NS / 10); if ((NS - nT) % 2 != 0) nT++; nP = (NS - nT) / 2;
    meanBatch = Math.max(1.0, ((NE - NA - 0.06 * NE) / 4.05) / Math.max(1, nP));
    assetId = new String[NA]; aType = new int[NA]; aOwn = new int[NA]; evCnt = new int[NA]; repCnt = new int[NA];
    delayedCnt = new int[NA]; dispCnt = new int[NA]; lastEvt = new Object[NA][]; tState = new String[NA]; trueLoc = new String[NA];
    everDmg = new boolean[NA]; lastDisp = new long[NA]; Arrays.fill(tState, "UNCOMMISSIONED"); Arrays.fill(lastDisp, -1);
    pool = new int[nW][NA]; psz = new int[nW]; relHead = new int[D + 2]; Arrays.fill(relHead, -1);
    shipRows = new Object[NS][]; shipCnt = new int[NS];
    // assets + commission events
    int wsum = 0; for (int x : T_W) wsum += x;
    for (int a = 0; a < NA; a++) {
      int q = rng.nextInt(wsum), t = 0; while (q >= T_W[t]) { q -= T_W[t]; t++; }
      aType[a] = t; aOwn[a] = sup.get(rng.nextInt(sup.size())); assetId[a] = id("ASSET-", a + 1, 7);
      int wi = a % nW; int cd = rng.nextInt(100) < 60 ? rng.nextInt(10) : rng.nextInt(60);
      long ts = BASE + cd * DAY + (7 + rng.nextInt(10)) * 3600L + rng.nextInt(3600);
      tAsset.add(assetId[a], id("AT-", t + 1, 4), oid(aOwn[a]), "SN" + pad(a * 7919L % 99999989L + 1, 8), locW[wi],
          Math.floorDiv(ts, DAY) - 5 - rng.nextInt(55), Math.floorDiv(ts, DAY));
      ev(a, "COMMISSION", ts, null, locW[wi], null, null, null, oid(ownW[wi]), act(ownW[wi], "WAREHOUSE_OPERATOR"), "NA", null, null, null);
      release(a, wi, ts);
    }
    // pair + transfer master (sorted by day with counting sort)
    pDay = new int[nP]; pCust = new int[nP]; pWh = new int[nP]; pCar1 = new int[nP]; pCar2 = new int[nP];
    for (int p = 0; p < nP; p++) {
      pDay[p] = sampleDay(3); pCust[p] = (int) (nC * StrictMath.pow(rng.nextDouble(), 1.5)); pWh[p] = rng.nextInt(nW);
      pCar1[p] = car.get(rng.nextInt(car.size())); pCar2[p] = car.get(rng.nextInt(car.size()));
    }
    pOrd = sortByDay(pDay, nP);
    tDay = new int[nT]; tW1 = new int[nT]; tW2 = new int[nT]; tCar = new int[nT];
    for (int t = 0; t < nT; t++) {
      tDay[t] = sampleDay(5); tW1[t] = rng.nextInt(nW); tW2[t] = (tW1[t] + 1 + rng.nextInt(nW - 1)) % nW; tCar[t] = car.get(rng.nextInt(car.size()));
    }
    tDay[nT - 1] = D - 1; tOrd = sortByDay(tDay, nT);
    // time sweep
    int pp = 0, tp = 0;
    for (int day = 0; day < D && !failed; day++) {
      for (int e = relHead[day]; e >= 0; e = relNext.a[e]) { int w = relW.a[e]; pool[w][psz[w]++] = relA.a[e]; }
      while (pp < nP && pDay[pOrd[pp]] == day) { pair(pp, pOrd[pp], day); pp++; }
      while (tp < nT && tDay[tOrd[tp]] == day) { xfer(tp, tOrd[tp], day, tOrd[tp] == nT - 1); tp++; if (failed) break; }
    }
    if (!failed && (pp < nP || tp < nT)) failed = true;
    if (failed) return;
    for (int i = 0; i < NS; i++) {
      Object[] r = shipRows[i]; r[S_CNT] = shipCnt[i];
      r[S_DELAY] = r[S_ACTDEL] == null ? null : (Object) (int) (((Long) r[S_ACTDEL] - (Long) r[S_EXP]) / 3600);
      tShip.add(r);
    }
  }

  void shipRow(int idx, String type, String paired, String orig, String dest, String carr, String shipper, String recv,
               long plan, Long act, long exp, Long actDel, String status) {
    shipRows[idx] = new Object[]{yid("SHP", idx + 1), type, paired, orig, dest, carr, shipper, recv, act(netOp, "PLANNER"), plan, act, exp, actDel, status, null, null};
  }
  void addSA(int idx, int a, String status) { shipCnt[idx]++; tSA.add(yid("SHP", idx + 1), assetId[a], status, shipCnt[idx]); }

  void pair(int k, int p, int day) {
    int io = 2 * k, ir = 2 * k + 1; String outId = yid("SHP", io + 1), retId = yid("SHP", ir + 1);
    int ci = pCust[p], wi = pWh[p], c1 = pCar1[p], c2 = pCar2[p];
    int size = (int) Math.max(1, Math.round(StrictMath.exp(StrictMath.log(meanBatch * scale) - 0.125 + 0.5 * rng.nextGaussian())));
    int tries = 0; while (psz[wi] == 0 && tries < nW) { wi = (wi + 1) % nW; tries++; }
    int n = Math.min(size, psz[wi]);
    long outPlan = BASE + day * DAY + (8 + rng.nextInt(10)) * 3600L;
    double d1 = km(wLat[wi], wLon[wi], cLat[ci], cLon[ci]); long td = Math.max(1, (long) Math.ceil(d1 / 700.0));
    long outAct = outPlan + rng.nextInt(4) * 3600L, expDel = outPlan + td * DAY;
    long actDel = Math.max(outAct + 6 * 3600L, expDel + delayH(c1) * 3600L);
    double dwell = Math.min(150, Math.max(1, StrictMath.exp(StrictMath.log(cDwell[ci]) + 0.45 * rng.nextGaussian())));
    long retPlan = Math.floorDiv(actDel + (long) (dwell * DAY) - BASE, DAY) * DAY + BASE + (8 + rng.nextInt(10)) * 3600L;
    int rd = rng.nextDouble() < 0.85 ? wi : rng.nextInt(nW);
    long retAct = retPlan + rng.nextInt(4) * 3600L;
    double d2 = km(cLat[ci], cLon[ci], wLat[rd], wLon[rd]); long td2 = Math.max(1, (long) Math.ceil(d2 / 700.0));
    long retExp = retPlan + td2 * DAY, retArr = Math.max(retAct + 6 * 3600L, retExp + delayH(c2) * 3600L);
    String Wid = locW[wi], Cid = locC[ci];
    if (n == 0) {
      shipRow(io, "OUTBOUND", null, Wid, Cid, oid(c1), oid(ownW[wi]), oid(ownC[ci]), outPlan, null, expDel, null, "CANCELLED");
      shipRow(ir, "RETURN", outId, Cid, locW[rd], oid(c2), oid(ownC[ci]), oid(ownW[rd]), retPlan, null, retExp, null, "CANCELLED");
      return;
    }
    int retCount = 0;
    for (int j = 0; j < n; j++) {
      int idx = rng.nextInt(psz[wi]); int a = pool[wi][idx]; pool[wi][idx] = pool[wi][--psz[wi]];
      int ty = aType[a]; long dep = T_DEP[ty], thr = thr(ty) * DAY, loan = T_LOAN[ty] * DAY;
      long dispTs = outAct + j * 20L, delivTs = actDel + j * 30L, recvTs = retArr + j * 30L, pickTs = retAct + j * 20L;
      String custOwn = oid(ownC[ci]);
      String dispEv = (String) ev(a, "DISPATCH", dispTs, Wid, null, outId, null, oid(ownW[wi]), oid(c1), act(ownW[wi], "WAREHOUSE_OPERATOR"), "GOOD", null, null, null)[0];
      led(a, outId, null, custOwn, dispEv, null, dispTs + 60, "CHARGE", "DEPOSIT_ON_DISPATCH", dep);
      boolean delivered = delivTs < ASOF;
      double lossScore = T_THEFT[ty] * (0.5 + cRisk[ci]) + 0.12 * rng.nextGaussian();
      boolean nonRet = lossScore > LOSS_CUT || (recvTs - dispTs) > thr;
      boolean lostEmitted = false;
      addSA(io, a, delivered ? "RECEIVED" : "SHIPPED");
      if (delivered) {
        double tScore = T_FRAG[ty] * (1 + 8 * ownDmg[c1]) + 0.1 * rng.nextGaussian();
        long lateH = (actDel - expDel) / 3600; String dr = null, cond = "GOOD";
        if (tScore > TCUT) { dr = "CONDITION_MISMATCH"; cond = "DAMAGED"; } else if (lateH > 72) dr = "LATE_DELIVERY";
        String ds = "NONE"; Long dres = null;
        if (dr != null) {
          long rt = delivTs + (3 + rng.nextInt(28)) * DAY;
          if (rt < ASOF) { ds = (lateH > 144 || tScore > TCUT + 0.15) ? "RESOLVED_ACCEPTED" : "RESOLVED_REJECTED"; dres = rt; } else ds = "OPEN";
          dispCnt[a]++;
        }
        ev(a, "DELIVERY_RECEIPT", delivTs, Wid, Cid, outId, null, oid(c1), custOwn, act(c1, "CARRIER"), cond, dr, ds, dres);
        long authTs = nonRet ? delivTs + loan : Math.max(delivTs + 3600, retPlan - 2 * DAY);
        String rmaId = null; Object[] rma = null;
        if (authTs < ASOF) {
          rmaId = yid("RMA", ++rmaN);
          rma = tRma.add(rmaId, assetId[a], outId, nonRet ? null : retId, oid(aOwn[a]), nonRet ? "OVERDUE_RECALL" : "END_OF_LOAN", authTs,
              Math.floorDiv(delivTs + loan, DAY), Math.floorDiv(dispTs + thr, DAY), "AUTHORIZED", null);
        }
        if (nonRet) {
          long lostTs = dispTs + thr;
          if (lostTs < ASOF) {
            String le = (String) ev(a, "MARK_LOST", lostTs, Cid, null, null, rmaId, custOwn, null, act(netOp, "LOGISTICS_ANALYST"), "NA", null, null, null)[0];
            lostEmitted = true; stLostNonRet++;
            if (lostTs + 3600 < ASOF) led(a, outId, rmaId, custOwn, le, null, lostTs + 3600, "DEDUCTION", "LOSS_FORFEIT", dep);
            if (rma != null) { rma[M_STATUS] = "EXPIRED"; rma[M_CLOSED] = lostTs; }
          }
        } else {
          if (pickTs < ASOF) {
            ev(a, "RETURN_PICKUP", pickTs, Cid, null, retId, rmaId, custOwn, oid(c2), act(c2, "CARRIER"), "GOOD", null, null, null);
            if (rma != null) rma[M_STATUS] = "IN_TRANSIT";
          }
          if (recvTs < ASOF) {
            long lateH2 = (retArr - retExp) / 3600; String dr2 = lateH2 > 72 ? "LATE_DELIVERY" : null; String ds2 = "NONE"; Long dres2 = null;
            if (dr2 != null) {
              long rt = recvTs + (3 + rng.nextInt(28)) * DAY;
              if (rt < ASOF) { ds2 = lateH2 > 144 ? "RESOLVED_ACCEPTED" : "RESOLVED_REJECTED"; dres2 = rt; } else ds2 = "OPEN";
              dispCnt[a]++;
            }
            Object[] rcv = ev(a, "RETURN_RECEIPT", recvTs, Cid, locW[rd], retId, rmaId, oid(c2), oid(ownW[rd]), act(ownW[rd], "WAREHOUSE_OPERATOR"), "GOOD", dr2, ds2, dres2);
            rcptRows.add(rcv); rcptDisp.add(dispTs);
            if (rma != null) { rma[M_STATUS] = "RECEIVED"; rma[M_CLOSED] = recvTs; }
            if (recvTs - dispTs > loan) { delayedCnt[a]++; stDelayed++; }
            afterReceipt(a, ty, (String) rcv[0], rmaId, recvTs, dispTs, rd, outId, custOwn, c2);
          }
        }
      }
      // return shipment membership
      if (retAct >= ASOF) { if (!lostEmitted) { addSA(ir, a, "PLANNED"); retCount++; } }
      else if (!nonRet) { addSA(ir, a, recvTs < ASOF ? "RECEIVED" : "SHIPPED"); retCount++; }
    }
    shipRow(io, "OUTBOUND", null, Wid, Cid, oid(c1), oid(ownW[wi]), oid(ownC[ci]), outPlan, outAct, expDel, actDel < ASOF ? actDel : null, actDel < ASOF ? "DELIVERED" : "IN_TRANSIT");
    if (retCount == 0) shipRow(ir, "RETURN", outId, Cid, locW[rd], oid(c2), oid(ownC[ci]), oid(ownW[rd]), retPlan, null, retExp, null, "CANCELLED");
    else if (retAct >= ASOF) shipRow(ir, "RETURN", outId, Cid, locW[rd], oid(c2), oid(ownC[ci]), oid(ownW[rd]), retPlan, null, retExp, null, "PLANNED");
    else shipRow(ir, "RETURN", outId, Cid, locW[rd], oid(c2), oid(ownC[ci]), oid(ownW[rd]), retPlan, retAct, retExp, retArr < ASOF ? retArr : null, retArr < ASOF ? "DELIVERED" : "IN_TRANSIT");
  }

  void afterReceipt(int a, int ty, String rcvId, String rmaId, long recvTs, long dispTs, int rd, String outId, String custOwn, int c2) {
    long loan = T_LOAN[ty] * DAY, dep = T_DEP[ty];
    double ratio = Math.min(2.0, (double) (recvTs - dispTs) / loan);
    double score = Math.max(0, T_FRAG[ty] * (0.6 + 0.4 * ratio) + 0.6 * ownDmg[c2] + 0.08 * rng.nextGaussian());
    long insTs = recvTs + (2 + rng.nextInt(38)) * 3600L;
    if (insTs >= ASOF) return;
    String res, sev;
    if (score < C_MINOR) { res = "PASS"; sev = "NONE"; }
    else if (score < C_MAJOR) { res = "FAIL_REPAIRABLE"; sev = "MINOR"; }
    else if (score < C_IRREP) { res = "FAIL_REPAIRABLE"; sev = "MAJOR"; }
    else { res = "FAIL_UNREPAIRABLE"; sev = "IRREPARABLE"; }
    String wh = locW[rd]; int who = ownW[rd];
    String insId = (String) ins(a, rcvId, rmaId, "RETURN_INTAKE", insTs, act(who, "WAREHOUSE_OPERATOR"), wh, res, sev, score)[0];
    if (!res.equals("PASS")) everDmg[a] = true;
    long lateDays = Math.max(0, (recvTs - dispTs) / DAY - T_LOAN[ty]);
    long lateFee = Math.min(dep, Math.min(dep / 2, dep * 2 / 100 * lateDays));
    long dmg = sev.equals("MINOR") ? dep * 15 / 100 : sev.equals("MAJOR") ? dep * 40 / 100 : sev.equals("IRREPARABLE") ? dep : 0;
    dmg = Math.min(dmg, dep - lateFee); long refund = dep - lateFee - dmg;
    long sTs = insTs + DAY;
    if (sTs + 4 < ASOF) {
      if (lateFee > 0) led(a, outId, rmaId, custOwn, rcvId, insId, sTs++, "DEDUCTION", "LATE_FEE", lateFee);
      if (dmg > 0) led(a, outId, rmaId, custOwn, rcvId, insId, sTs++, "DEDUCTION", sev.equals("IRREPARABLE") ? "DAMAGE_FORFEIT" : "DAMAGE_DEDUCTION", dmg);
      if (refund > 0) led(a, outId, rmaId, custOwn, rcvId, insId, sTs++, "REFUND", "RETURN_REFUND", refund);
    }
    if (res.equals("PASS")) { release(a, rd, insTs + 7200); return; }
    if (res.equals("FAIL_UNREPAIRABLE") || repCnt[a] >= T_REP[ty]) {
      long st = insTs + (4 + rng.nextInt(20)) * 3600L; if (st < ASOF) scrap(a, st, rd); return;
    }
    long sendTs = insTs + (2 + rng.nextInt(10)) * 3600L; if (sendTs >= ASOF) return;
    repCnt[a]++;
    int shop = rng.nextInt(nR);
    String sendId = (String) ev(a, "REPAIR_SEND", sendTs, wh, null, null, rmaId, oid(who), oid(ownR[shop]), act(who, "WAREHOUSE_OPERATOR"), "DAMAGED", null, null, null)[0];
    String rt = sev.equals("MINOR") ? "CLEAN_AND_SANITIZE" : (rng.nextBoolean() ? "COMPONENT_REPLACEMENT" : "STRUCTURAL_REPAIR");
    long days = sev.equals("MINOR") ? 3 + rng.nextInt(6) : 8 + rng.nextInt(14);
    long rcvTs = sendTs + days * DAY + rng.nextInt(7200);
    Object[] rep = tRep.add(yid("REP", ++repN), assetId[a], insId, sendId, null, locR[shop], oid(ownR[shop]), rt, sev, sendTs, null, null, "OPEN", "PENDING", null);
    if (rcvTs >= ASOF) return;
    String rcvEv = (String) ev(a, "REPAIR_RECEIPT", rcvTs, locR[shop], wh, null, rmaId, oid(ownR[shop]), oid(who), act(who, "WAREHOUSE_OPERATOR"), "GOOD", null, null, null)[0];
    rep[R_RCV] = rcvEv; rep[R_CLOSE] = rcvTs; rep[R_COST] = (sev.equals("MINOR") ? dep * 10 / 100 + 200 : dep * 30 / 100 + 500); rep[R_STATUS] = "CLOSED";
    closedRepairs.add(rep);
    long pTs = rcvTs + (4 + rng.nextInt(20)) * 3600L; if (pTs >= ASOF) return;
    double ps = Math.max(0, 0.45 * score + 0.12 * rng.nextGaussian()); boolean ok = ps < POST_CUT;
    String pid = (String) ins(a, rcvEv, rmaId, "POST_REPAIR", pTs, act(who, "WAREHOUSE_OPERATOR"), wh, ok ? "PASS" : "FAIL_UNREPAIRABLE", ok ? "NONE" : "IRREPARABLE", ps)[0];
    rep[R_POST] = pid;
    if (ok) { rep[R_OUT] = "RESTORED"; release(a, rd, pTs + 7200); }
    else { rep[R_OUT] = "SCRAPPED"; everDmg[a] = true; long st = pTs + 6 * 3600L; if (st < ASOF) scrap(a, st, rd); }
  }
  void scrap(int a, long ts, int w) {
    ev(a, "SCRAP", ts, locW[w], null, null, null, oid(ownW[w]), null, act(netOp, "OPERATIONS_MANAGER"), "NA", null, null, null);
  }

  void xfer(int pos, int t, int day, boolean fin) {
    int idx = 2 * nP + pos; String id = yid("SHP", idx + 1);
    int w1 = tW1[t], w2 = tW2[t], cr = tCar[t], q; long plan;
    if (fin) {
      preFinal = tEvt.rows.size(); residual = NE - inj - preFinal;
      w1 = 0; for (int w = 1; w < nW; w++) if (psz[w] > psz[w1]) w1 = w; if (w2 == w1) w2 = (w1 + 1) % nW;
      if (residual < 0 || residual > psz[w1]) { failed = true; return; }
      q = (int) residual; plan = BASE + (D - 1) * DAY + 12 * 3600L;
    } else { q = Math.min(3, psz[w1]); plan = BASE + day * DAY + (8 + rng.nextInt(10)) * 3600L; }
    long before = tEvt.rows.size();
    long actShip = plan + (fin ? 0 : rng.nextInt(3) * 3600L);
    long td = Math.max(1, (long) Math.ceil(km(wLat[w1], wLon[w1], wLat[w2], wLon[w2]) / 700.0)); if (fin) td = Math.max(td, 2);
    long exp = plan + td * DAY, actDel = Math.max(actShip + 6 * 3600L, exp + (fin ? 0 : delayH(cr) * 3600L));
    if (q == 0) { shipRow(idx, "TRANSFER", null, locW[w1], locW[w2], oid(cr), oid(ownW[w1]), oid(ownW[w2]), plan, null, exp, null, "CANCELLED"); return; }
    for (int j = 0; j < q; j++) {
      int pi = rng.nextInt(psz[w1]); int a = pool[w1][pi]; pool[w1][pi] = pool[w1][--psz[w1]];
      long dTs = actShip + j * 20L, rTs = actDel + j * 30L;
      ev(a, "TRANSFER_DISPATCH", dTs, locW[w1], null, id, null, oid(ownW[w1]), oid(cr), act(ownW[w1], "WAREHOUSE_OPERATOR"), "GOOD", null, null, null);
      boolean got = rTs < ASOF;
      if (got) { ev(a, "TRANSFER_RECEIPT", rTs, locW[w1], locW[w2], id, null, oid(cr), oid(ownW[w2]), act(ownW[w2], "WAREHOUSE_OPERATOR"), "GOOD", null, null, null); release(a, w2, rTs + 1800); }
      addSA(idx, a, got ? "RECEIVED" : "SHIPPED");
    }
    shipRow(idx, "TRANSFER", null, locW[w1], locW[w2], oid(cr), oid(ownW[w1]), oid(ownW[w2]), plan, actShip, exp, actDel < ASOF ? actDel : null, actDel < ASOF ? "DELIVERED" : "IN_TRANSIT");
    if (!fin) xferEv += tEvt.rows.size() - before;
  }
}
