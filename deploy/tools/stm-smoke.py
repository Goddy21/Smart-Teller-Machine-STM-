#!/usr/bin/env python3
"""
STM smoke test: calls the new and changed kiosk endpoints the way the kiosk does and prints PASS / FAIL for each.

Run ON THE SERVER (or any machine on the VPN):
    python3 stm-smoke.py --token <terminal token> [--kiosk STM-SMOKE-TEST] [--url https://10.8.0.1:8443]
                         [--operator <username> --pin <6 digits>]

Before the first run, add a test kiosk in the portal (Kiosk fleet > Add kiosk, ID STM-SMOKE-TEST) and copy its token.
Afterwards disable it (More > Disable) so it does not count as a real kiosk.

It changes nothing permanent: the test application is cancelled at the end (its details are deleted) and no card is
ordered. Test mode must be on (Simulator page, or SIM_ACCEPT_ALL=true), because it opens with an invented ID number.
"""
import argparse, base64, json, os, random, ssl, sys, time, urllib.error, urllib.request, uuid

PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABMsehoQAAAABJRU5ErkJggg=="
results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail and not ok else ""))


class Kiosk:
    def __init__(self, url, code, token):
        self.url, self.code, self.token = url.rstrip("/"), code, token
        self.ctx = ssl.create_default_context(); self.ctx.check_hostname = False; self.ctx.verify_mode = ssl.CERT_NONE
        self.access = self.sid = self.ct = None

    def call(self, method, path, body=None, auth=None, idem=False):
        h = {"Content-Type": "application/json", "X-Request-Id": str(uuid.uuid4()), "Accept-Language": "en"}
        if auth: h["Authorization"] = "Bearer " + auth
        elif self.access: h["Authorization"] = "Bearer " + self.access; h["X-Device-Id"] = self.code
        if self.sid: h["X-Session-Id"] = self.sid
        if self.ct: h["X-Customer-Token"] = self.ct
        if idem: h["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(self.url + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=30) as r:
                t = r.read(); return r.status, (json.loads(t) if t else {})
        except urllib.error.HTTPError as e:
            t = e.read()
            try: return e.code, json.loads(t)
            except Exception: return e.code, {"raw": t.decode(errors="replace")[:200]}


def err(r):
    e = r.get("error") if isinstance(r, dict) else None
    return f"{e.get('code')}: {e.get('message')}" if e else json.dumps(r)[:200]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="https://10.8.0.1:8443")
    ap.add_argument("--kiosk", default="STM-SMOKE-TEST")
    ap.add_argument("--token", required=True, help="terminal token of the test kiosk (shown once when it was added)")
    ap.add_argument("--operator", help="a terminal user's username (Kiosk fleet > Terminal users), optional")
    ap.add_argument("--pin")
    a = ap.parse_args()
    k = Kiosk(a.url, a.kiosk, a.token)

    print("\n1. Kiosk sign-in and health report (D-1, D-6)")
    s, r = k.call("POST", "/stm/v1/auth/device/token", {"deviceId": a.kiosk, "softwareVersion": "smoke-test", "nonce": str(uuid.uuid4())}, auth=a.token)
    check("Kiosk signs in", s == 200, f"{s} {err(r)}")
    if s != 200:
        print("\nCannot continue without a working token. Check the kiosk ID and token, and that the kiosk is enabled."); sys.exit(1)
    k.access = r["accessToken"]
    mods = {"upperDisplay": "OK", "touchScreen": "OK", "passportScanner": "OK", "receiptPrinter": "PAPER_LOW", "cardReader": "OK",
            "pinPad": "OK", "fingerprint": "OK", "cashRecycler": "OK", "handset": "OK", "camera": "OK", "a4Printer": "OK",
            "cardPrinter": {"status": "OK", "blankStock": 120}}
    s, r = k.call("POST", f"/stm/v1/devices/{a.kiosk}/health", {"modules": mods, "ipAddress": "10.8.0.99", "model": "Smoke test"})
    check("Health report with IP address and model accepted", s == 200 and r.get("accepted") is True, f"{s} {err(r)}")

    print("\n2. Lists for Your details (B-8b, new)")
    s, r = k.call("GET", "/stm/v1/onboarding/kyc-options")
    codes = [o.get("code") for o in r.get("monthlyIncome", [])] if s == 200 else []
    check("kyc-options answers", s == 200, f"{s} {err(r)}")
    check("Has the 5 income bands", codes == ["UP_TO_10K", "10K_50K", "50K_100K", "100K_500K", "OVER_500K"], str(codes))
    check("Has source-of-funds choices with Kiswahili labels",
          s == 200 and any(o.get("code") == "SALARY" and o.get("labelSw") for o in r.get("sourceOfFunds", [])))

    print("\n3. Account opening with the new Your details pages (B-3 .. B-8)")
    s, r = k.call("POST", "/stm/v1/sessions", {"trigger": "TOUCH", "language": "en"})
    check("Session starts", s == 201, f"{s} {err(r)}"); k.sid = r.get("sessionId")
    s, t = k.call("GET", "/stm/v1/legal/terms?product=SAV_PERSONAL")
    s, r = k.call("POST", "/stm/v1/onboarding/applications", {"productCode": "SAV_PERSONAL", "termsId": t.get("termsId"),
                  "acceptedAt": time.strftime("%Y-%m-%dT%H:%M:%S+03:00"),
                  "consents": [{"code": c["code"], "accepted": True} for c in t.get("consents", [])]})
    check("Application created", s == 201, f"{s} {err(r)}"); app = r.get("applicationId")
    idno = str(random.randint(40000000, 49999999))
    s, d = k.call("POST", "/stm/v1/identity/documents/verify", {"applicationId": app, "documentType": "NATIONAL_ID", "country": "KE",
                  "ocr": {"documentNumber": idno, "surname": "SMOKE", "givenNames": "TEST CUSTOMER", "dateOfBirth": "1990-01-01"},
                  "images": {"front": PX}})
    check("ID check (needs test mode)", s == 200, f"{s} {err(d)}")
    if s != 200:
        print("\nTurn test mode on (portal > Simulator) and run again."); summary(); return
    s, b = k.call("POST", "/stm/v1/identity/biometrics/verify", {"applicationId": app, "verificationId": d["verificationId"],
                  "face": {"image": PX, "livenessScore": 0.93}, "fingerprint": {"finger": "RIGHT_THUMB", "template": PX, "quality": 77, "image": PX}})
    check("Face + fingerprint with fingerprint.image accepted", s == 200 and b.get("overall") == "PASS", f"{s} {err(b)}")
    k.call("POST", f"/stm/v1/onboarding/applications/{app}/identity-confirm", {"verificationId": d["verificationId"], "confirmed": True})
    P = f"/stm/v1/onboarding/applications/{app}/kyc"
    s, r = k.call("PATCH", P, {"mobile": "0712 000 111", "email": "smoke@example.com", "address": {"county": "Nairobi", "subCounty": "Westlands", "ward": "Parklands"}})
    check("Page 1 without KRA PIN is saved, not complete yet", s == 200 and r.get("complete") is False and "kraPin" in r.get("missing", []), f"{s} {err(r)}")
    s, r = k.call("PATCH", P, {"kraPin": "A0" + idno + "Z"})
    check("Page 1 completed with the KRA PIN", s == 200 and r.get("complete") is True, f"{s} {err(r)}")
    s, r = k.call("PATCH", P, {"address": {"line1": "Smoke Estate, House 1", "poBox": "1234-00100"}, "occupation": "Tester",
                  "employer": "Self-employed: Smoke Ltd", "sourceOfFunds": "SALARY", "monthlyIncome": "KES 50,001 - 100,000"})
    check("Page 2 (address, work and income) saved", s == 200 and r.get("complete") is True, f"{s} {err(r)}")
    s, r = k.call("PATCH", P, {"nextOfKin": {"fullName": "Next Of Kin", "relationship": "Sister", "mobile": "0722 000 222"}})
    check("Page 3 (next of kin) saved", s == 200, f"{s} {err(r)}")
    s, r = k.call("PATCH", P, {"monthlyIncome": "LOTS"})
    check("Unknown income band refused", s == 400, f"{s}")
    s, r = k.call("POST", f"/stm/v1/onboarding/applications/{app}/cancel", {"reason": "CUSTOMER_CANCELLED", "atStep": "KYC"})
    check("Test application cancelled (its details deleted)", s == 200, f"{s} {err(r)}")
    k.call("POST", f"/stm/v1/sessions/{k.sid}/end", {"reason": "CUSTOMER_FINISHED"}); k.sid = None

    print("\n4. Card services: verification and customer summary (B-5, C-0) with the test customer")
    s, r = k.call("POST", "/stm/v1/sessions", {"trigger": "TOUCH", "language": "en"}); k.sid = r.get("sessionId")
    s, lk = k.call("POST", "/stm/v1/customers/lookup", {"by": "ACCOUNT_NUMBER", "accountNumber": "0190000105"})
    check("Test customer found (account 0190000105)", s == 200, f"{s} {err(lk)}")
    if s == 200:
        s, o = k.call("POST", "/stm/v1/otp/send", {"purpose": "CARD_SERVICES", "lookupRef": lk["lookupRef"]})
        s, v = k.call("POST", "/stm/v1/otp/verify", {"otpRef": o.get("otpRef"), "code": "123456"})
        check("Phone code accepted", s == 200, f"{s} {err(v)}")
        check("Lookup says which finger to scan (an enrolled one)",
              lk.get("fingerToScan") in (lk.get("enrolledFingers") or []), f"fingerToScan={lk.get('fingerToScan')} enrolled={lk.get('enrolledFingers')}")
        check("Lookup says the bank holds a face photo", lk.get("bankFaceOnFile") is True, str(lk.get("bankFaceOnFile")))
        s, b = k.call("POST", "/stm/v1/identity/biometrics/verify", {"customerId": lk["customerId"], "lookupRef": lk["lookupRef"],
                      "face": {"image": PX, "livenessScore": 0.9},
                      "fingerprint": {"finger": lk.get("fingerToScan") or "RIGHT_THUMB", "template": PX, "quality": 80}})
        check("Face + fingerprint pass for an existing customer", s == 200 and b.get("overall") == "PASS", f"{s} {err(b)}")
        check("Fingerprint matched against the bank's print", b.get("fingerprint", {}).get("against") == "BANK", str(b.get("fingerprint")))
        check("Face matched against the bank's photo", b.get("face", {}).get("against") == "BANK_PHOTO", str(b.get("face")))
        k.ct = b.get("customerToken")
        s, c = k.call("GET", f"/stm/v1/customers/{lk['customerId']}")
        check("Customer summary answers (new)", s == 200, f"{s} {err(c)}")
        if s == 200:
            accts = {x["accountNumber"]: x for x in c.get("accounts", [])}
            cards = {x["cardId"]: x for x in c.get("cards", [])}
            check("KYC block present", c.get("kyc", {}).get("status") in ("COMPLETE", "INCOMPLETE", "EXPIRED"))
            check("Account without a card can get one (newCardAllowed)", accts.get("0190000105", {}).get("newCardAllowed") is True)
            check("allowedCardProducts filled", bool(accts.get("0190000101", {}).get("allowedCardProducts")))
            check("Expiring card offers RENEW", "RENEW" in cards.get("crd_T002", {}).get("actions", []))
            check("Fraud card shows FRAUD_BLOCKED", cards.get("crd_T003", {}).get("status") == "FRAUD_BLOCKED")
            check("Not-yet-active card shows INACTIVE", cards.get("crd_T004", {}).get("status") == "INACTIVE")
            acc = accts.get("0190000105", {})
            s, q = k.call("POST", "/stm/v1/cards/fee-quotes", {"customerId": lk["customerId"], "accountId": acc.get("accountId"),
                          "journey": "NEW", "tier": "CLASSIC"})
            ok = s == 200 and q.get("total") and round(float(q["amount"]) + float(q["tax"]), 2) == float(q["total"]) and q.get("expiresAt")
            check("Card fee quote (POST /cards/fee-quotes): amount + tax = total", bool(ok), f"{s} {err(q)}")
            if s == 200:
                s, d = k.call("POST", f"/stm/v1/accounts/{acc.get('accountId')}/debit-check",
                              {"amount": q["total"], "currency": q["currency"], "quoteId": q["quoteId"]})
                check("Funds check (POST /accounts/{id}/debit-check) answers canDebit only", s == 200 and "canDebit" in d
                      and "available" not in d and "balance" not in d, f"{s} {err(d)}")
        k.ct = None
    k.call("POST", f"/stm/v1/sessions/{k.sid}/end", {"reason": "CUSTOMER_FINISHED"}); k.sid = None

    print("\n5. Maintenance-screen sign-in (O-1, O-2)")
    if a.operator and a.pin:
        s, r = k.call("POST", "/stm/v1/operators/login", {"username": a.operator, "pin": a.pin})
        check("Terminal user signs in", s == 200 and r.get("operatorRef"), f"{s} {err(r)}")
        if s == 200:
            s, r2 = k.call("POST", "/stm/v1/operators/logout", {"operatorRef": r["operatorRef"], "summary": "smoke test"})
            check("Terminal user signs out", s == 200, f"{s} {err(r2)}")
    else:
        s, r = k.call("POST", "/stm/v1/operators/login", {"username": "nobody-smoke", "pin": "000000"})
        check("Sign-in endpoint answers (wrong user refused)", s == 401 and err(r).startswith("OPERATOR_INVALID"), f"{s} {err(r)}")
        print("        (add --operator and --pin of a terminal user assigned to this kiosk to test a real sign-in)")
    teller_call(k)
    fingerprint_matcher(k)
    summary()


def teller_call(k):
    """Places a teller call and hangs up: works whether or not a teller is signed in."""
    print("\n6. Talk to a teller (D-8, D-9): status with the customer's message, hang up")
    s, r = k.call("POST", "/stm/v1/sessions", {"trigger": "TOUCH", "language": "en"}); k.sid = r.get("sessionId")
    s, c = k.call("POST", "/stm/v1/support/teller-calls", {"screen": "SMOKE", "language": "en"})
    check("Call placed (D-8) with a message for the customer", s == 201 and c.get("message"), f"{s} {err(c)}")
    if s == 201:
        s, g = k.call("GET", f"/stm/v1/support/teller-calls/{c['callId']}")
        check("Status (D-9) answers", s == 200 and g.get("status") in ("QUEUED", "RINGING", "CONNECTED", "NO_TELLER_AVAILABLE"),
              f"{s} {err(g)}")
        s, d = k.call("DELETE", f"/stm/v1/support/teller-calls/{c['callId']}")
        check("Hang up (D-9 DELETE)", s == 200 and d.get("status") in ("CANCELLED", "ENDED", "NO_TELLER_AVAILABLE"), f"{s} {err(d)}")
    k.call("POST", f"/stm/v1/sessions/{k.sid}/end", {"reason": "CUSTOMER_FINISHED"}); k.sid = None


def fingerprint_matcher(k):
    """Opens a real test account with a fingerprint image, then checks that card services refuses a different finger
    and accepts the same finger (real comparison, in test mode too). Leaves one test customer "Test Customer Smoke"."""
    data = os.path.join(os.path.dirname(os.path.abspath(__file__)), "smoke-data")
    if not os.path.isdir(data):
        print("\n7. Fingerprint matcher: skipped (smoke-data folder not found next to this script)"); return
    img = lambda f: base64.b64encode(open(os.path.join(data, f), "rb").read()).decode()
    print("\n7. Fingerprint matcher: a different finger is refused, the same finger passes (B-5)")
    s, r = k.call("POST", "/stm/v1/sessions", {"trigger": "TOUCH", "language": "en"}); k.sid = r.get("sessionId")
    s, t = k.call("GET", "/stm/v1/legal/terms?product=SAV_PERSONAL")
    s, r = k.call("POST", "/stm/v1/onboarding/applications", {"productCode": "SAV_PERSONAL", "termsId": t.get("termsId"),
                  "acceptedAt": time.strftime("%Y-%m-%dT%H:%M:%S+03:00"),
                  "consents": [{"code": c["code"], "accepted": True} for c in t.get("consents", [])]})
    app = r.get("applicationId"); idno = str(random.randint(40000000, 49999999))
    s, d = k.call("POST", "/stm/v1/identity/documents/verify", {"applicationId": app, "documentType": "NATIONAL_ID", "country": "KE",
                  "ocr": {"documentNumber": idno, "surname": "SMOKE", "givenNames": "TEST CUSTOMER", "dateOfBirth": "1990-01-01"},
                  "images": {"front": PX}})
    if s != 200:
        check("Test account opened (needs test mode)", False, f"{s} {err(d)}"); return
    s, b = k.call("POST", "/stm/v1/identity/biometrics/verify", {"applicationId": app, "verificationId": d["verificationId"],
                  "face": {"image": PX, "livenessScore": 0.93},
                  "fingerprint": {"finger": "RIGHT_THUMB", "template": PX, "quality": 80, "image": img("probe.png")}})
    k.call("POST", f"/stm/v1/onboarding/applications/{app}/identity-confirm", {"verificationId": d["verificationId"], "confirmed": True})
    k.call("PATCH", f"/stm/v1/onboarding/applications/{app}/kyc", {"kraPin": "A0" + idno + "Z", "mobile": "0712 000 333",
           "address": {"line1": "Smoke Estate", "town": "Nairobi", "county": "Nairobi"}})
    s, o = k.call("POST", "/stm/v1/otp/send", {"purpose": "ONBOARDING_PHONE", "applicationId": app, "channel": "SMS"})
    k.call("POST", "/stm/v1/otp/verify", {"otpRef": o.get("otpRef"), "code": "123456"})
    k.call("POST", f"/stm/v1/onboarding/applications/{app}/signature", {"image": img("signature.png"), "strokes": 9, "capturedWith": "STYLUS"})
    s, a = k.call("POST", f"/stm/v1/onboarding/applications/{app}/submit", {}, idem=True)
    acct = (a.get("account") or {}).get("accountNumber")
    check("Test account opened with a fingerprint image", s == 201 and acct, f"{s} {err(a)}")
    k.call("POST", f"/stm/v1/sessions/{k.sid}/end", {"reason": "CUSTOMER_FINISHED"}); k.sid = None
    if not acct: return
    s, r = k.call("POST", "/stm/v1/sessions", {"trigger": "TOUCH", "language": "en"}); k.sid = r.get("sessionId")
    s, lk = k.call("POST", "/stm/v1/customers/lookup", {"by": "ACCOUNT_NUMBER", "accountNumber": acct})
    s, o = k.call("POST", "/stm/v1/otp/send", {"purpose": "CARD_SERVICES", "lookupRef": lk.get("lookupRef")})
    k.call("POST", "/stm/v1/otp/verify", {"otpRef": o.get("otpRef"), "code": "123456"})
    body = lambda f: {"customerId": lk["customerId"], "lookupRef": lk["lookupRef"], "face": {"image": PX, "livenessScore": 0.93},
                      "fingerprint": {"finger": "RIGHT_THUMB", "template": PX, "quality": 80, "image": img(f)}}
    s, b = k.call("POST", "/stm/v1/identity/biometrics/verify", body("nonmatching.png"))
    check("A different finger is refused", s == 422 and err(b).startswith("BIOMETRIC_NO_MATCH"), f"{s} {err(b)}")
    s, b = k.call("POST", "/stm/v1/identity/biometrics/verify", body("matching.png"))
    fp = b.get("fingerprint", {})
    check("The same finger passes (prints really compared)", s == 200 and fp.get("method") == "MATCHER", f"{s} {err(b)} {fp}")
    if fp.get("matchScore") is not None: print(f"        match score {fp['matchScore']} (pass at 40)")
    k.call("POST", f"/stm/v1/sessions/{k.sid}/end", {"reason": "CUSTOMER_FINISHED"}); k.sid = None
    print("        (remove the test customer later: delete-test-customer.sql with -v name=smoke)")


def summary():
    ok = sum(1 for _, x in results if x)
    print(f"\n{ok} of {len(results)} checks passed." + ("" if ok == len(results) else "  Failed: " + ", ".join(n for n, x in results if not x)))
    sys.exit(0 if ok == len(results) else 1)


if __name__ == "__main__":
    main()
