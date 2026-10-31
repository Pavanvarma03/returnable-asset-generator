# Operations report

As of 2026-10-30, seed 20261022. Computed from custody events, shipments, inspections and the deposit ledger. Ground truth is not used.

## Headline numbers

| Measure | Value |
|---|---:|
| Assets tracked | 2000 |
| Loan cycles dispatched | 1864 |
| Assets out on loan, within allowed days | 113 |
| Assets overdue (past max loan days) | 12 |
| Assets lost | 39 |
| Loss rate (lost / loan cycles) | 2.1% |
| Late return rate (returns after max loan days) | 17.6% |
| Damage rate at intake (failed / inspected) | 2.0% |
| Loan duration, mean / median / 90th percentile (days) | 17.2 / 16 / 27 |

## Deposit position (USD)

| Measure | Amount |
|---|---:|
| Deposits charged | 92118.00 |
| Refunded | 83060.37 |
| Deducted (late fees, damage, forfeits) | 1724.67 |
| of which forfeited for loss or irreparable damage | 877.00 |
| Still held, assets within loan period | 7135.00 |
| Still held, assets overdue (at risk) | 213.00 |
| Held on other assets (awaiting inspection or settlement), net of duplicate refunds | -15.04 |
| Net balance (charged - refunded - deducted) | 7332.96 |

The held amounts add up to the net balance.

## Overdue ageing

| Days past max loan | Assets |
|---|---:|
| 1 to 7 | 6 |
| 8 to 14 | 1 |
| 15 to 30 | 3 |
| 31 to 60 | 2 |
| over 60 | 0 |

Full list, worst first, with the customer site, carrier and recommended action: `overdue_assets.csv`. Actions: up to 14 days overdue `SEND_REMINDER`; 15 to 39 `ISSUE_RECALL_RMA`; 40 or more `ESCALATE_BEFORE_LOSS_WRITE_OFF`; lost `CONFIRM_LOSS_AND_FILE_CLAIM`.

## By asset type

| Type | Loans | Returns | Late | Lost | Intake damage | Charged | Forfeited |
|---|---:|---:|---:|---:|---:|---:|---:|
| PAL-EUR | 308 | 283 | 6.7% | 6 | 3.2% | 5544.00 | 108.00 |
| PAL-GMA | 316 | 298 | 6.7% | 2 | 2.4% | 4424.00 | 28.00 |
| PAL-PLS | 138 | 126 | 0.0% | 1 | 0.0% | 4830.00 | 35.00 |
| CRT-STD | 204 | 190 | 27.4% | 1 | 0.0% | 4488.00 | 22.00 |
| CRT-FLD | 102 | 92 | 20.7% | 2 | 5.4% | 3060.00 | 60.00 |
| CAG-ROL | 150 | 138 | 6.5% | 2 | 0.0% | 12750.00 | 170.00 |
| TOT-60L | 206 | 179 | 52.0% | 12 | 0.6% | 1854.00 | 108.00 |
| IBC-1K | 58 | 54 | 0.0% | 0 | 0.0% | 14500.00 | 0.00 |
| GAY-BOX | 89 | 80 | 3.8% | 1 | 6.2% | 3560.00 | 40.00 |
| KEG-50L | 94 | 76 | 0.0% | 9 | 0.0% | 2820.00 | 270.00 |
| EQP-SCN | 50 | 44 | 0.0% | 0 | 0.0% | 32500.00 | 0.00 |
| RMC-BIN | 149 | 137 | 61.3% | 3 | 5.1% | 1788.00 | 36.00 |

## Carrier performance

On time = delivered no more than 6 hours after the promised time.

| Carrier | Delivered shipments | On time | Mean delay (hours) |
|---|---:|---:|---:|
| Carrier 01 | 187 | 80.2% | 9.7 |
| Carrier 02 | 190 | 86.8% | 5.7 |
| Carrier 03 | 204 | 91.2% | 3.6 |
| Carrier 04 | 192 | 92.7% | 4.2 |
| Carrier 05 | 185 | 96.2% | 1.4 |

## Customer sites holding the most overdue or lost assets

| Site | City | Risk tier | Overdue | Lost |
|---|---|---|---:|---:|
| Customer Site Portland 1 | Portland | HIGH | 4 | 21 |
| Customer Site Atlanta 10 | Atlanta | MEDIUM | 0 | 6 |
| Customer Site Seattle 3 | Seattle | LOW | 3 | 2 |
| Customer Site Kansas City 9 | Kansas City | HIGH | 0 | 5 |
| Customer Site Kansas City 2 | Kansas City | LOW | 2 | 1 |
| Customer Site Kansas City 6 | Kansas City | MEDIUM | 1 | 1 |
| Customer Site Minneapolis 11 | Minneapolis | LOW | 1 | 1 |
| Customer Site Newark 4 | Newark | LOW | 0 | 1 |
| Customer Site Boston 5 | Boston | LOW | 0 | 1 |
| Customer Site Kansas City 8 | Kansas City | LOW | 1 | 0 |

## Exception queue

Data problems found by the validation rules (not read from the ground truth). Total 35. Full list: `exceptions_queue.csv`.

| Issue | Count | Recommended action |
|---|---:|---|
| SIMULTANEOUS_LOCATIONS | 4 | Quarantine the asset location and confirm physical position |
| RETURN_BEFORE_DISPATCH | 5 | Reject the receipt event and review scan timestamps |
| DOUBLE_REFUND | 5 | Block the second payout and reclaim the amount |
| REPAIR_WITHOUT_INSPECTION | 1 | Reopen the repair and require an inspection |
| MISSING_ACTOR | 20 | Request actor attribution for the event |
