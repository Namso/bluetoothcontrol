import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * JobAnalyzer - Motor de análisis de dependencias de jobs migrados
 * desde Mainframe (COBOL/JCL) hacia Control-M.
 *
 * Java 8 + org.json
 *
 * COMPILAR:
 *   javac -cp json-20231013.jar JobAnalyzer.java
 *
 * EJECUTAR:
 *   java -Xmx4g -cp ".:json-20231013.jar" JobAnalyzer /ruta/a/jobs.json [profundidad_visor] [job_semilla]
 *
 * EJEMPLO:
 *   java -Xmx4g -cp ".:json-20231013.jar" JobAnalyzer datos.json 5 HABJG271
 *
 * SALIDA: Carpeta "analisis/" con reportes TXT, HTML y grafo 3D
 */
public class JobAnalyzer {

    // =========================================================================
    // MODELO
    // =========================================================================
    static class Job {
        String memname;
        String isn;
        int versionSerial;
        String datacenter;
        List<String> inCond;
        List<String> outCond;
        String jobname;
        boolean mirrorSelected;

        Job() {
            inCond = new ArrayList<>();
            outCond = new ArrayList<>();
            mirrorSelected = false;
        }
    }

    // =========================================================================
    // CAMPOS
    // =========================================================================
    private List<Job> rawJobs = new ArrayList<>();
    private Map<String, Job> resolvedJobs = new LinkedHashMap<>();
    private Map<String, List<Job>> mirrorMap = new HashMap<>();

    // Indice: condicion completa -> jobs que la PRODUCEN (tienen en outCond)
    private Map<String, List<String>> condProducers = new HashMap<>();
    // Indice: condicion completa -> jobs que la CONSUMEN (tienen en inCond)
    private Map<String, List<String>> condConsumers = new HashMap<>();

    // Todas las condiciones producidas y consumidas (para lookup O(1))
    private Set<String> allOutConds = new HashSet<>();
    private Set<String> allInConds = new HashSet<>();

    // =========================================================================
    // PASO 1: CARGAR JSON
    // =========================================================================
    public void loadJson(String filePath) throws Exception {
        System.out.println("[INFO] Cargando JSON desde: " + filePath);
        long start = System.currentTimeMillis();

        String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        JSONArray arr = new JSONArray(content);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            Job job = new Job();
            job.memname = obj.optString("memname", "");
            job.isn = obj.optString("isn", String.valueOf(obj.optInt("isn", 0)));
            job.versionSerial = obj.optInt("versionserial", obj.optInt("versionSerial", 1));
            job.datacenter = obj.optString("datacenter", "UNKNOWN");
            job.jobname = obj.optString("jobname", "").trim().toUpperCase();

            JSONArray inArr = obj.optJSONArray("inCond");
            if (inArr != null) {
                for (int j = 0; j < inArr.length(); j++) {
                    String c = inArr.getString(j).trim();
                    if (!c.isEmpty()) job.inCond.add(c);
                }
            }
            JSONArray outArr = obj.optJSONArray("outCond");
            if (outArr != null) {
                for (int j = 0; j < outArr.length(); j++) {
                    String c = outArr.getString(j).trim();
                    if (!c.isEmpty()) job.outCond.add(c);
                }
            }
            if (!job.jobname.isEmpty()) rawJobs.add(job);

            if (i > 0 && i % 100000 == 0)
                System.out.println("[INFO]   Parseados " + i + " jobs...");
        }
        content = null;
        System.gc();
        System.out.println("[INFO] Cargados " + rawJobs.size() + " jobs en " + (System.currentTimeMillis() - start) + "ms");
    }

    // =========================================================================
    // PASO 2: RESOLVER GEMELOS ESPEJO
    // =========================================================================
    public void resolveMirrors() {
        System.out.println("[INFO] Resolviendo gemelos espejo...");

        for (Job job : rawJobs) {
            if (!mirrorMap.containsKey(job.jobname))
                mirrorMap.put(job.jobname, new ArrayList<Job>());
            mirrorMap.get(job.jobname).add(job);
        }

        // Indice temporal de todas las outCond para calcular broken
        Set<String> tmpAllOut = new HashSet<>();
        for (Job job : rawJobs) tmpAllOut.addAll(job.outCond);

        for (Map.Entry<String, List<Job>> entry : mirrorMap.entrySet()) {
            List<Job> group = entry.getValue();
            if (group.size() == 1) {
                group.get(0).mirrorSelected = true;
                resolvedJobs.put(group.get(0).jobname, group.get(0));
            } else {
                Job best = null;
                int bestBroken = Integer.MAX_VALUE;
                int bestVer = Integer.MIN_VALUE;
                for (Job job : group) {
                    int broken = 0;
                    for (String c : job.inCond) {
                        if (!tmpAllOut.contains(c)) broken++;
                    }
                    if (broken < bestBroken || (broken == bestBroken && job.versionSerial > bestVer)) {
                        best = job;
                        bestBroken = broken;
                        bestVer = job.versionSerial;
                    }
                }
                if (best != null) {
                    best.mirrorSelected = true;
                    resolvedJobs.put(best.jobname, best);
                }
            }
        }
        System.out.println("[INFO] Jobs unicos despues de dedup: " + resolvedJobs.size() + " (de " + rawJobs.size() + " crudos)");
    }

    // =========================================================================
    // PASO 3: CONSTRUIR INDICES
    // =========================================================================
    public void buildIndices() {
        System.out.println("[INFO] Construyendo indices...");
        for (Job job : resolvedJobs.values()) {
            for (String c : job.outCond) {
                allOutConds.add(c);
                if (!condProducers.containsKey(c)) condProducers.put(c, new ArrayList<String>());
                condProducers.get(c).add(job.jobname);
            }
            for (String c : job.inCond) {
                allInConds.add(c);
                if (!condConsumers.containsKey(c)) condConsumers.put(c, new ArrayList<String>());
                condConsumers.get(c).add(job.jobname);
            }
        }
        System.out.println("[INFO] Condiciones producidas unicas: " + condProducers.size());
        System.out.println("[INFO] Condiciones consumidas unicas: " + condConsumers.size());
    }

    // =========================================================================
    // PASO 4: ANALISIS
    // =========================================================================
    // Listas resultado
    private List<String[]> initialJobs = new ArrayList<>();   // jobname, dc, inC, outC, brokenIn, brokenOut
    private List<String[]> finalJobs = new ArrayList<>();
    private List<String[]> criticalJobs = new ArrayList<>();
    private List<String[]> brokenIn = new ArrayList<>();      // jobname, dc, condicion
    private List<String[]> brokenOut = new ArrayList<>();
    private List<String[]> missingConds = new ArrayList<>();   // condicion, direccion, lista de jobs que la referencian
    private List<String[]> mirrorGroups = new ArrayList<>();   // jobname, dc, ver, inC, broken, selected

    // Grafo de dependencias: jobname -> set de jobnames que le siguen
    private Map<String, Set<String>> graphForward = new HashMap<>();
    // Grafo inverso: jobname -> set de jobnames que le preceden
    private Map<String, Set<String>> graphBackward = new HashMap<>();

    public void analyze() {
        System.out.println("[INFO] Analizando...");

        // 4a: Broken IN y condiciones faltantes
        System.out.println("[INFO]   Referencias rotas de entrada...");
        // condiciones que nadie produce y que alguien consume
        Map<String, List<String>> missingCondMap = new LinkedHashMap<>(); // cond -> [jobs que la esperan]

        for (Job job : resolvedJobs.values()) {
            int brkIn = 0;
            for (String c : job.inCond) {
                if (!allOutConds.contains(c)) {
                    brkIn++;
                    brokenIn.add(new String[]{job.jobname, job.datacenter, c});
                    if (!missingCondMap.containsKey(c)) missingCondMap.put(c, new ArrayList<String>());
                    if (!missingCondMap.get(c).contains(job.jobname))
                        missingCondMap.get(c).add(job.jobname);
                }
            }
        }

        // 4b: Broken OUT
        System.out.println("[INFO]   Referencias rotas de salida...");
        for (Job job : resolvedJobs.values()) {
            for (String c : job.outCond) {
                if (!allInConds.contains(c)) {
                    brokenOut.add(new String[]{job.jobname, job.datacenter, c});
                    // Tambien es una condicion "colgada"
                    // No la agregamos a missing porque missing es solo lo que alguien ESPERA y nadie produce
                }
            }
        }

        // 4c: Condiciones faltantes (alguien las espera en inCond pero nadie las produce en outCond)
        System.out.println("[INFO]   Condiciones faltantes...");
        for (Map.Entry<String, List<String>> e : missingCondMap.entrySet()) {
            StringBuilder refs = new StringBuilder();
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) refs.append(", ");
                if (i >= 20) { refs.append("...(+" + (e.getValue().size()-20) + " mas)"); break; }
                refs.append(e.getValue().get(i));
            }
            missingConds.add(new String[]{e.getKey(), String.valueOf(e.getValue().size()), refs.toString()});
        }
        // Ordenar por cantidad de referencias desc
        missingConds.sort(new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return Integer.compare(Integer.parseInt(b[1]), Integer.parseInt(a[1]));
            }
        });

        // 4d: Construir grafo de dependencias (job -> job) usando condiciones
        System.out.println("[INFO]   Construyendo grafo de dependencias...");
        for (Job job : resolvedJobs.values()) {
            if (!graphForward.containsKey(job.jobname)) graphForward.put(job.jobname, new HashSet<String>());
            if (!graphBackward.containsKey(job.jobname)) graphBackward.put(job.jobname, new HashSet<String>());
        }
        // Para cada condicion producida, ver quien la consume
        for (Map.Entry<String, List<String>> e : condProducers.entrySet()) {
            String cond = e.getKey();
            List<String> producers = e.getValue();
            List<String> consumers = condConsumers.get(cond);
            if (consumers != null) {
                for (String prod : producers) {
                    for (String cons : consumers) {
                        if (!prod.equals(cons)) {
                            if (!graphForward.containsKey(prod)) graphForward.put(prod, new HashSet<String>());
                            graphForward.get(prod).add(cons);
                            if (!graphBackward.containsKey(cons)) graphBackward.put(cons, new HashSet<String>());
                            graphBackward.get(cons).add(prod);
                        }
                    }
                }
            }
        }

        // 4e: Jobs iniciales - sin predecesores validos en el grafo
        System.out.println("[INFO]   Identificando jobs iniciales...");
        for (Job job : resolvedJobs.values()) {
            Set<String> preds = graphBackward.get(job.jobname);
            boolean hasPred = preds != null && !preds.isEmpty();
            if (!hasPred) {
                int brkIn = countBrokenIn(job);
                int brkOut = countBrokenOut(job);
                initialJobs.add(new String[]{job.jobname, job.datacenter,
                    String.valueOf(job.inCond.size()), String.valueOf(job.outCond.size()),
                    String.valueOf(brkIn), String.valueOf(brkOut)});
            }
        }

        // 4f: Jobs finales - sin sucesores validos en el grafo
        System.out.println("[INFO]   Identificando jobs finales...");
        for (Job job : resolvedJobs.values()) {
            Set<String> succs = graphForward.get(job.jobname);
            boolean hasSucc = succs != null && !succs.isEmpty();
            if (!hasSucc) {
                int brkIn = countBrokenIn(job);
                int brkOut = countBrokenOut(job);
                finalJobs.add(new String[]{job.jobname, job.datacenter,
                    String.valueOf(job.inCond.size()), String.valueOf(job.outCond.size()),
                    String.valueOf(brkIn), String.valueOf(brkOut)});
            }
        }

        // 4g: Jobs criticos - mayor cantidad de dependencias totales (inCond + outCond)
        System.out.println("[INFO]   Identificando jobs criticos...");
        List<String[]> allSummaries = new ArrayList<>();
        for (Job job : resolvedJobs.values()) {
            int brkIn = countBrokenIn(job);
            int brkOut = countBrokenOut(job);
            int total = job.inCond.size() + job.outCond.size();
            allSummaries.add(new String[]{job.jobname, job.datacenter, job.memname,
                String.valueOf(job.inCond.size()), String.valueOf(job.outCond.size()),
                String.valueOf(brkIn), String.valueOf(brkOut), String.valueOf(total)});
        }
        allSummaries.sort(new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return Integer.compare(Integer.parseInt(b[7]), Integer.parseInt(a[7]));
            }
        });
        int limit = Math.min(200, allSummaries.size());
        criticalJobs = new ArrayList<>(allSummaries.subList(0, limit));

        // 4h: Gemelos espejo
        System.out.println("[INFO]   Procesando gemelos espejo...");
        for (Map.Entry<String, List<Job>> entry : mirrorMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (Job j : entry.getValue()) {
                    int brk = countBrokenInRaw(j);
                    mirrorGroups.add(new String[]{j.jobname, j.datacenter,
                        String.valueOf(j.versionSerial), String.valueOf(j.inCond.size()),
                        String.valueOf(brk), j.mirrorSelected ? "SI" : "NO"});
                }
            }
        }

        System.out.println("[INFO] ====== RESUMEN ======");
        System.out.println("[INFO] Jobs crudos: " + rawJobs.size());
        System.out.println("[INFO] Jobs unicos: " + resolvedJobs.size());
        System.out.println("[INFO] Jobs iniciales: " + initialJobs.size());
        System.out.println("[INFO] Jobs finales: " + finalJobs.size());
        System.out.println("[INFO] Ref. rotas IN: " + brokenIn.size());
        System.out.println("[INFO] Ref. rotas OUT: " + brokenOut.size());
        System.out.println("[INFO] Condiciones faltantes: " + missingConds.size());
        System.out.println("[INFO] Gemelos espejo: " + mirrorGroups.size());
    }

    private int countBrokenIn(Job job) {
        int c = 0;
        for (String s : job.inCond) if (!allOutConds.contains(s)) c++;
        return c;
    }
    private int countBrokenOut(Job job) {
        int c = 0;
        for (String s : job.outCond) if (!allInConds.contains(s)) c++;
        return c;
    }
    // Cache para no recalcular en cada llamada
    private Set<String> _rawOutCondsCache = null;
    private int countBrokenInRaw(Job job) {
        // Usa todas las outCond crudas (no solo las resueltas) para comparar gemelos
        if (_rawOutCondsCache == null) {
            _rawOutCondsCache = new HashSet<>();
            for (Job j : rawJobs) _rawOutCondsCache.addAll(j.outCond);
        }
        int c = 0;
        for (String s : job.inCond) if (!_rawOutCondsCache.contains(s)) c++;
        return c;
    }

    // =========================================================================
    // PASO 5: EXPORTAR REPORTES TXT
    // =========================================================================
    public void exportTxt(String dir) throws IOException {
        Files.createDirectories(Paths.get(dir));

        // 00 Resumen
        try (PrintWriter pw = pw(dir + "/00_RESUMEN.txt")) {
            pw.println("================================================================");
            pw.println("  ANALISIS DE MIGRACION MAINFRAME -> CONTROL-M");
            pw.println("  Fecha: " + new Date());
            pw.println("================================================================");
            pw.println();
            pw.println("Jobs crudos leidos:            " + rawJobs.size());
            pw.println("Jobs unicos (dedup):           " + resolvedJobs.size());
            pw.println("Jobs iniciales:                " + initialJobs.size());
            pw.println("Jobs finales:                  " + finalJobs.size());
            pw.println("Jobs criticos (top 200):       " + criticalJobs.size());
            pw.println("Referencias rotas IN:          " + brokenIn.size());
            pw.println("Referencias rotas OUT:         " + brokenOut.size());
            pw.println("Condiciones faltantes:         " + missingConds.size());
            pw.println("Grupos gemelos espejo:         " + mirrorGroups.size());
            pw.println("Aristas grafo (forward):       " + countEdges(graphForward));
        }

        // 01 Iniciales
        try (PrintWriter pw = pw(dir + "/01_JOBS_INICIALES.txt")) {
            pw.println("JOBS INICIALES - Sin predecesores validos (disparan flujo)");
            pw.println(repeat("=", 100));
            pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", "JOBNAME","DATACENTER","IN","OUT","BRK_IN","BRK_OUT");
            pw.println(repeat("-", 100));
            for (String[] r : initialJobs)
                pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", r[0],r[1],r[2],r[3],r[4],r[5]);
            pw.println("\nTotal: " + initialJobs.size());
        }

        // 02 Finales
        try (PrintWriter pw = pw(dir + "/02_JOBS_FINALES.txt")) {
            pw.println("JOBS FINALES - Sin sucesores validos (ultimos en ejecutar)");
            pw.println(repeat("=", 100));
            pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", "JOBNAME","DATACENTER","IN","OUT","BRK_IN","BRK_OUT");
            pw.println(repeat("-", 100));
            for (String[] r : finalJobs)
                pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", r[0],r[1],r[2],r[3],r[4],r[5]);
            pw.println("\nTotal: " + finalJobs.size());
        }

        // 03 Criticos
        try (PrintWriter pw = pw(dir + "/03_JOBS_CRITICOS.txt")) {
            pw.println("JOBS CRITICOS - Mayor cantidad de dependencias totales");
            pw.println(repeat("=", 120));
            pw.printf("%-25s %-15s %-15s %-8s %-8s %-10s %-10s %-10s%n", "JOBNAME","DATACENTER","MEMNAME","IN","OUT","BRK_IN","BRK_OUT","TOTAL");
            pw.println(repeat("-", 120));
            for (String[] r : criticalJobs)
                pw.printf("%-25s %-15s %-15s %-8s %-8s %-10s %-10s %-10s%n", r[0],r[1],r[2],r[3],r[4],r[5],r[6],r[7]);
        }

        // 04 Rotas IN
        try (PrintWriter pw = pw(dir + "/04_REFERENCIAS_ROTAS_IN.txt")) {
            pw.println("REFERENCIAS ROTAS DE ENTRADA - inCond que ningun job produce en outCond");
            pw.println(repeat("=", 120));
            pw.printf("%-25s %-15s %-50s%n", "JOBNAME","DATACENTER","CONDICION_ROTA");
            pw.println(repeat("-", 120));
            for (String[] r : brokenIn)
                pw.printf("%-25s %-15s %-50s%n", r[0],r[1],r[2]);
            pw.println("\nTotal: " + brokenIn.size());
        }

        // 05 Rotas OUT
        try (PrintWriter pw = pw(dir + "/05_REFERENCIAS_ROTAS_OUT.txt")) {
            pw.println("REFERENCIAS ROTAS DE SALIDA - outCond que ningun job consume en inCond");
            pw.println(repeat("=", 120));
            pw.printf("%-25s %-15s %-50s%n", "JOBNAME","DATACENTER","CONDICION_ROTA");
            pw.println(repeat("-", 120));
            for (String[] r : brokenOut)
                pw.printf("%-25s %-15s %-50s%n", r[0],r[1],r[2]);
            pw.println("\nTotal: " + brokenOut.size());
        }

        // 06 Condiciones faltantes
        try (PrintWriter pw = pw(dir + "/06_CONDICIONES_FALTANTES.txt")) {
            pw.println("CONDICIONES FALTANTES - Condiciones que alguien espera pero nadie produce");
            pw.println("(Estas condiciones deberian existir como outCond de algun job)");
            pw.println(repeat("=", 140));
            pw.printf("%-50s %-8s %s%n", "CONDICION","#REFS","JOBS QUE LA ESPERAN");
            pw.println(repeat("-", 140));
            for (String[] r : missingConds)
                pw.printf("%-50s %-8s %s%n", r[0],r[1],r[2]);
            pw.println("\nTotal condiciones faltantes: " + missingConds.size());
        }

        // 07 Gemelos
        try (PrintWriter pw = pw(dir + "/07_GEMELOS_ESPEJO.txt")) {
            pw.println("GEMELOS ESPEJO - Mismo jobname en multiples datacenters/versiones");
            pw.println(repeat("=", 110));
            pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", "JOBNAME","DATACENTER","VER","IN_COND","BROKEN","ELEGIDO");
            pw.println(repeat("-", 110));
            for (String[] r : mirrorGroups)
                pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", r[0],r[1],r[2],r[3],r[4],r[5]);
        }

        System.out.println("[INFO] Reportes TXT exportados a: " + dir);
    }

    // =========================================================================
    // PASO 6: REPORTE HTML
    // =========================================================================
    public void exportHtmlReport(String dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n<title>Reporte de Analisis - Migracion Mainframe</title>\n<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box;}\n");
        sb.append("body{font-family:'Courier New',monospace;background:#0a0a1a;color:#c0c0c0;padding:20px;}\n");
        sb.append("h1{color:#00ffaa;text-align:center;margin-bottom:5px;font-size:22px;}\n");
        sb.append("h2{color:#00ccff;margin:30px 0 10px;font-size:16px;border-bottom:1px solid #333;padding-bottom:5px;}\n");
        sb.append(".subtitle{text-align:center;color:#666;margin-bottom:30px;font-size:12px;}\n");
        sb.append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;margin:20px 0;}\n");
        sb.append(".stat{background:#111;border:1px solid #333;border-radius:6px;padding:12px;text-align:center;}\n");
        sb.append(".stat .num{font-size:28px;font-weight:bold;color:#00ffaa;}\n");
        sb.append(".stat .lbl{font-size:11px;color:#888;margin-top:4px;}\n");
        sb.append(".stat.warn .num{color:#ffaa00;}\n");
        sb.append(".stat.err .num{color:#ff4444;}\n");
        sb.append("table{width:100%;border-collapse:collapse;margin:10px 0;font-size:11px;}\n");
        sb.append("th{background:#1a1a2e;color:#00ccff;padding:6px 8px;text-align:left;position:sticky;top:0;}\n");
        sb.append("td{padding:4px 8px;border-bottom:1px solid #1a1a2e;}\n");
        sb.append("tr:hover td{background:#1a1a2e;}\n");
        sb.append(".tbl-wrap{max-height:400px;overflow-y:auto;border:1px solid #333;border-radius:4px;margin-bottom:10px;}\n");
        sb.append("input[type=text]{background:#111;border:1px solid #444;color:#fff;padding:6px 10px;border-radius:4px;margin:5px 0;width:300px;font-family:inherit;}\n");
        sb.append(".tag{display:inline-block;padding:1px 6px;border-radius:3px;font-size:10px;margin:1px;}\n");
        sb.append(".tag-init{background:#004422;color:#00ffaa;}\n");
        sb.append(".tag-final{background:#442200;color:#ffaa00;}\n");
        sb.append(".tag-crit{background:#440000;color:#ff4444;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>&#9881; REPORTE DE ANALISIS - MIGRACION MAINFRAME A CONTROL-M</h1>\n");
        sb.append("<div class=\"subtitle\">Generado: " + new Date() + "</div>\n");

        // Stats
        sb.append("<div class=\"stats\">\n");
        addStat(sb, "", String.valueOf(rawJobs.size()), "Jobs Crudos");
        addStat(sb, "", String.valueOf(resolvedJobs.size()), "Jobs Unicos (dedup)");
        addStat(sb, "", String.valueOf(initialJobs.size()), "Jobs Iniciales");
        addStat(sb, "", String.valueOf(finalJobs.size()), "Jobs Finales");
        addStat(sb, "warn", String.valueOf(brokenIn.size()), "Ref. Rotas IN");
        addStat(sb, "warn", String.valueOf(brokenOut.size()), "Ref. Rotas OUT");
        addStat(sb, "err", String.valueOf(missingConds.size()), "Cond. Faltantes");
        addStat(sb, "", String.valueOf(mirrorGroups.size()/Math.max(1, countMirrorGroups())), "Grupos Gemelos");
        sb.append("</div>\n");

        // Tablas
        addTableSection(sb, "JOBS INICIALES (disparan el flujo)", new String[]{"Jobname","DC","InCond","OutCond","Broken IN","Broken OUT"}, initialJobs, "filter-init");
        addTableSection(sb, "JOBS FINALES (ultimos en ejecutar)", new String[]{"Jobname","DC","InCond","OutCond","Broken IN","Broken OUT"}, finalJobs, "filter-final");
        addTableSection(sb, "JOBS CRITICOS (top 200 por dependencias)", new String[]{"Jobname","DC","Memname","InCond","OutCond","Broken IN","Broken OUT","Total"}, criticalJobs, "filter-crit");
        addTableSection(sb, "REFERENCIAS ROTAS DE ENTRADA", new String[]{"Jobname","DC","Condicion Rota"}, brokenIn, "filter-brkin");
        addTableSection(sb, "REFERENCIAS ROTAS DE SALIDA", new String[]{"Jobname","DC","Condicion Rota"}, brokenOut, "filter-brkout");
        addTableSection(sb, "CONDICIONES FALTANTES (nadie las produce)", new String[]{"Condicion","#Refs","Jobs que la esperan"}, missingConds, "filter-miss");
        addTableSection(sb, "GEMELOS ESPEJO", new String[]{"Jobname","DC","Version","InCond","Broken","Elegido"}, mirrorGroups, "filter-mirror");

        // Script filtro
        sb.append("<script>\n");
        sb.append("document.querySelectorAll('input[type=text]').forEach(function(inp){\n");
        sb.append("  inp.addEventListener('input',function(){\n");
        sb.append("    var val=this.value.toUpperCase();\n");
        sb.append("    var tbody=this.parentElement.querySelector('tbody');\n");
        sb.append("    var rows=tbody.querySelectorAll('tr');\n");
        sb.append("    for(var i=0;i<rows.length;i++){\n");
        sb.append("      rows[i].style.display=rows[i].textContent.toUpperCase().indexOf(val)>=0?'':'none';\n");
        sb.append("    }\n");
        sb.append("  });\n");
        sb.append("});\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>");

        Files.write(Paths.get(dir + "/08_REPORTE.html"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[INFO] Reporte HTML exportado a: " + dir + "/08_REPORTE.html");
    }

    private int countMirrorGroups() {
        Set<String> names = new HashSet<>();
        for (String[] r : mirrorGroups) names.add(r[0]);
        return Math.max(1, names.size());
    }

    private void addStat(StringBuilder sb, String cls, String num, String lbl) {
        sb.append("<div class=\"stat " + cls + "\"><div class=\"num\">" + num + "</div><div class=\"lbl\">" + lbl + "</div></div>\n");
    }

    private void addTableSection(StringBuilder sb, String title, String[] headers, List<String[]> data, String filterId) {
        sb.append("<h2>" + title + " (" + data.size() + ")</h2>\n");
        sb.append("<div>\n");
        sb.append("<input type=\"text\" placeholder=\"Filtrar...\" id=\"" + filterId + "\">\n");
        sb.append("<div class=\"tbl-wrap\"><table><thead><tr>");
        for (String h : headers) sb.append("<th>" + h + "</th>");
        sb.append("</tr></thead><tbody>\n");
        for (String[] row : data) {
            sb.append("<tr>");
            for (String cell : row) {
                String escaped = cell.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
                sb.append("<td>" + escaped + "</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table></div></div>\n");
    }

    // =========================================================================
    // PASO 7: VISOR 3D CON THREE.JS (job semilla, profundidad +-N)
    // =========================================================================
    public void exportThreeJsViewer(String dir, String seedJob, int depth) throws IOException {
        System.out.println("[INFO] Generando visor 3D para job semilla: " + (seedJob.isEmpty() ? "(todos los iniciales)" : seedJob) + ", profundidad: " + depth);

        // Recolectar nodos via BFS bidireccional
        Set<String> visitedNodes = new LinkedHashSet<>();
        Map<String, String[]> edgesCollected = new LinkedHashMap<>(); // "src->tgt" -> [src,tgt]
        Map<String, String> nodeType = new HashMap<>(); // jobname -> tipo

        Set<String> initialSet = new HashSet<>();
        for (String[] r : initialJobs) initialSet.add(r[0]);
        Set<String> finalSet = new HashSet<>();
        for (String[] r : finalJobs) finalSet.add(r[0]);
        Set<String> critSet = new HashSet<>();
        for (String[] r : criticalJobs) critSet.add(r[0]);

        List<String> seeds = new ArrayList<>();
        if (seedJob != null && !seedJob.isEmpty()) {
            String upper = seedJob.toUpperCase();
            if (resolvedJobs.containsKey(upper)) {
                seeds.add(upper);
            } else {
                System.out.println("[WARN] Job semilla '" + seedJob + "' no encontrado. Usando jobs iniciales.");
                for (String[] r : initialJobs) seeds.add(r[0]);
            }
        } else {
            for (String[] r : initialJobs) seeds.add(r[0]);
        }

        // BFS FORWARD
        Set<String> visited = new HashSet<>();
        Queue<String[]> queue = new LinkedList<>(); // [jobname, depthLevel]
        for (String s : seeds) {
            queue.add(new String[]{s, "0"});
            visited.add(s);
        }
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            String jn = cur[0];
            int d = Integer.parseInt(cur[1]);
            visitedNodes.add(jn);
            if (d < depth) {
                Set<String> nexts = graphForward.get(jn);
                if (nexts != null) {
                    for (String next : nexts) {
                        String ek = jn + "->" + next;
                        edgesCollected.put(ek, new String[]{jn, next});
                        if (!visited.contains(next)) {
                            visited.add(next);
                            queue.add(new String[]{next, String.valueOf(d + 1)});
                        }
                    }
                }
            }
        }

        // BFS BACKWARD
        visited.clear();
        for (String s : seeds) {
            queue.add(new String[]{s, "0"});
            visited.add(s);
        }
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            String jn = cur[0];
            int d = Integer.parseInt(cur[1]);
            visitedNodes.add(jn);
            if (d < depth) {
                Set<String> preds = graphBackward.get(jn);
                if (preds != null) {
                    for (String pred : preds) {
                        String ek = pred + "->" + jn;
                        edgesCollected.put(ek, new String[]{pred, jn});
                        if (!visited.contains(pred)) {
                            visited.add(pred);
                            queue.add(new String[]{pred, String.valueOf(d + 1)});
                        }
                    }
                }
            }
        }

        // Clasificar nodos
        for (String n : visitedNodes) {
            if (initialSet.contains(n)) nodeType.put(n, "initial");
            else if (finalSet.contains(n)) nodeType.put(n, "final");
            else if (critSet.contains(n)) nodeType.put(n, "critical");
            else nodeType.put(n, "normal");
            // Marcar seeds
            if (seeds.contains(n)) nodeType.put(n, "seed");
        }

        System.out.println("[INFO] Visor: " + visitedNodes.size() + " nodos, " + edgesCollected.size() + " aristas");

        // Generar HTML con Three.js embebido
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>Visor 3D de Dependencias</title>\n");
        sb.append("<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box;}\n");
        sb.append("body{background:#000;overflow:hidden;font-family:monospace;}\n");
        sb.append("canvas{display:block;}\n");
        sb.append("#info{position:absolute;top:10px;left:10px;color:#0f0;font-size:12px;background:rgba(0,0,0,0.7);padding:10px;border-radius:6px;pointer-events:none;z-index:10;}\n");
        sb.append("#tooltip{position:absolute;display:none;color:#fff;background:rgba(0,0,30,0.9);border:1px solid #0af;padding:8px 12px;border-radius:4px;font-size:13px;pointer-events:none;z-index:20;max-width:400px;}\n");
        sb.append("#controls{position:absolute;bottom:10px;left:10px;color:#0f0;font-size:11px;background:rgba(0,0,0,0.7);padding:8px;border-radius:6px;z-index:10;}\n");
        sb.append("#legend{position:absolute;top:10px;right:10px;color:#ccc;font-size:11px;background:rgba(0,0,0,0.7);padding:10px;border-radius:6px;z-index:10;}\n");
        sb.append(".leg-item{display:flex;align-items:center;gap:6px;margin:3px 0;}\n");
        sb.append(".leg-dot{width:10px;height:10px;border-radius:50%;}\n");
        sb.append("#search-box{position:absolute;top:10px;left:50%;transform:translateX(-50%);z-index:10;}\n");
        sb.append("#search-box input{background:rgba(0,0,0,0.8);border:1px solid #0af;color:#fff;padding:6px 12px;border-radius:4px;font-family:monospace;width:300px;font-size:13px;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div id=\"info\">Nodos: " + visitedNodes.size() + " | Aristas: " + edgesCollected.size());
        if (seedJob != null && !seedJob.isEmpty()) sb.append(" | Semilla: " + seedJob.toUpperCase());
        sb.append(" | Profundidad: &plusmn;" + depth);
        sb.append("</div>\n");

        sb.append("<div id=\"tooltip\"></div>\n");

        sb.append("<div id=\"search-box\"><input type=\"text\" id=\"searchInput\" placeholder=\"Buscar jobname...\"></div>\n");

        sb.append("<div id=\"legend\">\n");
        sb.append("<b>Leyenda:</b><br>\n");
        sb.append("<div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#ff00ff\"></div> Semilla</div>\n");
        sb.append("<div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#00ff88\"></div> Inicial</div>\n");
        sb.append("<div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#ff8800\"></div> Final</div>\n");
        sb.append("<div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#ff0044\"></div> Critico</div>\n");
        sb.append("<div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#0088ff\"></div> Normal</div>\n");
        sb.append("</div>\n");

        sb.append("<div id=\"controls\">\n");
        sb.append("Arrastrar: Rotar | Scroll: Zoom | Click: Info\n");
        sb.append("</div>\n");

        // Datos JSON inlined
        sb.append("<script>\n");
        sb.append("var GRAPH_DATA = {\n");
        sb.append("  nodes: [\n");
        int idx = 0;
        Map<String, Integer> nodeIdx = new HashMap<>();
        for (String n : visitedNodes) {
            nodeIdx.put(n, idx);
            String type = nodeType.containsKey(n) ? nodeType.get(n) : "normal";
            Job j = resolvedJobs.get(n);
            int inC = j != null ? j.inCond.size() : 0;
            int outC = j != null ? j.outCond.size() : 0;
            String dc = j != null ? j.datacenter : "N/A";
            if (idx > 0) sb.append(",\n");
            sb.append("    {id:\"" + esc(n) + "\",type:\"" + type + "\",inC:" + inC + ",outC:" + outC + ",dc:\"" + esc(dc) + "\"}");
            idx++;
        }
        sb.append("\n  ],\n  edges: [\n");
        int ei = 0;
        for (String[] e : edgesCollected.values()) {
            if (ei > 0) sb.append(",\n");
            sb.append("    {s:\"" + esc(e[0]) + "\",t:\"" + esc(e[1]) + "\"}");
            ei++;
        }
        sb.append("\n  ]\n};\n");
        sb.append("</script>\n");

        // Three.js (r128 minificado inline seria enorme, usamos CDN como fallback)
        // Per user request, no external CSS/frameworks, but Three.js IS javascript, not a framework. It's a library.
        // We'll load three.js from CDN but provide a fallback message.
        sb.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js\"></script>\n");
        sb.append("<script>\n");
        sb.append("if(typeof THREE==='undefined'){document.body.innerHTML='<div style=\"color:red;padding:40px\">Error: No se pudo cargar Three.js. Requiere conexion a internet para CDN.</div>';}\n");
        sb.append("</script>\n");

        // Rendering script
        sb.append("<script>\n");
        sb.append("(function(){\n");
        sb.append("if(typeof THREE==='undefined') return;\n\n");

        sb.append("var W=window.innerWidth, H=window.innerHeight;\n");
        sb.append("var scene=new THREE.Scene();\n");
        sb.append("scene.background=new THREE.Color(0x000011);\n");
        sb.append("var camera=new THREE.PerspectiveCamera(60,W/H,1,50000);\n");
        sb.append("camera.position.set(0,0,500);\n");
        sb.append("var renderer=new THREE.WebGLRenderer({antialias:true});\n");
        sb.append("renderer.setSize(W,H);\n");
        sb.append("document.body.appendChild(renderer.domElement);\n\n");

        // Colors
        sb.append("var COLORS={seed:0xff00ff,initial:0x00ff88,'final':0xff8800,critical:0xff0044,normal:0x0088ff};\n\n");

        // Create nodes as spheres
        sb.append("var nodes=GRAPH_DATA.nodes;\n");
        sb.append("var edges=GRAPH_DATA.edges;\n");
        sb.append("var nodeMeshes=[];\n");
        sb.append("var nodeMap={};\n");
        sb.append("var nodePositions={};\n\n");

        // Layout: force-directed simple (initial placement then iterate)
        sb.append("// Initial placement: random sphere\n");
        sb.append("var N=nodes.length;\n");
        sb.append("var spread=Math.max(200, Math.sqrt(N)*15);\n");
        sb.append("for(var i=0;i<N;i++){\n");
        sb.append("  var n=nodes[i];\n");
        sb.append("  var phi=Math.acos(2*Math.random()-1);\n");
        sb.append("  var theta=2*Math.PI*Math.random();\n");
        sb.append("  var r=spread*(0.3+0.7*Math.random());\n");
        sb.append("  n.x=r*Math.sin(phi)*Math.cos(theta);\n");
        sb.append("  n.y=r*Math.sin(phi)*Math.sin(theta);\n");
        sb.append("  n.z=r*Math.cos(phi);\n");
        sb.append("  n.vx=0;n.vy=0;n.vz=0;\n");
        sb.append("  nodeMap[n.id]=n;\n");
        sb.append("}\n\n");

        // Build adjacency for layout
        sb.append("var adj={};\n");
        sb.append("for(var i=0;i<edges.length;i++){\n");
        sb.append("  var e=edges[i];\n");
        sb.append("  if(!adj[e.s])adj[e.s]=[];\n");
        sb.append("  if(!adj[e.t])adj[e.t]=[];\n");
        sb.append("  adj[e.s].push(e.t);\n");
        sb.append("  adj[e.t].push(e.s);\n");
        sb.append("}\n\n");

        // Force-directed layout iterations (run synchronously for small graphs, async for large)
        sb.append("var ITERATIONS=N>5000?80:N>1000?150:300;\n");
        sb.append("var repulsion=N>5000?800:N>1000?500:300;\n");
        sb.append("var attraction=0.005;\n");
        sb.append("var damping=0.9;\n\n");

        sb.append("function runLayout(iters){\n");
        sb.append("  for(var iter=0;iter<iters;iter++){\n");
        sb.append("    var dt=0.3;\n");
        // Repulsion (Barnes-Hut approximation for large N: simple grid)
        // For simplicity and correctness, use direct O(N^2) but cap at 5000 nodes
        sb.append("    if(N<=5000){\n");
        sb.append("      for(var i=0;i<N;i++){\n");
        sb.append("        for(var j=i+1;j<N;j++){\n");
        sb.append("          var dx=nodes[i].x-nodes[j].x;\n");
        sb.append("          var dy=nodes[i].y-nodes[j].y;\n");
        sb.append("          var dz=nodes[i].z-nodes[j].z;\n");
        sb.append("          var d2=dx*dx+dy*dy+dz*dz+1;\n");
        sb.append("          var f=repulsion/d2;\n");
        sb.append("          var d=Math.sqrt(d2);\n");
        sb.append("          var fx=f*dx/d, fy=f*dy/d, fz=f*dz/d;\n");
        sb.append("          nodes[i].vx+=fx;nodes[i].vy+=fy;nodes[i].vz+=fz;\n");
        sb.append("          nodes[j].vx-=fx;nodes[j].vy-=fy;nodes[j].vz-=fz;\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    } else {\n");
        // For large N, use only neighbor repulsion + random sample
        sb.append("      for(var i=0;i<N;i++){\n");
        sb.append("        var neighbors=adj[nodes[i].id]||[];\n");
        sb.append("        for(var k=0;k<neighbors.length;k++){\n");
        sb.append("          var nb=nodeMap[neighbors[k]];\n");
        sb.append("          if(!nb)continue;\n");
        sb.append("          var dx=nodes[i].x-nb.x,dy=nodes[i].y-nb.y,dz=nodes[i].z-nb.z;\n");
        sb.append("          var d2=dx*dx+dy*dy+dz*dz+1;\n");
        sb.append("          var f=repulsion*2/d2;\n");
        sb.append("          var d=Math.sqrt(d2);\n");
        sb.append("          nodes[i].vx+=f*dx/d;nodes[i].vy+=f*dy/d;nodes[i].vz+=f*dz/d;\n");
        sb.append("        }\n");
        sb.append("        // random sample repulsion\n");
        sb.append("        for(var s=0;s<5;s++){\n");
        sb.append("          var j=Math.floor(Math.random()*N);\n");
        sb.append("          if(j===i)continue;\n");
        sb.append("          var dx=nodes[i].x-nodes[j].x,dy=nodes[i].y-nodes[j].y,dz=nodes[i].z-nodes[j].z;\n");
        sb.append("          var d2=dx*dx+dy*dy+dz*dz+1;\n");
        sb.append("          var f=repulsion/d2;\n");
        sb.append("          var d=Math.sqrt(d2);\n");
        sb.append("          nodes[i].vx+=f*dx/d;nodes[i].vy+=f*dy/d;nodes[i].vz+=f*dz/d;\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        // Attraction
        sb.append("    for(var i=0;i<edges.length;i++){\n");
        sb.append("      var sn=nodeMap[edges[i].s],tn=nodeMap[edges[i].t];\n");
        sb.append("      if(!sn||!tn)continue;\n");
        sb.append("      var dx=tn.x-sn.x,dy=tn.y-sn.y,dz=tn.z-sn.z;\n");
        sb.append("      var d=Math.sqrt(dx*dx+dy*dy+dz*dz)+0.1;\n");
        sb.append("      var f=attraction*d;\n");
        sb.append("      sn.vx+=f*dx/d;sn.vy+=f*dy/d;sn.vz+=f*dz/d;\n");
        sb.append("      tn.vx-=f*dx/d;tn.vy-=f*dy/d;tn.vz-=f*dz/d;\n");
        sb.append("    }\n");
        // Update positions
        sb.append("    for(var i=0;i<N;i++){\n");
        sb.append("      nodes[i].vx*=damping;nodes[i].vy*=damping;nodes[i].vz*=damping;\n");
        sb.append("      nodes[i].x+=nodes[i].vx*dt;\n");
        sb.append("      nodes[i].y+=nodes[i].vy*dt;\n");
        sb.append("      nodes[i].z+=nodes[i].vz*dt;\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("runLayout(ITERATIONS);\n\n");

        // Create Three.js objects
        sb.append("// Create sphere meshes\n");
        sb.append("var sphereGeo=new THREE.SphereGeometry(1,8,8);\n");
        sb.append("for(var i=0;i<N;i++){\n");
        sb.append("  var n=nodes[i];\n");
        sb.append("  var col=COLORS[n.type]||COLORS.normal;\n");
        sb.append("  var mat=new THREE.MeshBasicMaterial({color:col});\n");
        sb.append("  var mesh=new THREE.Mesh(sphereGeo,mat);\n");
        sb.append("  var sz=Math.max(1.5, Math.min(6, Math.log2(1+(n.inC||0)+(n.outC||0))));\n");
        sb.append("  mesh.scale.set(sz,sz,sz);\n");
        sb.append("  mesh.position.set(n.x,n.y,n.z);\n");
        sb.append("  mesh.userData={id:n.id,type:n.type,inC:n.inC,outC:n.outC,dc:n.dc};\n");
        sb.append("  scene.add(mesh);\n");
        sb.append("  nodeMeshes.push(mesh);\n");
        sb.append("  nodePositions[n.id]=mesh.position;\n");
        sb.append("}\n\n");

        // Create edges as lines
        sb.append("var edgeMat=new THREE.LineBasicMaterial({color:0x224466,transparent:true,opacity:0.3});\n");
        sb.append("for(var i=0;i<edges.length;i++){\n");
        sb.append("  var sp=nodePositions[edges[i].s],tp=nodePositions[edges[i].t];\n");
        sb.append("  if(!sp||!tp)continue;\n");
        sb.append("  var geo=new THREE.BufferGeometry().setFromPoints([sp.clone(),tp.clone()]);\n");
        sb.append("  var line=new THREE.Line(geo,edgeMat);\n");
        sb.append("  scene.add(line);\n");
        sb.append("}\n\n");

        // Camera controls (manual orbit)
        sb.append("// Orbit controls\n");
        sb.append("var isDragging=false,prevX=0,prevY=0;\n");
        sb.append("var spherical={theta:0,phi:Math.PI/2,radius:spread*2};\n");
        sb.append("var target=new THREE.Vector3(0,0,0);\n\n");

        sb.append("function updateCamera(){\n");
        sb.append("  camera.position.x=target.x+spherical.radius*Math.sin(spherical.phi)*Math.cos(spherical.theta);\n");
        sb.append("  camera.position.y=target.y+spherical.radius*Math.cos(spherical.phi);\n");
        sb.append("  camera.position.z=target.z+spherical.radius*Math.sin(spherical.phi)*Math.sin(spherical.theta);\n");
        sb.append("  camera.lookAt(target);\n");
        sb.append("}\n");
        sb.append("updateCamera();\n\n");

        sb.append("renderer.domElement.addEventListener('mousedown',function(e){\n");
        sb.append("  if(e.button===0){isDragging=true;prevX=e.clientX;prevY=e.clientY;}\n");
        sb.append("});\n");
        sb.append("renderer.domElement.addEventListener('mousemove',function(e){\n");
        sb.append("  if(isDragging){\n");
        sb.append("    var dx=e.clientX-prevX, dy=e.clientY-prevY;\n");
        sb.append("    spherical.theta-=dx*0.005;\n");
        sb.append("    spherical.phi=Math.max(0.1,Math.min(Math.PI-0.1,spherical.phi-dy*0.005));\n");
        sb.append("    prevX=e.clientX;prevY=e.clientY;\n");
        sb.append("    updateCamera();\n");
        sb.append("  }\n");
        sb.append("  // Hover tooltip\n");
        sb.append("  checkHover(e);\n");
        sb.append("});\n");
        sb.append("renderer.domElement.addEventListener('mouseup',function(){isDragging=false;});\n");
        sb.append("renderer.domElement.addEventListener('wheel',function(e){\n");
        sb.append("  spherical.radius=Math.max(10,spherical.radius*(e.deltaY>0?1.1:0.9));\n");
        sb.append("  updateCamera();\n");
        sb.append("});\n\n");

        // Raycaster for hover
        sb.append("var raycaster=new THREE.Raycaster();\n");
        sb.append("raycaster.params.Points={threshold:5};\n");
        sb.append("var mouse=new THREE.Vector2();\n");
        sb.append("var tooltip=document.getElementById('tooltip');\n\n");

        sb.append("function checkHover(e){\n");
        sb.append("  mouse.x=(e.clientX/W)*2-1;\n");
        sb.append("  mouse.y=-(e.clientY/H)*2+1;\n");
        sb.append("  raycaster.setFromCamera(mouse,camera);\n");
        sb.append("  var hits=raycaster.intersectObjects(nodeMeshes);\n");
        sb.append("  if(hits.length>0){\n");
        sb.append("    var d=hits[0].object.userData;\n");
        sb.append("    tooltip.style.display='block';\n");
        sb.append("    tooltip.style.left=(e.clientX+15)+'px';\n");
        sb.append("    tooltip.style.top=(e.clientY+15)+'px';\n");
        sb.append("    tooltip.innerHTML='<b>'+d.id+'</b><br>Tipo: '+d.type+'<br>DC: '+d.dc+'<br>InCond: '+d.inC+'<br>OutCond: '+d.outC;\n");
        sb.append("  } else {\n");
        sb.append("    tooltip.style.display='none';\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        // Search functionality
        sb.append("var searchInput=document.getElementById('searchInput');\n");
        sb.append("searchInput.addEventListener('keydown',function(e){\n");
        sb.append("  if(e.key==='Enter'){\n");
        sb.append("    var val=this.value.toUpperCase();\n");
        sb.append("    // Reset all to original color\n");
        sb.append("    for(var i=0;i<nodeMeshes.length;i++){\n");
        sb.append("      var t=nodeMeshes[i].userData.type;\n");
        sb.append("      nodeMeshes[i].material.color.setHex(COLORS[t]||COLORS.normal);\n");
        sb.append("    }\n");
        sb.append("    if(!val)return;\n");
        sb.append("    for(var i=0;i<nodeMeshes.length;i++){\n");
        sb.append("      if(nodeMeshes[i].userData.id.indexOf(val)>=0){\n");
        sb.append("        nodeMeshes[i].material.color.setHex(0xffffff);\n");
        sb.append("        // Move camera to node\n");
        sb.append("        target.copy(nodeMeshes[i].position);\n");
        sb.append("        spherical.radius=100;\n");
        sb.append("        updateCamera();\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("});\n\n");

        // Resize
        sb.append("window.addEventListener('resize',function(){\n");
        sb.append("  W=window.innerWidth;H=window.innerHeight;\n");
        sb.append("  camera.aspect=W/H;camera.updateProjectionMatrix();\n");
        sb.append("  renderer.setSize(W,H);\n");
        sb.append("});\n\n");

        // Animate
        sb.append("function animate(){\n");
        sb.append("  requestAnimationFrame(animate);\n");
        sb.append("  renderer.render(scene,camera);\n");
        sb.append("}\n");
        sb.append("animate();\n\n");

        sb.append("})();\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>\n");

        Files.write(Paths.get(dir + "/09_VISOR_3D.html"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[INFO] Visor 3D exportado a: " + dir + "/09_VISOR_3D.html");
    }

    // =========================================================================
    // UTILIDADES
    // =========================================================================
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static PrintWriter pw(String path) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
    }

    private static int countEdges(Map<String, Set<String>> graph) {
        int count = 0;
        for (Set<String> s : graph.values()) count += s.size();
        return count;
    }

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java -Xmx4g -cp \".:json-20231013.jar\" JobAnalyzer <ruta_json> [profundidad] [job_semilla]");
            System.out.println();
            System.out.println("  <ruta_json>    : Archivo JSON con los jobs");
            System.out.println("  [profundidad]  : Profundidad BFS para visor 3D (default: 5)");
            System.out.println("  [job_semilla]  : Job semilla para visor 3D (default: todos los iniciales)");
            System.out.println();
            System.out.println("Ejemplo:");
            System.out.println("  java -Xmx4g -cp \".:json-20231013.jar\" JobAnalyzer datos.json 3 HABJG271");
            System.exit(1);
        }

        String jsonPath = args[0];
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String seedJob = args.length > 2 ? args[2] : "";

        String outputDir = "analisis";

        JobAnalyzer analyzer = new JobAnalyzer();

        // 1. Cargar
        analyzer.loadJson(jsonPath);

        // 2. Resolver gemelos
        analyzer.resolveMirrors();

        // 3. Indices
        analyzer.buildIndices();

        // 4. Analisis
        analyzer.analyze();

        // 5. Exportar TXT
        analyzer.exportTxt(outputDir);

        // 6. Exportar HTML
        analyzer.exportHtmlReport(outputDir);

        // 7. Exportar Visor 3D
        analyzer.exportThreeJsViewer(outputDir, seedJob, depth);

        System.out.println();
        System.out.println("[INFO] ============================================");
        System.out.println("[INFO] ANALISIS COMPLETADO");
        System.out.println("[INFO] Resultados en carpeta: " + outputDir + "/");
        System.out.println("[INFO] ============================================");
        System.out.println("[INFO] Archivos generados:");
        System.out.println("[INFO]   00_RESUMEN.txt");
        System.out.println("[INFO]   01_JOBS_INICIALES.txt");
        System.out.println("[INFO]   02_JOBS_FINALES.txt");
        System.out.println("[INFO]   03_JOBS_CRITICOS.txt");
        System.out.println("[INFO]   04_REFERENCIAS_ROTAS_IN.txt");
        System.out.println("[INFO]   05_REFERENCIAS_ROTAS_OUT.txt");
        System.out.println("[INFO]   06_CONDICIONES_FALTANTES.txt");
        System.out.println("[INFO]   07_GEMELOS_ESPEJO.txt");
        System.out.println("[INFO]   08_REPORTE.html");
        System.out.println("[INFO]   09_VISOR_3D.html");
    }
}
