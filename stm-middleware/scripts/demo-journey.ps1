<#
  Walks the Smart Teller customer journeys against a running middleware (demo profile), the way the kiosk calls the
  Smart Teller Gateway (/stm/v1). Each run registers a fresh demo kiosk at branch 012 Moi Avenue.

  Usage:  .\scripts\demo-journey.ps1                         # every flow below, in order
          .\scripts\demo-journey.ps1 -Flow AccountOpening    # Amara Kamau opens an account and gets a card (once per database)
          .\scripts\demo-journey.ps1 -Flow Referral          # Wanjiku: PEP hit -> REFERRED -> the portal clears it -> account opened
          .\scripts\demo-journey.ps1 -Flow FaceFail          # Juma: face never matches -> the portal confirms identity
          .\scripts\demo-journey.ps1 -Flow CardServices      # Achieng: account number + OTP + fingerprint -> new card on her current account
          .\scripts\demo-journey.ps1 -Flow Withdrawal        # Achieng: card + PIN 2580 -> balance -> KES 5,000 -> receipt
          .\scripts\demo-journey.ps1 -Flow Deposit           # anyone: KES 12,000 into account 0123457780, 3 notes returned
          .\scripts\demo-journey.ps1 -Flow Forex             # visitor passport P12345678: USD 200 -> KES cash
          .\scripts\demo-journey.ps1 -Flow Statement         # Achieng: mini statement (fee KES 30) and a 3-month PDF
          .\scripts\demo-journey.ps1 -Flow TellerCall        # "Talk to a teller" (needs call-control on 8090)

  Every OTP is 123456 in the demo profile (stm.sim.fixed-otp). Watch the portal while it runs: Customer sessions,
  Applications, Cash & forex, Reconciliation.
  Works in Windows PowerShell 5.1 and PowerShell 7.
#>
param(
    [string]$Base = "http://localhost:8080",
    [ValidateSet("All", "AccountOpening", "Referral", "FaceFail", "CardServices", "CardCare", "ExistingCustomer", "Withdrawal", "Deposit", "Forex", "Statement", "TellerCall")]
    [string]$Flow = "All",
    [string]$PortalKey = "dev-portal-key",
    [string]$ProvisioningSecret = "dev-provisioning-secret",
    [ValidateSet("en", "sw")]
    [string]$Language = "en"
)
$ErrorActionPreference = "Stop"
$png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABMsehoQAAAABJRU5ErkJggg=="
# A small signature drawing: the gateway refuses an empty or scribbled-over signature pad (SIGNATURE_UNREADABLE).
$sig = "iVBORw0KGgoAAAANSUhEUgAAAUAAAAB4CAAAAACmpXQCAAAEGklEQVR42u3cC67jIAwFUBZx979VZjQatU3BYBs7Ic1Fmk/1ntrklI8xhFJZlkohAQEJSEACshCQgAQkIAsBCUhAArIQkIAEJCALAQlIQAKyEJCABCTg+QV/CwHX/Ai4CggCrvndQ5CAPwr4/y8C+ofgm1TBpwAe3y2wgyi7tuDYNvzVpwZ2sY8CRPfVxoC+y0Q8ILqA2B0Qy4AIBsT7A+4DCGcLDqyCx1EJiAyTyn7hcBZg/x8Cmkalj7Z7KaAKxhnNIQ2w7QqvAlTVLPgBg5OCh6/k+OJSQGg7Hi9gTQKsVwNCC+i5TKQATv9/OuD0071dTfgdym9zJeC8ZnmHgt0AZ/d5CiD8gMgFRF2NN8pKWJoA2KSdQsIpN+C8ty+rcb1wK8fRLgkQ2njK/JNswOZWmw9aAKz7ASID8Ctgg1h3TG1YrMeXACryNsUflg4TbE5AWACVaalVwJoPiPZWnNHIN0cAIIaAUNgPf60sBAVfA604TbIBquuIcq44eQvzcBkLCOGDpAHFGnF89xLW2wsBrNmAnVlbICAGgDUdcHoDK4BN05VSeRbASSpvHA3cARBdQHmAtiwToQuIJhU6/LA4QFVqtyxNLNGuFw4AB1EbIISsh0qO7kRZEeucDDi7IDlQl1rVK+AQbkf0+8hcQBzb9dfrvdXBtxAOCAlQNqoYA75/0s+23hiwt+ek+3kvhDESJrPRfqS51koXFxeKvdvFdO4l9GVS1IaZQK8zygdEFuDa5FEAVC4B9vv1NEDFJQYDzvM/cqjiXzTOB6xWQJwI6Ep3QZn6hzZSiQSs64CWL92VsRYBYV4UUO9pPQWwngc4yDkgBrDGA6q3dFTvOK5rw8K0uO1BTIAYDLzSZZboVQRzxsAOCDlzDWlupB1qxfB8a0DXZkmpZ0S17H+BtLLTaygwAPqXopMB++P8YWixrAh2FnYwS00qsjGpu0mSANEboi1rdu+Mxzg1uQMgFip1N1ASAC2Lns4NwEXXtYbu9jEFaspACd0pswPQujG0aNL2hus5AVBOSPQwzKu0sO3tLrpNsVsB6h5FMgJCjiVDAJG1Xy9qJ3kvMaliSAH86kRin6yo8G6cSXngs/1V09MZKsDoB1Djt0H7tiFL1pYbLapNDdBfz0aASoe1J+d0gMGnGJwAWE2ACAesnSXzOwImPhM5B0Q7UQx//j7xYAT9BacBImHHfNbzhCvzzqW6UXIq9g6A9dpTO1KPvgl+YnLPY09wQvPCLwPWcw7mIOAuJ5s8DhAEfMrxWFueXLQP4GvG4Jvul988IvADBJMX962BcJwGoJV5wPF3AAzV5N+f5/SBOplfGEIsgMEN6HGAeO5Z2zwKnoAEJCALAQlIQAKyEJCABCQgCwEJSEACshCQgAQkIAFJsFb+AObKsxxI8q37AAAAAElFTkSuQmCC"
$script:session = $null
$script:customer = $null

# ------------------------------------------------------------------------------------------------ plumbing

class StmError : System.Exception {
    [int]$Status; [string]$Code; [object]$Details
    StmError([int]$s, [string]$c, [string]$m, [object]$d) : base("$c ($s): $m") { $this.Status = $s; $this.Code = $c; $this.Details = $d }
}

function Invoke-Json($Method, $Uri, $Headers, $Body) {
    $p = @{ Method = $Method; Uri = $Uri; Headers = $Headers }
    if ($null -ne $Body) { $p.ContentType = "application/json; charset=utf-8"; $p.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 8 -Compress)) }
    try {
        return Invoke-RestMethod @p
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $err = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { try { $err = ($_.ErrorDetails.Message | ConvertFrom-Json).error } catch { } }
        if ($err) { throw [StmError]::new($status, $err.code, $err.message, $err.details) }
        throw
    }
}

# Gateway call with the kiosk headers. -Idem for calls that move money, issue a card or charge a fee.
function Gw($Method, $Path, $Body = $null, [string]$Idem = $null, [switch]$Customer) {
    $h = @{
        Authorization     = "Bearer $script:token"
        "X-Device-Id"     = $script:device
        "X-Request-Id"    = [guid]::NewGuid().ToString()
        "Accept-Language" = $Language
    }
    if ($script:session) { $h["X-Session-Id"] = $script:session }
    if ($Customer -and $script:customer) { $h["X-Customer-Token"] = $script:customer }
    if ($Idem) { $h["Idempotency-Key"] = $Idem }
    Invoke-Json $Method "$Base/stm/v1$Path" $h $Body
}
function Portal($Method, $Path, $Body = $null) { Invoke-Json $Method "$Base/api/v1/portal$Path" @{ "X-Portal-Key" = $PortalKey } $Body }
function Key { [guid]::NewGuid().ToString() }
function Kes($n) { @{ amount = ("{0:0.00}" -f $n); currency = "KES" } }
function PinBlock([string]$pin) { ("0" + $pin.Length.ToString("X") + $pin).PadRight(16, "F") }   # DEV ONLY: the PIN pad does this
function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Say($text) { Write-Host "   $text" }
function Ok($text) { Write-Host "   OK  $text" -ForegroundColor Green }
function Warn($text) { Write-Host "   !!  $text" -ForegroundColor Yellow }
function Mix($mix) { ($mix | ForEach-Object { "$($_.count) x $($_.denomination)" }) -join ", " }

function Start-Session([string]$Trigger = "TOUCH") {
    $script:customer = $null
    $script:session = $null
    $s = Gw Post "/sessions" @{ trigger = $Trigger; language = $Language }
    $script:session = $s.sessionId
    $on = ($s.services.PSObject.Properties | Where-Object { $_.Value } | ForEach-Object { $_.Name }) -join ", "
    Say "D-2 session $($s.sessionId) - home tiles: $on"
}
function End-Session([string]$Reason = "CUSTOMER_FINISHED") {
    if (-not $script:session) { return }
    $e = Gw Post "/sessions/$script:session/end" @{ reason = $Reason; cardRetained = $false }
    if ($e.openItems.Count) { Warn "open items: $(($e.openItems | ForEach-Object { "$($_.type) $($_.id) -> $($_.action)" }) -join '; ')" }
    Say "D-4 session ended"
    $script:session = $null
    $script:customer = $null
}
function Card-Login([string]$PanToken, [string]$Pin) {
    $c = Gw Post "/auth/card" @{ entryMode = "EMV_CHIP"; panToken = $PanToken; emv = @{ aid = "A0000000031010" } }
    $p = Gw Post "/auth/card/pin-verify" @{ cardAuthRef = $c.cardAuthRef; pinBlock = (PinBlock $Pin); format = "ISO_9564_FORMAT_4" }
    $script:customer = $p.customerToken
    Ok "S-1/S-2 card $($c.maskedPan), PIN verified - welcome $($p.customerFirstName)"
    return $p
}
function Consents($terms) { @($terms.consents | ForEach-Object { @{ code = $_.code; accepted = $true } }) }

function Open-Application([string]$IdNumber, [string]$Surname, [string]$Dob) {
    $prod = (Gw Get "/products").products[0]
    $terms = Gw Get "/legal/terms?product=$($prod.code)"
    $app = Gw Post "/onboarding/applications" @{ productCode = $prod.code; termsId = $terms.termsId; consents = (Consents $terms) }
    Say "B-1..B-3 $($prod.name): terms $($terms.termsId) accepted, application $($app.applicationId)"
    $doc = Gw Post "/identity/documents/verify" @{ applicationId = $app.applicationId; documentType = "NATIONAL_ID"; country = "KE"
        ocr = @{ documentNumber = $IdNumber; surname = $Surname; dateOfBirth = $Dob }; images = @{ front = $png; back = $png } }
    Say "B-4 ID matched IPRS: $($doc.registryRecord.fullName), born $($doc.registryRecord.dateOfBirth)"
    return @{ app = $app.applicationId; idv = $doc.verificationId; name = $doc.registryRecord.fullName }
}
function Biometrics($a) {
    Gw Post "/identity/biometrics/verify" @{ applicationId = $a.app; verificationId = $a.idv
        face = @{ image = $png; livenessScore = 0.97; livenessVendor = "sdk" }; fingerprint = @{ finger = "RIGHT_THUMB"; template = $png; quality = 82 } }
}
function Finish-Application($a, [string]$Kra, [string]$Mobile, [string]$NameOnCard) {
    Gw Post "/onboarding/applications/$($a.app)/identity-confirm" @{ verificationId = $a.idv; confirmed = $true } | Out-Null
    $k = Gw Post "/kyc/kra-pin/validate" @{ kraPin = $Kra; idNumber = $a.id }
    Say "B-6/B-7 identity confirmed, KRA PIN $Kra $($k.status)"
    $kyc = Gw Patch "/onboarding/applications/$($a.app)/kyc" @{ kraPin = $Kra; mobile = $Mobile; address = @{ line1 = "Moi Avenue"; town = "Nairobi"; county = "Nairobi" } }
    Say "B-8 details captured: PEP $($kyc.screening.pep), sanctions $($kyc.screening.sanctions) -> $($kyc.status)"
    return $kyc
}
function Phone-Sign-Submit($a, [string]$NameOnCard) {
    $otp = Gw Post "/otp/send" @{ purpose = "ONBOARDING_PHONE"; applicationId = $a.app; channel = "SMS" }
    Gw Post "/otp/verify" @{ otpRef = $otp.otpRef; code = "123456" } | Out-Null
    Say "B-9/B-10 code sent to $($otp.maskedMobile) and verified"
    Gw Post "/onboarding/applications/$($a.app)/signature" @{ image = $sig; strokes = 14; capturedWith = "STYLUS" } | Out-Null
    $acc = Gw Post "/onboarding/applications/$($a.app)/submit" @{ cardProduct = "CLASSIC"; nameOnCard = $NameOnCard } -Idem (Key)
    $script:customer = $acc.customerToken
    Ok "B-11/B-12 signed, account $($acc.account.display) opened for $($acc.customerId) ($($acc.reference))"
    return $acc
}
function Issue-Card([hashtable]$Order, [string]$Pin) {
    $o = Gw Post "/cards/orders" $Order -Idem (Key) -Customer
    $fee = if ($o.fee.waived) { "fee waived" } else { "fee KES $($o.fee.amount)" }
    Say "C-5 card $($o.maskedPan) ordered, $fee - printing..."
    do { Start-Sleep -Milliseconds 1500; $p = Gw Get "/cards/orders/$($o.orderId)"
         Say ("C-6 " + (($p.steps | ForEach-Object { "$($_.code)=$($_.status)" }) -join " ")) } while ($p.status -ne "READY")
    Gw Post "/cards/orders/$($o.orderId)/dispense" @{ outcome = "TAKEN"; qualityCheck = "PASS" } | Out-Null
    Gw Post "/cards/$($o.cardId)/pin" @{ pinBlock = (PinBlock $Pin); format = "ISO_9564_FORMAT_4"; confirmed = $true } -Customer | Out-Null
    $act = Gw Post "/cards/$($o.cardId)/activate" @{ channel = "STM" } -Customer
    Ok "C-7..C-9 card taken, PIN set, active on $($act.linkedAccount) (ATM limit KES $($act.limits.atmDaily.amount))"
    $r = Gw Post "/receipts" @{ transactionRef = $o.reference; delivery = "PRINT" }
    Say "R-1 receipt: $($r.lines[3])"
}

# ------------------------------------------------------------------------------------------------ flows

function Flow-AccountOpening {
    Step "Open an account: Amara Njeri Kamau (ID 29876543)"
    Start-Session
    try {
        $a = Open-Application "29876543" "KAMAU" "1996-07-22"; $a.id = "29876543"
        $b = Biometrics $a; Say "B-5 face $($b.face.result) ($($b.face.score)), fingerprint $($b.fingerprint.result)"
        Finish-Application $a "A029876543K" "0712 345 678" | Out-Null
        $acc = Phone-Sign-Submit $a "AMARA N KAMAU"
        Issue-Card @{ service = "LINK_AND_ISSUE"; accountId = $acc.account.accountId; cardProduct = "CLASSIC"; nameOnCard = "AMARA N KAMAU"; applicationId = $a.app } "4826"
    } catch [StmError] {
        if ($_.Exception.Code -eq "EXISTING_CUSTOMER") { Warn "Amara already has an account in this database. Reset the database to run this flow again." }
        else { throw }
    }
    End-Session
}

function Flow-Referral {
    Step "Screening referral: Wanjiku Mary Ndegwa (ID 30222333) - PEP hit, a teller decides in the portal"
    Start-Session
    try {
        $a = Open-Application "30222333" "NDEGWA" "1985-11-30"; $a.id = "30222333"
        Biometrics $a | Out-Null
        $kyc = Finish-Application $a "A030222333N" "0722 000 111"
        if ($kyc.status -ne "REFERRED") { throw "expected REFERRED, got $($kyc.status)" }
        Warn "kiosk shows 'We need a moment' - Applications page in the portal shows Wanjiku with 'Needs teller'"
        Start-Sleep -Seconds 2
        $d = Portal Post "/applications/$($a.app)/decision" @{ decision = "CLEAR_REFERRAL"; notes = "PEP name match reviewed: different person (demo script)" }
        Ok "portal: referral cleared by $($d.decidedBy)"
        $v = Gw Get "/onboarding/applications/$($a.app)"
        Say "B-13 kiosk polls: $($v.status), next step $($v.nextStep)"
        Phone-Sign-Submit $a "WANJIKU M NDEGWA" | Out-Null
    } catch [StmError] {
        if ($_.Exception.Code -eq "EXISTING_CUSTOMER") { Warn "Wanjiku already has an account in this database. Reset the database to run this flow again." }
        else { throw }
    }
    End-Session
}

function Flow-FaceFail {
    Step "Face does not match: Juma Hassan Mwangi (ID 30111222) - three tries, then a teller confirms on video"
    Start-Session
    $a = Open-Application "30111222" "MWANGI" "1990-01-09"; $a.id = "30111222"
    for ($i = 1; $i -le 3; $i++) {
        try { Biometrics $a | Out-Null } catch [StmError] { Warn "B-5 try $i -> $($_.Exception.Code), attempts left $($_.Exception.Details.attemptsLeft)" }
    }
    Warn "kiosk offers 'Talk to a teller' - Applications page shows Juma with 'Face did not match'"
    $d = Portal Post "/applications/$($a.app)/decision" @{ decision = "VERIFY_IDENTITY"; notes = "Checked face against registry photo on video (demo script)" }
    Ok "portal: identity confirmed by $($d.decidedBy) -> $($d.status)"
    Gw Post "/onboarding/applications/$($a.app)/cancel" @{ reason = "CUSTOMER_CANCELLED"; atStep = "KYC" } | Out-Null
    Say "B-14 (demo stops here and cancels, so the flow can be repeated)"
    End-Session "CUSTOMER_CANCELLED"
}

function Flow-CardServices {
    Step "Card services without a card: Achieng (account 0123454521) gets a Gold card on her current account"
    Start-Session
    $lk = Gw Post "/customers/lookup" @{ by = "ACCOUNT_NUMBER"; accountNumber = "0123454521" }
    Say "C-1 found $($lk.displayName), $($lk.accountsFound) accounts, verify with $($lk.verificationRequired -join ' + ')"
    $otp = Gw Post "/otp/send" @{ purpose = "CARD_SERVICES"; lookupRef = $lk.lookupRef; channel = "SMS" }
    Gw Post "/otp/verify" @{ otpRef = $otp.otpRef; code = "123456" } | Out-Null
    $b = Gw Post "/identity/biometrics/verify" @{ customerId = $lk.customerId; lookupRef = $lk.lookupRef; fingerprint = @{ finger = "RIGHT_THUMB"; template = $png; quality = 80 } }
    $script:customer = $b.customerToken
    Ok "B-9/B-10 OTP and B-5 fingerprint $($b.fingerprint.result): customer token issued"
    (Gw Get "/customers/$($lk.customerId)/cards" -Customer).cards | ForEach-Object { Say "C-2 card $($_.maskedPan) $($_.product) $($_.status)" }
    $accs = (Gw Get "/customers/$($lk.customerId)/accounts?eligibleFor=CARD" -Customer).accounts
    $accs | ForEach-Object { Say ("C-3 {0} {1} KES {2} {3}" -f $_.type, $_.masked, $_.available.amount, $(if ($_.eligible) { "eligible" } else { "- $($_.reason)" })) }
    $target = $accs | Where-Object { $_.eligible } | Select-Object -First 1
    if (-not $target) { Warn "no account without a card left (run against a fresh database)"; End-Session; return }
    $q = Gw Post "/fees/quote" @{ service = "CARD_ISSUE"; cardProduct = "GOLD"; debitAccountId = $target.accountId } -Customer
    Say "C-4 fee: $(($q.lines | ForEach-Object { "$($_.label) $($_.amount)" }) -join ' + ') = KES $($q.total.amount)"
    Issue-Card @{ service = "LINK_AND_ISSUE"; accountId = $target.accountId; cardProduct = "GOLD"; nameOnCard = "ACHIENG W OTIENO"; feeQuoteId = $q.quoteId; customerAuthorised = $true } "7391"
    End-Session
}

# Code to the phone + thumbprint (C-1, B-9, B-10, B-5): the strong check that PIN reset, unblock, reactivation,
# enrolment and signature changes need.
function Verify-Customer([string]$IdNumber) {
    $lk = Gw Post "/customers/lookup" @{ by = "ID_DOCUMENT"; documentType = "NATIONAL_ID"; documentNumber = $IdNumber }
    $otp = Gw Post "/otp/send" @{ purpose = "CARD_SERVICES"; lookupRef = $lk.lookupRef; channel = "SMS" }
    Gw Post "/otp/verify" @{ otpRef = $otp.otpRef; code = "123456" } | Out-Null
    $b = Gw Post "/identity/biometrics/verify" @{ customerId = $lk.customerId; lookupRef = $lk.lookupRef; fingerprint = @{ finger = "RIGHT_THUMB"; template = $png; quality = 80 } }
    $script:customer = $b.customerToken
    Ok "C-1 $($lk.displayName): code + thumbprint matched against $($b.fingerprint.against)"
    return $lk.customerId
}

function Flow-CardCare {
    Step "Looking after cards: Mary (ID 25567890) has five cards in different states"
    Start-Session
    $cif = Verify-Customer "25567890"
    $cards = (Gw Get "/customers/$cif/cards" -Customer).cards
    $cards | ForEach-Object { Say ("P-1 {0} {1}: {2} -> {3}" -f $_.maskedPan, $_.product, $_.statusText, ($_.actions -join ", ")) }
    $frozen = $cards | Where-Object { $_.status -eq "TEMP_BLOCKED" } | Select-Object -First 1
    if ($frozen) {
        $u = Gw Post "/cards/$($frozen.cardId)/unblock" $null -Customer
        Ok "P-5 unblocked $($u.maskedPan) -> $($u.status) ($($u.reference))"
        $t = Gw Post "/cards/$($frozen.cardId)/temporary-block" @{ reason = "MISPLACED" } -Customer
        Ok "P-4 blocked it temporarily again -> $($t.status)"
    }
    $damaged = $cards | Where-Object { $_.status -eq "BLOCKED" } | Select-Object -First 1
    if ($damaged) {
        try { Gw Post "/cards/$($damaged.cardId)/unblock" $null -Customer | Out-Null } catch [StmError] { Warn "P-5 $($damaged.maskedPan): $($_.Exception.Code) -> $($_.Exception.Details.nextStep)" }
    }
    $locked = $cards | Where-Object { $_.status -eq "PIN_LOCKED" } | Select-Object -First 1
    if ($locked) {
        $r = Gw Post "/cards/$($locked.cardId)/pin/reset" @{ newPinBlock = (PinBlock "8642"); format = "ISO_9564_FORMAT_4"; confirmed = $true } -Customer
        Ok "P-3 PIN reset on locked card $($r.maskedPan) -> $($r.status)"
    }
    $active = $cards | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
    try { Gw Post "/cards/$($active.cardId)/pin/change" @{ currentPinBlock = (PinBlock "0000"); newPinBlock = (PinBlock "9731"); confirmed = $true } -Customer | Out-Null }
    catch [StmError] { Warn "P-2 wrong current PIN: $($_.Exception.Code), $($_.Exception.Details.attemptsLeft) attempts left" }
    foreach ($pin in @("2468", "9731")) {      # 2468 on a fresh database, 9731 after an earlier run
        try {
            $new = if ($pin -eq "2468") { "9731" } else { "2468" }
            $c = Gw Post "/cards/$($active.cardId)/pin/change" @{ currentPinBlock = (PinBlock $pin); newPinBlock = (PinBlock $new); confirmed = $true } -Customer
            Ok "P-2 PIN changed on $($c.maskedPan) ($($c.reference))"; break
        } catch [StmError] { if ($_.Exception.Code -ne "PIN_INCORRECT") { throw } }
    }
    End-Session
}

function Flow-ExistingCustomer {
    Step "A customer from before the Smart Teller: Mary has a dormant account, no fingerprints and no signature on file"
    Start-Session
    $cif = Verify-Customer "25567890"
    $p = Gw Get "/customers/$cif/profile" -Customer
    Say "M-1 fingerprints enrolled: $($p.biometrics.enrolled), signature on file: $($p.signature.onFile), dormant accounts: $($p.accounts.dormant)"
    Say "M-1 the kiosk should offer: $($p.suggestions -join ', ')"
    (Gw Get "/customers/$cif/accounts" -Customer).accounts | ForEach-Object { Say ("    {0} {1} {2} {3}" -f $_.type, $_.masked, $_.status, $_.nextStep) }
    $dormant = (Gw Get "/customers/$cif/accounts" -Customer).accounts | Where-Object { $_.status -eq "DORMANT" } | Select-Object -First 1
    if ($dormant) {
        $r = Gw Post "/accounts/$($dormant.accountId)/reactivate" @{ confirmed = $true } -Customer
        Ok "M-2 $($r.masked) reactivated -> $($r.status) ($($r.reference))"
    } else { Say "M-2 no dormant account left (already reactivated in an earlier run)" }
    $frozen = (Gw Get "/customers/$cif/accounts" -Customer).accounts | Where-Object { $_.status -eq "FROZEN" } | Select-Object -First 1
    if ($frozen) { try { Gw Post "/accounts/$($frozen.accountId)/reactivate" @{ confirmed = $true } -Customer | Out-Null } catch [StmError] { Warn "M-2 frozen $($frozen.masked): $($_.Exception.Code) -> teller" } }
    $e = Gw Post "/customers/$cif/biometrics/enrol" @{ consent = $true; fingerprints = @(@{ finger = "RIGHT_THUMB"; template = $png; quality = 85 }, @{ finger = "LEFT_THUMB"; template = $png; quality = 78 }) } -Customer
    Ok "M-3 fingerprints enrolled with the bank: $($e.fingers -join ', ')"
    try { Gw Post "/customers/$cif/signature" @{ image = $png; strokes = 2; capturedWith = "STYLUS" } -Customer | Out-Null } catch [StmError] { Warn "M-7 empty signature pad: $($_.Exception.Code)" }
    $s = Gw Post "/customers/$cif/signature" @{ image = $sig; strokes = 9; capturedWith = "STYLUS" } -Customer
    Ok "M-7 specimen signature $(if ($s.replacedPrevious) { 'replaced' } else { 'registered' }) ($($s.reference))"
    Say "M-4/M-5 change of phone number is not run here: it puts a 24-hour hold on the customer (see docs/CARD-AND-CUSTOMER-SERVICES.md)"
    End-Session
}

function Flow-Withdrawal {
    Step "Cash withdrawal: Achieng inserts her card (PIN 2580)"
    Start-Session "CARD"
    $p = Card-Login "tok_4512xxxx4521" "2580"
    $acc = $p.accounts | Where-Object { $_.primary } | Select-Object -First 1
    $bal = Gw Get "/accounts/$($acc.accountId)/balance" -Customer
    Say "S-3 available KES $($bal.available.amount)"
    $lim = Gw Get "/accounts/$($acc.accountId)/withdrawal-limits" -Customer
    Say "W-1 up to KES $($lim.perTransactionMax.amount) per withdrawal, KES $($lim.dailyRemaining.amount) left today, multiples of $($lim.multipleOf)"
    $amount = [Math]::Min(5000, [Math]::Floor([double]$lim.available.amount / 100) * 100)
    if ($amount -lt 100) { Warn "not enough money left on this account for the demo"; End-Session; return }
    $key = Key
    $w = Gw Post "/cash/withdrawals" @{ accountId = $acc.accountId; amount = (Kes $amount) } -Idem $key -Customer
    Say "W-2 authorised $($w.transactionRef): dispense $(Mix $w.dispense.mix)"
    $again = Gw Post "/cash/withdrawals" @{ accountId = $acc.accountId; amount = (Kes $amount) } -Idem $key -Customer
    Say "W-2 retried with the same Idempotency-Key -> same withdrawal $($again.withdrawalId) (no double debit)"
    Gw Post "/cash/withdrawals/$($w.withdrawalId)/confirm" @{ presented = $w.dispense.mix; taken = $true } | Out-Null
    Ok "W-3 customer took KES $amount, balance now KES $($w.balanceAfter.amount)"
    $r = Gw Post "/receipts" @{ transactionRef = $w.transactionRef; delivery = "SMS" }
    Say "R-1 receipt by SMS: $($r.sms)"
    End-Session
}

function Flow-Deposit {
    Step "Cash deposit: KES 12,000 into account 0123457780 (no card needed)"
    Start-Session
    $t = Gw Post "/accounts/validate" @{ type = "ACCOUNT"; accountNumber = "0123457780"; purpose = "CASH_DEPOSIT" }
    Say "K-1 $($t.accountName) - $($t.masked), up to $($t.limits.maxNotes) notes"
    $d = Gw Post "/cash/deposits" @{ targetRef = $t.targetRef; currency = "KES"; depositorMobile = "0712345678" }
    Say "K-2 shutter open for $($d.depositId)"
    $c = Gw Put "/cash/deposits/$($d.depositId)/count" @{ accepted = @(@{ denomination = "1000"; count = 10 }, @{ denomination = "500"; count = 4 }); rejected = @{ count = 3; reasons = @("UNRECOGNISED") }; suspectedCounterfeit = 0; escrowHeld = $true }
    Say "K-3 counted KES $($c.total.amount) ($(Mix $c.breakdown)), $($c.returnedNotes) notes returned"
    $k = Gw Post "/cash/deposits/$($d.depositId)/commit" @{ confirmedTotal = $c.total } -Idem (Key)
    Ok "K-4 credited $($k.transactionRef) / $($k.coreRef)"
    Gw Post "/receipts" @{ transactionRef = $k.transactionRef; delivery = "PRINT" } | Out-Null
    End-Session
}

function Flow-Forex {
    Step "Foreign exchange: a visitor (passport P12345678) changes USD 200 into shillings"
    Start-Session
    $r = Gw Get "/fx/rates"
    $r.rates | ForEach-Object { Say "X-1 $($_.currency): we buy $($_.buy), we sell $($_.sell)" }
    $idv = Gw Post "/identity/documents/verify" @{ purpose = "FOREX"; documentType = "PASSPORT"; country = "GBR"; ocr = @{ documentNumber = "P12345678"; surname = "SMITH"; dateOfBirth = "1979-04-03" } }
    Say "B-4 passport checked: $($idv.registryRecord.fullName)"
    $q = Gw Post "/fx/quotes" @{ direction = "BANK_BUYS"; currency = "USD"; identityVerificationId = $idv.verificationId }
    $x = Gw Post "/fx/exchanges" @{ quoteId = $q.quoteId; destination = @{ type = "CASH" } }
    $c = Gw Put "/fx/exchanges/$($x.exchangeId)/count" @{ currency = "USD"; accepted = @(@{ denomination = "100"; count = 2; serials = @("PB12345678A", "PB12345679A") }); rejected = @{ count = 0 } }
    Say "X-2..X-4 rate $($c.rate): USD $($c.foreignTotal.amount) -> KES $($c.localAmount.amount)"
    $f = Gw Post "/fx/exchanges/$($x.exchangeId)/confirm" @{ confirmedLocalAmount = $c.localAmount } -Idem (Key)
    Gw Post "/cash/withdrawals/$($f.withdrawalId)/confirm" @{ presented = $f.dispense.mix; taken = $true } | Out-Null
    Ok "X-5 $($f.transactionRef): paid out $(Mix $f.dispense.mix), taken"
    End-Session
}

function Flow-Statement {
    Step "Statements: Achieng (card + PIN) prints a mini statement and a 3-month PDF"
    Start-Session "CARD"
    $p = Card-Login "tok_4512xxxx4521" "2580"
    $acc = ($p.accounts | Where-Object { $_.primary } | Select-Object -First 1).accountId
    (Gw Get "/statements/options?accountId=$acc" -Customer).options | ForEach-Object { Say "S-4 $($_.type) on $($_.output): KES $($_.fee.amount)" }
    $m = Gw Post "/statements" @{ accountId = $acc; type = "MINI"; feeAccepted = $true } -Idem (Key) -Customer
    Ok "S-5 mini statement $($m.reference): $($m.lines.Count) lines, fee posted $($m.fee.transactionRef)"
    $f = Gw Post "/statements" @{ accountId = $acc; type = "FULL"; periodMonths = 3; feeAccepted = $true } -Idem (Key) -Customer
    $file = Join-Path ([IO.Path]::GetTempPath()) "$($f.reference).pdf"
    Invoke-WebRequest -Uri "$Base$($f.documentUrl)" -OutFile $file -UseBasicParsing -Headers @{
        Authorization = "Bearer $script:token"; "X-Device-Id" = $script:device; "X-Session-Id" = $script:session
        "X-Customer-Token" = $script:customer; "X-Request-Id" = (Key) }
    Ok "S-6 $($f.pages)-page statement saved to $file"
    End-Session
}

function Flow-TellerCall {
    Step "Talk to a teller (D-8): needs call-control on 8090 and a teller set to Available in the portal"
    Start-Session
    try {
        $c = Gw Post "/support/teller-calls" @{ screen = "A1"; flow = "CASH_DEPOSIT"; language = $Language }
        Say "D-8 call $($c.callId) $($c.status), about $($c.estimatedWaitSec) s"
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Seconds 2
            $v = Gw Get "/support/teller-calls/$($c.callId)"
            Say "D-9 $($v.status)"
            if ($v.media) { Ok "teller answered: join $($v.media.roomUrl) room $($v.media.room)"; break }
            if ($v.status -in @("ENDED", "CANCELLED", "UNAVAILABLE")) { Warn "no teller took the call: the kiosk offers to try again"; break }
        }
        if ($v.status -notin @("ENDED", "CANCELLED", "UNAVAILABLE")) { Gw Delete "/support/teller-calls/$($c.callId)" | Out-Null; Say "call cancelled by the demo" }
    } catch [StmError] {
        if ($_.Exception.Code -eq "VIDEO_UNAVAILABLE") { Warn "call-control is not running: the kiosk shows 'Video help is not available right now'" } else { throw }
    }
    End-Session
}

# ------------------------------------------------------------------------------------------------ run

Step "Register a demo kiosk and sign it in (D-1)"
$script:device = "STM-DEMO-" + (Get-Random -Minimum 10000 -Maximum 99999)
$reg = Invoke-Json Post "$Base/api/v1/terminals/register" @{ "X-Provisioning-Secret" = $ProvisioningSecret } @{ terminalCode = $script:device; branchCode = "012" }
$tok = Invoke-Json Post "$Base/stm/v1/auth/device/token" @{ Authorization = "Bearer $($reg.token)" } @{ deviceId = $script:device; softwareVersion = "demo-ps"; nonce = (Key) }
$script:token = $tok.accessToken
Say "kiosk $script:device at $($tok.branchName) ($($tok.branchCode)), token valid $($tok.expiresIn) s"
$h = Gw Post "/devices/$script:device/health" @{ modules = @{ cardReader = "OK"; cardPrinter = @{ status = "OK"; blankStock = 250 }; cashRecycler = "OK"
    receiptPrinter = "OK"; a4Printer = "OK"; idScanner = "OK"; fingerprint = "OK"; camera = "OK"; pinPad = "OK" } }
Say "D-6 health reported, disabled services: $(if ($h.disabledServices.Count) { $h.disabledServices -join ', ' } else { 'none' })"

$flows = if ($Flow -eq "All") { @("AccountOpening", "Referral", "FaceFail", "CardServices", "CardCare", "ExistingCustomer", "Withdrawal", "Deposit", "Forex", "Statement", "TellerCall") } else { @($Flow) }
$failed = @()
foreach ($f in $flows) {
    try { & "Flow-$f" }
    catch {
        $failed += $f
        Write-Host "   FAILED $f : $($_.Exception.Message)" -ForegroundColor Red
        try { End-Session "CUSTOMER_CANCELLED" } catch { }
    }
}
Write-Host ""
if ($failed.Count) { Write-Host "Done with failures: $($failed -join ', ')" -ForegroundColor Red; exit 1 }
Write-Host "Done. Open the portal (Customer sessions, Applications, Cash & forex, Reconciliation) to see what happened." -ForegroundColor Green
