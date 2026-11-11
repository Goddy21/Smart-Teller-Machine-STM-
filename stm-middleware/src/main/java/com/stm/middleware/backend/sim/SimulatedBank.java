package com.stm.middleware.backend.sim;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.Collections;
import com.stm.middleware.backend.CoreBanking;
import com.stm.middleware.backend.IdentityServices;
import com.stm.middleware.common.Hashing;
import com.stm.middleware.stm.core.Errors;
import com.stm.middleware.stm.core.Ids;
import com.stm.middleware.stm.core.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SIMULATED core banking, card management system + HSM, and Paybill collections, backed by the sim_* tables.
 * Postings are double-entry (customer account and a GL code under one core reference) so cash GLs reconcile.
 * Not used in the prod profile: plug in real adapters for the bank's core, CMS and HSM there.
 */
@Service
@Profile("!prod")
public class SimulatedBank implements CoreBanking, CardSystem, Collections {
    private static final SecureRandom RNG = new SecureRandom();

    /** An ACTIVE account with no customer activity for the dormancy period reads as DORMANT. */
    private final RowMapper<Account> ACCOUNT = (rs, n) -> {
        OffsetDateTime last = rs.getObject("last_activity_at", OffsetDateTime.class);
        String status = rs.getString("status");
        if ("ACTIVE".equals(status) && last != null && last.isBefore(OffsetDateTime.now().minusMonths(dormantAfterMonths()))) {
            status = "DORMANT";
        }
        return new Account(rs.getString("id"),
                rs.getString("account_number"), rs.getString("customer_id"), rs.getString("type"),
                rs.getString("product_code"), rs.getString("currency"), rs.getBigDecimal("ledger"),
                rs.getBigDecimal("available"), rs.getString("branch"), status, rs.getBoolean("card_eligible"),
                last, rs.getString("status_reason"));
    };

    private static final RowMapper<Customer> CUSTOMER = (rs, n) -> new Customer(rs.getString("id"),
            rs.getString("id_number"), rs.getString("full_name"), rs.getString("first_name"), rs.getString("mobile"),
            rs.getString("kra_pin"));

    private static final RowMapper<Card> CARD = (rs, n) -> {
        String status = effectiveStatus(rs.getString("status"), rs.getString("expiry"));
        String reason = "TEMP_BLOCKED".equals(status) ? rs.getString("temp_blocked_reason") : rs.getString("blocked_reason");
        OffsetDateTime at = "TEMP_BLOCKED".equals(status) ? rs.getObject("temp_blocked_at", OffsetDateTime.class)
                : rs.getObject("blocked_at", OffsetDateTime.class);
        return new Card(rs.getString("id"), rs.getString("customer_id"),
                rs.getString("account_id"), rs.getString("masked_pan"), rs.getString("product"), status,
                rs.getString("expiry"), rs.getInt("pin_tries_left"), reason, rs.getString("pin_hash") != null, at);
    };

    private static final Map<String, Biller> BILLERS = Map.of(
            "522522", new Biller("522522", "Blue River Utilities", "GL-PAYBILL-522522"),
            "888880", new Biller("888880", "Nairobi City Water", "GL-PAYBILL-888880"));

    private final JdbcClient jdbc;
    private final SimControl control;
    private final int dormantMonths;

    public SimulatedBank(JdbcClient jdbc, SimControl control,
                         @org.springframework.beans.factory.annotation.Value("${stm.accounts.dormant-after-months:12}") int dormantMonths) {
        this.jdbc = jdbc;
        this.control = control;
        this.dormantMonths = Math.max(1, dormantMonths);
    }

    private int dormantAfterMonths() { return dormantMonths; }

    // ================================================================== core banking

    @Override
    public Optional<Customer> customer(String customerId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_customers WHERE id = :id").param("id", customerId).query(CUSTOMER).optional();
    }

    @Override
    public KycProfile kycProfile(String customerId) {
        control.core();
        return jdbc.sql("SELECT kyc_reviewed_at, risk_rating, pep, kyc_flags FROM sim_customers WHERE id = :id")
                .param("id", customerId)
                .query((rs, n) -> new KycProfile(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getBoolean(3),
                        "CLEAR", parseFlags(rs.getString(4))))
                .optional()
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
    }

    /** kyc_flags holds a JSON array of plain codes, e.g. ["ADDRESS_UNVERIFIED"]. */
    private static List<String> parseFlags(String json) {
        if (json == null || json.isBlank()) return List.of();
        String inner = json.trim().replaceAll("^\\[|]$", "").trim();
        if (inner.isEmpty()) return List.of();
        return java.util.Arrays.stream(inner.split(",")).map(x -> x.trim().replace("\"", "")).filter(x -> !x.isEmpty()).toList();
    }

    @Override
    public Optional<Customer> customerByIdNumber(String idNumber) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_customers WHERE id_number = :n").param("n", idNumber).query(CUSTOMER).optional();
    }

    @Override
    public Optional<Account> account(String accountId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_accounts WHERE id = :id").param("id", accountId).query(ACCOUNT).optional();
    }

    @Override
    public Optional<Account> accountByNumber(String accountNumber) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_accounts WHERE account_number = :n").param("n", accountNumber)
                .query(ACCOUNT).optional();
    }

    @Override
    public List<Account> accounts(String customerId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_accounts WHERE customer_id = :c ORDER BY created_at, id")
                .param("c", customerId).query(ACCOUNT).list();
    }

    @Override
    @Transactional
    public Opened openCustomerAndAccount(NewCustomer c, String productCode, String accountType, String branchCode) {
        control.core();
        Optional<Customer> existing = customerByIdNumber(c.idNumber());
        String cif;
        if (existing.isPresent()) {
            cif = existing.get().id();
        } else {
            long n = jdbc.sql("SELECT nextval('sim_cif_seq')").query(Long.class).single();
            cif = String.format("CIF%08d", n);
            jdbc.sql("""
                    INSERT INTO sim_customers (id, id_number, full_name, first_name, mobile, kra_pin, address, branch)
                    VALUES (:id, :n, :f, :fn, :m, :k, :a, :b)""")
                    .param("id", cif).param("n", c.idNumber()).param("f", c.fullName()).param("fn", c.firstName())
                    .param("m", c.mobile()).param("k", c.kraPin()).param("a", c.addressJson()).param("b", branchCode)
                    .update();
        }
        long seq = jdbc.sql("SELECT nextval('sim_account_seq')").query(Long.class).single();
        String prefix = branchCode != null && branchCode.matches("\\d{3}") ? branchCode : "012";
        String number = prefix + String.format("%09d", 345_670_000L + seq);
        String id = Ids.next("acc");
        jdbc.sql("""
                INSERT INTO sim_accounts (id, account_number, customer_id, type, product_code, branch)
                VALUES (:id, :no, :c, :t, :p, :b)""")
                .param("id", id).param("no", number).param("c", cif).param("t", accountType).param("p", productCode)
                .param("b", prefix).update();
        return new Opened(cif, account(id).orElseThrow());
    }

    @Override
    @Transactional
    public Posting debit(String accountId, BigDecimal amount, String currency, String narrative, String creditGl) {
        control.core();
        Account a = lock(accountId, true);
        if (a.available().compareTo(amount) < 0) throw Errors.insufficientFunds(Money.of(a.available(), a.currency()));
        String ref = Ids.coreRef();
        BigDecimal after = a.ledger().subtract(amount);
        move(accountId, amount.negate());
        jdbc.sql("UPDATE sim_accounts SET last_activity_at = now() WHERE id = :id").param("id", accountId).update();
        insert(ref, accountId, "DR", amount, currency, narrative, after, null);
        insert(ref, creditGl, "CR", amount, currency, narrative, null, null);
        return new Posting(ref, a.ledger(), after, OffsetDateTime.now());
    }

    @Override
    @Transactional
    public Posting credit(String accountId, BigDecimal amount, String currency, String narrative, String debitGl) {
        control.core();
        Account a = lock(accountId, false);
        String ref = Ids.coreRef();
        BigDecimal after = a.ledger().add(amount);
        move(accountId, amount);
        insert(ref, accountId, "CR", amount, currency, narrative, after, null);
        insert(ref, debitGl, "DR", amount, currency, narrative, null, null);
        return new Posting(ref, a.ledger(), after, OffsetDateTime.now());
    }

    @Override
    @Transactional
    public String transferGl(String debitGl, String creditGl, BigDecimal amount, String currency, String narrative) {
        control.core();
        String ref = Ids.coreRef();
        insert(ref, debitGl, "DR", amount, currency, narrative, null, null);
        insert(ref, creditGl, "CR", amount, currency, narrative, null, null);
        return ref;
    }

    private record Leg(String accountId, String direction, BigDecimal amount, String currency) {}

    @Override
    @Transactional
    public String reverse(String coreRef, String narrative) {
        control.core();
        Optional<String> done = jdbc.sql("SELECT core_ref FROM sim_postings WHERE reversal_of = :r LIMIT 1")
                .param("r", coreRef).query(String.class).optional();
        if (done.isPresent()) return done.get();
        List<Leg> legs = jdbc.sql("SELECT account_id, direction, amount, currency FROM sim_postings WHERE core_ref = :r ORDER BY id")
                .param("r", coreRef)
                .query((rs, n) -> new Leg(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4))).list();
        if (legs.isEmpty()) throw Errors.notFound("POSTING_NOT_FOUND", "Posting not found", "Muamala haukupatikana");
        String ref = Ids.coreRef();
        for (Leg l : legs) {
            String dir = l.direction().equals("DR") ? "CR" : "DR";
            BigDecimal after = null;
            Optional<Account> acc = jdbc.sql("SELECT * FROM sim_accounts WHERE id = :id FOR UPDATE")
                    .param("id", l.accountId()).query(ACCOUNT).optional();
            if (acc.isPresent()) {
                BigDecimal delta = dir.equals("CR") ? l.amount() : l.amount().negate();
                after = acc.get().ledger().add(delta);
                move(l.accountId(), delta);
            }
            insert(ref, l.accountId(), dir, l.amount(), l.currency(), narrative, after, coreRef);
        }
        return ref;
    }

    @Override
    public List<Line> history(String accountId, OffsetDateTime since, int limit) {
        control.core();
        return jdbc.sql("""
                SELECT posted_at, narrative, amount, direction, balance_after FROM sim_postings
                WHERE account_id = :a AND posted_at >= :s ORDER BY posted_at DESC, id DESC LIMIT :l""")
                .param("a", accountId).param("s", since).param("l", limit)
                .query((rs, n) -> new Line(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5)))
                .list();
    }

    /** Asset GLs (cash): debits increase the balance. */
    @Override
    public BigDecimal glBalance(String gl, String currency) {
        return jdbc.sql("""
                SELECT coalesce(sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)
                FROM sim_postings WHERE account_id = :g AND currency = :c""")
                .param("g", gl).param("c", currency).query(BigDecimal.class).single();
    }

    /**
     * Locks the account row and applies the status rules: money out needs ACTIVE; money in is also accepted on
     * DORMANT and FROZEN accounts (it does not wake a dormant account); nothing moves on a CLOSED one.
     */
    private Account lock(String accountId, boolean debit) {
        Account a = jdbc.sql("SELECT * FROM sim_accounts WHERE id = :id FOR UPDATE").param("id", accountId)
                .query(ACCOUNT).optional()
                .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana"));
        if (a.active()) return a;
        if (!debit && !"CLOSED".equals(a.status())) return a;
        if (a.dormant()) throw Errors.accountDormant(a.id());
        throw Errors.accountRestricted(a.status());
    }

    private void move(String accountId, BigDecimal delta) {
        jdbc.sql("UPDATE sim_accounts SET ledger = ledger + :d, available = available + :d WHERE id = :id")
                .param("d", delta).param("id", accountId).update();
    }

    private void insert(String ref, String account, String dir, BigDecimal amount, String currency, String narrative,
                        BigDecimal after, String reversalOf) {
        jdbc.sql("""
                INSERT INTO sim_postings (core_ref, account_id, direction, amount, currency, narrative, balance_after, reversal_of)
                VALUES (:r, :a, :d, :amt, :c, :n, :b, :rev)""")
                .param("r", ref).param("a", account).param("d", dir).param("amt", amount).param("c", currency)
                .param("n", narrative.length() > 120 ? narrative.substring(0, 120) : narrative)
                .param("b", after).param("rev", reversalOf).update();
    }

    // ================================================================== customer maintenance

    @Override
    @Transactional
    public Account reactivate(String accountId, String reference) {
        control.core();
        Account a = jdbc.sql("SELECT * FROM sim_accounts WHERE id = :id FOR UPDATE").param("id", accountId)
                .query(ACCOUNT).optional()
                .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana"));
        if (a.active()) return a;
        if (!a.dormant()) throw Errors.accountRestricted(a.status());
        jdbc.sql("UPDATE sim_accounts SET status = 'ACTIVE', last_activity_at = now(), reactivated_at = now(), status_reason = NULL WHERE id = :id")
                .param("id", accountId).update();
        return account(accountId).orElseThrow();
    }

    @Override
    public Optional<OffsetDateTime> mobileChangedAt(String customerId) {
        control.core();
        return jdbc.sql("SELECT mobile_changed_at FROM sim_customers WHERE id = :id").param("id", customerId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class))).optional().flatMap(x -> x);
    }

    @Override
    public boolean mobileInUse(String mobile, String exceptCustomerId) {
        control.core();
        return jdbc.sql("SELECT count(*) FROM sim_customers WHERE mobile = :m AND id <> :id")
                .param("m", mobile).param("id", exceptCustomerId).query(Long.class).single() > 0;
    }

    @Override
    @Transactional
    public void changeMobile(String customerId, String newMobile, String channel, String reference) {
        control.core();
        String old = jdbc.sql("SELECT mobile FROM sim_customers WHERE id = :id FOR UPDATE").param("id", customerId)
                .query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("CUSTOMER_NOT_FOUND", "Customer not found", "Mteja hakupatikana"));
        jdbc.sql("UPDATE sim_customers SET mobile = :m, mobile_changed_at = now() WHERE id = :id")
                .param("m", newMobile).param("id", customerId).update();
        jdbc.sql("""
                INSERT INTO sim_mobile_changes (customer_id, old_mobile, new_mobile, channel, reference)
                VALUES (:c, :o, :n, :ch, :r)""")
                .param("c", customerId).param("o", old).param("n", newMobile).param("ch", channel).param("r", reference).update();
    }

    @Override
    public Biometrics biometrics(String customerId) {
        control.core();
        record Row(String finger, OffsetDateTime at, String source) {}
        List<Row> rows = jdbc.sql("SELECT finger, enrolled_at, source FROM sim_biometrics WHERE customer_id = :c ORDER BY finger")
                .param("c", customerId).query((rs, n) -> new Row(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3))).list();
        if (rows.isEmpty()) return new Biometrics(false, List.of(), null, null);
        Row latest = rows.stream().max(java.util.Comparator.comparing(Row::at)).orElseThrow();
        return new Biometrics(true, rows.stream().map(Row::finger).toList(), latest.at(), latest.source());
    }

    @Override
    @Transactional
    public Biometrics enrolBiometrics(String customerId, List<FingerTemplate> fingers, String source) {
        control.core();
        for (FingerTemplate f : fingers) {
            jdbc.sql("""
                    INSERT INTO sim_biometrics (customer_id, finger, template_sha256, quality, source)
                    VALUES (:c, :f, :h, :q, :s)
                    ON CONFLICT (customer_id, finger) DO UPDATE
                      SET template_sha256 = excluded.template_sha256, quality = excluded.quality,
                          source = excluded.source, enrolled_at = now()""")
                    .param("c", customerId).param("f", f.finger())
                    .param("h", Hashing.sha256Hex(f.template() == null ? new byte[0] : f.template()))
                    .param("q", f.quality()).param("s", source).update();
        }
        return biometrics(customerId);
    }

    /** The simulator has no matcher: a readable print (quality 40+) of an enrolled finger matches. */
    @Override
    public Optional<IdentityServices.MatchResult> matchEnrolledFingerprint(String customerId, String finger, byte[] template, int quality) {
        control.core();
        long n = jdbc.sql("SELECT count(*) FROM sim_biometrics WHERE customer_id = :c AND finger = :f")
                .param("c", customerId).param("f", finger).query(Long.class).single();
        if (n == 0) return Optional.empty();
        if (control.acceptAll()) return Optional.of(new IdentityServices.MatchResult("MATCH", 0.97));
        return Optional.of(quality < 40 ? new IdentityServices.MatchResult("NO_MATCH", 0.24) : new IdentityServices.MatchResult("MATCH", 0.97));
    }

    @Override
    public Optional<Signature> signature(String customerId) {
        control.core();
        return jdbc.sql("""
                SELECT id, captured_at, source FROM sim_signatures WHERE customer_id = :c AND status = 'ACTIVE'
                ORDER BY captured_at DESC LIMIT 1""").param("c", customerId)
                .query((rs, n) -> new Signature(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3))).optional();
    }

    @Override
    public Optional<SignatureImage> signatureImage(String customerId) {
        control.core();
        return jdbc.sql("""
                SELECT image, mime_type FROM sim_signatures WHERE customer_id = :c AND status = 'ACTIVE'
                ORDER BY captured_at DESC LIMIT 1""").param("c", customerId)
                .query((rs, n) -> new SignatureImage(rs.getBytes(1), rs.getString(2))).optional();
    }

    @Override
    @Transactional
    public Signature registerSignature(String customerId, byte[] image, String mimeType, String source) {
        control.core();
        jdbc.sql("UPDATE sim_signatures SET status = 'SUPERSEDED' WHERE customer_id = :c AND status = 'ACTIVE'")
                .param("c", customerId).update();
        String id = Ids.next("sig");
        jdbc.sql("""
                INSERT INTO sim_signatures (id, customer_id, image, mime_type, sha256, source)
                VALUES (:id, :c, :i, :m, :h, :s)""")
                .param("id", id).param("c", customerId).param("i", image).param("m", mimeType)
                .param("h", Hashing.sha256Hex(image)).param("s", source).update();
        return signature(customerId).orElseThrow();
    }

    // ================================================================== card system + HSM

    private static String effectiveStatus(String status, String expiry) {
        if (("ACTIVE".equals(status) || "INACTIVE_PIN_REQUIRED".equals(status) || "TEMP_BLOCKED".equals(status))
                && YearMonth.parse(expiry).isBefore(YearMonth.now())) return "EXPIRED";
        return status;
    }

    @Override
    public Optional<Card> card(String cardId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_cards WHERE id = :id").param("id", cardId).query(CARD).optional();
    }

    @Override
    public Optional<Card> byPanToken(String panToken) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_cards WHERE pan_token = :t").param("t", panToken).query(CARD).optional();
    }

    @Override
    public List<Card> cards(String customerId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_cards WHERE customer_id = :c ORDER BY created_at").param("c", customerId)
                .query(CARD).list();
    }

    @Override
    @Transactional
    public Card create(String customerId, String accountId, String product, String nameOnCard) {
        control.core();
        String last4;
        String token;
        do {
            last4 = String.format("%04d", RNG.nextInt(10_000));
            token = "tok_4512xxxx" + last4;
        } while (jdbc.sql("SELECT count(*) FROM sim_cards WHERE pan_token = :t").param("t", token).query(Long.class).single() > 0);
        String id = Ids.next("crd");
        jdbc.sql("""
                INSERT INTO sim_cards (id, customer_id, account_id, pan_token, masked_pan, product, status, expiry)
                VALUES (:id, :c, :a, :t, :m, :p, 'INACTIVE_PIN_REQUIRED', :e)""")
                .param("id", id).param("c", customerId).param("a", accountId).param("t", token)
                .param("m", "4512 •••• •••• " + last4).param("p", product)
                .param("e", YearMonth.now().plusYears(4).toString()).update();
        return card(id).orElseThrow();
    }

    @Override
    public String personalisationPayload(Card card, String nameOnCard) {
        byte[] raw = new byte[96];
        RNG.nextBytes(raw);
        return "SIM-EMV:" + Base64.getEncoder().encodeToString(raw);
    }

    @Override
    @Transactional
    public void setPin(String cardId, String pinBlock, String format, String ksn) {
        control.core();
        String pin = DevPinBlock.decode(pinBlock);
        jdbc.sql("UPDATE sim_cards SET pin_hash = :h, pin_tries_left = 3 WHERE id = :id")
                .param("h", Hashing.sha256Hex(cardId + ":" + pin)).param("id", cardId).update();
    }

    @Override
    @Transactional
    public PinCheck verifyPin(String cardId, String pinBlock, String format, String ksn) {
        control.core();
        String pin = DevPinBlock.decode(pinBlock);
        String hash = jdbc.sql("SELECT pin_hash FROM sim_cards WHERE id = :id FOR UPDATE").param("id", cardId)
                .query(String.class).optional().orElse(null);
        if (hash != null && hash.equals(Hashing.sha256Hex(cardId + ":" + pin))) {
            jdbc.sql("UPDATE sim_cards SET pin_tries_left = 3 WHERE id = :id").param("id", cardId).update();
            return new PinCheck(true, 3);
        }
        int left = jdbc.sql("""
                UPDATE sim_cards SET pin_tries_left = greatest(pin_tries_left - 1, 0),
                       status = CASE WHEN pin_tries_left - 1 <= 0 THEN 'PIN_LOCKED' ELSE status END
                WHERE id = :id RETURNING pin_tries_left""")
                .param("id", cardId).query(Integer.class).single();
        return new PinCheck(false, left);
    }

    @Override
    public String pinPolicyViolation(String pinBlock, String format, String ksn) {
        return DevPinBlock.policyViolation(DevPinBlock.decode(pinBlock));
    }

    @Override
    @Transactional
    public Card activate(String cardId) {
        control.core();
        int n = jdbc.sql("UPDATE sim_cards SET status = 'ACTIVE' WHERE id = :id AND pin_hash IS NOT NULL AND status = 'INACTIVE_PIN_REQUIRED'")
                .param("id", cardId).update();
        Card c = card(cardId).orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
        if (n == 0 && !"ACTIVE".equals(c.status())) throw Errors.wrongState("Set the PIN before activating this card.");
        return c;
    }

    @Override
    @Transactional
    public Card block(String cardId, String reason) {
        control.core();
        jdbc.sql("""
                UPDATE sim_cards SET status = 'BLOCKED', blocked_reason = :r, blocked_at = coalesce(blocked_at, now())
                WHERE id = :id AND status <> 'DESTROYED'""")
                .param("r", reason).param("id", cardId).update();
        return card(cardId).orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
    }

    @Override
    @Transactional
    public Card temporaryBlock(String cardId, String reason) {
        control.core();
        int n = jdbc.sql("""
                UPDATE sim_cards SET status = 'TEMP_BLOCKED', temp_blocked_at = now(), temp_blocked_reason = :r
                WHERE id = :id AND status = 'ACTIVE'""").param("r", reason).param("id", cardId).update();
        Card c = card(cardId).orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
        if (n == 0 && !"TEMP_BLOCKED".equals(c.status())) {
            throw Errors.wrongState("Only an active card can be blocked temporarily.").detail("cardStatus", c.status());
        }
        return c;
    }

    @Override
    @Transactional
    public Card unblock(String cardId) {
        control.core();
        int n = jdbc.sql("""
                UPDATE sim_cards SET status = 'ACTIVE', temp_blocked_at = NULL, temp_blocked_reason = NULL
                WHERE id = :id AND status = 'TEMP_BLOCKED'""").param("id", cardId).update();
        Card c = card(cardId).orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
        if (n == 0 && !"ACTIVE".equals(c.status())) {
            throw Errors.wrongState("This card is not temporarily blocked.").detail("cardStatus", c.status());
        }
        return c;
    }

    @Override
    @Transactional
    public void changePin(String cardId, String newPinBlock, String format, String ksn) {
        control.core();
        String pin = DevPinBlock.decode(newPinBlock);
        jdbc.sql("UPDATE sim_cards SET pin_hash = :h, pin_tries_left = 3, pin_changed_at = now() WHERE id = :id")
                .param("h", Hashing.sha256Hex(cardId + ":" + pin)).param("id", cardId).update();
    }

    @Override
    @Transactional
    public Card resetPin(String cardId, String newPinBlock, String format, String ksn) {
        control.core();
        String pin = DevPinBlock.decode(newPinBlock);
        jdbc.sql("""
                UPDATE sim_cards SET pin_hash = :h, pin_tries_left = 3, pin_changed_at = now(),
                       status = CASE WHEN status = 'PIN_LOCKED' THEN 'ACTIVE' ELSE status END
                WHERE id = :id""").param("h", Hashing.sha256Hex(cardId + ":" + pin)).param("id", cardId).update();
        return card(cardId).orElseThrow(() -> Errors.notFound("CARD_NOT_FOUND", "Card not found", "Kadi haikupatikana"));
    }

    @Override
    public void destroy(String cardId, String reason) {
        control.core();
        jdbc.sql("UPDATE sim_cards SET status = 'DESTROYED', blocked_reason = :r WHERE id = :id")
                .param("r", reason).param("id", cardId).update();
    }

    // ================================================================== collections

    @Override
    public Optional<Biller> paybill(String paybill, String accountReference) {
        control.core();
        return Optional.ofNullable(BILLERS.get(paybill));
    }
}
