package com.citi.learning;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class Day1Controller {

    @GetMapping("/hello")
    public Map<String,String> hello() {
        return Map.of("message","Case API is running","day","Day 1 - Design Patterns");
    }

    @GetMapping("/day1/collections/count")
    public Map<String,Object> countErrors() {
        List<String> events = List.of("timeout","5xx","timeout","5xx","5xx","timeout","circuit_open","5xx");
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (String e : events) counts.merge(e, 1, Integer::sum);
        List<Map.Entry<String,Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String,Integer>comparingByValue().reversed());
        return Map.of("counts", counts, "most_common", sorted.get(0).getKey(),
                      "spl_equivalent","| stats count by error_type | sort -count");
    }

    @GetMapping("/day1/collections/group")
    public Map<String,Object> groupByService() {
        List<String[]> events = List.of(
            new String[]{"case-api","500"}, new String[]{"pega-proxy","503"},
            new String[]{"case-api","500"}, new String[]{"auth-svc","401"},
            new String[]{"pega-proxy","503"}, new String[]{"case-api","200"});
        Map<String,List<String>> byService = new LinkedHashMap<>();
        for (String[] ev : events)
            byService.computeIfAbsent(ev[0], k -> new ArrayList<>()).add(ev[1]);
        Map<String,Object> report = new LinkedHashMap<>();
        for (var entry : byService.entrySet()) {
            List<String> s = entry.getValue();
            long errors = s.stream().filter(x -> x.startsWith("4") || x.startsWith("5")).count();
            double rate = (double) errors / s.size();
            report.put(entry.getKey(), Map.of("statuses", s, "error_rate",
                String.format("%.0f%%", rate*100), "circuit", rate>=0.5?"OPEN":"CLOSED"));
        }
        return Map.of("report", report, "spl_equivalent","| stats values(status) by service");
    }

    interface RetryStrategy { boolean shouldRetry(int attempt); String name(); }
    static class ExponentialBackoff implements RetryStrategy {
        public boolean shouldRetry(int a) { return a < 3; }
        public String name() { return "ExponentialBackoff"; }
    }
    static class NoRetry implements RetryStrategy {
        public boolean shouldRetry(int a) { return false; }
        public String name() { return "NoRetry"; }
    }
    static class LinearBackoff implements RetryStrategy {
        public boolean shouldRetry(int a) { return a < 2; }
        public String name() { return "LinearBackoff"; }
    }

    private List<String> callService(RetryStrategy strategy, String url) {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (!strategy.shouldRetry(i)) { log.add("Stopped at attempt " + i); break; }
            log.add("Attempt " + i + ": calling " + url);
        }
        return log;
    }

    @GetMapping("/day1/strategy/exponential")
    public Map<String,Object> strategyExp() {
        var s = new ExponentialBackoff();
        return Map.of("strategy",s.name(),"trace",callService(s,"/api/v1/cases/C-123"),
                      "insight","callService() never changed. Only the object passed in changed.");
    }

    @GetMapping("/day1/strategy/noretry")
    public Map<String,Object> strategyNone() {
        var s = new NoRetry();
        return Map.of("strategy",s.name(),"trace",callService(s,"/api/v1/cases/C-123"),
                      "insight","Zero calls made. NoRetry.shouldRetry() returned false at attempt 0.");
    }

    @GetMapping("/day1/strategy/linear")
    public Map<String,Object> strategyLinear() {
        var s = new LinearBackoff();
        return Map.of("strategy",s.name(),"trace",callService(s,"/api/v1/cases/C-123"),
                      "insight","New strategy = one new class. callService() untouched.");
    }

    @GetMapping("/day1/circuitbreaker/simulate")
    public Map<String,Object> cbSimulate(
            @RequestParam(defaultValue="6") int failures,
            @RequestParam(defaultValue="50") int thresholdPct) {
        double threshold = thresholdPct / 100.0;
        List<Boolean> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) calls.add(i >= failures);
        List<String> timeline = new ArrayList<>();
        List<Boolean> window = new ArrayList<>();
        String state = "CLOSED";
        for (int i = 0; i < calls.size(); i++) {
            window.add(calls.get(i));
            if (window.size() > 10) window.remove(0);
            long fails = window.stream().filter(r -> !r).count();
            double rate = (double) fails / window.size();
            String prev = state;
            if (state.equals("CLOSED") && rate >= threshold) state = "OPEN";
            timeline.add(String.format("Call %2d: %-8s | failure rate: %3.0f%% | circuit: %s%s",
                i+1, calls.get(i)?"SUCCESS":"FAILURE", rate*100, state,
                !prev.equals(state)?" <- TRANSITION":""));
        }
        return new LinkedHashMap<>(Map.of(
            "config", Map.of("failures",failures,"threshold",thresholdPct+"%"),
            "timeline", timeline,
            "final_state", state,
            "tip", "Try: ?failures=2 (stays CLOSED)  or  ?failures=9 (opens fast)"));
    }
}
