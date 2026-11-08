package com.stm.middleware.backend.sim;

import com.stm.middleware.backend.CardSystem;
import com.stm.middleware.backend.Collections;
import com.stm.middleware.backend.CoreBanking;
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

    private static final RowMapper<Account> ACCOUNT = (rs, n) -> new Account(rs.getString("id"),
            rs.getString("account_number"), rs.getString("customer_id"), rs.getString("type"),
            rs.getString("product_code"), rs.getString("currency"), rs.getBigDecimal("ledger"),
            rs.getBigDecimal("available"), rs.getString("branch"), rs.getString("status"), rs.getBoolean("card_eligible"));

    private static final RowMapper<Customer> CUSTOMER = (rs, n) -> new Customer(rs.getString("id"),
            rs.getString("id_number"), rs.getString("full_name"), rs.getString("first_name"), rs.getString("mobile"),
            rs.getString("kra_pin"));

    private static final RowMapper<Card> CARD = (rs, n) -> new Card(rs.getString("id"), rs.getString("customer_id"),
            rs.getString("account_id"), rs.getString("masked_pan"), rs.getString("product"), effectiveStatus(rs.getString("status"),
            rs.getString("expiry")), rs.getString("expiry"), rs.getInt("pin_tries_left"));

    private static final Map<String, Biller> BILLERS = Map.of(
            "522522", new Biller("522522", "Blue River Utilities", "GL-PAYBILL-522522"),
            "888880", new Biller("888880", "Nairobi City Water", "GL-PAYBILL-888880"));

    private final JdbcClient jdbc;
    private final SimControl control;

    public SimulatedBank(JdbcClient jdbc, SimControl control) {
        this.jdbc = jdbc;
        this.control = control;
    }

    // ================================================================== core banking

    @Override
    public Optional<Customer> customer(String customerId) {
        control.core();
        return jdbc.sql("SELECT * FROM sim_customers WHERE id = :id").param("id", customerId).query(CUSTOMER).optional();
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
        Account a = lock(accountId);
        if (a.available().compareTo(amount) < 0) throw Errors.insufficientFunds(Money.of(a.available(), a.currency()));
        String ref = Ids.coreRef();
        BigDecimal after = a.ledger().subtract(amount);
        move(accountId, amount.negate());
        insert(ref, accountId, "DR", amount, currency, narrative, after, null);
        insert(ref, creditGl, "CR", amount, currency, narrative, null, null);
        return new Posting(ref, a.ledger(), after, OffsetDateTime.now());
    }

    @Override
    @Transactional
    public Posting credit(String accountId, BigDecimal amount, String currency, String narrative, String debitGl) {
        control.core();
        Account a = lock(accountId);
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

    private Account lock(String accountId) {
        Account a = jdbc.sql("SELECT * FROM sim_accounts WHERE id = :id FOR UPDATE").param("id", accountId)
                .query(ACCOUNT).optional()
                .orElseThrow(() -> Errors.notFound("ACCOUNT_NOT_FOUND", "Account not found", "Akaunti haikupatikana"));
        if (!"ACTIVE".equals(a.status())) {
            throw Errors.of(HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED", "This account cannot be used here.",
                    "Akaunti hii haiwezi kutumika hapa.", false);
        }
        return a;
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

    // ================================================================== card system + HSM

    private static String effectiveStatus(String status, String expiry) {
        if (("ACTIVE".equals(status) || "INACTIVE_PIN_REQUIRED".equals(status))
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
