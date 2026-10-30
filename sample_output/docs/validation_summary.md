# Validation summary

Seed 20261022, as-of 2026-10-30. Each row: check, items examined, violations, detail. PASS = 0 violations.

| Check | Examined | Violations | Result | Detail |
|---|---:|---:|---|---|
| volume.assets | 2000 | 0 | PASS | rows=2000 |
| volume.shipments | 1000 | 0 | PASS | rows=1000 |
| volume.custody_events | 10000 | 0 | PASS | rows=10000 |
| pk_unique.asset_type | 12 | 0 | PASS |  |
| pk_unique.owner | 19 | 0 | PASS |  |
| pk_unique.location | 15 | 0 | PASS |  |
| pk_unique.actor | 41 | 0 | PASS |  |
| pk_unique.reusable_asset | 2000 | 0 | PASS |  |
| pk_unique.shipment | 1000 | 0 | PASS |  |
| pk_unique.shipment_asset | 4136 | 0 | PASS |  |
| pk_unique.return_authorization | 1779 | 0 | PASS |  |
| pk_unique.custody_event | 10000 | 0 | PASS |  |
| pk_unique.inspection | 1733 | 0 | PASS |  |
| pk_unique.repair | 34 | 0 | PASS |  |
| pk_unique.deposit_ledger | 3936 | 0 | PASS |  |
| fk_integrity.asset_type | 0 | 0 | PASS |  |
| enum_values.asset_type | 12 | 0 | PASS |  |
| required_fields.asset_type | 12 | 0 | PASS |  |
| fk_integrity.owner | 0 | 0 | PASS |  |
| enum_values.owner | 19 | 0 | PASS |  |
| required_fields.owner | 19 | 0 | PASS |  |
| fk_integrity.location | 15 | 0 | PASS |  |
| enum_values.location | 15 | 0 | PASS |  |
| required_fields.location | 15 | 0 | PASS |  |
| fk_integrity.actor | 41 | 0 | PASS |  |
| enum_values.actor | 41 | 0 | PASS |  |
| required_fields.actor | 41 | 0 | PASS |  |
| fk_integrity.reusable_asset | 6000 | 0 | PASS |  |
| enum_values.reusable_asset | 2000 | 0 | PASS |  |
| required_fields.reusable_asset | 2000 | 0 | PASS |  |
| fk_integrity.shipment | 6450 | 0 | PASS |  |
| enum_values.shipment | 1000 | 0 | PASS |  |
| required_fields.shipment | 1000 | 0 | PASS |  |
| fk_integrity.shipment_asset | 8272 | 0 | PASS |  |
| enum_values.shipment_asset | 4136 | 0 | PASS |  |
| required_fields.shipment_asset | 4136 | 0 | PASS |  |
| fk_integrity.return_authorization | 7071 | 0 | PASS |  |
| enum_values.return_authorization | 1779 | 0 | PASS |  |
| required_fields.return_authorization | 1779 | 0 | PASS |  |
| fk_integrity.custody_event | 63226 | 0 | PASS |  |
| enum_values.custody_event | 10000 | 0 | PASS |  |
| required_fields.custody_event | 10000 | 0 | PASS |  |
| fk_integrity.inspection | 8665 | 0 | PASS |  |
| enum_values.inspection | 1733 | 0 | PASS |  |
| required_fields.inspection | 1733 | 0 | PASS |  |
| fk_integrity.repair | 237 | 0 | PASS |  |
| enum_values.repair | 34 | 0 | PASS |  |
| required_fields.repair | 34 | 0 | PASS |  |
| fk_integrity.deposit_ledger | 19849 | 0 | PASS |  |
| enum_values.deposit_ledger | 3936 | 0 | PASS |  |
| required_fields.deposit_ledger | 3936 | 0 | PASS |  |
| id_format_and_consistency | 14813 | 0 | PASS | ASSET-/SHP-/RMA-/REP-/CUST- patterns, CUST id embeds asset id and seq |
| duplicate.asset_serial_number | 2000 | 0 | PASS |  |
| duplicate_or_gapped.seq_no_per_asset | 10000 | 0 | PASS |  |
| lifecycle.state_transitions | 9996 | 0 | PASS | every event must be a legal transition (see lifecycle_rules.json) |
| lifecycle.location_continuity | 9996 | 0 | PASS | departure/terminal event from_location must equal the reconstructed current location |
| date_ordering.custody_events | 10000 | 0 | PASS | event_ts non-decreasing along seq_no and recorded_ts >= event_ts (excluding detected RETURN_BEFORE_DISPATCH) |
| lifecycle.loss_only_after_threshold | 10000 | 0 | PASS | MARK_LOST only when outstanding >= loss_threshold_days |
| lifecycle.no_return_after_loss_threshold | 10000 | 0 | PASS | RETURN_RECEIPT only within loss_threshold_days |
| lifecycle.damage_follow_up | 1733 | 0 | PASS | PASS -> dispatch/transfer; FAIL_REPAIRABLE -> repair or scrap; FAIL_UNREPAIRABLE -> scrap |
| date_ordering.shipments | 1000 | 0 | PASS | planned<=actual ship<=actual delivery; expected>planned; origin<>destination |
| arithmetic.shipment_asset_count_and_delay | 1000 | 0 | PASS | asset_count equals bridge rows; delay_hours recomputed |
| lifecycle.shipment_status_consistency | 1000 | 0 | PASS | status matches timestamps and bridge rows |
| join.event_shipment_membership | 7885 | 0 | PASS | event shipment type matches event type and asset is on the shipment (bridge) |
| date_ordering.rma_status | 1779 | 0 | PASS | closed_ts >= authorized_ts; closed only when RECEIVED/EXPIRED |
| rules.inspection_links_and_severity_bands | 1733 | 0 | PASS | inspection after its event; severity recomputed from damage_score bands |
| rules.repair_requires_prior_failed_inspection | 34 | 0 | PASS | linked inspection: same asset, intake, FAIL_REPAIRABLE, earlier than repair; closed_ts>=opened_ts |
| reconciliation.deposit_accounts | 1864 | 0 | PASS | charge == refund + deductions for every settled account; exactly one charge; amount equals type deposit; excess refunds only where DOUBLE_REFUND detected |
| arithmetic.ledger_amounts | 3936 | 0 | PASS | amounts > 0 and charge equals type deposit |
| reconstruction.current_state_from_events | 1967 | 0 | PASS | derived state == generator truth for assets without detected anomalies |
| reconstruction.current_location_from_events | 1967 | 0 | PASS | derived location == generator truth |
| reconstruction.days_outstanding | 1967 | 0 | PASS | derived outstanding age == generator truth |
| reconstruction.loss_status | 1967 | 0 | PASS | derived loss status == generator truth |
| anomaly_detection.SIMULTANEOUS_LOCATIONS | 4 | 0 | PASS | injected=4 detected=4 TP=4 FP=0 FN=0 |
| anomaly_detection.RETURN_BEFORE_DISPATCH | 5 | 0 | PASS | injected=5 detected=5 TP=5 FP=0 FN=0 |
| anomaly_detection.DOUBLE_REFUND | 5 | 0 | PASS | injected=5 detected=5 TP=5 FP=0 FN=0 |
| anomaly_detection.REPAIR_WITHOUT_INSPECTION | 1 | 0 | PASS | injected=1 detected=1 TP=1 FP=0 FN=0 |
| anomaly_detection.MISSING_ACTOR | 20 | 0 | PASS | injected=20 detected=20 TP=20 FP=0 FN=0 |
| coverage.delayed_returns | 342 | 0 | PASS | late returns (> max_loan_days) |
| coverage.damaged_assets | 34 | 0 | PASS | assets with a failed inspection |
| coverage.lost_assets | 39 | 0 | PASS | assets in LOST state |
| coverage.disputed_handoffs | 94 | 0 | PASS | custody events with dispute_flag |
| coverage.scrapped_assets | 7 | 0 | PASS | assets in SCRAPPED state |
| coverage.cancelled_shipments | 0 | 0 | PASS | exception path: cancelled shipments |
| coverage.anomaly_SIMULTANEOUS_LOCATIONS | 4 | 0 | PASS |  |
| coverage.anomaly_RETURN_BEFORE_DISPATCH | 5 | 0 | PASS |  |
| coverage.anomaly_DOUBLE_REFUND | 5 | 0 | PASS |  |
| coverage.anomaly_REPAIR_WITHOUT_INSPECTION | 1 | 0 | PASS |  |
| coverage.anomaly_MISSING_ACTOR | 20 | 0 | PASS |  |

**89 of 89 checks pass.**

## Row counts

| Table | Rows |
|---|---:|
| asset_type | 12 |
| owner | 19 |
| location | 15 |
| actor | 41 |
| reusable_asset | 2000 |
| shipment | 1000 |
| shipment_asset | 4136 |
| return_authorization | 1779 |
| custody_event | 10000 |
| inspection | 1733 |
| repair | 34 |
| deposit_ledger | 3936 |
| ground_truth (anomalies) | 35 |

## Distribution profile

| Metric | Value |
|---|---:|
| events: COMMISSION | 2001 |
| events: DELIVERY_RECEIPT | 1842 |
| events: DISPATCH | 1864 |
| events: MARK_LOST | 39 |
| events: REPAIR_RECEIPT | 34 |
| events: REPAIR_SEND | 34 |
| events: RETURN_PICKUP | 1730 |
| events: RETURN_RECEIPT | 1702 |
| events: SCRAP | 7 |
| events: TRANSFER_DISPATCH | 456 |
| events: TRANSFER_RECEIPT | 291 |
| assets in state AT_WAREHOUSE | 1664 |
| assets in state IN_TRANSIT_OUT | 23 |
| assets in state IN_TRANSIT_RETURN | 30 |
| assets in state IN_TRANSIT_TRANSFER | 165 |
| assets in state LOST | 39 |
| assets in state SCRAPPED | 7 |
| assets in state WITH_CUSTOMER | 72 |
| shipments DELIVERED | 958 |
| shipments IN_TRANSIT | 18 |
| shipments PLANNED | 24 |
| intake inspections FAIL_REPAIRABLE | 34 |
| intake inspections PASS | 1665 |
| RMA AUTHORIZED | 10 |
| RMA EXPIRED | 39 |
| RMA IN_TRANSIT | 30 |
| RMA RECEIVED | 1700 |
| late returns (cycles) | 342 |
| assets ever damaged | 34 |
| assets lost | 39 |
| assets scrapped | 7 |
| disputed handoff events | 94 |
