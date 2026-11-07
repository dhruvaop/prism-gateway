package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CacheSimilarityTest {
    @Autowired PrismService prism;
    @Test void suppliedParaphrasesCrossTheSearchTenantThreshold() {
        double a=prism.similarity("How do I reset my password on the dashboard?","What are the steps to reset my dashboard password?");
        double b=prism.similarity("What is the refund policy for annual plans?","If I bought an annual plan, can I get my money back?");
        assertTrue(a>=.92,"password reset paraphrase score="+a);assertTrue(b>=.92,"refund paraphrase score="+b);
    }
    @Test void twoFactorNearMissStaysBelowThreshold() {
        double score=prism.similarity("How do I reset my password on the dashboard?","How do I reset my two-factor authentication on the dashboard?");
        assertTrue(score<.92,"near miss score="+score);
    }
}
