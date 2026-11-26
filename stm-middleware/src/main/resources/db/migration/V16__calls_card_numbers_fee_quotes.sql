-- 1. Teller calls: who ended the call and why (CUSTOMER_HUNG_UP, TELLER_ENDED, NO_TELLER_AVAILABLE, ...).
ALTER TABLE teller_calls ALTER COLUMN status TYPE VARCHAR(24);
ALTER TABLE teller_calls ADD COLUMN ended_by VARCHAR(10);
ALTER TABLE teller_calls ADD COLUMN end_reason VARCHAR(30);

-- 2. Card numbers: every card in the (simulated) card system gets a full 16-digit card number with a valid Luhn check
-- digit. Only the card system and the printer job hold it; everything else keeps showing the masked number.
ALTER TABLE sim_cards ADD COLUMN pan VARCHAR(19);
CREATE UNIQUE INDEX ux_sim_cards_pan ON sim_cards (pan);

-- Existing cards keep their last four digits: 4512 + 8 random digits + the last four, with digit 12 chosen so the
-- whole number passes the Luhn check (digit 12 is not doubled, so changing it by d changes the sum by d).
CREATE FUNCTION pg_temp.card_number(last4 TEXT) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    d INT[];
    s INT := 0;
    v INT;
    i INT;
    out TEXT := '';
BEGIN
    d := ARRAY[4, 5, 1, 2];
    FOR i IN 5..12 LOOP d := d || floor(random() * 10)::INT; END LOOP;
    FOR i IN 1..4 LOOP d := d || substr(last4, i, 1)::INT; END LOOP;
    FOR i IN 1..16 LOOP
        v := d[17 - i];
        IF i % 2 = 0 THEN v := v * 2; IF v > 9 THEN v := v - 9; END IF; END IF;
        s := s + v;
    END LOOP;
    d[12] := (d[12] + (10 - s % 10) % 10) % 10;
    FOR i IN 1..16 LOOP out := out || d[i]::TEXT; END LOOP;
    RETURN out;
END $$;

UPDATE sim_cards SET pan = pg_temp.card_number(right(regexp_replace(masked_pan, '[^0-9]', '', 'g'), 4))
WHERE pan IS NULL;

-- 3. Fee quotes for the kiosk's card journeys (POST /cards/fee-quotes): journey, tax part, free-card reason.
ALTER TABLE fee_quotes ADD COLUMN journey VARCHAR(12);
ALTER TABLE fee_quotes ADD COLUMN tax NUMERIC(19,2);
ALTER TABLE fee_quotes ADD COLUMN reason_code VARCHAR(30);
ALTER TABLE fee_quotes ADD COLUMN replaces_card_id VARCHAR(30);

-- 4. Card orders: the kiosk's request id, and the tax part of what was charged.
ALTER TABLE card_orders ADD COLUMN request_id VARCHAR(80);
ALTER TABLE card_orders ADD COLUMN fee_tax NUMERIC(19,2);
CREATE INDEX ix_card_orders_request ON card_orders (request_id);
