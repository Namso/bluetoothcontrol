import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

public class FlowAnalyzer {

    public static class Job {
        String key;
        String memname;
        String isn;
        int versionSerial;
        String datacenter;
        String jobname;
        List<String> inCond = new ArrayList<String>();
        List<String> outCond = new ArrayList<String>();
    }

    public static class ScoredJob {
        String jobname;
        String datacenter;
        int score;

        ScoredJob(String jobname, String datacenter, int score) {
            this.jobname = jobname;
            this.datacenter = datacenter;
            this.score = score;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("jobname", jobname);
            o.put("datacenter", datacenter);
            o.put("score", score);
            return o;
        }
    }

    public static class BrokenReference {
        String jobname;
        String datacenter;
        String condition;
        String expectedFrom;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("jobname", jobname);
            o.put("datacenter", datacenter);
            o.put("condition", condition);
            o.put("expectedFrom", expectedFrom);
            return o;
        }
    }

    public static class Edge {
        String source;
        String target;

        Edge(String source, String target) {
            this.source = source;
            this.target = target;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("source", source);
            o.put("target", target);
            return o;
        }
    }

    public static class JobInfo {
        String jobname;
        String datacenter;
        String isn;
        int inCount;
        int outCount;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("jobname", jobname);
            o.put("datacenter", datacenter);
            o.put("isn", isn);
            o.put("inCount", inCount);
            o.put("outCount", outCount);
            return o;
        }
    }

    public static class AnalysisResult {
        int totalJobsRead;
        int canonicalCount;
        int totalStarters;
        int totalFinals;
        int totalBrokenReferences;
        int totalMissingJobs;
        List<String> starters = new ArrayList<String>();
        List<String> finals = new ArrayList<String>();
        List<ScoredJob> topInbound = new ArrayList<ScoredJob>();
        List<ScoredJob> topOutbound = new ArrayList<ScoredJob>();
        List<BrokenReference> brokenReferences = new ArrayList<BrokenReference>();
        List<String> missingJobs = new ArrayList<String>();
        Map<String, Integer> inboundScore = new HashMap<String, Integer>();
        Map<String, Integer> outboundScore = new HashMap<String, Integer>();
        List<String> mapNodes = new ArrayList<String>();
        List<Edge> mapEdges = new ArrayList<Edge>();
        Map<String, JobInfo> jobsByName = new LinkedHashMap<String, JobInfo>();

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("totalJobsRead", totalJobsRead);
            o.put("canonicalCount", canonicalCount);
            o.put("totalStarters", totalStarters);
            o.put("totalFinals", totalFinals);
            o.put("totalBrokenReferences", totalBrokenReferences);
            o.put("totalMissingJobs", totalMissingJobs);
            o.put("starters", new JSONArray(starters));
            o.put("finals", new JSONArray(finals));

            JSONArray topIn = new JSONArray();
            for (ScoredJob s : topInbound) {
                topIn.put(s.toJson());
            }
            o.put("topInbound", topIn);

            JSONArray topOut = new JSONArray();
            for (ScoredJob s : topOutbound) {
                topOut.put(s.toJson());
            }
            o.put("topOutbound", topOut);

            JSONArray broken = new JSONArray();
            for (BrokenReference b : brokenReferences) {
                broken.put(b.toJson());
            }
            o.put("brokenReferences", broken);
            o.put("missingJobs", new JSONArray(missingJobs));
            o.put("inboundScore", new JSONObject(inboundScore));
            o.put("outboundScore", new JSONObject(outboundScore));
            o.put("mapNodes", new JSONArray(mapNodes));

            JSONArray edges = new JSONArray();
            for (Edge e : mapEdges) {
                edges.put(e.toJson());
            }
            o.put("mapEdges", edges);

            JSONObject jobsMeta = new JSONObject();
            for (Map.Entry<String, JobInfo> row : jobsByName.entrySet()) {
                jobsMeta.put(row.getKey(), row.getValue().toJson());
            }
            o.put("jobsByName", jobsMeta);
            return o;
        }
    }

    public AnalysisResult analyze(String rawJsonArray) {
        JSONArray arr = new JSONArray(rawJsonArray);
        List<Job> jobs = parseJobs(arr);
        return analyzeJobs(jobs);
    }

    public AnalysisResult analyzeFile(String jsonFilePath) throws Exception {
        BufferedInputStream input = null;
        try {
            input = new BufferedInputStream(new FileInputStream(jsonFilePath));
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(new JSONTokener(reader));
            List<Job> jobs = parseJobs(arr);
            return analyzeJobs(jobs);
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private List<Job> parseJobs(JSONArray arr) {
        List<Job> jobs = new ArrayList<Job>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }

            Job job = new Job();
            job.memname = o.optString("memname", "");
            job.isn = o.optString("isn", String.valueOf(i + 1));
            job.versionSerial = o.optInt("versionserial", 0);
            job.datacenter = safeValue(o.optString("datacenter", "UNKNOWN"));
            job.jobname = safeValue(o.optString("jobname", ""));
            if (job.jobname.length() == 0) {
                continue;
            }

            job.inCond = toStringList(o.optJSONArray("inCond"));
            job.outCond = toStringList(o.optJSONArray("outCond"));
            job.key = job.datacenter + "::" + job.jobname + "::" + job.versionSerial + "::" + job.isn + "::" + i;
            jobs.add(job);
        }
        return jobs;
    }

    private AnalysisResult analyzeJobs(List<Job> jobs) {
        AnalysisResult result = new AnalysisResult();
        result.totalJobsRead = jobs.size();

        Map<String, Map<String, Set<String>>> outByDatacenter = buildOutByDatacenter(jobs);
        Map<String, Integer> brokenPerKey = computeBrokenByVariant(jobs, outByDatacenter);
        List<Job> canonical = selectCanonicalJobs(jobs, brokenPerKey);
        result.canonicalCount = canonical.size();

        Map<String, Set<String>> providersByCond = new HashMap<String, Set<String>>();
        Map<String, Set<String>> consumersByCond = new HashMap<String, Set<String>>();
        for (Job job : canonical) {
            for (String cond : job.outCond) {
                addToSetMap(providersByCond, cond, job.jobname);
            }
            for (String cond : job.inCond) {
                addToSetMap(consumersByCond, cond, job.jobname);
            }
        }

        Map<String, Integer> inboundScore = new HashMap<String, Integer>();
        Map<String, Integer> outboundScore = new HashMap<String, Integer>();
        Set<String> missingNames = new HashSet<String>();

        for (Job job : canonical) {
            JobInfo info = new JobInfo();
            info.jobname = job.jobname;
            info.datacenter = job.datacenter;
            info.isn = job.isn;
            info.inCount = job.inCond.size();
            info.outCount = job.outCond.size();
            result.jobsByName.put(job.jobname, info);

            int inbound = 0;
            int outbound = 0;

            for (String cond : job.inCond) {
                Set<String> providers = providersByCond.get(cond);
                int providersCount = countWithoutSelf(providers, job.jobname);
                inbound += providersCount;

                if (providersCount == 0) {
                    BrokenReference broken = new BrokenReference();
                    broken.jobname = job.jobname;
                    broken.datacenter = job.datacenter;
                    broken.condition = cond;
                    broken.expectedFrom = "N/A";
                    result.brokenReferences.add(broken);

                    // Missing planning is reported by full unresolved condition, not inferred jobname.
                    missingNames.add(cond);
                }
            }

            for (String cond : job.outCond) {
                Set<String> consumers = consumersByCond.get(cond);
                outbound += countWithoutSelf(consumers, job.jobname);
            }

            inboundScore.put(job.jobname, Integer.valueOf(inbound));
            outboundScore.put(job.jobname, Integer.valueOf(outbound));
        }

        for (Job job : canonical) {
            // Starter is structural: jobs without inCond.
            if (job.inCond.isEmpty()) {
                result.starters.add(job.jobname);
            }

            Set<String> seenTargets = new HashSet<String>();
            for (String cond : job.outCond) {
                Set<String> consumers = consumersByCond.get(cond);
                if (consumers == null) {
                    continue;
                }
                for (String target : consumers) {
                    if (!job.jobname.equals(target)) {
                        seenTargets.add(target);
                    }
                }
            }
            int successors = seenTargets.size();
            if (successors == 0) {
                result.finals.add(job.jobname);
            }
        }

        Collections.sort(result.starters);
        Collections.sort(result.finals);
        result.totalStarters = result.starters.size();
        result.totalFinals = result.finals.size();
        result.totalBrokenReferences = result.brokenReferences.size();

        result.topInbound = buildTop(canonical, inboundScore, 50);
        result.topOutbound = buildTop(canonical, outboundScore, 50);
        result.missingJobs = new ArrayList<String>(missingNames);
        Collections.sort(result.missingJobs);
        result.totalMissingJobs = result.missingJobs.size();

        result.inboundScore = inboundScore;
        result.outboundScore = outboundScore;

        buildFullGraph(canonical, providersByCond, consumersByCond, result);
        return result;
    }

    private Map<String, Map<String, Set<String>>> buildOutByDatacenter(List<Job> jobs) {
        Map<String, Map<String, Set<String>>> map = new HashMap<String, Map<String, Set<String>>>();
        for (Job job : jobs) {
            Map<String, Set<String>> byCond = map.get(job.datacenter);
            if (byCond == null) {
                byCond = new HashMap<String, Set<String>>();
                map.put(job.datacenter, byCond);
            }
            for (String cond : job.outCond) {
                addToSetMap(byCond, cond, job.key);
            }
        }
        return map;
    }

    private Map<String, Integer> computeBrokenByVariant(List<Job> jobs, Map<String, Map<String, Set<String>>> outByDatacenter) {
        Map<String, Integer> brokenByKey = new HashMap<String, Integer>();
        for (Job job : jobs) {
            Map<String, Set<String>> byCond = outByDatacenter.get(job.datacenter);
            if (byCond == null) {
                byCond = Collections.emptyMap();
            }

            int brokenCount = 0;
            for (String cond : job.inCond) {
                Set<String> providers = byCond.get(cond);
                if (countWithoutSelf(providers, job.key) == 0) {
                    brokenCount++;
                }
            }
            brokenByKey.put(job.key, Integer.valueOf(brokenCount));
        }
        return brokenByKey;
    }

    private List<Job> selectCanonicalJobs(List<Job> jobs, final Map<String, Integer> brokenPerKey) {
        Map<String, List<Job>> byName = new HashMap<String, List<Job>>();
        for (Job job : jobs) {
            List<Job> list = byName.get(job.jobname);
            if (list == null) {
                list = new ArrayList<Job>();
                byName.put(job.jobname, list);
            }
            list.add(job);
        }

        List<Job> canonical = new ArrayList<Job>();
        for (List<Job> variants : byName.values()) {
            Collections.sort(variants, new Comparator<Job>() {
                public int compare(Job a, Job b) {
                    int brokenA = valueOf(brokenPerKey, a.key);
                    int brokenB = valueOf(brokenPerKey, b.key);
                    if (brokenA != brokenB) {
                        return Integer.compare(brokenA, brokenB);
                    }
                    if (a.inCond.size() != b.inCond.size()) {
                        return Integer.compare(a.inCond.size(), b.inCond.size());
                    }
                    if (a.outCond.size() != b.outCond.size()) {
                        return Integer.compare(b.outCond.size(), a.outCond.size());
                    }
                    return Integer.compare(b.versionSerial, a.versionSerial);
                }
            });
            canonical.add(variants.get(0));
        }
        return canonical;
    }

    private void buildFullGraph(
        List<Job> canonical,
        Map<String, Set<String>> providersByCond,
        Map<String, Set<String>> consumersByCond,
        AnalysisResult result
    ) {
        Set<String> nodes = new LinkedHashSet<String>();
        for (Job job : canonical) {
            nodes.add(job.jobname);
        }

        Set<String> edgeKeys = new HashSet<String>();
        for (Map.Entry<String, Set<String>> entry : providersByCond.entrySet()) {
            String condition = entry.getKey();
            Set<String> providers = entry.getValue();
            Set<String> consumers = consumersByCond.get(condition);
            if (consumers == null || consumers.isEmpty()) {
                continue;
            }

            for (String source : providers) {
                for (String target : consumers) {
                    if (source.equals(target)) {
                        continue;
                    }
                    String key = source + "->" + target;
                    if (edgeKeys.contains(key)) {
                        continue;
                    }
                    edgeKeys.add(key);
                    result.mapEdges.add(new Edge(source, target));
                }
            }
        }

        result.mapNodes = new ArrayList<String>(nodes);
        Collections.sort(result.mapNodes);
    }

    private List<ScoredJob> buildTop(List<Job> canonical, Map<String, Integer> scoreMap, int max) {
        List<ScoredJob> scored = new ArrayList<ScoredJob>();
        for (Job job : canonical) {
            scored.add(new ScoredJob(job.jobname, job.datacenter, valueOf(scoreMap, job.jobname)));
        }

        Collections.sort(scored, new Comparator<ScoredJob>() {
            public int compare(ScoredJob a, ScoredJob b) {
                return Integer.compare(b.score, a.score);
            }
        });

        if (scored.size() > max) {
            return new ArrayList<ScoredJob>(scored.subList(0, max));
        }
        return scored;
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> list = new ArrayList<String>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            Object value = arr.opt(i);
            if (value != null) {
                list.add(String.valueOf(value));
            }
        }
        return list;
    }

    private static String safeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static int countWithoutSelf(Set<String> set, String self) {
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String value : set) {
            if (!value.equals(self)) {
                count++;
            }
        }
        return count;
    }

    private static <K> int valueOf(Map<K, Integer> map, K key) {
        Integer val = map.get(key);
        return val == null ? 0 : val.intValue();
    }

    private static <K, V> void addToSetMap(Map<K, Set<V>> map, K key, V value) {
        Set<V> set = map.get(key);
        if (set == null) {
            set = new HashSet<V>();
            map.put(key, set);
        }
        set.add(value);
    }
}