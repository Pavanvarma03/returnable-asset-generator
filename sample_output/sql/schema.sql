CREATE TABLE asset_type (
  asset_type_id VARCHAR(64) NOT NULL,
  type_code VARCHAR(64) NOT NULL,
  type_name VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL CHECK (category IN ('PALLET','REUSABLE_PACKAGING','EQUIPMENT','RETURN_CONTAINER')),
  deposit_amount DECIMAL(14,2) NOT NULL,
  max_loan_days INTEGER NOT NULL,
  loss_threshold_days INTEGER NOT NULL,
  max_repair_cycles INTEGER NOT NULL,
  fragility_index DECIMAL(10,4) NOT NULL,
  theft_risk_index DECIMAL(10,4) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE,
  PRIMARY KEY (asset_type_id)
);

CREATE TABLE owner (
  owner_id VARCHAR(64) NOT NULL,
  owner_name VARCHAR(64) NOT NULL,
  owner_type VARCHAR(32) NOT NULL CHECK (owner_type IN ('NETWORK_OPERATOR','SUPPLIER','CARRIER','WAREHOUSE_OPERATOR','REPAIR_VENDOR','CUSTOMER')),
  country VARCHAR(64) NOT NULL,
  on_time_rate DECIMAL(10,4),
  damage_factor DECIMAL(10,4),
  active_from DATE NOT NULL,
  PRIMARY KEY (owner_id)
);

CREATE TABLE location (
  location_id VARCHAR(64) NOT NULL,
  location_name VARCHAR(64) NOT NULL,
  location_type VARCHAR(32) NOT NULL CHECK (location_type IN ('WAREHOUSE','CUSTOMER_SITE','REPAIR_SHOP')),
  owner_id VARCHAR(64) NOT NULL,
  city VARCHAR(64) NOT NULL,
  country VARCHAR(64) NOT NULL,
  latitude DECIMAL(10,4) NOT NULL,
  longitude DECIMAL(10,4) NOT NULL,
  risk_tier VARCHAR(32) CHECK (risk_tier IN ('LOW','MEDIUM','HIGH')),
  typical_dwell_days INTEGER,
  active_from DATE NOT NULL,
  PRIMARY KEY (location_id),
  FOREIGN KEY (owner_id) REFERENCES owner(owner_id)
);

CREATE TABLE actor (
  actor_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  actor_role VARCHAR(32) NOT NULL CHECK (actor_role IN ('SUPPLIER','PLANNER','CARRIER','WAREHOUSE_OPERATOR','LOGISTICS_ANALYST','OPERATIONS_MANAGER')),
  display_name VARCHAR(64) NOT NULL,
  active_from DATE NOT NULL,
  PRIMARY KEY (actor_id),
  FOREIGN KEY (owner_id) REFERENCES owner(owner_id)
);

CREATE TABLE reusable_asset (
  asset_id VARCHAR(64) NOT NULL,
  asset_type_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  serial_number VARCHAR(64) NOT NULL,
  home_location_id VARCHAR(64) NOT NULL,
  manufactured_date DATE NOT NULL,
  commissioned_date DATE NOT NULL,
  PRIMARY KEY (asset_id),
  FOREIGN KEY (asset_type_id) REFERENCES asset_type(asset_type_id),
  FOREIGN KEY (owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (home_location_id) REFERENCES location(location_id)
);

CREATE TABLE shipment (
  shipment_id VARCHAR(64) NOT NULL,
  shipment_type VARCHAR(32) NOT NULL CHECK (shipment_type IN ('OUTBOUND','RETURN','TRANSFER')),
  paired_shipment_id VARCHAR(64),
  origin_location_id VARCHAR(64) NOT NULL,
  dest_location_id VARCHAR(64) NOT NULL,
  carrier_owner_id VARCHAR(64) NOT NULL,
  shipper_owner_id VARCHAR(64) NOT NULL,
  receiver_owner_id VARCHAR(64) NOT NULL,
  planner_actor_id VARCHAR(64),
  planned_ship_ts TIMESTAMP NOT NULL,
  actual_ship_ts TIMESTAMP,
  expected_delivery_ts TIMESTAMP NOT NULL,
  actual_delivery_ts TIMESTAMP,
  status VARCHAR(32) NOT NULL CHECK (status IN ('PLANNED','IN_TRANSIT','DELIVERED','CANCELLED')),
  asset_count INTEGER NOT NULL,
  delay_hours INTEGER,
  PRIMARY KEY (shipment_id),
  FOREIGN KEY (paired_shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (origin_location_id) REFERENCES location(location_id),
  FOREIGN KEY (dest_location_id) REFERENCES location(location_id),
  FOREIGN KEY (carrier_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (shipper_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (receiver_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (planner_actor_id) REFERENCES actor(actor_id)
);

CREATE TABLE shipment_asset (
  shipment_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  line_status VARCHAR(32) NOT NULL CHECK (line_status IN ('PLANNED','SHIPPED','RECEIVED')),
  line_no INTEGER NOT NULL,
  PRIMARY KEY (shipment_id, asset_id),
  FOREIGN KEY (shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id)
);

CREATE TABLE return_authorization (
  rma_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  outbound_shipment_id VARCHAR(64) NOT NULL,
  return_shipment_id VARCHAR(64),
  authorizing_owner_id VARCHAR(64) NOT NULL,
  reason_code VARCHAR(32) NOT NULL CHECK (reason_code IN ('END_OF_LOAN','OVERDUE_RECALL')),
  authorized_ts TIMESTAMP NOT NULL,
  expected_return_date DATE NOT NULL,
  expiry_date DATE NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status IN ('AUTHORIZED','IN_TRANSIT','RECEIVED','EXPIRED')),
  closed_ts TIMESTAMP,
  PRIMARY KEY (rma_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id),
  FOREIGN KEY (outbound_shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (return_shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (authorizing_owner_id) REFERENCES owner(owner_id)
);

CREATE TABLE custody_event (
  custody_event_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  seq_no INTEGER NOT NULL,
  event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('COMMISSION','DISPATCH','DELIVERY_RECEIPT','RETURN_PICKUP','RETURN_RECEIPT','TRANSFER_DISPATCH','TRANSFER_RECEIPT','REPAIR_SEND','REPAIR_RECEIPT','MARK_LOST','SCRAP')),
  event_ts TIMESTAMP NOT NULL,
  from_location_id VARCHAR(64),
  to_location_id VARCHAR(64),
  shipment_id VARCHAR(64),
  rma_id VARCHAR(64),
  from_owner_id VARCHAR(64),
  to_owner_id VARCHAR(64),
  actor_id VARCHAR(64),
  actor_role VARCHAR(32) CHECK (actor_role IN ('SUPPLIER','PLANNER','CARRIER','WAREHOUSE_OPERATOR','LOGISTICS_ANALYST','OPERATIONS_MANAGER')),
  condition_reported VARCHAR(32) NOT NULL CHECK (condition_reported IN ('GOOD','DAMAGED','NA')),
  dispute_flag BOOLEAN NOT NULL,
  dispute_reason VARCHAR(32) CHECK (dispute_reason IN ('LATE_DELIVERY','CONDITION_MISMATCH')),
  dispute_status VARCHAR(32) NOT NULL CHECK (dispute_status IN ('NONE','OPEN','RESOLVED_ACCEPTED','RESOLVED_REJECTED')),
  dispute_resolved_ts TIMESTAMP,
  recorded_ts TIMESTAMP NOT NULL,
  source_system VARCHAR(32) NOT NULL CHECK (source_system IN ('WMS_SCAN','TMS_FEED','MANUAL_ENTRY')),
  PRIMARY KEY (custody_event_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id),
  FOREIGN KEY (from_location_id) REFERENCES location(location_id),
  FOREIGN KEY (to_location_id) REFERENCES location(location_id),
  FOREIGN KEY (shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (rma_id) REFERENCES return_authorization(rma_id),
  FOREIGN KEY (from_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (to_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (actor_id) REFERENCES actor(actor_id)
);

CREATE TABLE inspection (
  inspection_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  custody_event_id VARCHAR(64) NOT NULL,
  rma_id VARCHAR(64),
  inspection_stage VARCHAR(32) NOT NULL CHECK (inspection_stage IN ('RETURN_INTAKE','POST_REPAIR')),
  inspection_ts TIMESTAMP NOT NULL,
  inspector_actor_id VARCHAR(64) NOT NULL,
  location_id VARCHAR(64) NOT NULL,
  result VARCHAR(32) NOT NULL CHECK (result IN ('PASS','FAIL_REPAIRABLE','FAIL_UNREPAIRABLE')),
  severity VARCHAR(32) NOT NULL CHECK (severity IN ('NONE','MINOR','MAJOR','IRREPARABLE')),
  damage_score DECIMAL(10,4) NOT NULL,
  PRIMARY KEY (inspection_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id),
  FOREIGN KEY (custody_event_id) REFERENCES custody_event(custody_event_id),
  FOREIGN KEY (rma_id) REFERENCES return_authorization(rma_id),
  FOREIGN KEY (inspector_actor_id) REFERENCES actor(actor_id),
  FOREIGN KEY (location_id) REFERENCES location(location_id)
);

CREATE TABLE repair (
  repair_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  inspection_id VARCHAR(64),
  send_event_id VARCHAR(64) NOT NULL,
  receipt_event_id VARCHAR(64),
  repair_location_id VARCHAR(64) NOT NULL,
  vendor_owner_id VARCHAR(64) NOT NULL,
  repair_type VARCHAR(32) NOT NULL CHECK (repair_type IN ('CLEAN_AND_SANITIZE','COMPONENT_REPLACEMENT','STRUCTURAL_REPAIR')),
  severity VARCHAR(32) NOT NULL CHECK (severity IN ('MINOR','MAJOR')),
  opened_ts TIMESTAMP NOT NULL,
  closed_ts TIMESTAMP,
  cost_amount DECIMAL(14,2),
  status VARCHAR(32) NOT NULL CHECK (status IN ('OPEN','CLOSED')),
  outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('PENDING','RESTORED','SCRAPPED')),
  post_repair_inspection_id VARCHAR(64),
  PRIMARY KEY (repair_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id),
  FOREIGN KEY (inspection_id) REFERENCES inspection(inspection_id),
  FOREIGN KEY (send_event_id) REFERENCES custody_event(custody_event_id),
  FOREIGN KEY (receipt_event_id) REFERENCES custody_event(custody_event_id),
  FOREIGN KEY (repair_location_id) REFERENCES location(location_id),
  FOREIGN KEY (vendor_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (post_repair_inspection_id) REFERENCES inspection(inspection_id)
);

CREATE TABLE deposit_ledger (
  ledger_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  dispatch_shipment_id VARCHAR(64) NOT NULL,
  rma_id VARCHAR(64),
  depositor_owner_id VARCHAR(64) NOT NULL,
  source_event_id VARCHAR(64),
  inspection_id VARCHAR(64),
  entry_ts TIMESTAMP NOT NULL,
  entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('CHARGE','REFUND','DEDUCTION')),
  reason_code VARCHAR(32) NOT NULL CHECK (reason_code IN ('DEPOSIT_ON_DISPATCH','RETURN_REFUND','LATE_FEE','DAMAGE_DEDUCTION','DAMAGE_FORFEIT','LOSS_FORFEIT')),
  amount DECIMAL(14,2) NOT NULL,
  currency VARCHAR(32) NOT NULL CHECK (currency IN ('USD')),
  PRIMARY KEY (ledger_id),
  FOREIGN KEY (asset_id) REFERENCES reusable_asset(asset_id),
  FOREIGN KEY (dispatch_shipment_id) REFERENCES shipment(shipment_id),
  FOREIGN KEY (rma_id) REFERENCES return_authorization(rma_id),
  FOREIGN KEY (depositor_owner_id) REFERENCES owner(owner_id),
  FOREIGN KEY (source_event_id) REFERENCES custody_event(custody_event_id),
  FOREIGN KEY (inspection_id) REFERENCES inspection(inspection_id)
);

