# Validation summary

Seed 20261022, as-of 2026-10-30. Each row: check, items examined, violations, detail. PASS = 0 violations.

| Check | Examined | Violations | Result | Detail |
|---|---:|---:|---|---|
| volume.assets | 20000 | 0 | PASS | rows=20000 |
| volume.shipments | 10000 | 0 | PASS | rows=10000 |
| volume.custody_events | 100000 | 0 | PASS | rows=100000 |
| pk_unique.asset_type | 12 | 0 | PASS |  |
| pk_unique.owner | 65 | 0 | PASS |  |
| pk_unique.location | 143 | 0 | PASS |  |
| pk_unique.actor | 65 | 0 | PASS |  |
| pk_unique.reusable_asset | 20000 | 0 | PASS |  |
| pk_unique.shipment | 10000 | 0 | PASS |  |
| pk_unique.shipment_asset | 40688 | 0 | PASS |  |
| pk_unique.return_authorization | 17891 | 0 | PASS |  |
| pk_unique.custody_event | 100000 | 0 | PASS |  |
| pk_unique.inspection | 17818 | 0 | PASS |  |
| pk_unique.repair | 556 | 0 | PASS |  |
| pk_unique.deposit_ledger | 41723 | 0 | PASS |  |
| fk_integrity.asset_type | 0 | 0 | PASS |  |
| enum_values.asset_type | 12 | 0 | PASS |  |
| required_fields.asset_type | 12 | 0 | PASS |  |
| fk_integrity.owner | 0 | 0 | PASS |  |
| enum_values.owner | 65 | 0 | PASS |  |
| required_fields.owner | 65 | 0 | PASS |  |
| fk_integrity.location | 143 | 0 | PASS |  |
| enum_values.location | 143 | 0 | PASS |  |
| required_fields.location | 143 | 0 | PASS |  |
| fk_integrity.actor | 65 | 0 | PASS |  |
| enum_values.actor | 65 | 0 | PASS |  |
| required_fields.actor | 65 | 0 | PASS |  |
| fk_integrity.reusable_asset | 60000 | 0 | PASS |  |
| enum_values.reusable_asset | 20000 | 0 | PASS |  |
| required_fields.reusable_asset | 20000 | 0 | PASS |  |
| fk_integrity.shipment | 64500 | 0 | PASS |  |
| enum_values.shipment | 10000 | 0 | PASS |  |
| required_fields.shipment | 10000 | 0 | PASS |  |
| fk_integrity.shipment_asset | 81376 | 0 | PASS |  |
| enum_values.shipment_asset | 40688 | 0 | PASS |  |
| required_fields.shipment_asset | 40688 | 0 | PASS |  |
| fk_integrity.return_authorization | 71315 | 0 | PASS |  |
| enum_values.return_authorization | 17891 | 0 | PASS |  |
| required_fields.return_authorization | 17891 | 0 | PASS |  |
| fk_integrity.custody_event | 633794 | 0 | PASS |  |
| enum_values.custody_event | 100000 | 0 | PASS |  |
| required_fields.custody_event | 100000 | 0 | PASS |  |
| fk_integrity.inspection | 89090 | 0 | PASS |  |
| enum_values.inspection | 17818 | 0 | PASS |  |
| required_fields.inspection | 17818 | 0 | PASS |  |
| fk_integrity.repair | 3873 | 0 | PASS |  |
| enum_values.repair | 556 | 0 | PASS |  |
| required_fields.repair | 556 | 0 | PASS |  |
| fk_integrity.deposit_ledger | 212491 | 0 | PASS |  |
| enum_values.deposit_ledger | 41723 | 0 | PASS |  |
| required_fields.deposit_ledger | 41723 | 0 | PASS |  |
| id_format_and_consistency | 148447 | 0 | PASS | ASSET-/SHP-/RMA-/REP-/CUST- patterns, CUST id embeds asset id and seq |
| duplicate.asset_serial_number | 20000 | 0 | PASS |  |
| duplicate_or_gapped.seq_no_per_asset | 100000 | 0 | PASS |  |
| lifecycle.state_transitions | 99960 | 0 | PASS | every event must be a legal transition (see lifecycle_rules.json) |
| lifecycle.location_continuity | 99960 | 0 | PASS | departure/terminal event from_location must equal the reconstructed current location |
| date_ordering.custody_events | 100000 | 0 | PASS | event_ts non-decreasing along seq_no and recorded_ts >= event_ts (excluding detected RETURN_BEFORE_DISPATCH) |
| lifecycle.loss_only_after_threshold | 100000 | 0 | PASS | MARK_LOST only when outstanding >= loss_threshold_days |
| lifecycle.no_return_after_loss_threshold | 100000 | 0 | PASS | RETURN_RECEIPT only within loss_threshold_days |
| lifecycle.damage_follow_up | 17818 | 0 | PASS | PASS -> dispatch/transfer; FAIL_REPAIRABLE -> repair or scrap; FAIL_UNREPAIRABLE -> scrap |
| date_ordering.shipments | 10000 | 0 | PASS | planned<=actual ship<=actual delivery; expected>planned; origin<>destination |
| arithmetic.shipment_asset_count_and_delay | 10000 | 0 | PASS | asset_count equals bridge rows; delay_hours recomputed |
| lifecycle.shipment_status_consistency | 10000 | 0 | PASS | status matches timestamps and bridge rows |
| join.event_shipment_membership | 78596 | 0 | PASS | event shipment type matches event type and asset is on the shipment (bridge) |
| date_ordering.rma_status | 17891 | 0 | PASS | closed_ts >= authorized_ts; closed only when RECEIVED/EXPIRED |
| rules.inspection_links_and_severity_bands | 17818 | 0 | PASS | inspection after its event; severity recomputed from damage_score bands |
| rules.repair_requires_prior_failed_inspection | 556 | 0 | PASS | linked inspection: same asset, intake, FAIL_REPAIRABLE, earlier than repair; closed_ts>=opened_ts |
| reconciliation.deposit_accounts | 18822 | 0 | PASS | charge == refund + deductions for every settled account; exactly one charge; amount equals type deposit; excess refunds only where DOUBLE_REFUND detected |
| arithmetic.ledger_amounts | 41723 | 0 | PASS | amounts > 0 and charge equals type deposit |
| reconstruction.current_state_from_events | 19651 | 0 | PASS | derived state == generator truth for assets without detected anomalies |
| reconstruction.current_location_from_events | 19651 | 0 | PASS | derived location == generator truth |
| reconstruction.days_outstanding | 19651 | 0 | PASS | derived outstanding age == generator truth |
| reconstruction.loss_status | 19651 | 0 | PASS | derived loss status == generator truth |
| anomaly_detection.SIMULTANEOUS_LOCATIONS | 40 | 0 | PASS | injected=40 detected=40 TP=40 FP=0 FN=0 |
| anomaly_detection.RETURN_BEFORE_DISPATCH | 57 | 0 | PASS | injected=57 detected=57 TP=57 FP=0 FN=0 |
| anomaly_detection.DOUBLE_REFUND | 57 | 0 | PASS | injected=57 detected=57 TP=57 FP=0 FN=0 |
| anomaly_detection.REPAIR_WITHOUT_INSPECTION | 9 | 0 | PASS | injected=9 detected=9 TP=9 FP=0 FN=0 |
| anomaly_detection.MISSING_ACTOR | 200 | 0 | PASS | injected=200 detected=200 TP=200 FP=0 FN=0 |
| coverage.delayed_returns | 5369 | 0 | PASS | late returns (> max_loan_days) |
| coverage.damaged_assets | 550 | 0 | PASS | assets with a failed inspection |
| coverage.lost_assets | 203 | 0 | PASS | assets in LOST state |
| coverage.disputed_handoffs | 957 | 0 | PASS | custody events with dispute_flag |
| coverage.scrapped_assets | 81 | 0 | PASS | assets in SCRAPPED state |
| coverage.cancelled_shipments | 3 | 0 | PASS | exception path: cancelled shipments |
| coverage.anomaly_SIMULTANEOUS_LOCATIONS | 40 | 0 | PASS |  |
| coverage.anomaly_RETURN_BEFORE_DISPATCH | 57 | 0 | PASS |  |
| coverage.anomaly_DOUBLE_REFUND | 57 | 0 | PASS |  |
| coverage.anomaly_REPAIR_WITHOUT_INSPECTION | 9 | 0 | PASS |  |
| coverage.anomaly_MISSING_ACTOR | 200 | 0 | PASS |  |

**89 of 89 checks pass.**

## Row counts

| Table | Rows |
|---|---:|
| asset_type | 12 |
| owner | 65 |
| location | 143 |
| actor | 65 |
| reusable_asset | 20000 |
| shipment | 10000 |
| shipment_asset | 40688 |
| return_authorization | 17891 |
| custody_event | 100000 |
| inspection | 17818 |
| repair | 556 |
| deposit_ledger | 41723 |
| ground_truth (anomalies) | 363 |

## Distribution profile

| Metric | Value |
|---|---:|
| events: COMMISSION | 20012 |
| events: DELIVERY_RECEIPT | 18644 |
| events: DISPATCH | 18822 |
| events: MARK_LOST | 203 |
| events: REPAIR_RECEIPT | 552 |
| events: REPAIR_SEND | 556 |
| events: RETURN_PICKUP | 17516 |
| events: RETURN_RECEIPT | 17360 |
| events: SCRAP | 81 |
| events: TRANSFER_DISPATCH | 3303 |
| events: TRANSFER_RECEIPT | 2951 |
| assets in state AT_WAREHOUSE | 18075 |
| assets in state IN_REPAIR | 5 |
| assets in state IN_TRANSIT_OUT | 180 |
| assets in state IN_TRANSIT_RETURN | 176 |
| assets in state IN_TRANSIT_TRANSFER | 357 |
| assets in state LOST | 203 |
| assets in state SCRAPPED | 81 |
| assets in state WITH_CUSTOMER | 923 |
| shipments CANCELLED | 3 |
| shipments DELIVERED | 9638 |
| shipments IN_TRANSIT | 108 |
| shipments PLANNED | 251 |
| intake inspections FAIL_REPAIRABLE | 557 |
| intake inspections PASS | 16710 |
| RMA AUTHORIZED | 172 |
| RMA EXPIRED | 203 |
| RMA IN_TRANSIT | 176 |
| RMA RECEIVED | 17340 |
| late returns (cycles) | 5369 |
| assets ever damaged | 550 |
| assets lost | 203 |
| assets scrapped | 81 |
| disputed handoff events | 957 |
