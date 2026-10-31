package assetgen;

import java.time.*;
import java.util.*;

/** Run parameters, business-rule constants, catalogue data, lifecycle state machine and column indexes. */
public final class Config {
  private Config() {}

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
    double a = StrictMath.sin(dl / 2) * StrictMath.sin(dl / 2) + StrictMath.cos(la1 * r) * StrictMath.cos(la2 * r) * StrictMath.sin(dg / 2) * StrictMath.sin(dg / 2);
    return 12742 * StrictMath.asin(Math.sqrt(a));
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


  static int[] sortByDay(int[] day, int n) {
    int[] c = new int[D + 2];
    for (int i = 0; i < n; i++) c[day[i] + 1]++;
    for (int i = 0; i <= D; i++) c[i + 1] += c[i];
    int[] o = new int[n];
    for (int i = 0; i < n; i++) o[c[day[i]]++] = i;
    return o;
  }
}
