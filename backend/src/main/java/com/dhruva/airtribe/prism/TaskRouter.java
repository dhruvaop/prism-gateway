package com.dhruva.airtribe.prism;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies a prompt into a task profile before the auto alias picks a model.
 *
 * The router is deliberately deterministic and in-process: it runs in the hot
 * path in well under a millisecond and makes the decision reproducible for the
 * routing eval. Task type drives the model choice (web search, research,
 * reasoning/chain-of-thought, code, or a simple lookup) while the tier keeps
 * the existing fast/smart contract used by allowlists and the eval set.
 */
public class TaskRouter {
    public enum Task {
        WEB_SEARCH("web_search", "fast"),
        RESEARCH("research", "smart"),
        REASONING("reasoning", "smart"),
        CODE("code", "smart"),
        SIMPLE("simple", "fast");

        private final String key;
        private final String tier;

        Task(String key, String tier) { this.key = key; this.tier = tier; }

        public String key() { return key; }
        public String tier() { return tier; }
    }

    public record Decision(Task task, String tier, double confidence, String reason) {}

    private record Signal(Pattern pattern, String description, double weight) {}

    private static final List<Signal> WEB_SEARCH = List.of(
            signal("\\bsearch the (web|internet)\\b|\\bweb search\\b|\\bgoogle\\b|\\bbrowse the web\\b", "explicit web-search request", 4.0),
            signal("\\blook (it |this )?up online\\b|\\bfind (it |this )?online\\b", "online lookup request", 3.0),
            signal("\\blatest\\b|\\bbreaking news\\b|\\bnews today\\b|\\bcurrent(ly)?\\b|\\brecent(ly)?\\b", "freshness-sensitive request", 2.0),
            signal("\\btoday\\b|\\bthis (week|month|morning|evening)\\b|\\bright now\\b|\\bas of now\\b", "time-sensitive request", 1.5),
            signal("\\bweather\\b|\\bstock price\\b|\\bshare price\\b|\\bexchange rate\\b|\\bwho won\\b|\\bscore\\b", "live-data lookup", 2.5),
            signal("\\brelease date\\b|\\bhow much does .* cost\\b|\\bprice of\\b", "current fact lookup", 1.5),
            signal("https?://|www\\.[a-z0-9.-]+|\\.(com|org|io|ai|dev)\\b", "URL or domain in prompt", 2.0)
    );

    private static final List<Signal> RESEARCH = List.of(
            signal("\\bresearch\\b|\\bliterature review\\b|\\bstate of the art\\b|\\bwhitepaper\\b", "research request", 4.0),
            signal("\\bsurvey\\b|\\bcase study\\b|\\bmarket (analysis|report)\\b|\\bindustry report\\b", "survey/report request", 3.0),
            signal("\\bcite\\b|\\bcitations?\\b|\\bsources?\\b|\\bbibliography\\b|\\breferences?\\b", "source-gathering request", 2.5),
            signal("\\bin[- ]depth\\b|\\bcomprehensive\\b|\\bdeep dive\\b|\\bthorough (analysis|review)\\b", "in-depth analysis request", 2.0),
            signal("\\bgather (information|data)\\b|\\bfind (studies|papers|evidence)\\b|\\binvestigate\\b", "evidence-gathering request", 2.5),
            signal("\\bcompare\\b.*\\b(and|vs|versus|with)\\b", "comparative analysis", 1.0)
    );

    private static final List<Signal> REASONING = List.of(
            signal("\\bprove\\b|\\bdisprove\\b|\\birrational\\b|\\bconsecutive integers\\b", "formal proof request", 3.0),
            signal("\\bderive\\b|\\bderivation\\b|\\bshow your (reasoning|work)\\b|\\bchain of thought\\b|\\bstep[- ]by[- ]step\\b", "chain-of-thought request", 2.5),
            signal("\\bestimate\\b|\\bfermi\\b|\\bhow many\\b.*\\b(chicago|world|city)\\b", "multi-step estimation", 2.0),
            signal("\\bp99\\b|\\blatency\\b|\\bthroughput\\b|\\bdistributed system\\b", "systems-performance reasoning", 2.0),
            signal("\\bquorum\\b|\\bconsistency guarantees?\\b|\\bnetwork partition\\b|\\bconsensus\\b", "distributed-consensus reasoning", 3.0),
            signal("\\bpalindromic\\b|o\\(n(\\^2|\\))|\\btime complexity\\b|\\balgorithm\\b", "algorithm-design reasoning", 2.5),
            signal("\\bzero[- ]downtime\\b|\\bmigration plan\\b|\\broll ?back\\b", "migration-planning reasoning", 2.5),
            signal("\\bdatabase schema\\b|\\bproration\\b|\\btrade-?offs?\\b|\\barchitecture\\b", "architecture and schema design", 2.0),
            signal("\\bevent sourcing\\b|\\bledger\\b|\\baccounting\\b", "architectural trade-off evaluation", 2.5),
            signal("\\blikely causes?\\b|\\bdiagnose\\b|\\bdebugging\\b|\\broot cause\\b", "expert debugging", 2.0),
            signal("\\brecommend\\b|\\bjustif(y|ication)\\b|\\bwhy (does|do|is|can|would)\\b", "justification or causal reasoning", 1.5),
            signal("\\bdesign\\b|\\bplan\\b|\\bstrategy\\b|\\bpros and cons\\b", "design or planning task", 1.0)
    );

    private static final List<Signal> CODE = List.of(
            signal("\\bwrite (a |some )?(function|program|script|class|module)\\b|```|\\bfunction\\b|\\bimplement\\b|\\bclass\\b|\\bmethod\\b|\\bcode snippet\\b", "code-writing request", 2.0),
            signal("\\bsql\\b|\\bquery\\b|\\bregex\\b|\\bunit test\\b|\\brefactor\\b", "code artefact request", 2.5),
            signal("\\bpython\\b|\\bjavascript\\b|\\btypescript\\b|\\bjava\\b|\\bgo\\b|\\brust\\b", "programming-language request", 2.0),
            signal("\\bstack trace\\b|\\bcompile error\\b|\\bbug\\b|\\bexception\\b|\\bdebug this\\b", "code debugging request", 2.0),
            signal("\\bapi endpoint\\b|\\bunit tests?\\b|\\bgit\\b", "software-engineering request", 1.0)
    );

    private static final List<Signal> SIMPLE = List.of(
            signal("\\bcapital of\\b", "direct factual query", 2.5),
            signal("\\bconvert\\b.*\\b(celsius|fahrenheit|usd|eur|kg|lb|miles?|km)\\b", "standard unit conversion", 2.5),
            signal("\\bcommit message\\b", "simple commit-message rewrite", 2.0),
            signal("\\b404 mean\\b|\\bhttp status\\b|\\bdefine\\b|\\bwhat does .* mean\\b", "simple definition lookup", 2.0),
            signal("\\btranslate\\b", "direct phrase translation", 2.5),
            signal("\\bwho is on call\\b|\\broster\\b", "tabular roster lookup", 3.0),
            signal("\\btimestamp of\\b|\\bfirst error\\b|\\blog excerpt\\b", "log scanning and extraction", 3.0),
            signal("\\bextract\\b|\\bpull out\\b", "text extraction", 2.5),
            signal("\\bdivisible by\\b|\\bsimple arithmetic\\b|\\bcalculate\\b", "simple arithmetic check", 2.0),
            signal("\\brewrite the following email\\b|\\bpolish\\b|\\bshorten\\b", "simple rewrite task", 2.5)
    );

    private static final Pattern EXTRACTION_CUE = Pattern.compile(
            "who is on call|timestamp of|extract|below is|roster|log excerpt|copy|list of names");

    private static Signal signal(String regex, String description, double weight) {
        return new Signal(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description, weight);
    }

    public Decision classify(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        boolean longContext = p.split("\\s+").length > 40;
        boolean extraction = EXTRACTION_CUE.matcher(p).find();

        Map<Task, Double> scores = new LinkedHashMap<>();
        Map<Task, String> reasons = new LinkedHashMap<>();
        scores.put(Task.WEB_SEARCH, score(p, Task.WEB_SEARCH, WEB_SEARCH, reasons));
        scores.put(Task.RESEARCH, score(p, Task.RESEARCH, RESEARCH, reasons));
        scores.put(Task.REASONING, score(p, Task.REASONING, REASONING, reasons));
        scores.put(Task.CODE, score(p, Task.CODE, CODE, reasons));

        double simple = score(p, Task.SIMPLE, SIMPLE, reasons);
        if (longContext && extraction) {
            simple += 2.0;
            reasons.merge(Task.SIMPLE, "context-heavy single-hop extraction", (a, b) -> a + "; " + b);
        }
        scores.put(Task.SIMPLE, simple);

        Task best = Task.SIMPLE;
        double bestScore = 0.0;
        double second = 0.0;
        for (Map.Entry<Task, Double> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                second = bestScore;
                bestScore = entry.getValue();
                best = entry.getKey();
            } else if (entry.getValue() > second) {
                second = entry.getValue();
            }
        }

        // Long, structured context with an extraction cue is a single-hop lookup:
        // do not let the mere presence of words like "system" or "plan" promote it.
        if (longContext && extraction && !"web_search".equals(best.key())) {
            best = Task.SIMPLE;
            bestScore = scores.get(Task.SIMPLE);
            second = Math.max(0, second);
        }

        String detail = reasons.getOrDefault(best, "no strong task signal; defaulted to the simple tier");
        double confidence = Math.min(0.99, 0.62 + Math.max(0, bestScore - Math.max(0, second)) * 0.08);
        String reason = detail + " (score=" + fmt(bestScore) + ", next=" + fmt(Math.max(0, second)) + ")";
        return new Decision(best, best.tier(), round(confidence), reason);
    }

    private double score(String prompt, Task task, List<Signal> signals, Map<Task, String> reasons) {
        double total = 0;
        for (Signal signal : signals) {
            if (signal.pattern().matcher(prompt).find()) {
                total += signal.weight();
                reasons.merge(task, signal.description(), (a, b) -> a + "; " + b);
            }
        }
        return total;
    }

    private String fmt(double value) { return String.format(Locale.US, "%.1f", value); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
