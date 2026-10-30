# Data dictionary

Types: STR text, INT integer, DEC2 money with 2 decimals (USD), DEC4 decimal with 4 places, TS UTC timestamp (ISO-8601), DATE ISO date, BOOL, ENUM closed list. NULLs are written as empty CSV fields. `Required = Y` means NOT NULL. Unique = the field is a primary key (or states `unique`).

## asset_type

Reusable asset type and its lifecycle/deposit rules

- Primary key: `asset_type_id`
- Cardinality / relationships: asset_type 1:N reusable_asset

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| asset_type_id | STR | Y |  |  | Y | PK; AT-NNNN; unique |
| type_code | STR | Y |  |  | Y | unique short code |
| type_name | STR | Y |  |  |  |  |
| category | ENUM | Y | PALLET, REUSABLE_PACKAGING, EQUIPMENT, RETURN_CONTAINER |  |  |  |
| deposit_amount | DEC2 | Y |  | USD |  | > 0; charged per dispatch |
| max_loan_days | INT | Y |  | days |  | 7..120; overdue after this |
| loss_threshold_days | INT | Y |  | days |  | > max_loan_days; outstanding longer than this => LOST |
| max_repair_cycles | INT | Y |  | count |  | 0..5; exceeded => SCRAP |
| fragility_index | DEC4 | Y |  | 0-1 |  | damage propensity input of the inspection rule |
| theft_risk_index | DEC4 | Y |  | 0-1 |  | loss propensity input of the loss rule |
| effective_from | DATE | Y |  |  |  | rule effective date |
| effective_to | DATE | N |  |  |  | NULL = still effective; >= effective_from |

## owner

Organisation that owns assets, operates sites or moves goods

- Primary key: `owner_id`
- Cardinality / relationships: owner 1:N location, 1:N actor, 1:N reusable_asset (legal owner)

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| owner_id | STR | Y |  |  | Y | PK; OWN-NNNN; unique |
| owner_name | STR | Y |  |  |  | synthetic name |
| owner_type | ENUM | Y | NETWORK_OPERATOR, SUPPLIER, CARRIER, WAREHOUSE_OPERATOR, REPAIR_VENDOR, CUSTOMER |  |  |  |
| country | STR | Y |  |  |  | ISO-3166 alpha-2 |
| on_time_rate | DEC4 | N |  | 0-1 |  | carriers only: share of on-time deliveries |
| damage_factor | DEC4 | N |  | 0-1 |  | carriers only: handling damage factor |
| active_from | DATE | Y |  |  |  |  |

## location

Physical site: warehouse, customer site or repair shop

- Primary key: `location_id`
- Cardinality / relationships: location N:1 owner; location 1:N shipment (origin/destination)

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| location_id | STR | Y |  |  | Y | PK; LOC-NNNN; unique |
| location_name | STR | Y |  |  |  |  |
| location_type | ENUM | Y | WAREHOUSE, CUSTOMER_SITE, REPAIR_SHOP |  |  |  |
| owner_id | STR | Y |  |  |  | FK owner.owner_id FK -> owner.owner_id |
| city | STR | Y |  |  |  |  |
| country | STR | Y |  |  |  |  |
| latitude | DEC4 | Y |  | deg |  | -90..90 |
| longitude | DEC4 | Y |  | deg |  | -180..180 |
| risk_tier | ENUM | N | LOW, MEDIUM, HIGH |  |  | customer sites only: asset-retention risk |
| typical_dwell_days | INT | N |  | days |  | customer sites only: usual holding time |
| active_from | DATE | Y |  |  |  |  |

## actor

Named role-holder performing custody actions (supporting entity)

- Primary key: `actor_id`
- Cardinality / relationships: owner 1:N actor; actor 1:N custody_event

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| actor_id | STR | Y |  |  | Y | PK; ACT-NNNN; unique |
| owner_id | STR | Y |  |  |  | FK owner.owner_id FK -> owner.owner_id |
| actor_role | ENUM | Y | SUPPLIER, PLANNER, CARRIER, WAREHOUSE_OPERATOR, LOGISTICS_ANALYST, OPERATIONS_MANAGER |  |  |  |
| display_name | STR | Y |  |  |  | synthetic label, no personal data |
| active_from | DATE | Y |  |  |  |  |

## reusable_asset

Individually tracked reusable asset (static attributes; state is derived from events)

- Primary key: `asset_id`
- Cardinality / relationships: asset_type 1:N reusable_asset; owner 1:N reusable_asset; reusable_asset 1:N custody_event; N:M shipment via shipment_asset

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| asset_id | STR | Y |  |  | Y | PK; ASSET-NNNNNNN; unique; immutable |
| asset_type_id | STR | Y |  |  |  | FK FK -> asset_type.asset_type_id |
| owner_id | STR | Y |  |  |  | FK; legal owner (supplier) FK -> owner.owner_id |
| serial_number | STR | Y |  |  | Y | unique |
| home_location_id | STR | Y |  |  |  | FK; warehouse of commissioning FK -> location.location_id |
| manufactured_date | DATE | Y |  |  |  | <= commissioned_date |
| commissioned_date | DATE | Y |  |  |  | equals date of the COMMISSION custody event |

## shipment

Dispatch, return or inter-warehouse transfer movement

- Primary key: `shipment_id`
- Cardinality / relationships: shipment 1:N shipment_asset N:1 reusable_asset (N:M via bridge); RETURN shipment 0..1:1 paired OUTBOUND shipment

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| shipment_id | STR | Y |  |  | Y | PK; SHP-2026-NNNNNN; unique |
| shipment_type | ENUM | Y | OUTBOUND, RETURN, TRANSFER |  |  |  |
| paired_shipment_id | STR | N |  |  |  | FK shipment (RETURN -> its OUTBOUND) FK -> shipment.shipment_id |
| origin_location_id | STR | Y |  |  |  | FK FK -> location.location_id |
| dest_location_id | STR | Y |  |  |  | FK; <> origin FK -> location.location_id |
| carrier_owner_id | STR | Y |  |  |  | FK owner (CARRIER) FK -> owner.owner_id |
| shipper_owner_id | STR | Y |  |  |  | FK owner FK -> owner.owner_id |
| receiver_owner_id | STR | Y |  |  |  | FK owner FK -> owner.owner_id |
| planner_actor_id | STR | N |  |  |  | FK actor (PLANNER) FK -> actor.actor_id |
| planned_ship_ts | TS | Y |  | UTC |  |  |
| actual_ship_ts | TS | N |  | UTC |  | >= planned_ship_ts; NULL if not shipped |
| expected_delivery_ts | TS | Y |  | UTC |  | > planned_ship_ts |
| actual_delivery_ts | TS | N |  | UTC |  | >= actual_ship_ts; NULL if not delivered |
| status | ENUM | Y | PLANNED, IN_TRANSIT, DELIVERED, CANCELLED |  |  | derived from timestamps vs as-of date and bridge rows |
| asset_count | INT | Y |  | count |  | = number of shipment_asset rows (derived) |
| delay_hours | INT | N |  | hours |  | = (actual_delivery - expected_delivery)/3600 (derived) |

## shipment_asset

Bridge table: assets carried by a shipment (N:M)

- Primary key: `shipment_id, asset_id`
- Cardinality / relationships: shipment N:M reusable_asset

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| shipment_id | STR | Y |  |  |  | FK; part of composite PK FK -> shipment.shipment_id |
| asset_id | STR | Y |  |  |  | FK; part of composite PK FK -> reusable_asset.asset_id |
| line_status | ENUM | Y | PLANNED, SHIPPED, RECEIVED |  |  |  |
| line_no | INT | Y |  |  |  | position within shipment, >= 1 |

## return_authorization

Authorisation for an asset to come back (one per asset loan)

- Primary key: `rma_id`
- Cardinality / relationships: reusable_asset 1:N return_authorization; return_authorization N:1 outbound shipment; N:1 return shipment (optional)

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| rma_id | STR | Y |  |  | Y | PK; RMA-2026-NNNNNN; unique |
| asset_id | STR | Y |  |  |  | FK FK -> reusable_asset.asset_id |
| outbound_shipment_id | STR | Y |  |  |  | FK shipment (OUTBOUND) FK -> shipment.shipment_id |
| return_shipment_id | STR | N |  |  |  | FK shipment (RETURN); NULL for overdue recalls FK -> shipment.shipment_id |
| authorizing_owner_id | STR | Y |  |  |  | FK owner; asset legal owner FK -> owner.owner_id |
| reason_code | ENUM | Y | END_OF_LOAN, OVERDUE_RECALL |  |  |  |
| authorized_ts | TS | Y |  | UTC |  | after delivery |
| expected_return_date | DATE | Y |  |  |  | delivery date + max_loan_days |
| expiry_date | DATE | Y |  |  |  | dispatch date + loss_threshold_days |
| status | ENUM | Y | AUTHORIZED, IN_TRANSIT, RECEIVED, EXPIRED |  |  |  |
| closed_ts | TS | N |  | UTC |  | set when RECEIVED or EXPIRED |

## custody_event

Append-only custody/state-change event (history is never overwritten)

- Primary key: `custody_event_id`
- Cardinality / relationships: reusable_asset 1:N custody_event; shipment 0..1:N custody_event; return_authorization 0..1:N custody_event; actor 0..1:N custody_event

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| custody_event_id | STR | Y |  |  | Y | PK; CUST-<asset-id>-NNNNNN; unique |
| asset_id | STR | Y |  |  |  | FK FK -> reusable_asset.asset_id |
| seq_no | INT | Y |  |  |  | 1..n per asset, gap-free; matches ID suffix |
| event_type | ENUM | Y | COMMISSION, DISPATCH, DELIVERY_RECEIPT, RETURN_PICKUP, RETURN_RECEIPT, TRANSFER_DISPATCH, TRANSFER_RECEIPT, REPAIR_SEND, REPAIR_RECEIPT, MARK_LOST, SCRAP |  |  | see lifecycle_rules.json |
| event_ts | TS | Y |  | UTC |  | non-decreasing along seq_no |
| from_location_id | STR | N |  |  |  | FK; NULL for COMMISSION FK -> location.location_id |
| to_location_id | STR | N |  |  |  | FK; set on arrival events only (asset location after the event), NULL while in transit/terminal FK -> location.location_id |
| shipment_id | STR | N |  |  |  | FK FK -> shipment.shipment_id |
| rma_id | STR | N |  |  |  | FK FK -> return_authorization.rma_id |
| from_owner_id | STR | N |  |  |  | FK custodian before FK -> owner.owner_id |
| to_owner_id | STR | N |  |  |  | FK custodian after FK -> owner.owner_id |
| actor_id | STR | N |  |  |  | FK; business rule: must be present (missing => MISSING_ACTOR anomaly) FK -> actor.actor_id |
| actor_role | ENUM | N | SUPPLIER, PLANNER, CARRIER, WAREHOUSE_OPERATOR, LOGISTICS_ANALYST, OPERATIONS_MANAGER |  |  | role of actor |
| condition_reported | ENUM | Y | GOOD, DAMAGED, NA |  |  |  |
| dispute_flag | BOOL | Y |  |  |  | true iff dispute_reason present |
| dispute_reason | ENUM | N | LATE_DELIVERY, CONDITION_MISMATCH |  |  | late > 72h, or transit-damage score above cut |
| dispute_status | ENUM | Y | NONE, OPEN, RESOLVED_ACCEPTED, RESOLVED_REJECTED |  |  |  |
| dispute_resolved_ts | TS | N |  | UTC |  | only when RESOLVED_* |
| recorded_ts | TS | Y |  | UTC |  | ingestion time, >= event_ts for baseline rows |
| source_system | ENUM | Y | WMS_SCAN, TMS_FEED, MANUAL_ENTRY |  |  |  |

## inspection

Inspection of a returned or repaired asset

- Primary key: `inspection_id`
- Cardinality / relationships: custody_event 1:0..1 inspection; reusable_asset 1:N inspection

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| inspection_id | STR | Y |  |  | Y | PK; INS-2026-NNNNNN; unique |
| asset_id | STR | Y |  |  |  | FK FK -> reusable_asset.asset_id |
| custody_event_id | STR | Y |  |  |  | FK; RETURN_RECEIPT or REPAIR_RECEIPT event FK -> custody_event.custody_event_id |
| rma_id | STR | N |  |  |  | FK FK -> return_authorization.rma_id |
| inspection_stage | ENUM | Y | RETURN_INTAKE, POST_REPAIR |  |  |  |
| inspection_ts | TS | Y |  | UTC |  | > triggering event ts |
| inspector_actor_id | STR | Y |  |  |  | FK FK -> actor.actor_id |
| location_id | STR | Y |  |  |  | FK FK -> location.location_id |
| result | ENUM | Y | PASS, FAIL_REPAIRABLE, FAIL_UNREPAIRABLE |  |  |  |
| severity | ENUM | Y | NONE, MINOR, MAJOR, IRREPARABLE |  |  | from damage_score bands |
| damage_score | DEC4 | Y |  | 0-1.5 |  | fragility*(0.6+0.4*loan_ratio)+0.6*carrier_damage+noise |

## repair

Repair job on a damaged asset

- Primary key: `repair_id`
- Cardinality / relationships: inspection 1:0..1 repair; reusable_asset 1:N repair

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| repair_id | STR | Y |  |  | Y | PK; REP-2026-NNNNNN; unique |
| asset_id | STR | Y |  |  |  | FK FK -> reusable_asset.asset_id |
| inspection_id | STR | N |  |  |  | FK; triggering inspection (rule: required, missing => REPAIR_WITHOUT_INSPECTION) FK -> inspection.inspection_id |
| send_event_id | STR | Y |  |  |  | FK REPAIR_SEND event FK -> custody_event.custody_event_id |
| receipt_event_id | STR | N |  |  |  | FK REPAIR_RECEIPT event; NULL while open FK -> custody_event.custody_event_id |
| repair_location_id | STR | Y |  |  |  | FK FK -> location.location_id |
| vendor_owner_id | STR | Y |  |  |  | FK FK -> owner.owner_id |
| repair_type | ENUM | Y | CLEAN_AND_SANITIZE, COMPONENT_REPLACEMENT, STRUCTURAL_REPAIR |  |  |  |
| severity | ENUM | Y | MINOR, MAJOR |  |  | copied from inspection |
| opened_ts | TS | Y |  | UTC |  |  |
| closed_ts | TS | N |  | UTC |  | >= opened_ts |
| cost_amount | DEC2 | N |  | USD |  | set when closed |
| status | ENUM | Y | OPEN, CLOSED |  |  |  |
| outcome | ENUM | Y | PENDING, RESTORED, SCRAPPED |  |  | from post-repair inspection |
| post_repair_inspection_id | STR | N |  |  |  | FK FK -> inspection.inspection_id |

## deposit_ledger

Append-only deposit charges, refunds and deductions

- Primary key: `ledger_id`
- Cardinality / relationships: deposit account = (asset_id, dispatch_shipment_id); account 1:N ledger rows; balance derived

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| ledger_id | STR | Y |  |  | Y | PK; LED-2026-NNNNNN; unique |
| asset_id | STR | Y |  |  |  | FK FK -> reusable_asset.asset_id |
| dispatch_shipment_id | STR | Y |  |  |  | FK OUTBOUND shipment (account key) FK -> shipment.shipment_id |
| rma_id | STR | N |  |  |  | FK FK -> return_authorization.rma_id |
| depositor_owner_id | STR | Y |  |  |  | FK customer owner FK -> owner.owner_id |
| source_event_id | STR | N |  |  |  | FK custody event that caused the entry FK -> custody_event.custody_event_id |
| inspection_id | STR | N |  |  |  | FK FK -> inspection.inspection_id |
| entry_ts | TS | Y |  | UTC |  |  |
| entry_type | ENUM | Y | CHARGE, REFUND, DEDUCTION |  |  |  |
| reason_code | ENUM | Y | DEPOSIT_ON_DISPATCH, RETURN_REFUND, LATE_FEE, DAMAGE_DEDUCTION, DAMAGE_FORFEIT, LOSS_FORFEIT |  |  |  |
| amount | DEC2 | Y |  | USD |  | > 0; sign is implied by entry_type |
| currency | ENUM | Y | USD |  |  |  |

## asset_status_derived

Per-asset status derived ONLY from custody events, repairs and the ledger

- Primary key: `asset_id`

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| asset_id | STR | Y |  |  | Y | FK reusable_asset |
| asset_type_id | STR | Y |  |  |  |  |
| current_state | ENUM | Y | UNCOMMISSIONED, AT_WAREHOUSE, IN_TRANSIT_OUT, WITH_CUSTOMER, IN_TRANSIT_RETURN, IN_TRANSIT_TRANSFER, IN_REPAIR, LOST, SCRAPPED |  |  | state after replaying events through the lifecycle state machine |
| current_location_id | STR | N |  |  |  | location after the last arrival event; NULL while in transit |
| last_event_id | STR | Y |  |  |  |  |
| last_event_ts | TS | Y |  | UTC |  |  |
| event_count | INT | Y |  | count |  |  |
| loan_cycles_completed | INT | Y |  | count |  | number of RETURN_RECEIPT events |
| late_returns | INT | Y |  | count |  | returns after max_loan_days |
| repair_count | INT | Y |  | count |  | REPAIR_SEND events |
| open_cycle_dispatch_ts | TS | N |  | UTC |  | last DISPATCH while outstanding |
| days_outstanding | INT | N |  | days |  | floor((as_of - last DISPATCH)/86400) while outstanding or LOST |
| loss_status | ENUM | Y | LOST, OVERDUE, ON_LOAN, NOT_OUTSTANDING |  |  | LOST = MARK_LOST; OVERDUE = outstanding > max_loan_days |
| deposit_held | DEC2 | Y |  | USD |  | sum(CHARGE) - sum(REFUND) - sum(DEDUCTION) over the asset's accounts |
| dq_flags | STR | N |  |  |  | pipe-separated anomaly detectors that fired for the asset |

## deposit_account_derived

Deposit account balances derived from the ledger

- Primary key: `asset_id, dispatch_shipment_id`

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| asset_id | STR | Y |  |  |  |  |
| dispatch_shipment_id | STR | Y |  |  |  |  |
| charged | DEC2 | Y |  | USD |  |  |
| refunded | DEC2 | Y |  | USD |  |  |
| deducted | DEC2 | Y |  | USD |  |  |
| balance | DEC2 | Y |  | USD |  | charged - refunded - deducted |
| account_status | ENUM | Y | OPEN, SETTLED, OVER_REFUNDED, UNDER_SETTLED |  |  |  |

## ground_truth

Labelled injected anomalies (kept out of input data)

- Primary key: `gt_id`

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| gt_id | STR | Y |  |  | Y | GT-NNNNNN |
| entity_type | STR | Y |  |  |  | table affected |
| entity_id | STR | Y |  |  |  | primary key of the anomalous row |
| asset_id | STR | Y |  |  |  |  |
| issue_type | ENUM | Y | SIMULTANEOUS_LOCATIONS, RETURN_BEFORE_DISPATCH, DOUBLE_REFUND, REPAIR_WITHOUT_INSPECTION, MISSING_ACTOR |  |  |  |
| related_entity_id | STR | N |  |  |  | e.g. the original event / ledger row |
| expected_decision | STR | Y |  |  |  | expected pipeline decision |
| description | STR | Y |  |  |  |  |

## asset_labels

Ground-truth expected asset outcomes from the generator's own rule engine

- Primary key: `asset_id`

| Field | Type | Required | Allowed values | Unit | Unique | Validation rule / foreign key |
|---|---|---|---|---|---|---|
| asset_id | STR | Y |  |  | Y |  |
| true_final_state | ENUM | Y | UNCOMMISSIONED, AT_WAREHOUSE, IN_TRANSIT_OUT, WITH_CUSTOMER, IN_TRANSIT_RETURN, IN_TRANSIT_TRANSFER, IN_REPAIR, LOST, SCRAPPED |  |  |  |
| true_current_location_id | STR | N |  |  |  |  |
| true_ever_damaged | BOOL | Y |  |  |  |  |
| true_delayed_returns | INT | Y |  |  |  |  |
| true_disputed_handoffs | INT | Y |  |  |  |  |
| true_loss_status | ENUM | Y | LOST, OVERDUE, ON_LOAN, NOT_OUTSTANDING |  |  |  |
| true_days_outstanding | INT | N |  | days |  |  |

