package com.dhruva.airtribe.prism;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the tier contract used by the routing eval and the task→model map
 * used by auto routing. The 20 prompts mirror data/routing_eval.jsonl.
 */
class TaskRouterTest {
    private final TaskRouter router = new TaskRouter();

    @Test void simpleLookupsRouteToTheFastTier() {
        assertTier(TaskRouter.Task.SIMPLE, "fast", "What is the capital of France?");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Convert 72 degrees Fahrenheit to Celsius.");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Write a one-line commit message for fixing a typo in the README.");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "What does HTTP status code 404 mean?");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Translate 'good morning' to Spanish.");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Is 91 divisible by 7?");
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Extract the email address from this text: 'Reach out to Jordan at jordan.lee@example.com.'");
    }

    @Test void longContextExtractionStaysOnTheFastTier() {
        String roster = "Here is our on-call roster for the next two weeks: Monday - Priya, Tuesday - Chen, Wednesday - Amara, Thursday - Diego, Friday - Fatima, Saturday - Lukas, Sunday - Mei. Who is on call this Thursday?";
        assertTier(TaskRouter.Task.SIMPLE, "fast", roster);
        assertTier(TaskRouter.Task.SIMPLE, "fast", "Rewrite the following email to sound more polite without changing its meaning: 'Send me the Q3 numbers now.'");
    }

    @Test void reasoningAndResearchRouteToTheSmartTier() {
        assertTier(TaskRouter.Task.REASONING, "smart", "Prove that the square root of 2 is irrational.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Estimate how many piano tuners work in Chicago. Show your reasoning.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Why can adding a cache increase p99 latency in a distributed system? Give three plausible mechanisms.");
        assertTier(TaskRouter.Task.RESEARCH, "smart", "Research the state of the art in vector search and cite your sources.");
        assertTierOnly("smart", "Compare event sourcing with plain CRUD for an accounting ledger and recommend one for an early-stage fintech startup, with justification.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Explain what consistency guarantees a leaderless quorum datastore with N=3, R=2, W=2 provides during a network partition.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Draft a zero-downtime migration plan for moving a monolithic web app to services, including how you would roll back at each stage.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Our API p95 latency doubled after a deploy that only 'added logging'. List the most likely causes in order of likelihood.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Prove or disprove: the sum of any five consecutive integers is divisible by 5.");
    }

    @Test void codeAndSchemaTasksRouteToTheSmartTier() {
        assertTier(TaskRouter.Task.REASONING, "smart", "Design a database schema for a multi-tenant SaaS billing system with usage-based pricing, proration, and refunds. Explain your trade-offs.");
        assertTier(TaskRouter.Task.REASONING, "smart", "Write a function that returns the longest palindromic substring of a string in better than O(n^2) time, and explain how the algorithm works.");
        assertTier(TaskRouter.Task.CODE, "smart", "Implement a Python function that parses this CSV and add unit tests.");
    }

    @Test void webSearchIsDetectedAndServedByTheFastTier() {
        TaskRouter.Decision decision = router.classify("Search the web for the latest NVIDIA earnings release and cite the source.");
        assertEquals(TaskRouter.Task.WEB_SEARCH, decision.task());
        assertEquals("fast", decision.tier());
        assertTrue(decision.confidence() > 0.6);
    }

    @Test void unknownPromptFallsBackToTheSimpleTier() {
        TaskRouter.Decision decision = router.classify("hello there");
        assertEquals(TaskRouter.Task.SIMPLE, decision.task());
        assertEquals("fast", decision.tier());
        assertNotNull(decision.reason());
    }

    private void assertTier(TaskRouter.Task task, String tier, String prompt) {
        TaskRouter.Decision decision = router.classify(prompt);
        assertEquals(task, decision.task(), () -> "task for: " + prompt + " — " + decision.reason());
        assertEquals(tier, decision.tier(), () -> "tier for: " + prompt);
    }

    private void assertTierOnly(String tier, String prompt) {
        assertEquals(tier, router.classify(prompt).tier(), () -> "tier for: " + prompt);
    }
}
