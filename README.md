# Returnable asset data generator

Java program that creates test data for tracking returnable assets (pallets, crates, roll cages, kegs, scanners) as they move between warehouses, customers and repair shops. It produces 12 related tables, a few labelled errors, and a short operations report.

Default size: 20,000 assets, 10,000 shipments, 100,000 custody events. Seed: 20261022.

## Requirements

Java 17 or newer.

## Build and run

Mac or Linux:

    ./build.sh
    ./run.sh --out out

Windows:

    mkdir build
    javac -d build src\main\java\assetgen\*.java
    java -cp build assetgen.Main --out out

Options: --seed, --assets, --shipments, --events, --as-of (date, default 2026-10-30), --out, --sql false.

Small set:

    ./run.sh --assets 2000 --shipments 1000 --events 10000 --out small

It takes a few seconds and ends with a line like `done: 89/89 checks pass`.

Full dataset zip: https://github.com/Pavanvarma03/returnable-asset-generator/releases

## Output

- data: asset_type, owner, location, actor, reusable_asset, shipment, shipment_asset, return_authorization, custody_event, inspection, repair, deposit_ledger
- derived: asset_status_derived, deposit_account_derived
- ground_truth: ground_truth.csv/json (the injected errors), asset_labels.csv
- reports: operations_report.md, overdue_assets.csv, exceptions_queue.csv
- sql: schema.sql, seed.sql
- docs: data_dictionary.md, lifecycle_rules.json, validation_summary.md/json, source_manifest.json
- checksums.sha256

The same seed and options give the same files. Compare checksums.sha256 with full_run_docs/checksums_full_run.sha256 to check.

## Source files

src/main/java/assetgen:

- Main, Cli: start-up and options
- Config: settings, rule constants, asset types, lifecycle states
- Schema, Table, Col: table definitions and export
- World: creates master data and simulates dispatches, returns, inspections, repairs and deposits
- AnomalyInjector: adds the five errors and writes the ground truth
- Validator: checks keys, joins, dates, deposit totals and lifecycle rules
- Report: overdue and lost assets, deposits, carriers, sites, exceptions
- Exporter, Output: write the files
- IntList, LongList: small int and long arrays

## Notes

- All data is made up.
- Event numbers and IDs follow creation order, not strictly time order.
- The ground_truth folder is only for scoring a detector. The report and validator do not read it.
