package com.stm.middleware.journey;

import com.stm.middleware.gateway.TerminalPrincipal;
import com.stm.middleware.journey.SessionDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Kiosk-facing API: one endpoint per screen action of the Account Opening and Debit Card journeys. */
@RestController
@RequestMapping("/api/v1/kiosk")
public class SessionController {
    private final SessionService service;
    private final KioskStatusService status;
    private final ProductRepository products;

    public SessionController(SessionService service, KioskStatusService status, ProductRepository products) {
        this.service = service;
        this.status = status;
        this.products = products;
    }

    // ---------------------------------------------------------------- machine status and catalogue
    /** Poll on the idle screen: drives "Temporarily out of service" and which menu tiles are enabled. */
    @GetMapping("/status")
    public KioskStatusService.Status machineStatus(@AuthenticationPrincipal TerminalPrincipal p) {
        return status.status(p.terminalId());
    }

    @GetMapping("/products")
    public List<ProductRepository.Product> products() {
        return products.listActive();
    }

    // ---------------------------------------------------------------- lifecycle
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@AuthenticationPrincipal TerminalPrincipal p,
                              @RequestHeader("Idempotency-Key") String key,
                              @Valid @RequestBody CreateSessionRequest r) {
        return service.view(service.create(p, key, r.journey(), r.language()));
    }

    @GetMapping("/sessions/{id}")
    public SessionView get(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.get(p, id));
    }

    /** "I'm Still Here" on the session-timeout screen. */
    @PostMapping("/sessions/{id}/keepalive")
    public SessionView keepAlive(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.keepAlive(p, id));
    }

    @PostMapping("/sessions/{id}/back")
    public SessionView back(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.back(p, id));
    }

    /** Cancel / End Session. */
    @PostMapping("/sessions/{id}/cancel")
    public SessionView cancel(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.cancel(p, id));
    }

    /** "Done / Finish Session". */
    @PostMapping("/sessions/{id}/complete")
    public SessionView complete(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.complete(p, id));
    }

    /** "Connect to Remote Video Teller / Support": flags the session; the call itself goes over the WebSocket. */
    @PostMapping("/sessions/{id}/escalate")
    public SessionView escalate(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                @Valid @RequestBody(required = false) EscalateRequest r) {
        return service.view(service.escalate(p, id, r == null ? null : r.reason()));
    }

    // ---------------------------------------------------------------- Journey A steps 1-2 / Journey B "scan passport"
    @PostMapping("/sessions/{id}/passport")
    public PassportResult passport(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                   @Valid @RequestBody PassportRequest r) {
        return service.submitPassport(p, id, r.mrzLine1(), r.mrzLine2());
    }

    @PostMapping("/sessions/{id}/details")
    public SessionView details(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                               @Valid @RequestBody DetailsRequest r) {
        return service.view(service.confirmDetails(p, id, r.mobile(), r.email(), r.consentAccepted()));
    }

    // ---------------------------------------------------------------- Journey B step 1: find account
    @PostMapping("/sessions/{id}/account/lookup")
    public LookupResult lookup(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                               @Valid @RequestBody LookupRequest r) {
        return service.lookupAccount(p, id, r.method(), r.value());
    }

    // ---------------------------------------------------------------- biometrics (A step 3 / B ownership check)
    @PostMapping("/sessions/{id}/biometrics/face")
    public BiometricResponse face(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                  @Valid @RequestBody FaceRequest r) {
        return service.submitFace(p, id, SessionService.b64(r.imageBase64()), r.livenessPassed());
    }

    @PostMapping("/sessions/{id}/biometrics/fingerprint")
    public BiometricResponse fingerprint(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                         @Valid @RequestBody FingerprintRequest r) {
        return service.submitFingerprint(p, id, SessionService.b64(r.templateBase64()));
    }

    // ---------------------------------------------------------------- Journey A step 4: supporting documents
    /** type = PROOF_OF_ADDRESS (step 4) or SIGNED_AGREEMENT (step 6, paper signing). Call on "Accept". */
    @PostMapping(value = "/sessions/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentAccepted upload(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                   @RequestParam String type, @RequestPart("file") MultipartFile file) throws IOException {
        return service.uploadDocument(p, id, type, file.getContentType(), file.getBytes());
    }

    @PostMapping("/sessions/{id}/supporting-documents/accept")
    public SessionView acceptDocs(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.acceptSupportingDocs(p, id));
    }

    /** "Skip: Basic account tier". */
    @PostMapping("/sessions/{id}/supporting-documents/skip")
    public SessionView skipDocs(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.skipSupportingDocs(p, id));
    }

    // ---------------------------------------------------------------- Journey A steps 5-6: product and agreement
    @PutMapping("/sessions/{id}/product")
    public SessionView selectProduct(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                     @Valid @RequestBody ProductRequest r) {
        return service.view(service.selectProduct(p, id, r.productCode()));
    }

    /** "Continue" on step 5. */
    @PostMapping("/sessions/{id}/product/continue")
    public SessionView continueToAgreement(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.continueToAgreement(p, id));
    }

    /** "Print Terms & Conditions (A4)": printable HTML. The agreement version is in the X-Agreement-Version header. */
    @GetMapping("/sessions/{id}/agreement")
    public ResponseEntity<String> agreement(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        String html = service.agreementHtml(p, id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("X-Agreement-Version", AgreementService.VERSION)
                .body(html);
    }

    /** "Confirm Signature": stores the signature and opens the account. Returns the session at step ACCOUNT_OPENED. */
    @PostMapping("/sessions/{id}/agreement/sign")
    public SessionView sign(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                            @Valid @RequestBody SignRequest r) {
        return service.view(service.sign(p, id, r.method(), r.signaturePngBase64(), r.accepted(), r.agreementVersion()));
    }

    /** Retry if signing succeeded but the bank was unavailable (idempotent). */
    @PostMapping("/sessions/{id}/account")
    public SessionView openAccount(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.view(service.openAccount(p, id));
    }

    // ---------------------------------------------------------------- cards
    /** "Yes, Issue Card Now" / "No, Complete Account Opening Only" on step 7. */
    @PostMapping("/sessions/{id}/card/offer")
    public SessionView cardOffer(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                 @Valid @RequestBody CardOfferRequest r) {
        return service.view(service.cardOffer(p, id, r.issueNow()));
    }

    @GetMapping("/sessions/{id}/card/name-options")
    public List<String> nameOptions(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.nameOptions(p, id);
    }

    /** "Confirm & Dispense": the PIN must already be encrypted by the PIN pad. Returns the dispenser payload. */
    @PostMapping("/sessions/{id}/card/issue")
    public CardIssuanceResponse issueCard(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                          @Valid @RequestBody CardIssueRequest r) {
        return service.issueCard(p, id, r.embossedName(), r.encryptedPinBlock(), r.pinKeyId());
    }

    /** Dispenser outcome. success=false triggers the "Temporary hardware issue" flow (Error C). */
    @PostMapping("/sessions/{id}/card/dispense-result")
    public SessionView dispenseResult(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                      @Valid @RequestBody DispenseResultRequest r) {
        return service.view(service.dispenseResult(p, id, r.success(), r.errorCode()));
    }

    @PostMapping("/sessions/{id}/hardware-fault")
    public FaultResponse hardwareFault(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                                       @Valid @RequestBody HardwareFaultRequest r) {
        return service.hardwareFault(p, id, r.device(), r.errorCode());
    }

    // ---------------------------------------------------------------- receipt
    /** Localised content for "Print Thermal Receipt" and the emergency voucher. */
    @GetMapping("/sessions/{id}/receipt")
    public ReceiptService.Receipt receipt(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id) {
        return service.receipt(p, id);
    }

    /** "Send Email / SMS Summary". */
    @PostMapping("/sessions/{id}/summary")
    public void summary(@AuthenticationPrincipal TerminalPrincipal p, @PathVariable UUID id,
                        @Valid @RequestBody SummaryRequest r) {
        service.sendSummary(p, id, r.channel());
    }
}
