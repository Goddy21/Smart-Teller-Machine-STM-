package com.stm.middleware.banking.mock;

import com.stm.middleware.journey.Mrz;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helps the kiosk team: demo personas with ready-made passport MRZ lines. Only exists in the demo profile. */
@RestController
@Profile("demo")
@RequestMapping("/api/v1/kiosk/demo")
public class DemoController {
    private final MockBankAdapter bank;

    public DemoController(MockBankAdapter bank) {
        this.bank = bank;
    }

    @GetMapping("/personas")
    public Map<String, Object> personas() {
        List<Map<String, Object>> out = bank.personas().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerId", p.customerId());
            m.put("fullName", p.fullName());
            m.put("scenario", p.scenario());
            m.put("existingCustomer", p.existingCustomer());
            m.put("accountNo", bank.accountNoOf(p.customerId()).orElse(null));
            m.put("nationalId", p.nationalId());
            m.put("passportNumber", p.passportNumber());
            if (p.passportNumber() != null) {
                String[] mrz = Mrz.generate(p.passportNumber(), p.nationality(), p.nationality(), p.surname(),
                        p.givenNames(), p.dateOfBirth(), p.sex().charAt(0), p.passportExpiry());
                m.put("mrzLine1", mrz[0]);
                m.put("mrzLine2", mrz[1]);
            }
            return m;
        }).toList();
        return Map.of("personas", out, "note", "Demo data only. Use mrzLine1/mrzLine2 with POST /sessions/{id}/passport.");
    }
}
