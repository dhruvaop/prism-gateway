package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin console must be able to answer "who uses Prism, how much, and
 * what have they paid" without exposing tenant keys or Razorpay secrets.
 */
@SpringBootTest(properties = "prism.billing.trial-requests=2")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAnalyticsTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    private String token;

    @BeforeAll
    void signInAsAdmin() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String email = "analytics" + suffix + "@example.test";
        auth.signUp("Analytics Admin", email, "AnalyticsPass!2026");
        token = auth.signIn(email, "AnalyticsPass!2026").token();
    }

    @Test void analyticsEndpointsRequireAnAdminJwt() throws Exception {
        mvc.perform(get("/admin/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/billing/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/customer-usage")).andExpect(status().isUnauthorized());
    }

    @Test void metricsExposeProductUsageAndBillingShape() throws Exception {
        String body = mvc.perform(get("/admin/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_users").isNumber())
                .andExpect(jsonPath("$.active_users").isNumber())
                .andExpect(jsonPath("$.trial_limit").value(2))
                .andExpect(jsonPath("$.requests_total").isNumber())
                .andExpect(jsonPath("$.mrr_paise").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"signups_7d\""));
        assertTrue(body.contains("\"plan_breakdown\""));
        assertFalse(body.contains("razorpay_key_secret"));
        assertFalse(body.contains("test-key-secret"));
    }

    @Test void customerUsageAndBillingLedgersAreSanitized() throws Exception {
        String customers = mvc.perform(get("/admin/customer-usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andReturn().getResponse().getContentAsString();
        assertFalse(customers.contains("\"virtual_key\""), "customer usage must never expose raw tenant keys");
        assertFalse(customers.matches("(?s).*prism-sk-[0-9a-f]{40,}.*"), "customer usage must not contain raw keys");

        String transactions = mvc.perform(get("/admin/billing/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").isArray())
                .andReturn().getResponse().getContentAsString();
        assertFalse(transactions.contains("razorpay_key_secret"));
        assertFalse(transactions.contains("test-key-secret"));

        String keys = mvc.perform(get("/admin/keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(keys.contains("\"virtual_key\""), "the admin key list must return key_ref/key_hint only");
        assertTrue(keys.contains("\"key_ref\""));
        assertTrue(keys.contains("\"key_hint\""));
    }
}
