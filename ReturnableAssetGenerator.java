import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Synthetic data generator: returnable assets (pallets, reusable packaging, equipment, return containers).
 *
 * Complexity: O(A + S + E) time and memory (A assets, S shipments, E custody events).
 *  - every record is produced in O(1); pools are swap-remove arrays; releases use day buckets (counting sort);
 *  - validation/derivation groups events with a counting sort and hash maps (expected O(1) per lookup).
 *  - the volume calibration repeats the whole O(N) simulation a small constant number of times (<= ~12).
 *
 * Run:  java ReturnableAssetGenerator.java --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --out out
 */
public class ReturnableAssetGenerator {
  // ======================= parameters =======================
  static long SEED = 20261022L;
  static int NA = 20000, NS = 10000, NE = 100000;
  static String OUT = "out", ASOF_STR = "2026-10-30";
  static boolean SQL = true;
  static final long DAY = 86400L, BASE = LocalDate.of(2026, 1, 1).toEpochDay() * DAY;
  static final long D2025 = LocalDate.of(2025, 1, 1).toEpochDay();
  static long ASOF; static int D;

  // documented business-rule parameters
  static double LOSS_CUT = 0.40;   // non-return if theft_risk*(0.5+site_risk)+noise > LOSS_CUT
  static double TCUT = 0.90;       // transit damage dispute cut
  static double C_MINOR = 0.56, C_MAJOR = 0.70, C_IRREP = 0.88; // inspection damage-score bands
  static double POST_CUT = 0.40;   // post-repair re-inspection pass cut

  static String pad(long n, int w) { String s = Long.toString(n); return "0".repeat(Math.max(0, w - s.length())) + s; }
  static String id(String p, long n, int w) { return p + pad(n, w); }
  static String yid(String p, long n) { return p + "-2026-" + pad(n, 6); }
  static int ai(String assetId) { return Integer.parseInt(assetId.substring(6)) - 1; }
  static double km(double la1, double lo1, double la2, double lo2) {
    double r = Math.PI / 180, dl = (la2 - la1) * r, dg = (lo2 - lo1) * r;
    double a = Math.sin(dl / 2) * Math.sin(dl / 2) + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dg / 2) * Math.sin(dg / 2);
    return 12742 * Math.asin(Math.sqrt(a));
  }

  // ======================= catalogue =======================
  static final String[] T_CODE = {"PAL-EUR","PAL-GMA","PAL-PLS","CRT-STD","CRT-FLD","CAG-ROL","TOT-60L","IBC-1K","GAY-BOX","KEG-50L","EQP-SCN","RMC-BIN"};
  static final String[] T_NAME = {"EUR Pallet 1200x800","GMA Pallet 48x40","Plastic Pallet","Stackable Crate","Foldable Crate","Roll Cage","Returnable Tote 60L","IBC 1000L","Gaylord Bulk Box","Keg 50L","RF Scanner","Return Merchandise Bin"};
  static final String[] T_CAT = {"PALLET","PALLET","PALLET","REUSABLE_PACKAGING","REUSABLE_PACKAGING","REUSABLE_PACKAGING","REUSABLE_PACKAGING","REUSABLE_PACKAGING","REUSABLE_PACKAGING","REUSABLE_PACKAGING","EQUIPMENT","RETURN_CONTAINER"};
  static final int[] T_DEP = {1800,1400,3500,2200,3000,8500,900,25000,4000,3000,65000,1200};   // cents
  static final int[] T_LOAN = {30,30,45,21,21,28,14,60,30,45,90,14};
  static final int[] T_REP = {3,3,2,2,2,3,1,3,2,2,2,2};
  static final double[] T_FRAG = {0.42,0.40,0.25,0.30,0.38,0.35,0.28,0.30,0.45,0.18,0.22,0.33};
  static final double[] T_THEFT = {0.10,0.12,0.08,0.10,0.12,0.15,0.18,0.06,0.07,0.22,0.20,0.14};
  static final int[] T_W = {18,16,6,10,6,8,12,3,5,5,3,8};
  static int thr(int t) { return T_LOAN[t] + 60; }

  static final String[] CITY = {"Atlanta","Chicago","Dallas","Los Angeles","Seattle","Denver","Memphis","Columbus","Newark","Miami","Phoenix","Minneapolis","Kansas City","Charlotte","Houston","Salt Lake City","Boston","Nashville","Portland","Indianapolis","Detroit","St. Louis","Reno","Louisville"};
  static final double[] CLAT = {33.749,41.878,32.777,34.052,47.606,39.739,35.150,39.961,40.736,25.762,33.448,44.978,39.100,35.227,29.760,40.761,42.360,36.163,45.515,39.768,42.331,38.627,39.530,38.253};
  static final double[] CLON = {-84.388,-87.630,-96.797,-118.244,-122.332,-104.990,-90.049,-82.999,-74.172,-80.192,-112.074,-93.265,-94.579,-80.843,-95.370,-111.891,-71.059,-86.781,-122.679,-86.158,-83.046,-90.199,-119.814,-85.759};

  // lifecycle state machine (explicit, also exported as lifecycle_rules.json)
  static final Map<String, String> TRANS = new LinkedHashMap<>();
  static void tr(String f, String e, String t) { TRANS.put(f + "|" + e, t); }
  static {
    tr("UNCOMMISSIONED","COMMISSION","AT_WAREHOUSE"); tr("AT_WAREHOUSE","DISPATCH","IN_TRANSIT_OUT");
    tr("IN_TRANSIT_OUT","DELIVERY_RECEIPT","WITH_CUSTOMER"); tr("WITH_CUSTOMER","RETURN_PICKUP","IN_TRANSIT_RETURN");
    tr("IN_TRANSIT_RETURN","RETURN_RECEIPT","AT_WAREHOUSE"); tr("AT_WAREHOUSE","TRANSFER_DISPATCH","IN_TRANSIT_TRANSFER");
    tr("IN_TRANSIT_TRANSFER","TRANSFER_RECEIPT","AT_WAREHOUSE"); tr("AT_WAREHOUSE","REPAIR_SEND","IN_REPAIR");
    tr("IN_REPAIR","REPAIR_RECEIPT","AT_WAREHOUSE"); tr("WITH_CUSTOMER","MARK_LOST","LOST");
    tr("IN_TRANSIT_OUT","MARK_LOST","LOST"); tr("IN_TRANSIT_RETURN","MARK_LOST","LOST"); tr("AT_WAREHOUSE","SCRAP","SCRAPPED");
  }
  static final Set<String> ARRIVAL = Set.of("COMMISSION","DELIVERY_RECEIPT","RETURN_RECEIPT","TRANSFER_RECEIPT","REPAIR_RECEIPT");
  static final Set<String> OUTSTANDING = Set.of("IN_TRANSIT_OUT","WITH_CUSTOMER","IN_TRANSIT_RETURN","LOST");
  static final String E_EVT = "COMMISSION|DISPATCH|DELIVERY_RECEIPT|RETURN_PICKUP|RETURN_RECEIPT|TRANSFER_DISPATCH|TRANSFER_RECEIPT|REPAIR_SEND|REPAIR_RECEIPT|MARK_LOST|SCRAP";
  static final String E_STATE = "UNCOMMISSIONED|AT_WAREHOUSE|IN_TRANSIT_OUT|WITH_CUSTOMER|IN_TRANSIT_RETURN|IN_TRANSIT_TRANSFER|IN_REPAIR|LOST|SCRAPPED";

  // column indexes
  static final int E_ID=0,E_ASSET=1,E_SEQ=2,E_TYPE=3,E_TS=4,E_FROM=5,E_TO=6,E_SHIP=7,E_RMA=8,E_FOWN=9,E_TOWN=10,E_ACT=11,E_ROLE=12,E_COND=13,E_DISP=14,E_DR=15,E_DS=16,E_DRTS=17,E_REC=18,E_SRC=19;
  static final int I_ID=0,I_ASSET=1,I_EVT=2,I_RMA=3,I_STAGE=4,I_TS=5,I_INSP=6,I_LOC=7,I_RES=8,I_SEV=9,I_SCORE=10;
  static final int R_ID=0,R_ASSET=1,R_INS=2,R_SEND=3,R_RCV=4,R_LOC=5,R_VEN=6,R_TYPE=7,R_SEV=8,R_OPEN=9,R_CLOSE=10,R_COST=11,R_STATUS=12,R_OUT=13,R_POST=14;
  static final int L_ID=0,L_ASSET=1,L_SHIP=2,L_RMA=3,L_DEP=4,L_EVT=5,L_INS=6,L_TS=7,L_TYPE=8,L_REASON=9,L_AMT=10,L_CUR=11;
  static final int S_ID=0,S_TYPE=1,S_PAIR=2,S_ORIG=3,S_DEST=4,S_CAR=5,S_SHIPPER=6,S_RECV=7,S_PLANNER=8,S_PLAN=9,S_ACT=10,S_EXP=11,S_ACTDEL=12,S_STATUS=13,S_CNT=14,S_DELAY=15;
  static final int M_ID=0,M_ASSET=1,M_OUT=2,M_RET=3,M_OWN=4,M_REASON=5,M_AUTH=6,M_EXPRET=7,M_EXPIRY=8,M_STATUS=9,M_CLOSED=10;

  // ======================= tables =======================
  static final class Col {
    final String n, t, allowed, unit, rule; final boolean req; String fk = ""; Set<String> set;
    Col(String n, String t, boolean req, String allowed, String unit, String rule) {
      this.n = n; this.t = t; this.req = req; this.allowed = allowed; this.unit = unit; this.rule = rule;
      if (!allowed.isEmpty()) set = new HashSet<>(Arrays.asList(allowed.split("\\|")));
    }
  }
  static final class Table {
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
  static final class IL { int[] a = new int[1024]; int n; void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; } }
  static final class LL { long[] a = new long[1024]; int n; void add(long v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; } }

  static int[] sortByDay(int[] day, int n) {
    int[] c = new int[D + 2];
    for (int i = 0; i < n; i++) c[day[i] + 1]++;
    for (int i = 0; i <= D; i++) c[i + 1] += c[i];
    int[] o = new int[n];
    for (int i = 0; i < n; i++) o[c[day[i]]++] = i;
    return o;
  }

  // ======================= world (generator) =======================
  static final class World {
    final Random rng = new Random(SEED); final double scale;
    boolean failed; long residual, xferEv, preFinal; int inj;
    final Table tType, tOwn, tLoc, tAct, tAsset, tShip, tSA, tRma, tEvt, tIns, tRep, tLed, tGT;
    final List<String> ownIds = new ArrayList<>();
    final List<Integer> sup = new ArrayList<>(), car = new ArrayList<>(), wop = new ArrayList<>(), ven = new ArrayList<>(), cus = new ArrayList<>();
    int netOp; final double[] ownOnTime = new double[1000], ownDmg = new double[1000];
    final Map<String, List<String>> actBy = new HashMap<>(); final Map<String, String> actRole = new HashMap<>();
    int nW, nC, nR, nP, nT, locN, actN, ledN, insN, repN, rmaN, gtN;
    String[] locW, locC, locR; int[] ownW, ownC, ownR; double[] wLat, wLon, cLat, cLon, cDwell, cRisk;
    String[] assetId; int[] aType, aOwn, evCnt, repCnt, delayedCnt, dispCnt; Object[][] lastEvt;
    String[] tState, trueLoc; boolean[] everDmg; long[] lastDisp;
    // pairs / transfers master
    int[] pDay, pCust, pWh, pCar1, pCar2, pOrd, tDay, tW1, tW2, tCar, tOrd;
    Object[][] shipRows; int[] shipCnt;
    int[][] pool; int[] psz; int[] relHead; IL relA = new IL(), relW = new IL(), relNext = new IL();
    final List<Object[]> rcptRows = new ArrayList<>(), refundRows = new ArrayList<>(), closedRepairs = new ArrayList<>(); final LL rcptDisp = new LL();
    double meanBatch; int lineNoSeq;
    long stDelayed, stLostNonRet;

    World(double scale) {
      this.scale = scale;
      tType = new Table("asset_type", "asset_type_id", "Reusable asset type and its lifecycle/deposit rules")
        .rel("asset_type 1:N reusable_asset")
        .c("asset_type_id","STR",true,"","","PK; AT-NNNN; unique")
        .c("type_code","STR",true,"","","unique short code")
        .c("type_name","STR",true,"","","")
        .c("category","ENUM",true,"PALLET|REUSABLE_PACKAGING|EQUIPMENT|RETURN_CONTAINER","","")
        .c("deposit_amount","DEC2",true,"","USD","> 0; charged per dispatch")
        .c("max_loan_days","INT",true,"","days","7..120; overdue after this")
        .c("loss_threshold_days","INT",true,"","days","> max_loan_days; outstanding longer than this => LOST")
        .c("max_repair_cycles","INT",true,"","count","0..5; exceeded => SCRAP")
        .c("fragility_index","DEC4",true,"","0-1","damage propensity input of the inspection rule")
        .c("theft_risk_index","DEC4",true,"","0-1","loss propensity input of the loss rule")
        .c("effective_from","DATE",true,"","","rule effective date")
        .c("effective_to","DATE",false,"","","NULL = still effective; >= effective_from");
      tOwn = new Table("owner", "owner_id", "Organisation that owns assets, operates sites or moves goods")
        .rel("owner 1:N location, 1:N actor, 1:N reusable_asset (legal owner)")
        .c("owner_id","STR",true,"","","PK; OWN-NNNN; unique")
        .c("owner_name","STR",true,"","","synthetic name")
        .c("owner_type","ENUM",true,"NETWORK_OPERATOR|SUPPLIER|CARRIER|WAREHOUSE_OPERATOR|REPAIR_VENDOR|CUSTOMER","","")
        .c("country","STR",true,"","","ISO-3166 alpha-2")
        .c("on_time_rate","DEC4",false,"","0-1","carriers only: share of on-time deliveries")
        .c("damage_factor","DEC4",false,"","0-1","carriers only: handling damage factor")
        .c("active_from","DATE",true,"","","");
      tLoc = new Table("location", "location_id", "Physical site: warehouse, customer site or repair shop")
        .rel("location N:1 owner; location 1:N shipment (origin/destination)")
        .c("location_id","STR",true,"","","PK; LOC-NNNN; unique")
        .c("location_name","STR",true,"","","")
        .c("location_type","ENUM",true,"WAREHOUSE|CUSTOMER_SITE|REPAIR_SHOP","","")
        .c("owner_id","STR",true,"","","FK owner.owner_id").fk("owner_id","owner.owner_id")
        .c("city","STR",true,"","","")
        .c("country","STR",true,"","","")
        .c("latitude","DEC4",true,"","deg","-90..90")
        .c("longitude","DEC4",true,"","deg","-180..180")
        .c("risk_tier","ENUM",false,"LOW|MEDIUM|HIGH","","customer sites only: asset-retention risk")
        .c("typical_dwell_days","INT",false,"","days","customer sites only: usual holding time")
        .c("active_from","DATE",true,"","","");
      tAct = new Table("actor", "actor_id", "Named role-holder performing custody actions (supporting entity)")
        .rel("owner 1:N actor; actor 1:N custody_event")
        .c("actor_id","STR",true,"","","PK; ACT-NNNN; unique")
        .c("owner_id","STR",true,"","","FK owner.owner_id").fk("owner_id","owner.owner_id")
        .c("actor_role","ENUM",true,"SUPPLIER|PLANNER|CARRIER|WAREHOUSE_OPERATOR|LOGISTICS_ANALYST|OPERATIONS_MANAGER","","")
        .c("display_name","STR",true,"","","synthetic label, no personal data")
        .c("active_from","DATE",true,"","","");
      tAsset = new Table("reusable_asset", "asset_id", "Individually tracked reusable asset (static attributes; state is derived from events)")
        .rel("asset_type 1:N reusable_asset; owner 1:N reusable_asset; reusable_asset 1:N custody_event; N:M shipment via shipment_asset")
        .c("asset_id","STR",true,"","","PK; ASSET-NNNNNNN; unique; immutable")
        .c("asset_type_id","STR",true,"","","FK").fk("asset_type_id","asset_type.asset_type_id")
        .c("owner_id","STR",true,"","","FK; legal owner (supplier)").fk("owner_id","owner.owner_id")
        .c("serial_number","STR",true,"","","unique")
        .c("home_location_id","STR",true,"","","FK; warehouse of commissioning").fk("home_location_id","location.location_id")
        .c("manufactured_date","DATE",true,"","","<= commissioned_date")
        .c("commissioned_date","DATE",true,"","","equals date of the COMMISSION custody event");
      tShip = new Table("shipment", "shipment_id", "Dispatch, return or inter-warehouse transfer movement")
        .rel("shipment 1:N shipment_asset N:1 reusable_asset (N:M via bridge); RETURN shipment 0..1:1 paired OUTBOUND shipment")
        .c("shipment_id","STR",true,"","","PK; SHP-2026-NNNNNN; unique")
        .c("shipment_type","ENUM",true,"OUTBOUND|RETURN|TRANSFER","","")
        .c("paired_shipment_id","STR",false,"","","FK shipment (RETURN -> its OUTBOUND)").fk("paired_shipment_id","shipment.shipment_id")
        .c("origin_location_id","STR",true,"","","FK").fk("origin_location_id","location.location_id")
        .c("dest_location_id","STR",true,"","","FK; <> origin").fk("dest_location_id","location.location_id")
        .c("carrier_owner_id","STR",true,"","","FK owner (CARRIER)").fk("carrier_owner_id","owner.owner_id")
        .c("shipper_owner_id","STR",true,"","","FK owner").fk("shipper_owner_id","owner.owner_id")
        .c("receiver_owner_id","STR",true,"","","FK owner").fk("receiver_owner_id","owner.owner_id")
        .c("planner_actor_id","STR",false,"","","FK actor (PLANNER)").fk("planner_actor_id","actor.actor_id")
        .c("planned_ship_ts","TS",true,"","UTC","")
        .c("actual_ship_ts","TS",false,"","UTC",">= planned_ship_ts; NULL if not shipped")
        .c("expected_delivery_ts","TS",true,"","UTC","> planned_ship_ts")
        .c("actual_delivery_ts","TS",false,"","UTC",">= actual_ship_ts; NULL if not delivered")
        .c("status","ENUM",true,"PLANNED|IN_TRANSIT|DELIVERED|CANCELLED","","derived from timestamps vs as-of date and bridge rows")
        .c("asset_count","INT",true,"","count","= number of shipment_asset rows (derived)")
        .c("delay_hours","INT",false,"","hours","= (actual_delivery - expected_delivery)/3600 (derived)");
      tSA = new Table("shipment_asset", "shipment_id,asset_id", "Bridge table: assets carried by a shipment (N:M)")
        .rel("shipment N:M reusable_asset")
        .c("shipment_id","STR",true,"","","FK; part of composite PK").fk("shipment_id","shipment.shipment_id")
        .c("asset_id","STR",true,"","","FK; part of composite PK").fk("asset_id","reusable_asset.asset_id")
        .c("line_status","ENUM",true,"PLANNED|SHIPPED|RECEIVED","","")
        .c("line_no","INT",true,"","","position within shipment, >= 1");
      tRma = new Table("return_authorization", "rma_id", "Authorisation for an asset to come back (one per asset loan)")
        .rel("reusable_asset 1:N return_authorization; return_authorization N:1 outbound shipment; N:1 return shipment (optional)")
        .c("rma_id","STR",true,"","","PK; RMA-2026-NNNNNN; unique")
        .c("asset_id","STR",true,"","","FK").fk("asset_id","reusable_asset.asset_id")
        .c("outbound_shipment_id","STR",true,"","","FK shipment (OUTBOUND)").fk("outbound_shipment_id","shipment.shipment_id")
        .c("return_shipment_id","STR",false,"","","FK shipment (RETURN); NULL for overdue recalls").fk("return_shipment_id","shipment.shipment_id")
        .c("authorizing_owner_id","STR",true,"","","FK owner; asset legal owner").fk("authorizing_owner_id","owner.owner_id")
        .c("reason_code","ENUM",true,"END_OF_LOAN|OVERDUE_RECALL","","")
        .c("authorized_ts","TS",true,"","UTC","after delivery")
        .c("expected_return_date","DATE",true,"","","delivery date + max_loan_days")
        .c("expiry_date","DATE",true,"","","dispatch date + loss_threshold_days")
        .c("status","ENUM",true,"AUTHORIZED|IN_TRANSIT|RECEIVED|EXPIRED","","")
        .c("closed_ts","TS",false,"","UTC","set when RECEIVED or EXPIRED");
      tEvt = new Table("custody_event", "custody_event_id", "Append-only custody/state-change event (history is never overwritten)")
        .rel("reusable_asset 1:N custody_event; shipment 0..1:N custody_event; return_authorization 0..1:N custody_event; actor 0..1:N custody_event")
        .c("custody_event_id","STR",true,"","","PK; CUST-<asset-id>-NNNNNN; unique")
        .c("asset_id","STR",true,"","","FK").fk("asset_id","reusable_asset.asset_id")
        .c("seq_no","INT",true,"","","1..n per asset, gap-free; matches ID suffix")
        .c("event_type","ENUM",true,E_EVT,"","see lifecycle_rules.json")
        .c("event_ts","TS",true,"","UTC","non-decreasing along seq_no")
        .c("from_location_id","STR",false,"","","FK; NULL for COMMISSION").fk("from_location_id","location.location_id")
        .c("to_location_id","STR",false,"","","FK; set on arrival events only (asset location after the event), NULL while in transit/terminal").fk("to_location_id","location.location_id")
        .c("shipment_id","STR",false,"","","FK").fk("shipment_id","shipment.shipment_id")
        .c("rma_id","STR",false,"","","FK").fk("rma_id","return_authorization.rma_id")
        .c("from_owner_id","STR",false,"","","FK custodian before").fk("from_owner_id","owner.owner_id")
        .c("to_owner_id","STR",false,"","","FK custodian after").fk("to_owner_id","owner.owner_id")
        .c("actor_id","STR",false,"","","FK; business rule: must be present (missing => MISSING_ACTOR anomaly)").fk("actor_id","actor.actor_id")
        .c("actor_role","ENUM",false,"SUPPLIER|PLANNER|CARRIER|WAREHOUSE_OPERATOR|LOGISTICS_ANALYST|OPERATIONS_MANAGER","","role of actor")
        .c("condition_reported","ENUM",true,"GOOD|DAMAGED|NA","","")
        .c("dispute_flag","BOOL",true,"","","true iff dispute_reason present")
        .c("dispute_reason","ENUM",false,"LATE_DELIVERY|CONDITION_MISMATCH","","late > 72h, or transit-damage score above cut")
        .c("dispute_status","ENUM",true,"NONE|OPEN|RESOLVED_ACCEPTED|RESOLVED_REJECTED","","")
        .c("dispute_resolved_ts","TS",false,"","UTC","only when RESOLVED_*")
        .c("recorded_ts","TS",true,"","UTC","ingestion time, >= event_ts for baseline rows")
        .c("source_system","ENUM",true,"WMS_SCAN|TMS_FEED|MANUAL_ENTRY","","");
      tIns = new Table("inspection", "inspection_id", "Inspection of a returned or repaired asset")
        .rel("custody_event 1:0..1 inspection; reusable_asset 1:N inspection")
        .c("inspection_id","STR",true,"","","PK; INS-2026-NNNNNN; unique")
        .c("asset_id","STR",true,"","","FK").fk("asset_id","reusable_asset.asset_id")
        .c("custody_event_id","STR",true,"","","FK; RETURN_RECEIPT or REPAIR_RECEIPT event").fk("custody_event_id","custody_event.custody_event_id")
        .c("rma_id","STR",false,"","","FK").fk("rma_id","return_authorization.rma_id")
        .c("inspection_stage","ENUM",true,"RETURN_INTAKE|POST_REPAIR","","")
        .c("inspection_ts","TS",true,"","UTC","> triggering event ts")
        .c("inspector_actor_id","STR",true,"","","FK").fk("inspector_actor_id","actor.actor_id")
        .c("location_id","STR",true,"","","FK").fk("location_id","location.location_id")
        .c("result","ENUM",true,"PASS|FAIL_REPAIRABLE|FAIL_UNREPAIRABLE","","")
        .c("severity","ENUM",true,"NONE|MINOR|MAJOR|IRREPARABLE","","from damage_score bands")
        .c("damage_score","DEC4",true,"","0-1.5","fragility*(0.6+0.4*loan_ratio)+0.6*carrier_damage+noise");
      tRep = new Table("repair", "repair_id", "Repair job on a damaged asset")
        .rel("inspection 1:0..1 repair; reusable_asset 1:N repair")
        .c("repair_id","STR",true,"","","PK; REP-2026-NNNNNN; unique")
        .c("asset_id","STR",true,"","","FK").fk("asset_id","reusable_asset.asset_id")
        .c("inspection_id","STR",false,"","","FK; triggering inspection (rule: required, missing => REPAIR_WITHOUT_INSPECTION)").fk("inspection_id","inspection.inspection_id")
        .c("send_event_id","STR",true,"","","FK REPAIR_SEND event").fk("send_event_id","custody_event.custody_event_id")
        .c("receipt_event_id","STR",false,"","","FK REPAIR_RECEIPT event; NULL while open").fk("receipt_event_id","custody_event.custody_event_id")
        .c("repair_location_id","STR",true,"","","FK").fk("repair_location_id","location.location_id")
        .c("vendor_owner_id","STR",true,"","","FK").fk("vendor_owner_id","owner.owner_id")
        .c("repair_type","ENUM",true,"CLEAN_AND_SANITIZE|COMPONENT_REPLACEMENT|STRUCTURAL_REPAIR","","")
        .c("severity","ENUM",true,"MINOR|MAJOR","","copied from inspection")
        .c("opened_ts","TS",true,"","UTC","")
        .c("closed_ts","TS",false,"","UTC",">= opened_ts")
        .c("cost_amount","DEC2",false,"","USD","set when closed")
        .c("status","ENUM",true,"OPEN|CLOSED","","")
        .c("outcome","ENUM",true,"PENDING|RESTORED|SCRAPPED","","from post-repair inspection")
        .c("post_repair_inspection_id","STR",false,"","","FK").fk("post_repair_inspection_id","inspection.inspection_id");
      tLed = new Table("deposit_ledger", "ledger_id", "Append-only deposit charges, refunds and deductions")
        .rel("deposit account = (asset_id, dispatch_shipment_id); account 1:N ledger rows; balance derived")
        .c("ledger_id","STR",true,"","","PK; LED-2026-NNNNNN; unique")
        .c("asset_id","STR",true,"","","FK").fk("asset_id","reusable_asset.asset_id")
        .c("dispatch_shipment_id","STR",true,"","","FK OUTBOUND shipment (account key)").fk("dispatch_shipment_id","shipment.shipment_id")
        .c("rma_id","STR",false,"","","FK").fk("rma_id","return_authorization.rma_id")
        .c("depositor_owner_id","STR",true,"","","FK customer owner").fk("depositor_owner_id","owner.owner_id")
        .c("source_event_id","STR",false,"","","FK custody event that caused the entry").fk("source_event_id","custody_event.custody_event_id")
        .c("inspection_id","STR",false,"","","FK").fk("inspection_id","inspection.inspection_id")
        .c("entry_ts","TS",true,"","UTC","")
        .c("entry_type","ENUM",true,"CHARGE|REFUND|DEDUCTION","","")
        .c("reason_code","ENUM",true,"DEPOSIT_ON_DISPATCH|RETURN_REFUND|LATE_FEE|DAMAGE_DEDUCTION|DAMAGE_FORFEIT|LOSS_FORFEIT","","")
        .c("amount","DEC2",true,"","USD","> 0; sign is implied by entry_type")
        .c("currency","ENUM",true,"USD","","");
      tGT = new Table("ground_truth", "gt_id", "Labelled injected anomalies (kept out of input data)")
        .c("gt_id","STR",true,"","","GT-NNNNNN")
        .c("entity_type","STR",true,"","","table affected")
        .c("entity_id","STR",true,"","","primary key of the anomalous row")
        .c("asset_id","STR",true,"","","")
        .c("issue_type","ENUM",true,"SIMULTANEOUS_LOCATIONS|RETURN_BEFORE_DISPATCH|DOUBLE_REFUND|REPAIR_WITHOUT_INSPECTION|MISSING_ACTOR","","")
        .c("related_entity_id","STR",false,"","","e.g. the original event / ledger row")
        .c("expected_decision","STR",true,"","","expected pipeline decision")
        .c("description","STR",true,"","","");
    }

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
      return rng.nextDouble() < ownOnTime[carOwner] ? rng.nextInt(7) - 3 : 6 + (long) (-Math.log(1 - rng.nextDouble()) * 48);
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
        pDay[p] = sampleDay(3); pCust[p] = (int) (nC * Math.pow(rng.nextDouble(), 1.5)); pWh[p] = rng.nextInt(nW);
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
      int size = (int) Math.max(1, Math.round(Math.exp(Math.log(meanBatch * scale) - 0.125 + 0.5 * rng.nextGaussian())));
      int tries = 0; while (psz[wi] == 0 && tries < nW) { wi = (wi + 1) % nW; tries++; }
      int n = Math.min(size, psz[wi]);
      long outPlan = BASE + day * DAY + (8 + rng.nextInt(10)) * 3600L;
      double d1 = km(wLat[wi], wLon[wi], cLat[ci], cLon[ci]); long td = Math.max(1, (long) Math.ceil(d1 / 700.0));
      long outAct = outPlan + rng.nextInt(4) * 3600L, expDel = outPlan + td * DAY;
      long actDel = Math.max(outAct + 6 * 3600L, expDel + delayH(c1) * 3600L);
      double dwell = Math.min(150, Math.max(1, Math.exp(Math.log(cDwell[ci]) + 0.45 * rng.nextGaussian())));
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

    // ---- labelled anomaly injection (after valid baseline) ----
    void gt(String entity, String id, String asset, String issue, String related, String decision, String desc) {
      tGT.add(yid("GT", ++gtN).replace("-2026-", "-"), entity, id, asset, issue, related, decision, desc);
    }
    void inject() {
      Set<Object[]> used = Collections.newSetFromMap(new IdentityHashMap<>());
      int got = 0, tries = 0;
      while (got < inj && tries < inj * 400) {
        tries++; int a = rng.nextInt(NA); Object[] last = lastEvt[a];
        if (last == null || used.contains(last) || !ARRIVAL.contains((String) last[E_TYPE])) continue;
        String other = locW[rng.nextInt(nW)]; if (other.equals(last[E_TO])) continue;
        int seq = ++evCnt[a]; String eid = "CUST-" + assetId[a] + "-" + pad(seq, 6);
        Object[] r = tEvt.add(eid, assetId[a], seq, last[E_TYPE], last[E_TS], last[E_FROM], other, last[E_SHIP], last[E_RMA], last[E_FOWN], last[E_TOWN],
            last[E_ACT], last[E_ROLE], last[E_COND], false, null, "NONE", null, (Long) last[E_REC] + 60, "MANUAL_ENTRY");
        used.add(last); used.add(r); got++;
        gt("custody_event", eid, assetId[a], "SIMULTANEOUS_LOCATIONS", (String) last[E_ID], "FLAG_AND_QUARANTINE_LOCATION", "Second arrival event at the same timestamp placing the asset in a different location");
      }
      pick(Math.max(1, rcptRows.size() / 300), rcptRows.size(), used, rcptRows, (r, i) -> {
        long disp = rcptDisp.a[i]; r[E_TS] = disp - 3600L * (1 + rng.nextInt(48));
        gt("custody_event", (String) r[E_ID], (String) r[E_ASSET], "RETURN_BEFORE_DISPATCH", null, "REJECT_EVENT_AND_REVIEW", "RETURN_RECEIPT timestamp precedes the DISPATCH of the same loan");
      });
      pick(Math.max(1, refundRows.size() / 300), refundRows.size(), used, refundRows, (r, i) -> {
        Object[] n = tLed.add(yid("LED", ++ledN), r[1], r[2], r[3], r[4], r[5], r[6], Math.min(ASOF - 1, (Long) r[L_TS] + 3600L * (24 + rng.nextInt(120))), "REFUND", "RETURN_REFUND", r[L_AMT], "USD");
        gt("deposit_ledger", (String) n[0], (String) r[1], "DOUBLE_REFUND", (String) r[0], "BLOCK_PAYOUT_AND_RECLAIM", "Second RETURN_REFUND for the same deposit account");
      });
      pick(Math.max(1, closedRepairs.size() / 60), closedRepairs.size(), used, closedRepairs, (r, i) -> {
        r[R_INS] = null;
        gt("repair", (String) r[R_ID], (String) r[R_ASSET], "REPAIR_WITHOUT_INSPECTION", null, "REOPEN_REPAIR_REQUIRE_INSPECTION", "Repair closed without a triggering inspection reference");
      });
      pick(Math.max(1, NE / 500), tEvt.rows.size(), used, tEvt.rows, (r, i) -> {
        r[E_ACT] = null; r[E_ROLE] = null;
        gt("custody_event", (String) r[E_ID], (String) r[E_ASSET], "MISSING_ACTOR", null, "REQUEST_ACTOR_ATTRIBUTION", "Custody event has no actor");
      });
      // stable regroup of events by asset (rows already seq-ordered per asset)
      int[] cnt = new int[NA + 1]; for (Object[] r : tEvt.rows) cnt[ai((String) r[E_ASSET]) + 1]++;
      for (int i = 0; i < NA; i++) cnt[i + 1] += cnt[i];
      Object[][] out = new Object[tEvt.rows.size()][]; for (Object[] r : tEvt.rows) out[cnt[ai((String) r[E_ASSET])]++] = r;
      tEvt.rows = new ArrayList<>(Arrays.asList(out));
    }
    interface Mut { void go(Object[] r, int i); }
    void pick(int want, int pop, Set<Object[]> used, List<Object[]> src, Mut m) {
      int got = 0, tries = 0;
      while (got < want && tries < want * 400 + 1000) {
        tries++; int i = rng.nextInt(pop); Object[] r = src.get(i); if (used.contains(r)) continue;
        used.add(r); m.go(r, i); got++;
      }
    }
  }

  // ======================= validation + derived outputs =======================
  static final class Validator {
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

  // ======================= output =======================
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

  public static void main(String[] args) throws Exception {
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
    // ---- calibrate batch size so the baseline lands just below the event target, final transfer absorbs the exact remainder ----
    double scale = 1.0; World w = null; int it;
    for (it = 0; it < 40; it++) {
      w = new World(scale); w.run();
      boolean ok = !w.failed && w.residual >= 0 && w.residual <= 0.006 * NE;
      System.out.printf(Locale.ROOT, "calibration %d: scale=%.4f failed=%b residual=%d%n", it, scale, w.failed, w.residual);
      if (ok) break;
      double cust = Math.max(1, w.preFinal - NA - w.xferEv), target = NE - w.inj - 0.003 * NE - NA - w.xferEv;
      if (w.preFinal == 0) { scale *= 0.9; continue; }
      scale *= Math.max(0.5, Math.min(2.0, target / cust));
    }
    if (w.failed || w.residual < 0) throw new IllegalStateException("calibration failed; try larger --events relative to --assets");
    w.inject();
    Validator v = new Validator(w); v.run();

    Path out = Paths.get(OUT); Files.createDirectories(out);
    for (String d : new String[]{"data", "derived", "ground_truth", "sql", "docs"}) Files.createDirectories(out.resolve(d));
    List<Table> core = List.of(w.tType, w.tOwn, w.tLoc, w.tAct, w.tAsset, w.tShip, w.tSA, w.tRma, w.tEvt, w.tIns, w.tRep, w.tLed);
    for (Table t : core) t.csv(out.resolve("data/" + t.name + ".csv"));
    v.tDer.csv(out.resolve("derived/asset_status_derived.csv")); v.tDep.csv(out.resolve("derived/deposit_account_derived.csv"));
    w.tGT.csv(out.resolve("ground_truth/ground_truth.csv")); v.tLab.csv(out.resolve("ground_truth/asset_labels.csv"));
    StringBuilder gj = new StringBuilder("[\n");
    for (int i = 0; i < w.tGT.rows.size(); i++) {
      Object[] r = w.tGT.rows.get(i);
      gj.append("  {\"gt_id\":").append(jstr((String) r[0])).append(",\"entity_type\":").append(jstr((String) r[1])).append(",\"entity_id\":").append(jstr((String) r[2]))
        .append(",\"asset_id\":").append(jstr((String) r[3])).append(",\"issue_type\":").append(jstr((String) r[4])).append(",\"related_entity_id\":").append(jstr((String) r[5]))
        .append(",\"expected_decision\":").append(jstr((String) r[6])).append(",\"description\":").append(jstr((String) r[7])).append("}").append(i < w.tGT.rows.size() - 1 ? ",\n" : "\n");
    }
    Files.writeString(out.resolve("ground_truth/ground_truth.json"), gj.append("]\n").toString());
    StringBuilder lj = new StringBuilder("{\n  \"states\": [");
    String[] sts = Validator.class.getName().isEmpty() ? null : E_STATE.split("\\|"); for (int i = 0; i < sts.length; i++) lj.append(i > 0 ? ", " : "").append(jstr(sts[i]));
    lj.append("],\n  \"transitions\": [\n"); int ti = 0;
    for (Map.Entry<String, String> e : TRANS.entrySet()) { String[] p = e.getKey().split("\\|"); lj.append("    {\"from\":").append(jstr(p[0])).append(",\"event\":").append(jstr(p[1])).append(",\"to\":").append(jstr(e.getValue())).append("}").append(++ti < TRANS.size() ? ",\n" : "\n"); }
    lj.append("  ],\n  \"rules\": {\n")
      .append("    \"loss\": \"asset outstanding (dispatch to receipt, or to as-of date) longer than loss_threshold_days is LOST (MARK_LOST at dispatch+threshold, deposit forfeited)\",\n")
      .append("    \"non_return\": \"theft_risk_index*(0.5+site_risk)+noise > " + LOSS_CUT + " or return would arrive after loss threshold\",\n")
      .append("    \"damage\": \"score=fragility*(0.6+0.4*min(2,days/max_loan))+0.6*carrier_damage_factor+noise; PASS<" + C_MINOR + "; MINOR<" + C_MAJOR + "; MAJOR<" + C_IRREP + "; else IRREPARABLE\",\n")
      .append("    \"repair\": \"FAIL_REPAIRABLE -> REPAIR_SEND unless repairs >= max_repair_cycles (then SCRAP); post-repair score=0.45*score+noise must be < " + POST_CUT + " else SCRAP\",\n")
      .append("    \"deposit\": \"CHARGE at dispatch; at intake inspection: LATE_FEE 2%/day of deposit capped at 50%, DAMAGE_DEDUCTION 15%(MINOR)/40%(MAJOR), DAMAGE_FORFEIT 100%(IRREPARABLE), remainder REFUNDED; LOSS_FORFEIT 100% on MARK_LOST\",\n")
      .append("    \"dispute\": \"delivery late > 72h => LATE_DELIVERY; transit damage score > " + TCUT + " => CONDITION_MISMATCH; resolved after 3-30 days when before as-of date\"\n  }\n}\n");
    Files.writeString(out.resolve("docs/lifecycle_rules.json"), lj.toString());
    if (SQL) {
      try (BufferedWriter sw = Files.newBufferedWriter(out.resolve("sql/schema.sql"))) { for (Table t : core) sw.write(t.ddl()); }
      try (BufferedWriter sw = Files.newBufferedWriter(out.resolve("sql/seed.sql"))) { sw.write("-- load order respects foreign keys\n"); for (Table t : core) t.sql(sw); }
    }
    List<Table> dd = new ArrayList<>(core); dd.add(v.tDer); dd.add(v.tDep); dd.add(w.tGT); dd.add(v.tLab);
    Files.writeString(out.resolve("docs/data_dictionary.md"), dictionary(dd));

    // validation summary
    long fails = 0; StringBuilder vs = new StringBuilder("# Validation summary\n\nSeed " + SEED + ", as-of " + ASOF_STR + ". Each row: check, items examined, violations, detail. PASS = 0 violations.\n\n| Check | Examined | Violations | Result | Detail |\n|---|---:|---:|---|---|\n");
    StringBuilder vj = new StringBuilder("[\n");
    for (int i = 0; i < v.res.size(); i++) {
      Object[] r = v.res.get(i); boolean pass = (Long) r[2] == 0; if (!pass) fails++;
      vs.append("| ").append(r[0]).append(" | ").append(r[1]).append(" | ").append(r[2]).append(" | ").append(pass ? "PASS" : "FAIL").append(" | ").append(r[3]).append(" |\n");
      vj.append("  {\"check\":").append(jstr((String) r[0])).append(",\"examined\":").append(r[1]).append(",\"violations\":").append(r[2]).append(",\"pass\":").append(pass).append(",\"detail\":").append(jstr((String) r[3])).append("}").append(i < v.res.size() - 1 ? ",\n" : "\n");
    }
    vs.append("\n**" + (v.res.size() - fails) + " of " + v.res.size() + " checks pass.**\n\n## Row counts\n\n| Table | Rows |\n|---|---:|\n");
    for (Table t : core) vs.append("| ").append(t.name).append(" | ").append(t.rows.size()).append(" |\n");
    vs.append("| ground_truth (anomalies) | " + w.tGT.rows.size() + " |\n\n## Distribution profile\n\n| Metric | Value |\n|---|---:|\n");
    Map<String, Integer> shipSt = new TreeMap<>(); for (Object[] r : w.tShip.rows) shipSt.merge((String) r[S_STATUS], 1, Integer::sum);
    Map<String, Integer> insRes = new TreeMap<>(); for (Object[] r : w.tIns.rows) if (r[I_STAGE].equals("RETURN_INTAKE")) insRes.merge((String) r[I_RES], 1, Integer::sum);
    Map<String, Integer> fin = new TreeMap<>(); for (int a = 0; a < NA; a++) fin.merge(w.tState[a], 1, Integer::sum);
    Map<String, Integer> rmaSt = new TreeMap<>(); for (Object[] r : w.tRma.rows) rmaSt.merge((String) r[M_STATUS], 1, Integer::sum);
    for (Map.Entry<String, Long> e : v.evTypes.entrySet()) vs.append("| events: " + e.getKey() + " | " + e.getValue() + " |\n");
    for (Map.Entry<String, Integer> e : fin.entrySet()) vs.append("| assets in state " + e.getKey() + " | " + e.getValue() + " |\n");
    for (Map.Entry<String, Integer> e : shipSt.entrySet()) vs.append("| shipments " + e.getKey() + " | " + e.getValue() + " |\n");
    for (Map.Entry<String, Integer> e : insRes.entrySet()) vs.append("| intake inspections " + e.getKey() + " | " + e.getValue() + " |\n");
    for (Map.Entry<String, Integer> e : rmaSt.entrySet()) vs.append("| RMA " + e.getKey() + " | " + e.getValue() + " |\n");
    vs.append("| late returns (cycles) | " + v.profile[0] + " |\n| assets ever damaged | " + v.profile[1] + " |\n| assets lost | " + v.profile[2] + " |\n| assets scrapped | " + v.profile[3] + " |\n| disputed handoff events | " + v.profile[4] + " |\n");
    Files.writeString(out.resolve("docs/validation_summary.md"), vs.toString());
    Files.writeString(out.resolve("docs/validation_summary.json"), vj.append("]\n").toString());
    Files.writeString(out.resolve("docs/generator_command.txt"), "java ReturnableAssetGenerator.java --seed " + SEED + " --assets " + NA + " --shipments " + NS + " --events " + NE + " --as-of " + ASOF_STR + " --out <dir>\n");
    Files.writeString(out.resolve("docs/README.md"), readme(w));

    // manifest + checksums
    List<Path> files = new ArrayList<>(); try (var s = Files.walk(out)) { s.filter(Files::isRegularFile).sorted().forEach(files::add); }
    StringBuilder man = new StringBuilder("{\n  \"generator\": \"ReturnableAssetGenerator.java\",\n  \"seed\": " + SEED + ",\n  \"as_of\": \"" + ASOF_STR + "\",\n  \"external_sources\": [],\n  \"provenance\": \"All data is synthetic and generated from the documented rules; no external or personal data was used. City names and coordinates are public geographic facts used only as realistic anchors; organisation and actor names are fabricated labels.\",\n  \"files\": [\n");
    StringBuilder chk = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      Path f = files.get(i); String rel = out.relativize(f).toString().replace('\\', '/'); String h = sha(f);
      chk.append(h).append("  ").append(rel).append("\n");
      man.append("    {\"path\":").append(jstr(rel)).append(",\"bytes\":").append(Files.size(f)).append(",\"sha256\":\"").append(h).append("\"}").append(i < files.size() - 1 ? ",\n" : "\n");
    }
    Files.writeString(out.resolve("docs/source_manifest.json"), man.append("  ]\n}\n").toString());
    Files.writeString(out.resolve("checksums.sha256"), chk.toString());
    System.out.println("done: " + (v.res.size() - fails) + "/" + v.res.size() + " checks pass; events=" + w.tEvt.rows.size() + " anomalies=" + w.tGT.rows.size());
    for (Object[] r : v.res) if ((Long) r[2] != 0) System.out.println("FAIL " + r[0] + " viol=" + r[2] + " " + r[3]);
  }

  static String readme(World w) {
    return "# Returnable assets: synthetic custody, return and deposit data\n\n"
      + "Coordinates return merchandise, reusable packaging, pallets and equipment across locations and custody transitions.\n\n"
      + "## Run\n\n`java ReturnableAssetGenerator.java --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --as-of 2026-10-30 --out out`\n\n"
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
