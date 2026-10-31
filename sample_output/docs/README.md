# Returnable asset data

Test data for tracking returnable assets (pallets, crates, roll cages, kegs, scanners) between warehouses, customers and repair shops.

## How to run

    javac -d build src/main/java/assetgen/*.java
    java -cp build assetgen.Main --seed 20261022 --assets 20000 --shipments 10000 --events 100000 --out out

Use --assets 2000 --shipments 1000 --events 10000 for the small set. Use --sql false to skip the SQL files.

## Folders

- data: the 12 tables as CSV
- derived: asset status and deposit balances worked out from the events
- ground_truth: the injected errors and the expected result for each asset (not used by anything else)
- reports: operations report, overdue and lost asset list, exception list
- sql: schema.sql and seed.sql
- docs: data dictionary, lifecycle rules, validation summary, manifest
- checksums.sha256: SHA-256 of every file

## IDs

ASSET-0000001, SHP-2026-000001, RMA-2026-000001, REP-2026-000001, CUST-ASSET-0000001-000001 (asset id plus event number).

## How the data is made

1. Asset types, owners, locations, actors and assets are created first.
2. Each day, batches of assets are dispatched from warehouses to customers and later returned. Some are late, damaged, repaired, scrapped or lost.
3. Damage, loss, late fees and refunds follow the rules in docs/lifecycle_rules.json.
4. Five kinds of errors are added afterwards and listed in ground_truth: two locations at once, return before dispatch, double refund, repair without inspection, event without actor.
5. The validator works out each asset's state, location, days outstanding and deposit balance from the events and checks them against the generator.

All data is made up. No outside data is used.
