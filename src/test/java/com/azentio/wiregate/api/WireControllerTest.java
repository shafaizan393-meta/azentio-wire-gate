package com.azentio.wiregate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azentio.wiregate.service.WireDecisionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WireController.class)
class WireControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WireDecisionService decisionService;

    @Test
    void invalidWireReturnsFieldLevelBadRequest() throws Exception {
        String invalidRequest = """
                {
                  "msgId": "invalid",
                  "uetr": "not-a-uuid",
                  "createdAt": "2026-11-14T12:00:00Z",
                  "amount": 100000000.00,
                  "currency": "inr",
                  "chargeBearer": "CRED",
                  "debtor": {"name": "Synthetic", "accountId": "1234"},
                  "debtorAgentBic": "bad",
                  "creditor": {"name": "Synthetic", "accountId": "200000000001", "country": "AE"},
                  "creditorAgentBic": "DEMOAEADXXX"
                }
                """;

        String body = mockMvc.perform(post("/api/v1/wires")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"error\":\"VALIDATION_FAILED\"")
                .contains("\"uetr\"").contains("\"amount\"").contains("\"currency\"")
                .contains("\"debtor.accountId\"").contains("\"debtorAgentBic\"");
        verify(decisionService, never()).decide(any());
    }

    @Test
    void invalidEnumValueReturnsFieldLevelBadRequest() throws Exception {
        String request = """
                {
                  "msgId": "message-1",
                  "uetr": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "createdAt": "2026-11-14T12:00:00Z",
                  "amount": 100.00,
                  "currency": "INR",
                  "chargeBearer": "INVALID",
                  "debtor": {"name": "Synthetic Debtor", "accountId": "100000000001"},
                  "debtorAgentBic": "TESTINBBXXX",
                  "creditor": {"name": "Synthetic Creditor", "accountId": "200000000001", "country": "AE"},
                  "creditorAgentBic": "DEMOAEADXXX"
                }
                """;

        String body = mockMvc.perform(post("/api/v1/wires")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"error\":\"VALIDATION_FAILED\"")
                .contains("\"chargeBearer\"");
        verify(decisionService, never()).decide(any());
    }

    @Test
    void validWireReturnsMaskedDecisionResponse() throws Exception {
        UUID uetr = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        when(decisionService.decide(any())).thenReturn(new WireResponse(
                uetr, WireStatus.APPROVED, List.of(), new BigDecimal("100.00"), "INR",
                BigDecimal.ZERO, new BigDecimal("100.00"), "****0001", "****0001",
                "TESTINBBXXX", "DEMOAEADXXX", null, "IN-AE", null));
        String request = """
                {
                  "msgId": "message-1",
                  "uetr": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "createdAt": "2026-11-14T12:00:00Z",
                  "amount": 100.00,
                  "currency": "INR",
                  "chargeBearer": "CRED",
                  "debtor": {"name": "Synthetic Debtor", "accountId": "100000000001"},
                  "debtorAgentBic": "TESTINBBXXX",
                  "creditor": {"name": "Synthetic Creditor", "accountId": "200000000001", "country": "AE"},
                  "creditorAgentBic": "DEMOAEADXXX"
                }
                """;

        String body = mockMvc.perform(post("/api/v1/wires")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"debtorAccountMasked\":\"****0001\"")
                .doesNotContain("100000000001");
    }
}
