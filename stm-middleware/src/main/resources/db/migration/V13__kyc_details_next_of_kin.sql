-- "Your details" on the kiosk (3 pages): contact and address, work and income, next of kin (B-8).
ALTER TABLE applications ADD COLUMN email          VARCHAR(120);
ALTER TABLE applications ADD COLUMN employer       VARCHAR(100);
ALTER TABLE applications ADD COLUMN monthly_income VARCHAR(20);     -- UP_TO_10K, 10K_50K, 50K_100K, 100K_500K, OVER_500K
ALTER TABLE applications ADD COLUMN next_of_kin    TEXT;            -- {"fullName","relationship","mobile"}

-- What the simulated core bank keeps from account opening (the real CBS takes the same fields).
ALTER TABLE sim_customers ADD COLUMN email   VARCHAR(120);
ALTER TABLE sim_customers ADD COLUMN profile TEXT;                 -- occupation, employer, income, source of funds, next of kin
