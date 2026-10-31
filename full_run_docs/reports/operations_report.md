# Operations report

As of 2026-10-30, seed 20261022. Computed from custody events, shipments, inspections and the deposit ledger. Ground truth is not used.

## Headline numbers

| Measure | Value |
|---|---:|
| Assets tracked | 20000 |
| Loan cycles dispatched | 18822 |
| Assets out on loan, within allowed days | 1072 |
| Assets overdue (past max loan days) | 207 |
| Assets lost | 203 |
| Loss rate (lost / loan cycles) | 1.1% |
| Late return rate (returns after max loan days) | 28.3% |
| Damage rate at intake (failed / inspected) | 3.2% |
| Loan duration, mean / median / 90th percentile (days) | 20.7 / 19 / 33 |

## Deposit position (USD)

| Measure | Amount |
|---|---:|
| Deposits charged | 935727.00 |
| Refunded | 833902.12 |
| Deducted (late fees, damage, forfeits) | 30080.73 |
| of which forfeited for loss or irreparable damage | 11794.00 |
| Still held, assets within loan period | 62349.32 |
| Still held, assets overdue (at risk) | 6682.20 |
| Held on other assets (awaiting inspection or settlement), net of duplicate refunds | 2712.63 |
| Net balance (charged - refunded - deducted) | 71744.15 |

The held amounts add up to the net balance.

## Overdue ageing

| Days past max loan | Assets |
|---|---:|
| 1 to 7 | 94 |
| 8 to 14 | 44 |
| 15 to 30 | 41 |
| 31 to 60 | 28 |
| over 60 | 0 |

Full list, worst first, with the customer site, carrier and recommended action: `overdue_assets.csv`. Actions: up to 14 days overdue `SEND_REMINDER`; 15 to 39 `ISSUE_RECALL_RMA`; 40 or more `ESCALATE_BEFORE_LOSS_WRITE_OFF`; lost `CONFIRM_LOSS_AND_FILE_CLAIM`.

## By asset type

| Type | Loans | Returns | Late | Lost | Intake damage | Charged | Forfeited |
|---|---:|---:|---:|---:|---:|---:|---:|
| PAL-EUR | 3254 | 3014 | 14.5% | 14 | 5.5% | 58572.00 | 252.00 |
| PAL-GMA | 2914 | 2676 | 13.6% | 25 | 3.7% | 40796.00 | 350.00 |
| PAL-PLS | 1220 | 1141 | 2.0% | 2 | 0.0% | 42700.00 | 70.00 |
| CRT-STD | 1935 | 1773 | 39.9% | 12 | 0.9% | 42570.00 | 264.00 |
| CRT-FLD | 1131 | 1050 | 39.4% | 6 | 6.1% | 33930.00 | 180.00 |
| CAG-ROL | 1483 | 1360 | 16.8% | 20 | 1.2% | 126055.00 | 1700.00 |
| TOT-60L | 2340 | 2129 | 71.3% | 54 | 1.1% | 21060.00 | 486.00 |
| IBC-1K | 589 | 538 | 0.4% | 2 | 0.0% | 147250.00 | 500.00 |
| GAY-BOX | 931 | 870 | 14.4% | 4 | 9.6% | 37240.00 | 160.00 |
| KEG-50L | 901 | 798 | 1.9% | 38 | 0.0% | 27030.00 | 1140.00 |
| EQP-SCN | 522 | 469 | 0.0% | 10 | 0.0% | 339300.00 | 6500.00 |
| RMC-BIN | 1602 | 1485 | 71.2% | 16 | 6.3% | 19224.00 | 192.00 |

## Carrier performance

On time = delivered no more than 6 hours after the promised time.

| Carrier | Delivered shipments | On time | Mean delay (hours) |
|---|---:|---:|---:|
| Carrier 01 | 1950 | 79.5% | 10.3 |
| Carrier 02 | 1855 | 84.2% | 8.0 |
| Carrier 03 | 1931 | 87.4% | 6.5 |
| Carrier 04 | 1865 | 93.2% | 4.0 |
| Carrier 05 | 2037 | 96.5% | 2.0 |

## Customer sites holding the most overdue or lost assets

| Site | City | Risk tier | Overdue | Lost |
|---|---|---|---:|---:|
| Customer Site Reno 12 | Reno | LOW | 10 | 5 |
| Customer Site Charlotte 7 | Charlotte | LOW | 9 | 3 |
| Customer Site Phoenix 29 | Phoenix | LOW | 11 | 1 |
| Customer Site Dallas 11 | Dallas | LOW | 8 | 3 |
| Customer Site Miami 59 | Miami | HIGH | 3 | 8 |
| Customer Site Seattle 17 | Seattle | LOW | 9 | 1 |
| Customer Site Portland 37 | Portland | LOW | 10 | 0 |
| Customer Site Louisville 19 | Louisville | HIGH | 1 | 8 |
| Customer Site Los Angeles 47 | Los Angeles | HIGH | 0 | 9 |
| Customer Site Detroit 72 | Detroit | HIGH | 5 | 4 |

## Exception queue

Data problems found by the validation rules (not read from the ground truth). Total 363. Full list: `exceptions_queue.csv`.

| Issue | Count | Recommended action |
|---|---:|---|
| SIMULTANEOUS_LOCATIONS | 40 | Quarantine the asset location and confirm physical position |
| RETURN_BEFORE_DISPATCH | 57 | Reject the receipt event and review scan timestamps |
| DOUBLE_REFUND | 57 | Block the second payout and reclaim the amount |
| REPAIR_WITHOUT_INSPECTION | 9 | Reopen the repair and require an inspection |
| MISSING_ACTOR | 200 | Request actor attribution for the event |
