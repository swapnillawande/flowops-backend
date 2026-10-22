-- Historical rows have no trustworthy actor. Do not assign them to the user running this upgrade.
ALTER TABLE stock_movements ADD COLUMN actor_subject TEXT, ADD COLUMN actor_issuer TEXT, ADD COLUMN actor_name TEXT;
ALTER TABLE shipment_status_events ADD COLUMN actor_subject TEXT, ADD COLUMN actor_issuer TEXT, ADD COLUMN actor_name TEXT;

-- A subject is unique within its issuer, so capture both or neither.
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movement_actor_pair CHECK (
    (actor_subject IS NULL AND actor_issuer IS NULL AND actor_name IS NULL) OR
    (actor_subject IS NOT NULL AND actor_issuer IS NOT NULL AND
     length(trim(actor_subject)) > 0 AND length(trim(actor_issuer)) > 0));
ALTER TABLE shipment_status_events ADD CONSTRAINT ck_shipment_event_actor_pair CHECK (
    (actor_subject IS NULL AND actor_issuer IS NULL AND actor_name IS NULL) OR
    (actor_subject IS NOT NULL AND actor_issuer IS NOT NULL AND
     length(trim(actor_subject)) > 0 AND length(trim(actor_issuer)) > 0));
