# Returnable assets: synthetic custody, return and deposit data

Coordinates return merchandise, reusable packaging, pallets and equipment across locations and custody transitions.

## Run

```
./build.sh
./run.sh --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --as-of 2026-10-30 --out out
```

Starter set: `./run.sh --assets 2000 --shipments 1000 --events 10000 --out starter`. Same generator, same rules, only the scale changes. Java 17+, no dependencies. `--sql false` skips SQL.

## Source layout (`src/main/java/assetgen`)

| File | Role |
|---|---|
| `Main` | Entry point: parse options, calibrate volume, inject anomalies, validate, export |
| `Cli` | Command-line options |
| `Config` | Run parameters, business-rule constants, asset-type catalogue, lifecycle state machine |
| `Schema` | Table definitions: fields, types, enums, keys, validation rules |
| `Table`, `Col` | Table and column model; CSV, DDL and INSERT export |
| `World` | Master data and the day-by-day simulation (dispatch, return, inspection, repair, deposits) |
| `AnomalyInjector` | Injects the five labelled edge cases and writes the ground truth |
| `Validator` | Integrity checks, derived values, reconciliation, anomaly detection |
| `Report` | Operations report: overdue and lost assets, deposit exposure, loss and damage rates, carrier and site performance, exception queue |
| `Exporter`, `Output` | Writes data, docs, manifest and checksums |
| `IntList`, `LongList` | Primitive growable arrays |

Complexity: O(A+S+E). Pools are swap-remove arrays, asset release uses day buckets (counting sort), derivation uses counting sort and hash maps. Volume calibration repeats the O(N) simulation a small constant number of times.

## Layout

- `data/` core entities (CSV): asset_type, owner, location, actor (supporting), reusable_asset, shipment, shipment_asset (bridge), return_authorization, custody_event, inspection, repair, deposit_ledger
- `derived/` values computed from source records only: asset_status_derived (state, location, days outstanding, loss status, deposit held), deposit_account_derived
- `reports/` operations_report.md, overdue_assets.csv (worst first, with recommended action), exceptions_queue.csv. Computed from the data, never from the ground truth.
- `ground_truth/` ground_truth.csv/json (injected anomalies) and asset_labels.csv (expected outcomes). Never an input feature; no data table references it.
- `sql/` schema.sql (PK, FK, CHECK) and seed.sql (insert order respects FKs)
- `docs/` data_dictionary.md, lifecycle_rules.json, validation_summary.md/json, source_manifest.json, generator_command.txt
- `checksums.sha256` SHA-256 of every file

## IDs

ASSET-0000001, SHP-2026-000001, RMA-2026-000001, REP-2026-000001, CUST-<asset-id>-000001 (per-asset sequence, gap-free). Supporting IDs: AT-0001, OWN-0001, LOC-0001, ACT-0001, INS-2026-000001, LED-2026-000001, GT-000001. IDs are zero-padded counters assigned once and never changed; they are assigned in generation order, not strictly by timestamp.

## Process

1. Master data: asset types (lifecycle rules), owners, locations, actors, assets with COMMISSION events.
2. A day-by-day sweep dispatches batches from warehouse pools. Shipments are paired OUTBOUND/RETURN trips plus warehouse TRANSFERs; assets are drawn from the pool of the origin warehouse, so every event has valid parents and a continuous location chain.
3. Each loan: DISPATCH, DELIVERY_RECEIPT, RMA, RETURN_PICKUP, RETURN_RECEIPT, intake inspection, then release to pool, repair (REPAIR_SEND, REPAIR_RECEIPT, post-repair inspection) or SCRAP. Lost assets get MARK_LOST. Deposit ledger rows are append-only.
4. Outcomes follow the rules in `docs/lifecycle_rules.json` (inputs are carrier on-time rate and damage factor, site risk tier and dwell, type fragility, theft risk, loan length); noise is a latent term, outcomes are never assigned independently.
5. After the valid baseline, five labelled anomalies are injected (SIMULTANEOUS_LOCATIONS, RETURN_BEFORE_DISPATCH, DOUBLE_REFUND, REPAIR_WITHOUT_INSPECTION, MISSING_ACTOR), each recorded in the ground truth. Only the targeted rows change.
6. The validator recomputes everything from source records (state replay, locations, days outstanding, deposit balances) and compares with the ground truth.

## Cardinalities

asset_type 1:N reusable_asset; owner 1:N location/actor/asset; reusable_asset 1:N custody_event, inspection, repair, return_authorization; shipment N:M reusable_asset through shipment_asset; RETURN shipment 0..1:1 paired OUTBOUND; custody_event 1:0..1 inspection; inspection 1:0..1 repair; deposit account (asset, dispatch shipment) 1:N ledger rows. Optional FKs: custody_event.shipment_id/rma_id/actor_id, repair.inspection_id, shipment.paired_shipment_id.

## Lifecycle states

UNCOMMISSIONED, AT_WAREHOUSE, IN_TRANSIT_OUT, WITH_CUSTOMER, IN_TRANSIT_RETURN, IN_TRANSIT_TRANSFER, IN_REPAIR, LOST (terminal), SCRAPPED (terminal). Effective dates: asset_type.effective_from/to, asset.commissioned_date, owner/location/actor active_from.

## Reading the data

Location after an event is `to_location_id` on arrival events and unknown (in transit) otherwise. Days outstanding = floor((end - last DISPATCH)/86400), where end is the return receipt or the as-of date. The as-of date is a parameter; all timestamps are earlier than it, and later planned steps are shown as PLANNED/IN_TRANSIT.

## Provenance

Fully synthetic. No external dataset, no personal data. See `docs/source_manifest.json`.

## Repository contents

- `src/main/java/assetgen/` is the generator source.
- `build.sh`, `run.sh` build and run it.
- `sample_output/` is the starter dataset and its report (2,000 assets, 1,000 shipments, 10,000 events).
- `full_run_docs/` holds the validation summary, data dictionary, lifecycle rules and checksums for the full 20,000 / 10,000 / 100,000 run.
