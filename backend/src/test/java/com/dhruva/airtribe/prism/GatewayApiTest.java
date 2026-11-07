package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayApiTest {
    @Autowired MockMvc mvc;
    @Autowired PrismService prism;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    private MvcResult chat(String key,String body)throws Exception{
        MockHttpServletRequestBuilder request=post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body);
        if(key!=null)request.header("Authorization","Bearer "+key);
        MvcResult result=mvc.perform(request).andReturn();
        return result.getRequest().isAsyncStarted()?mvc.perform(asyncDispatch(result)).andReturn():result;
    }
    private void assertError(MvcResult result,int status,String type)throws Exception{
        org.junit.jupiter.api.Assertions.assertEquals(status,result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(type,json.readTree(result.getResponse().getContentAsByteArray()).path("error").path("type").asText());
    }

    @Test void invalidVirtualKeyHasDistinctOpenAiError() throws Exception {
        assertError(chat("invalid","{\"model\":\"fast\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),401,"authentication_error");
    }
    @Test void missingVirtualKeyIsRejected() throws Exception {
        assertError(chat(null,"{\"model\":\"fast\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),401,"authentication_error");
    }
    @Test void modelAllowlistIsEnforcedBeforeProviderCall() throws Exception {
        assertError(chat("prism-sk-search-1a2b3c","{\"model\":\"smart\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),403,"model_not_allowed");
    }
    @Test void unknownAliasReturnsClearNotFoundError() throws Exception {
        assertError(chat("prism-sk-search-1a2b3c","{\"model\":\"not-a-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),404,"not_found_error");
    }
    @Test void classifierHandlesShortHardAndLongTrivialTraps() {
        org.junit.jupiter.api.Assertions.assertTrue(prism.autoRoute("Prove that the square root of 2 is irrational.").startsWith("smart|"));
        org.junit.jupiter.api.Assertions.assertTrue(prism.autoRoute("Here is our on-call roster: Monday Priya, Tuesday Chen, Wednesday Amara, Thursday Diego, Friday Fatima, Saturday Lukas, Sunday Mei, next Monday Tom, next Tuesday Sara. Who is on call this Thursday?").startsWith("fast|"));
    }
    @Test void overviewAndEvalApisAreAvailable() throws Exception {
        mvc.perform(get("/admin/overview")).andExpect(status().isOk()).andExpect(jsonPath("$.active_keys").value(4));
        mvc.perform(get("/admin/router/eval")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(20)).andExpect(jsonPath("$.accuracy").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0.9)));
    }
    @Test void seededTenantsAreStoredInThePersistentDatabase() {
        org.junit.jupiter.api.Assertions.assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM tenant_keys", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals("search", db.queryForObject("SELECT team FROM tenant_keys WHERE virtual_key=?", String.class, "prism-sk-search-1a2b3c"));
    }
    @Test void malformedEmptyMessagesReturn400() throws Exception {
        assertError(chat("prism-sk-research-4d5e6f","{\"model\":\"fast\",\"messages\":[]}"),400,"invalid_request_error");
    }
    @Test void exhaustedBudgetHasDistinctError() throws Exception {
        db.update("INSERT INTO usage_monthly(virtual_key,month,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits) VALUES(?,?,?,0,0,0,?,0) ON CONFLICT(virtual_key,month) DO UPDATE SET cost_usd=excluded.cost_usd", "prism-sk-budget-demo-0j1k2l",YearMonth.now(ZoneOffset.UTC).toString(),"budget-demo",0.00001);
        assertError(chat("prism-sk-budget-demo-0j1k2l","{\"model\":\"fast\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),429,"budget_exceeded");
    }
}
