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
 *   java -Xmx4g -cp ".:json-20231013.jar" JobAnalyzer <ruta_json> [profundidad] [job_semilla] [job_arbol]
 *
 * PARAMETROS:
 *   <ruta_json>    : Archivo JSON con los jobs (requerido)
 *   [profundidad]  : Profundidad BFS para visor 3D general (default: 5)
 *   [job_semilla]  : Job semilla para visor 3D general (default: todos los iniciales)
 *   [job_arbol]    : Job extra para generar su arbol completo de dependencias
 *
 * EJEMPLO:
 *   java -Xmx4g -cp ".:json-20231013.jar" JobAnalyzer datos.json 5 HABJG271 HABJG272
 *
 * SALIDA: Carpeta "analisis/" con reportes TXT, HTML y visor 3D
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

    private Map<String, List<String>> condProducers = new HashMap<>();
    private Map<String, List<String>> condConsumers = new HashMap<>();

    private Set<String> allOutConds = new HashSet<>();
    private Set<String> allInConds = new HashSet<>();

    // Resultados
    private List<String[]> initialJobs = new ArrayList<>();
    private List<String[]> finalJobs = new ArrayList<>();
    private List<String[]> criticalJobs = new ArrayList<>();
    private List<String[]> brokenIn = new ArrayList<>();
    private List<String[]> brokenOut = new ArrayList<>();
    private List<String[]> missingConds = new ArrayList<>();
    private List<String[]> mirrorGroups = new ArrayList<>();

    private Map<String, Set<String>> graphForward = new HashMap<>();
    private Map<String, Set<String>> graphBackward = new HashMap<>();

    private Set<String> initialSet = new HashSet<>();
    private Set<String> finalSet = new HashSet<>();

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
    public void analyze() {
        System.out.println("[INFO] Analizando...");

        // 4a: Broken IN y condiciones faltantes
        System.out.println("[INFO]   Referencias rotas de entrada...");
        Map<String, List<String>> missingCondMap = new LinkedHashMap<>();

        for (Job job : resolvedJobs.values()) {
            for (String c : job.inCond) {
                if (!allOutConds.contains(c)) {
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
                }
            }
        }

        // 4c: Condiciones faltantes
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
        missingConds.sort(new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return Integer.compare(Integer.parseInt(b[1]), Integer.parseInt(a[1]));
            }
        });

        // 4d: Construir grafo
        System.out.println("[INFO]   Construyendo grafo de dependencias...");
        for (Job job : resolvedJobs.values()) {
            if (!graphForward.containsKey(job.jobname)) graphForward.put(job.jobname, new HashSet<String>());
            if (!graphBackward.containsKey(job.jobname)) graphBackward.put(job.jobname, new HashSet<String>());
        }
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

        // 4e: Jobs iniciales
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
                initialSet.add(job.jobname);
            }
        }

        // 4f: Jobs finales
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
                finalSet.add(job.jobname);
            }
        }

        // 4g: Jobs criticos
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
    private Set<String> _rawOutCondsCache = null;
    private int countBrokenInRaw(Job job) {
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
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>&#9881; REPORTE DE ANALISIS - MIGRACION MAINFRAME A CONTROL-M</h1>\n");
        sb.append("<div class=\"subtitle\">Generado: " + new Date() + "</div>\n");

        sb.append("<div class=\"stats\">\n");
        addStat(sb, "", String.valueOf(rawJobs.size()), "Jobs Crudos");
        addStat(sb, "", String.valueOf(resolvedJobs.size()), "Jobs Unicos (dedup)");
        addStat(sb, "", String.valueOf(initialJobs.size()), "Jobs Iniciales");
        addStat(sb, "", String.valueOf(finalJobs.size()), "Jobs Finales");
        addStat(sb, "warn", String.valueOf(brokenIn.size()), "Ref. Rotas IN");
        addStat(sb, "warn", String.valueOf(brokenOut.size()), "Ref. Rotas OUT");
        addStat(sb, "err", String.valueOf(missingConds.size()), "Cond. Faltantes");
        addStat(sb, "", String.valueOf(countMirrorGroups()), "Grupos Gemelos");
        sb.append("</div>\n");

        addTableSection(sb, "JOBS INICIALES", new String[]{"Jobname","DC","InCond","OutCond","Broken IN","Broken OUT"}, initialJobs);
        addTableSection(sb, "JOBS FINALES", new String[]{"Jobname","DC","InCond","OutCond","Broken IN","Broken OUT"}, finalJobs);
        addTableSection(sb, "JOBS CRITICOS (top 200)", new String[]{"Jobname","DC","Memname","InCond","OutCond","Broken IN","Broken OUT","Total"}, criticalJobs);
        addTableSection(sb, "REFERENCIAS ROTAS DE ENTRADA", new String[]{"Jobname","DC","Condicion Rota"}, brokenIn);
        addTableSection(sb, "REFERENCIAS ROTAS DE SALIDA", new String[]{"Jobname","DC","Condicion Rota"}, brokenOut);
        addTableSection(sb, "CONDICIONES FALTANTES", new String[]{"Condicion","#Refs","Jobs que la esperan"}, missingConds);
        addTableSection(sb, "GEMELOS ESPEJO", new String[]{"Jobname","DC","Version","InCond","Broken","Elegido"}, mirrorGroups);

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
        sb.append("</script>\n</body>\n</html>");

        Files.write(Paths.get(dir + "/08_REPORTE.html"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[INFO] Reporte HTML exportado a: " + dir + "/08_REPORTE.html");
    }

    private int countMirrorGroups() {
        Set<String> names = new HashSet<>();
        for (String[] r : mirrorGroups) names.add(r[0]);
        return names.size();
    }
    private void addStat(StringBuilder sb, String cls, String num, String lbl) {
        sb.append("<div class=\"stat " + cls + "\"><div class=\"num\">" + num + "</div><div class=\"lbl\">" + lbl + "</div></div>\n");
    }
    private void addTableSection(StringBuilder sb, String title, String[] headers, List<String[]> data) {
        sb.append("<h2>" + title + " (" + data.size() + ")</h2>\n");
        sb.append("<div>\n<input type=\"text\" placeholder=\"Filtrar...\">\n");
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
    // PASO 7: VISOR 3D CON THREE.JS (general)
    // =========================================================================
    public void exportThreeJsViewer(String dir, String seedJob, int depth) throws IOException {
        System.out.println("[INFO] Generando visor 3D para job semilla: " + (seedJob.isEmpty() ? "(todos los iniciales)" : seedJob) + ", profundidad: " + depth);

        Set<String> seedSet = new LinkedHashSet<>();
        if (seedJob != null && !seedJob.isEmpty()) {
            String upper = seedJob.toUpperCase();
            if (resolvedJobs.containsKey(upper)) {
                seedSet.add(upper);
            } else {
                System.out.println("[WARN] Job semilla '" + seedJob + "' no encontrado. Usando jobs iniciales.");
                for (String[] r : initialJobs) seedSet.add(r[0]);
            }
        } else {
            for (String[] r : initialJobs) seedSet.add(r[0]);
        }

        // BFS bidireccional para recolectar nodos y aristas
        BfsResult bfs = bfsBidirectional(seedSet, depth);

        System.out.println("[INFO] Visor: " + bfs.nodes.size() + " nodos, " + bfs.edges.size() + " aristas");

        if (bfs.nodes.isEmpty()) {
            String emptyHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Sin datos</title></head>"
                + "<body style='background:#000;color:#f00;padding:40px;font-family:monospace'>"
                + "<h1>No se encontraron nodos para visualizar</h1>"
                + "<p>El job semilla '" + esc(seedJob) + "' no tiene conexiones.</p>"
                + "</body></html>";
            Files.write(Paths.get(dir + "/09_VISOR_3D.html"), emptyHtml.getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Generar HTML con depth controls en la web
        String html = generateViewerHtml(bfs, seedSet, "VISOR 3D DE DEPENDENCIAS", true);
        Files.write(Paths.get(dir + "/09_VISOR_3D.html"), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("[INFO] Visor 3D exportado a: " + dir + "/09_VISOR_3D.html");
    }

    // =========================================================================
    // PASO 8: ARBOL COMPLETO DE UN JOB (nueva funcionalidad)
    // Genera TXT y visor HTML con profundidad controlable desde la web
    // =========================================================================
    public void exportJobTree(String dir, String jobArbol) throws IOException {
        if (jobArbol == null || jobArbol.isEmpty()) return;
        String upper = jobArbol.toUpperCase();
        if (!resolvedJobs.containsKey(upper)) {
            System.out.println("[WARN] Job arbol '" + jobArbol + "' no encontrado en los jobs resueltos.");
            return;
        }

        System.out.println("[INFO] =====================================================");
        System.out.println("[INFO] Generando arbol completo para: " + upper);
        System.out.println("[INFO] =====================================================");

        Job seedJobObj = resolvedJobs.get(upper);

        // =============================================
        // BFS completo sin limite (backward hasta iniciales, forward hasta finales)
        // =============================================
        Set<String> seedSet = new LinkedHashSet<>();
        seedSet.add(upper);

        // BFS backward completo (hasta los iniciadores)
        Set<String> allBackward = new LinkedHashSet<>();
        allBackward.add(upper);
        {
            Queue<String> queue = new LinkedList<>();
            queue.add(upper);
            Set<String> visited = new HashSet<>();
            visited.add(upper);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                Set<String> preds = graphBackward.get(current);
                if (preds != null) {
                    for (String pred : preds) {
                        if (!visited.contains(pred)) {
                            visited.add(pred);
                            allBackward.add(pred);
                            queue.add(pred);
                        }
                    }
                }
            }
        }

        // BFS forward completo (hasta los finales)
        Set<String> allForward = new LinkedHashSet<>();
        allForward.add(upper);
        {
            Queue<String> queue = new LinkedList<>();
            queue.add(upper);
            Set<String> visited = new HashSet<>();
            visited.add(upper);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                Set<String> succs = graphForward.get(current);
                if (succs != null) {
                    for (String succ : succs) {
                        if (!visited.contains(succ)) {
                            visited.add(succ);
                            allForward.add(succ);
                            queue.add(succ);
                        }
                    }
                }
            }
        }

        // Union
        Set<String> allNodes = new LinkedHashSet<>();
        allNodes.addAll(allBackward);
        allNodes.addAll(allForward);

        // Recolectar edges
        Map<String, String[]> allEdges = new LinkedHashMap<>();
        for (String node : allNodes) {
            Set<String> succs = graphForward.get(node);
            if (succs != null) {
                for (String succ : succs) {
                    if (allNodes.contains(succ)) {
                        String ek = node + "|" + succ;
                        // Determinar dirección: si el source está en backward-only = bck; si está en forward = fwd
                        String dir2;
                        if (allBackward.contains(node) && allBackward.contains(succ) && !allForward.contains(node) && !allForward.contains(succ)) {
                            dir2 = "bck";
                        } else if (allBackward.contains(node) && allBackward.contains(succ) &&
                                   !node.equals(upper) && !succ.equals(upper)) {
                            dir2 = "bck";
                        } else {
                            dir2 = "fwd";
                        }
                        // Edge from backward zone to seed is backward
                        if (allBackward.contains(node) && succ.equals(upper)) dir2 = "bck";
                        // Edge from seed forward is forward
                        if (node.equals(upper)) dir2 = "fwd";

                        if (!allEdges.containsKey(ek))
                            allEdges.put(ek, new String[]{node, succ, dir2});
                    }
                }
            }
        }

        // Encontrar los iniciadores y finales dentro del arbol
        List<String> treeInitials = new ArrayList<>();
        List<String> treeFinals = new ArrayList<>();
        for (String node : allNodes) {
            // Es inicial en el arbol si no tiene predecesores dentro del arbol
            boolean hasPredInTree = false;
            Set<String> preds = graphBackward.get(node);
            if (preds != null) {
                for (String p : preds) {
                    if (allNodes.contains(p)) { hasPredInTree = true; break; }
                }
            }
            if (!hasPredInTree) treeInitials.add(node);

            boolean hasSuccInTree = false;
            Set<String> succs = graphForward.get(node);
            if (succs != null) {
                for (String s : succs) {
                    if (allNodes.contains(s)) { hasSuccInTree = true; break; }
                }
            }
            if (!hasSuccInTree) treeFinals.add(node);
        }

        // Calcular profundidades desde la semilla (backward=negativo, forward=positivo)
        Map<String, Integer> depthMap = new HashMap<>();
        depthMap.put(upper, 0);
        // Forward
        {
            Queue<String> queue = new LinkedList<>();
            queue.add(upper);
            Set<String> visited = new HashSet<>();
            visited.add(upper);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int cd = depthMap.get(current);
                Set<String> succs = graphForward.get(current);
                if (succs != null) {
                    for (String succ : succs) {
                        if (allForward.contains(succ) && !visited.contains(succ)) {
                            visited.add(succ);
                            depthMap.put(succ, cd + 1);
                            queue.add(succ);
                        }
                    }
                }
            }
        }
        // Backward
        {
            Queue<String> queue = new LinkedList<>();
            queue.add(upper);
            Set<String> visited = new HashSet<>();
            visited.add(upper);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int cd = depthMap.containsKey(current) ? depthMap.get(current) : 0;
                Set<String> preds = graphBackward.get(current);
                if (preds != null) {
                    for (String pred : preds) {
                        if (allBackward.contains(pred) && !visited.contains(pred)) {
                            visited.add(pred);
                            depthMap.put(pred, cd - 1);
                            queue.add(pred);
                        }
                    }
                }
            }
        }

        int maxFwdDepth = 0;
        int maxBckDepth = 0;
        for (int d : depthMap.values()) {
            if (d > maxFwdDepth) maxFwdDepth = d;
            if (d < maxBckDepth) maxBckDepth = d;
        }

        System.out.println("[INFO] Arbol completo: " + allNodes.size() + " nodos, " + allEdges.size() + " aristas");
        System.out.println("[INFO] Profundidad backward: " + maxBckDepth + ", forward: " + maxFwdDepth);
        System.out.println("[INFO] Iniciadores del arbol: " + treeInitials.size());
        System.out.println("[INFO] Finales del arbol: " + treeFinals.size());

        // =============================================
        // EXPORTAR TXT
        // =============================================
        try (PrintWriter pw = pw(dir + "/10_ARBOL_" + upper + ".txt")) {
            pw.println("================================================================");
            pw.println("  ARBOL COMPLETO DE DEPENDENCIAS");
            pw.println("  Job analizado: " + upper);
            pw.println("  Fecha: " + new Date());
            pw.println("================================================================");
            pw.println();
            pw.println("Datacenter:                    " + seedJobObj.datacenter);
            pw.println("Memname:                       " + seedJobObj.memname);
            pw.println("ISN:                           " + seedJobObj.isn);
            pw.println("VersionSerial:                 " + seedJobObj.versionSerial);
            pw.println("InCond del job:                " + seedJobObj.inCond.size());
            pw.println("OutCond del job:               " + seedJobObj.outCond.size());
            pw.println();
            pw.println("ARBOL:");
            pw.println("  Total nodos:                 " + allNodes.size());
            pw.println("  Total aristas:               " + allEdges.size());
            pw.println("  Profundidad backward (max):  " + Math.abs(maxBckDepth));
            pw.println("  Profundidad forward (max):   " + maxFwdDepth);
            pw.println("  Iniciadores (raices):        " + treeInitials.size());
            pw.println("  Finales (hojas):             " + treeFinals.size());

            pw.println();
            pw.println(repeat("=", 100));
            pw.println("INICIADORES DEL ARBOL (raices que disparan el flujo hasta " + upper + ")");
            pw.println(repeat("-", 100));
            pw.printf("%-25s %-15s %-8s %-8s %-10s%n", "JOBNAME","DATACENTER","IN","OUT","PROFUNDIDAD");
            pw.println(repeat("-", 100));
            for (String jn : treeInitials) {
                Job j = resolvedJobs.get(jn);
                if (j == null) continue;
                int d = depthMap.containsKey(jn) ? depthMap.get(jn) : 0;
                pw.printf("%-25s %-15s %-8d %-8d %-10d%n", jn, j.datacenter, j.inCond.size(), j.outCond.size(), d);
            }

            pw.println();
            pw.println(repeat("=", 100));
            pw.println("FINALES DEL ARBOL (hojas que terminan despues de " + upper + ")");
            pw.println(repeat("-", 100));
            pw.printf("%-25s %-15s %-8s %-8s %-10s%n", "JOBNAME","DATACENTER","IN","OUT","PROFUNDIDAD");
            pw.println(repeat("-", 100));
            for (String jn : treeFinals) {
                Job j = resolvedJobs.get(jn);
                if (j == null) continue;
                int d = depthMap.containsKey(jn) ? depthMap.get(jn) : 0;
                pw.printf("%-25s %-15s %-8d %-8d %-10d%n", jn, j.datacenter, j.inCond.size(), j.outCond.size(), d);
            }

            pw.println();
            pw.println(repeat("=", 100));
            pw.println("TODOS LOS NODOS DEL ARBOL (ordenados por profundidad)");
            pw.println(repeat("-", 100));
            pw.printf("%-25s %-15s %-8s %-8s %-10s %-10s%n", "JOBNAME","DATACENTER","IN","OUT","PROFUNDIDAD","TIPO");
            pw.println(repeat("-", 100));

            List<Map.Entry<String, Integer>> sortedByDepth = new ArrayList<>(depthMap.entrySet());
            sortedByDepth.sort(new Comparator<Map.Entry<String, Integer>>() {
                @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return Integer.compare(a.getValue(), b.getValue());
                }
            });

            for (Map.Entry<String, Integer> entry : sortedByDepth) {
                String jn = entry.getKey();
                Job j = resolvedJobs.get(jn);
                if (j == null) continue;
                String tipo = jn.equals(upper) ? "SEMILLA" :
                              treeInitials.contains(jn) ? "INICIAL" :
                              treeFinals.contains(jn) ? "FINAL" : "NORMAL";
                pw.printf("%-25s %-15s %-8d %-8d %-10d %-10s%n", jn, j.datacenter, j.inCond.size(), j.outCond.size(), entry.getValue(), tipo);
            }

            pw.println();
            pw.println("Total nodos: " + allNodes.size());
        }

        System.out.println("[INFO] Reporte TXT del arbol exportado a: " + dir + "/10_ARBOL_" + upper + ".txt");

        // =============================================
        // EXPORTAR VISOR HTML CON CONTROL DE PROFUNDIDAD DESDE LA WEB
        // =============================================
        String html = generateTreeViewerHtml(upper, allNodes, allEdges, depthMap, maxBckDepth, maxFwdDepth,
                                              treeInitials, treeFinals);
        Files.write(Paths.get(dir + "/11_VISOR_ARBOL_" + upper + ".html"), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("[INFO] Visor HTML del arbol exportado a: " + dir + "/11_VISOR_ARBOL_" + upper + ".html");
    }

    // =========================================================================
    // PASO 9: RUTAS PRINCIPALES (RUTA CRITICA MAS LARGA)
    // =========================================================================
    public void exportCriticalPaths(String dir) throws IOException {
        System.out.println("[INFO] Buscando las 5 rutas internas mas largas...");

        // Encontrar los 5 longest paths usando BFS desde cada job inicial
        // Para eficiencia, usamos BFS/DFS iterativo con memoización
        // Longest path en DAG se resuelve con orden topológico

        // Paso 1: Orden topológico (Kahn's algorithm)
        Map<String, Integer> inDegree = new HashMap<>();
        for (String jn : resolvedJobs.keySet()) {
            inDegree.put(jn, 0);
        }
        for (Map.Entry<String, Set<String>> e : graphForward.entrySet()) {
            for (String succ : e.getValue()) {
                if (inDegree.containsKey(succ)) {
                    inDegree.put(succ, inDegree.get(succ) + 1);
                }
            }
        }

        Queue<String> tQueue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) tQueue.add(e.getKey());
        }

        List<String> topoOrder = new ArrayList<>();
        while (!tQueue.isEmpty()) {
            String current = tQueue.poll();
            topoOrder.add(current);
            Set<String> succs = graphForward.get(current);
            if (succs != null) {
                for (String succ : succs) {
                    if (inDegree.containsKey(succ)) {
                        inDegree.put(succ, inDegree.get(succ) - 1);
                        if (inDegree.get(succ) == 0) {
                            tQueue.add(succ);
                        }
                    }
                }
            }
        }

        // Si hay ciclos, topoOrder será menor que resolvedJobs
        boolean hasCycles = topoOrder.size() < resolvedJobs.size();
        if (hasCycles) {
            System.out.println("[WARN] El grafo tiene ciclos. Se analizaran solo los " + topoOrder.size() + " nodos sin ciclos.");
        }

        // Paso 2: Longest path via DP en orden topológico
        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        for (String jn : topoOrder) {
            dist.put(jn, 0);
            parent.put(jn, null);
        }

        for (String jn : topoOrder) {
            int d = dist.get(jn);
            Set<String> succs = graphForward.get(jn);
            if (succs != null) {
                for (String succ : succs) {
                    if (dist.containsKey(succ) && d + 1 > dist.get(succ)) {
                        dist.put(succ, d + 1);
                        parent.put(succ, jn);
                    }
                }
            }
        }

        // Paso 3: Encontrar los endpoints con mayor distancia
        List<Map.Entry<String, Integer>> sortedDist = new ArrayList<>(dist.entrySet());
        sortedDist.sort(new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });

        // Top 5 rutas unicas (diferentes endpoints o diferentes paths)
        List<List<String>> topPaths = new ArrayList<>();
        Set<String> usedEndpoints = new HashSet<>();

        for (Map.Entry<String, Integer> entry : sortedDist) {
            if (topPaths.size() >= 5) break;
            String endpoint = entry.getKey();
            if (usedEndpoints.contains(endpoint)) continue;

            // Reconstruir path
            List<String> path = new ArrayList<>();
            String current = endpoint;
            Set<String> visited = new HashSet<>();
            while (current != null && !visited.contains(current)) {
                path.add(0, current);
                visited.add(current);
                current = parent.get(current);
            }

            if (path.size() >= 3) { // Solo rutas con al menos 3 jobs
                topPaths.add(path);
                usedEndpoints.add(endpoint);
                // Marcar el inicio también para evitar rutas muy similares
                usedEndpoints.add(path.get(0));
            }
        }

        System.out.println("[INFO] Encontradas " + topPaths.size() + " rutas principales");

        // Exportar TXT
        try (PrintWriter pw = pw(dir + "/12_RUTAS_PRINCIPALES.txt")) {
            pw.println("================================================================");
            pw.println("  RUTAS PRINCIPALES DEL BANCO");
            pw.println("  Las rutas internas mas largas del grafo de dependencias");
            pw.println("  Fecha: " + new Date());
            pw.println("================================================================");
            pw.println();

            if (hasCycles) {
                pw.println("NOTA: El grafo contiene ciclos. Se analizaron " + topoOrder.size() +
                           " de " + resolvedJobs.size() + " nodos (los que no participan en ciclos).");
                pw.println();
            }

            pw.println("Se analizaron " + resolvedJobs.size() + " jobs unicos.");
            pw.println("Se identificaron las " + topPaths.size() + " rutas mas largas.\n");

            for (int i = 0; i < topPaths.size(); i++) {
                List<String> path = topPaths.get(i);
                pw.println(repeat("=", 120));
                pw.println("RUTA #" + (i + 1) + " - Longitud: " + path.size() + " jobs");
                pw.println(repeat("=", 120));
                pw.println();
                pw.println("  INICIO: " + path.get(0));
                pw.println("  FIN:    " + path.get(path.size() - 1));
                pw.println();

                pw.printf("  %-6s %-25s %-15s %-15s %-8s %-8s %-10s%n",
                          "#PASO","JOBNAME","DATACENTER","MEMNAME","IN","OUT","TIPO");
                pw.println("  " + repeat("-", 105));

                for (int j = 0; j < path.size(); j++) {
                    String jn = path.get(j);
                    Job job = resolvedJobs.get(jn);
                    String dc = job != null ? job.datacenter : "N/A";
                    String mem = job != null ? job.memname : "N/A";
                    int inC = job != null ? job.inCond.size() : 0;
                    int outC = job != null ? job.outCond.size() : 0;
                    String tipo = initialSet.contains(jn) ? "INICIAL" :
                                  finalSet.contains(jn) ? "FINAL" : "NORMAL";
                    pw.printf("  %-6d %-25s %-15s %-15s %-8d %-8d %-10s%n",
                              (j + 1), jn, dc, mem, inC, outC, tipo);
                }

                pw.println();

                // Imprimir las condiciones que conectan cada paso
                pw.println("  CADENA DE CONDICIONES:");
                pw.println("  " + repeat("-", 80));
                for (int j = 0; j < path.size() - 1; j++) {
                    String from = path.get(j);
                    String to = path.get(j + 1);
                    Job fromJob = resolvedJobs.get(from);
                    Job toJob = resolvedJobs.get(to);
                    // Encontrar las condiciones que conectan from->to
                    List<String> connecting = new ArrayList<>();
                    if (fromJob != null && toJob != null) {
                        for (String outC : fromJob.outCond) {
                            if (toJob.inCond.contains(outC)) {
                                connecting.add(outC);
                            }
                        }
                    }
                    pw.printf("  %s -> %s%n", from, to);
                    if (connecting.isEmpty()) {
                        pw.println("    (conexion indirecta via condiciones compartidas)");
                    } else {
                        for (String c : connecting) {
                            pw.println("    Condicion: " + c);
                        }
                    }
                }
                pw.println();
            }

            if (topPaths.isEmpty()) {
                pw.println("No se encontraron rutas con al menos 3 jobs.");
                pw.println("Esto puede indicar que el grafo esta muy fragmentado.");
            }
        }

        System.out.println("[INFO] Rutas principales exportadas a: " + dir + "/12_RUTAS_PRINCIPALES.txt");
    }

    // =========================================================================
    // GENERADOR DE HTML VISOR 3D (reutilizable)
    // =========================================================================
    private String generateViewerHtml(BfsResult bfs, Set<String> seedSet, String title, boolean withDepthControls) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>" + esc(title) + "</title>\n");
        appendViewerStyles(sb);
        sb.append("</head>\n<body>\n");

        // Info panel
        sb.append("<div id=\"info\">\n");
        sb.append("  <b>" + esc(title) + "</b><br>\n");
        sb.append("  Nodos: <span id=\"nodeCount\">" + bfs.nodes.size() + "</span>");
        sb.append(" | Aristas: <span id=\"edgeCount\">" + bfs.edges.size() + "</span><br>\n");
        if (!seedSet.isEmpty() && seedSet.size() <= 3) {
            sb.append("  Semilla: <span style=\"color:#f0f;font-weight:bold\">");
            int si = 0;
            for (String s : seedSet) {
                if (si > 0) sb.append(", ");
                sb.append(esc(s));
                si++;
            }
            sb.append("</span><br>\n");
        }
        sb.append("</div>\n");

        sb.append("<div id=\"tooltip\"></div>\n");
        sb.append("<div id=\"search-box\"><input type=\"text\" id=\"searchInput\" placeholder=\"Buscar jobname y presionar Enter...\"></div>\n");

        // Depth controls
        if (withDepthControls) {
            sb.append("<div id=\"depth-controls\">\n");
            sb.append("  <label>Prof. Atras (-): <input type=\"number\" id=\"depthBack\" value=\"5\" min=\"0\" max=\"999\" style=\"width:60px\"></label>\n");
            sb.append("  <label style=\"margin-left:10px\">Prof. Adelante (+): <input type=\"number\" id=\"depthFwd\" value=\"5\" min=\"0\" max=\"999\" style=\"width:60px\"></label>\n");
            sb.append("  <button id=\"btnApplyDepth\" style=\"margin-left:10px\">Aplicar</button>\n");
            sb.append("</div>\n");
        }

        // Legend
        appendLegend(sb);

        sb.append("<div id=\"controls\">Arrastrar: Rotar | Scroll: Zoom | Hover: Info del nodo | Enter: Buscar</div>\n");

        // Inline data
        sb.append("<script>\n");
        appendDataSets(sb, seedSet, bfs.nodes);
        appendNodesJson(sb, bfs.nodes);
        appendEdgesJson(sb, bfs.edges);

        // All graph data for depth filtering (export full forward/backward adjacency for nodes present)
        sb.append("var GRAPH_FWD={};\n");
        sb.append("var GRAPH_BCK={};\n");
        for (String node : bfs.nodes) {
            Set<String> fwd = graphForward.get(node);
            if (fwd != null && !fwd.isEmpty()) {
                sb.append("GRAPH_FWD[\"" + esc(node) + "\"]=[");
                int fi = 0;
                for (String f : fwd) {
                    if (fi > 0) sb.append(",");
                    sb.append("\"" + esc(f) + "\"");
                    fi++;
                }
                sb.append("];\n");
            }
            Set<String> bck = graphBackward.get(node);
            if (bck != null && !bck.isEmpty()) {
                sb.append("GRAPH_BCK[\"" + esc(node) + "\"]=[");
                int fi = 0;
                for (String b : bck) {
                    if (fi > 0) sb.append(",");
                    sb.append("\"" + esc(b) + "\"");
                    fi++;
                }
                sb.append("];\n");
            }
        }

        // Also export ALL graph data so depth filter can expand beyond initial BFS
        sb.append("var ALL_GRAPH_FWD={};\nvar ALL_GRAPH_BCK={};\n");
        sb.append("var ALL_NODES={};\n");
        // This could be huge, so we only export nodes reachable within a reasonable range
        // Actually, let's export everything - the user needs it
        for (Map.Entry<String, Set<String>> entry : graphForward.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sb.append("ALL_GRAPH_FWD[\"" + esc(entry.getKey()) + "\"]=[");
                int fi = 0;
                for (String f : entry.getValue()) {
                    if (fi > 0) sb.append(",");
                    sb.append("\"" + esc(f) + "\"");
                    fi++;
                }
                sb.append("];\n");
            }
        }
        for (Map.Entry<String, Set<String>> entry : graphBackward.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sb.append("ALL_GRAPH_BCK[\"" + esc(entry.getKey()) + "\"]=[");
                int fi = 0;
                for (String b : entry.getValue()) {
                    if (fi > 0) sb.append(",");
                    sb.append("\"" + esc(b) + "\"");
                    fi++;
                }
                sb.append("];\n");
            }
        }
        // Export all node metadata
        for (Job j : resolvedJobs.values()) {
            sb.append("ALL_NODES[\"" + esc(j.jobname) + "\"]={inC:" + j.inCond.size()
                + ",outC:" + j.outCond.size()
                + ",dc:\"" + esc(j.datacenter) + "\""
                + ",mem:\"" + esc(j.memname) + "\""
                + ",isn:\"" + esc(j.isn) + "\"};\n");
        }

        sb.append("</script>\n\n");

        // Three.js
        sb.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js\"></script>\n");
        sb.append("<script>\nif(typeof THREE==='undefined'){\n");
        sb.append("  document.body.innerHTML='<div style=\"color:#f44;padding:40px;font-family:monospace\">Error: No se pudo cargar Three.js. Requiere conexion a internet.</div>';\n}\n</script>\n\n");

        // Main rendering script
        sb.append("<script>\n");
        appendRenderScript(sb, withDepthControls);
        sb.append("</script>\n</body>\n</html>\n");

        return sb.toString();
    }

    // =========================================================================
    // GENERADOR DE HTML VISOR ARBOL (con profundidad controlable desde web)
    // =========================================================================
    private String generateTreeViewerHtml(String seedJob,
                                           Set<String> allNodes,
                                           Map<String, String[]> allEdges,
                                           Map<String, Integer> depthMap,
                                           int maxBckDepth, int maxFwdDepth,
                                           List<String> treeInitials,
                                           List<String> treeFinals) {

        Set<String> seedSet = new LinkedHashSet<>();
        seedSet.add(seedJob);

        Set<String> treeInitialSet = new HashSet<>(treeInitials);
        Set<String> treeFinalSet = new HashSet<>(treeFinals);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>Arbol de " + esc(seedJob) + "</title>\n");
        appendViewerStyles(sb);
        sb.append("</head>\n<body>\n");

        // Info panel
        sb.append("<div id=\"info\">\n");
        sb.append("  <b>ARBOL COMPLETO DE DEPENDENCIAS</b><br>\n");
        sb.append("  Semilla: <span style=\"color:#f0f;font-weight:bold\">" + esc(seedJob) + "</span><br>\n");
        sb.append("  Nodos totales: <span id=\"totalNodes\">" + allNodes.size() + "</span><br>\n");
        sb.append("  Nodos visibles: <span id=\"nodeCount\">" + allNodes.size() + "</span>");
        sb.append(" | Aristas: <span id=\"edgeCount\">" + allEdges.size() + "</span><br>\n");
        sb.append("  Prof. max atras: " + Math.abs(maxBckDepth) + " | adelante: " + maxFwdDepth + "\n");
        sb.append("</div>\n");

        sb.append("<div id=\"tooltip\"></div>\n");
        sb.append("<div id=\"search-box\"><input type=\"text\" id=\"searchInput\" placeholder=\"Buscar jobname y presionar Enter...\"></div>\n");

        // Depth controls
        sb.append("<div id=\"depth-controls\">\n");
        sb.append("  <label>Prof. Atras (-): <input type=\"number\" id=\"depthBack\" value=\"" + Math.abs(maxBckDepth) + "\" min=\"0\" max=\"999\" style=\"width:60px\"></label>\n");
        sb.append("  <label style=\"margin-left:10px\">Prof. Adelante (+): <input type=\"number\" id=\"depthFwd\" value=\"" + maxFwdDepth + "\" min=\"0\" max=\"999\" style=\"width:60px\"></label>\n");
        sb.append("  <button id=\"btnApplyDepth\" style=\"margin-left:10px\">Aplicar</button>\n");
        sb.append("</div>\n");

        // Legend
        appendLegend(sb);

        sb.append("<div id=\"controls\">Arrastrar: Rotar | Scroll: Zoom | Hover: Info del nodo | Enter: Buscar | Controles de profundidad arriba</div>\n");

        // Data
        sb.append("<script>\n");

        // Seed set
        sb.append("var SEED_SET={\"" + esc(seedJob) + "\":1};\n");

        // Initial/Final dentro del arbol
        sb.append("var INITIAL_SET={");
        { int si=0; for (String s : treeInitialSet) { if(si>0) sb.append(","); sb.append("\""+esc(s)+"\":1"); si++; } }
        sb.append("};\n");
        sb.append("var FINAL_SET={");
        { int si=0; for (String s : treeFinalSet) { if(si>0) sb.append(","); sb.append("\""+esc(s)+"\":1"); si++; } }
        sb.append("};\n\n");

        // All nodes with depth info
        sb.append("var ALL_TREE_NODES=[\n");
        { int idx=0;
          for (String n : allNodes) {
              Job j = resolvedJobs.get(n);
              int inC = j != null ? j.inCond.size() : 0;
              int outC = j != null ? j.outCond.size() : 0;
              String dc = j != null ? j.datacenter : "N/A";
              String mem = j != null ? j.memname : "";
              String isn = j != null ? j.isn : "";
              int depth = depthMap.containsKey(n) ? depthMap.get(n) : 0;
              if (idx > 0) sb.append(",\n");
              sb.append("  {id:\"" + esc(n) + "\",inC:" + inC + ",outC:" + outC + ",dc:\"" + esc(dc) + "\",mem:\"" + esc(mem) + "\",isn:\"" + esc(isn) + "\",depth:" + depth + "}");
              idx++;
          }
        }
        sb.append("\n];\n\n");

        // All edges
        sb.append("var ALL_TREE_EDGES=[\n");
        { int ei=0;
          for (String[] e : allEdges.values()) {
              if (ei > 0) sb.append(",\n");
              sb.append("  {s:\"" + esc(e[0]) + "\",t:\"" + esc(e[1]) + "\",d:\"" + e[2] + "\"}");
              ei++;
          }
        }
        sb.append("\n];\n\n");

        // Depth map for filtering
        sb.append("var DEPTH_MAP={};\n");
        for (Map.Entry<String, Integer> entry : depthMap.entrySet()) {
            sb.append("DEPTH_MAP[\"" + esc(entry.getKey()) + "\"]=" + entry.getValue() + ";\n");
        }
        sb.append("\n");

        // Graph adjacency for re-expansion
        sb.append("var ALL_GRAPH_FWD={};\nvar ALL_GRAPH_BCK={};\n");
        for (String node : allNodes) {
            Set<String> fwd = graphForward.get(node);
            if (fwd != null && !fwd.isEmpty()) {
                sb.append("ALL_GRAPH_FWD[\"" + esc(node) + "\"]=[");
                int fi=0; for (String f : fwd) { if(fi>0) sb.append(","); sb.append("\""+esc(f)+"\""); fi++; }
                sb.append("];\n");
            }
            Set<String> bck = graphBackward.get(node);
            if (bck != null && !bck.isEmpty()) {
                sb.append("ALL_GRAPH_BCK[\"" + esc(node) + "\"]=[");
                int fi=0; for (String b : bck) { if(fi>0) sb.append(","); sb.append("\""+esc(b)+"\""); fi++; }
                sb.append("];\n");
            }
        }

        // All node metadata
        sb.append("var ALL_NODES={};\n");
        for (String n : allNodes) {
            Job j = resolvedJobs.get(n);
            if (j != null) {
                sb.append("ALL_NODES[\"" + esc(n) + "\"]={inC:" + j.inCond.size()
                    + ",outC:" + j.outCond.size()
                    + ",dc:\"" + esc(j.datacenter) + "\""
                    + ",mem:\"" + esc(j.memname) + "\""
                    + ",isn:\"" + esc(j.isn) + "\"};\n");
            }
        }

        sb.append("</script>\n\n");

        // Three.js
        sb.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js\"></script>\n");
        sb.append("<script>\nif(typeof THREE==='undefined'){\n");
        sb.append("  document.body.innerHTML='<div style=\"color:#f44;padding:40px;font-family:monospace\">Error: No se pudo cargar Three.js.</div>';\n}\n</script>\n\n");

        // Main script (tree version with depth controls)
        sb.append("<script>\n");
        appendTreeRenderScript(sb);
        sb.append("</script>\n</body>\n</html>\n");

        return sb.toString();
    }

    // =========================================================================
    // HTML HELPERS
    // =========================================================================
    private void appendViewerStyles(StringBuilder sb) {
        sb.append("<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box;}\n");
        sb.append("body{background:#000810;overflow:hidden;font-family:'Courier New',monospace;}\n");
        sb.append("canvas{display:block;}\n");
        sb.append("#info{position:absolute;top:10px;left:10px;color:#0fa;font-size:12px;background:rgba(0,0,20,0.85);padding:12px 16px;border-radius:8px;border:1px solid #0fa;pointer-events:none;z-index:10;line-height:1.6;}\n");
        sb.append("#tooltip{position:absolute;display:none;color:#fff;background:rgba(0,5,30,0.95);border:1px solid #0af;padding:10px 14px;border-radius:6px;font-size:12px;pointer-events:none;z-index:20;max-width:450px;line-height:1.5;}\n");
        sb.append("#tooltip b{color:#0fa;}\n");
        sb.append("#legend{position:absolute;top:10px;right:10px;color:#ccc;font-size:11px;background:rgba(0,0,20,0.85);padding:12px;border-radius:8px;border:1px solid #335;z-index:10;line-height:1.8;}\n");
        sb.append(".leg-item{display:flex;align-items:center;gap:8px;margin:2px 0;}\n");
        sb.append(".leg-dot{width:12px;height:12px;border-radius:50%;border:1px solid rgba(255,255,255,0.3);}\n");
        sb.append(".leg-line{width:24px;height:3px;border-radius:2px;}\n");
        sb.append("#search-box{position:absolute;top:10px;left:50%;transform:translateX(-50%);z-index:10;}\n");
        sb.append("#search-box input{background:rgba(0,5,20,0.9);border:1px solid #0af;color:#fff;padding:8px 14px;border-radius:6px;font-family:'Courier New',monospace;width:320px;font-size:13px;}\n");
        sb.append("#depth-controls{position:absolute;bottom:50px;left:50%;transform:translateX(-50%);z-index:10;background:rgba(0,5,20,0.9);border:1px solid #0af;padding:10px 16px;border-radius:6px;color:#ccc;font-size:12px;}\n");
        sb.append("#depth-controls input[type=number]{background:#111;border:1px solid #444;color:#0fa;padding:4px 6px;border-radius:4px;font-family:'Courier New',monospace;}\n");
        sb.append("#depth-controls button{background:#0af;color:#000;border:none;padding:6px 14px;border-radius:4px;cursor:pointer;font-weight:bold;font-family:'Courier New',monospace;}\n");
        sb.append("#depth-controls button:hover{background:#0cf;}\n");
        sb.append("#controls{position:absolute;bottom:10px;left:10px;color:#888;font-size:10px;background:rgba(0,0,20,0.7);padding:8px 12px;border-radius:6px;z-index:10;}\n");
        sb.append("</style>\n");
    }

    private void appendLegend(StringBuilder sb) {
        sb.append("<div id=\"legend\">\n");
        sb.append("  <b style=\"color:#0fa\">&#9632; LEYENDA</b><br>\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#ff00ff;width:16px;height:16px;\"></div> <b>Semilla</b> (nodo grande)</div>\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#ffdd00\"></div> Inicial (sin predecesores)</div>\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#4488ff\"></div> Final (sin sucesores)</div>\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-dot\" style=\"background:#00cc88\"></div> Normal</div>\n");
        sb.append("  <hr style=\"border-color:#333;margin:4px 0\">\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-line\" style=\"background:#00cc66\"></div> Arista forward (+)</div>\n");
        sb.append("  <div class=\"leg-item\"><div class=\"leg-line\" style=\"background:#ff6622\"></div> Arista backward (-)</div>\n");
        sb.append("</div>\n");
    }

    private void appendDataSets(StringBuilder sb, Set<String> seedSet, Set<String> nodes) {
        sb.append("var SEED_SET={");
        { int si=0; for (String s : seedSet) { if(si>0) sb.append(","); sb.append("\""+esc(s)+"\":1"); si++; } }
        sb.append("};\n");

        sb.append("var INITIAL_SET={");
        { int si=0; for (String s : initialSet) { if(nodes.contains(s)) { if(si>0) sb.append(","); sb.append("\""+esc(s)+"\":1"); si++; } } }
        sb.append("};\n");

        sb.append("var FINAL_SET={");
        { int si=0; for (String s : finalSet) { if(nodes.contains(s)) { if(si>0) sb.append(","); sb.append("\""+esc(s)+"\":1"); si++; } } }
        sb.append("};\n\n");
    }

    private void appendNodesJson(StringBuilder sb, Set<String> nodes) {
        sb.append("var NODES=[\n");
        int idx = 0;
        for (String n : nodes) {
            Job j = resolvedJobs.get(n);
            int inC = j != null ? j.inCond.size() : 0;
            int outC = j != null ? j.outCond.size() : 0;
            String dc = j != null ? j.datacenter : "N/A";
            String mem = j != null ? j.memname : "";
            String isn = j != null ? j.isn : "";
            if (idx > 0) sb.append(",\n");
            sb.append("  {id:\"" + esc(n) + "\",inC:" + inC + ",outC:" + outC + ",dc:\"" + esc(dc) + "\",mem:\"" + esc(mem) + "\",isn:\"" + esc(isn) + "\"}");
            idx++;
        }
        sb.append("\n];\n\n");
    }

    private void appendEdgesJson(StringBuilder sb, Map<String, String[]> edges) {
        sb.append("var EDGES=[\n");
        int ei = 0;
        for (String[] e : edges.values()) {
            if (ei > 0) sb.append(",\n");
            sb.append("  {s:\"" + esc(e[0]) + "\",t:\"" + esc(e[1]) + "\",d:\"" + e[2] + "\"}");
            ei++;
        }
        sb.append("\n];\n\n");
    }

    // =========================================================================
    // THREE.JS RENDER SCRIPT (for general visor)
    // =========================================================================
    private void appendRenderScript(StringBuilder sb, boolean withDepthControls) {
        sb.append("(function(){\n");
        sb.append("if(typeof THREE==='undefined') return;\n\n");

        appendCommonRenderCode(sb);

        // Depth control handler
        if (withDepthControls) {
            appendDepthControlHandler(sb, false);
        }

        sb.append("})();\n");
    }

    // =========================================================================
    // THREE.JS RENDER SCRIPT (for tree visor)
    // =========================================================================
    private void appendTreeRenderScript(StringBuilder sb) {
        sb.append("(function(){\n");
        sb.append("if(typeof THREE==='undefined') return;\n\n");

        // For tree, we start with ALL nodes and filter by depth
        sb.append("var NODES=ALL_TREE_NODES;\nvar EDGES=ALL_TREE_EDGES;\n\n");

        appendCommonRenderCode(sb);

        // Depth control handler for tree
        appendDepthControlHandler(sb, true);

        sb.append("})();\n");
    }

    // =========================================================================
    // COMMON RENDER CODE
    // =========================================================================
    private void appendCommonRenderCode(StringBuilder sb) {
        sb.append("var W=window.innerWidth, H=window.innerHeight;\n");
        sb.append("var scene=new THREE.Scene();\n");
        sb.append("scene.background=new THREE.Color(0x000810);\n");
        sb.append("var camera=new THREE.PerspectiveCamera(60,W/H,0.5,100000);\n");
        sb.append("var renderer=new THREE.WebGLRenderer({antialias:true});\n");
        sb.append("renderer.setSize(W,H);\n");
        sb.append("renderer.setPixelRatio(window.devicePixelRatio);\n");
        sb.append("document.body.appendChild(renderer.domElement);\n\n");

        sb.append("var COL_SEED=0xff00ff, COL_INITIAL=0xffdd00, COL_FINAL=0x4488ff, COL_NORMAL=0x00cc88;\n");
        sb.append("var COL_EDGE_FWD=0x00cc66, COL_EDGE_BCK=0xff6622;\n\n");

        sb.append("function getNodeColor(id){ if(SEED_SET[id]) return COL_SEED; if(INITIAL_SET[id]) return COL_INITIAL; if(FINAL_SET[id]) return COL_FINAL; return COL_NORMAL; }\n");
        sb.append("function getNodeType(id){ if(SEED_SET[id]) return 'SEMILLA'; if(INITIAL_SET[id]) return 'INICIAL'; if(FINAL_SET[id]) return 'FINAL'; return 'NORMAL'; }\n");
        sb.append("function getNodeSize(id,inC,outC){ if(SEED_SET[id]) return 6; return Math.max(1.5, Math.min(4.5, Math.log2(1+inC+outC))); }\n");
        sb.append("function getLabelColor(id){ if(SEED_SET[id]) return '#ff88ff'; if(INITIAL_SET[id]) return '#ffee66'; if(FINAL_SET[id]) return '#6699ff'; return '#88ccaa'; }\n\n");

        // buildScene function so we can rebuild on depth change
        sb.append("var nodeMeshes=[], labelSprites=[], edgeLines=[], nodePositions={};\n");
        sb.append("var currentNodes=NODES, currentEdges=EDGES;\n\n");

        sb.append("function clearScene(){\n");
        sb.append("  for(var i=0;i<nodeMeshes.length;i++) scene.remove(nodeMeshes[i]);\n");
        sb.append("  for(var i=0;i<labelSprites.length;i++) scene.remove(labelSprites[i]);\n");
        sb.append("  for(var i=0;i<edgeLines.length;i++) scene.remove(edgeLines[i]);\n");
        sb.append("  nodeMeshes=[]; labelSprites=[]; edgeLines=[]; nodePositions={};\n");
        sb.append("}\n\n");

        sb.append("function makeTextSprite(text,color,fontSize){\n");
        sb.append("  var canvas=document.createElement('canvas');\n");
        sb.append("  var ctx=canvas.getContext('2d');\n");
        sb.append("  fontSize=fontSize||28;\n");
        sb.append("  ctx.font='bold '+fontSize+'px Courier New';\n");
        sb.append("  var w=ctx.measureText(text).width+8;\n");
        sb.append("  canvas.width=w; canvas.height=fontSize+8;\n");
        sb.append("  ctx.font='bold '+fontSize+'px Courier New';\n");
        sb.append("  ctx.fillStyle=color||'#ffffff';\n");
        sb.append("  ctx.fillText(text,4,fontSize);\n");
        sb.append("  var tex=new THREE.CanvasTexture(canvas);\n");
        sb.append("  tex.minFilter=THREE.LinearFilter;\n");
        sb.append("  var mat=new THREE.SpriteMaterial({map:tex,transparent:true,depthTest:false});\n");
        sb.append("  var sprite=new THREE.Sprite(mat);\n");
        sb.append("  sprite.scale.set(w/fontSize*3, 3, 1);\n");
        sb.append("  return sprite;\n");
        sb.append("}\n\n");

        sb.append("function buildScene(nodes, edges){\n");
        sb.append("  clearScene();\n");
        sb.append("  currentNodes=nodes; currentEdges=edges;\n");
        sb.append("  var N=nodes.length;\n");
        sb.append("  if(N===0) return;\n\n");

        // Assign positions
        sb.append("  var nodeMap={};\n");
        sb.append("  var spread=Math.max(300, Math.sqrt(N)*20);\n");
        sb.append("  for(var i=0;i<N;i++){\n");
        sb.append("    var n=nodes[i];\n");
        sb.append("    var phi=Math.acos(2*Math.random()-1);\n");
        sb.append("    var theta=2*Math.PI*Math.random();\n");
        sb.append("    var r=spread*(0.2+0.8*Math.random());\n");
        sb.append("    n.x=r*Math.sin(phi)*Math.cos(theta);\n");
        sb.append("    n.y=r*Math.sin(phi)*Math.sin(theta);\n");
        sb.append("    n.z=r*Math.cos(phi);\n");
        sb.append("    n.vx=0;n.vy=0;n.vz=0;\n");
        sb.append("    nodeMap[n.id]=n;\n");
        sb.append("  }\n");
        // Center seed
        sb.append("  for(var key in SEED_SET){ if(nodeMap[key]){nodeMap[key].x=0;nodeMap[key].y=0;nodeMap[key].z=0;} }\n\n");

        // Adjacency
        sb.append("  var adj={};\n");
        sb.append("  for(var i=0;i<edges.length;i++){\n");
        sb.append("    var e=edges[i];\n");
        sb.append("    if(!adj[e.s])adj[e.s]=[];\n");
        sb.append("    if(!adj[e.t])adj[e.t]=[];\n");
        sb.append("    adj[e.s].push(e.t); adj[e.t].push(e.s);\n");
        sb.append("  }\n\n");

        // Force layout
        sb.append("  var ITERS=N>5000?60:N>1000?120:N>200?200:300;\n");
        sb.append("  var repK=N>5000?1200:N>1000?800:500;\n");
        sb.append("  var attrK=0.004, damp=0.88;\n");
        sb.append("  console.log('Layout: '+N+' nodos, '+edges.length+' aristas, '+ITERS+' iters');\n");
        sb.append("  var t0=performance.now();\n\n");

        sb.append("  for(var iter=0;iter<ITERS;iter++){\n");
        sb.append("    if(N<=3000){\n");
        sb.append("      for(var i=0;i<N;i++) for(var j=i+1;j<N;j++){\n");
        sb.append("        var dx=nodes[i].x-nodes[j].x,dy=nodes[i].y-nodes[j].y,dz=nodes[i].z-nodes[j].z;\n");
        sb.append("        var d2=dx*dx+dy*dy+dz*dz+1; var f=repK/d2; var d=Math.sqrt(d2);\n");
        sb.append("        var fx=f*dx/d,fy=f*dy/d,fz=f*dz/d;\n");
        sb.append("        nodes[i].vx+=fx;nodes[i].vy+=fy;nodes[i].vz+=fz;\n");
        sb.append("        nodes[j].vx-=fx;nodes[j].vy-=fy;nodes[j].vz-=fz;\n");
        sb.append("      }\n");
        sb.append("    } else {\n");
        sb.append("      for(var i=0;i<N;i++){\n");
        sb.append("        var nb=adj[nodes[i].id]||[];\n");
        sb.append("        for(var k=0;k<nb.length;k++){\n");
        sb.append("          var other=nodeMap[nb[k]]; if(!other) continue;\n");
        sb.append("          var dx=nodes[i].x-other.x,dy=nodes[i].y-other.y,dz=nodes[i].z-other.z;\n");
        sb.append("          var d2=dx*dx+dy*dy+dz*dz+1; var f=repK*3/d2; var d=Math.sqrt(d2);\n");
        sb.append("          nodes[i].vx+=f*dx/d;nodes[i].vy+=f*dy/d;nodes[i].vz+=f*dz/d;\n");
        sb.append("        }\n");
        sb.append("        for(var s=0;s<8;s++){\n");
        sb.append("          var j=Math.floor(Math.random()*N); if(j===i) continue;\n");
        sb.append("          var dx=nodes[i].x-nodes[j].x,dy=nodes[i].y-nodes[j].y,dz=nodes[i].z-nodes[j].z;\n");
        sb.append("          var d2=dx*dx+dy*dy+dz*dz+1; var f=repK/d2; var d=Math.sqrt(d2);\n");
        sb.append("          nodes[i].vx+=f*dx/d;nodes[i].vy+=f*dy/d;nodes[i].vz+=f*dz/d;\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    for(var i=0;i<edges.length;i++){\n");
        sb.append("      var sn=nodeMap[edges[i].s],tn=nodeMap[edges[i].t];\n");
        sb.append("      if(!sn||!tn) continue;\n");
        sb.append("      var dx=tn.x-sn.x,dy=tn.y-sn.y,dz=tn.z-sn.z;\n");
        sb.append("      var d=Math.sqrt(dx*dx+dy*dy+dz*dz)+0.1; var f=attrK*d;\n");
        sb.append("      sn.vx+=f*dx/d;sn.vy+=f*dy/d;sn.vz+=f*dz/d;\n");
        sb.append("      tn.vx-=f*dx/d;tn.vy-=f*dy/d;tn.vz-=f*dz/d;\n");
        sb.append("    }\n");
        sb.append("    for(var i=0;i<N;i++){\n");
        sb.append("      nodes[i].vx*=damp;nodes[i].vy*=damp;nodes[i].vz*=damp;\n");
        sb.append("      nodes[i].x+=nodes[i].vx*0.3; nodes[i].y+=nodes[i].vy*0.3; nodes[i].z+=nodes[i].vz*0.3;\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  console.log('Layout done in '+(performance.now()-t0).toFixed(0)+'ms');\n\n");

        // Create meshes
        sb.append("  var sphereGeo=new THREE.SphereGeometry(1,12,12);\n");
        sb.append("  for(var i=0;i<N;i++){\n");
        sb.append("    var n=nodes[i];\n");
        sb.append("    var col=getNodeColor(n.id); var sz=getNodeSize(n.id,n.inC,n.outC);\n");
        sb.append("    var mat=new THREE.MeshBasicMaterial({color:col});\n");
        sb.append("    var mesh=new THREE.Mesh(sphereGeo,mat);\n");
        sb.append("    mesh.scale.set(sz,sz,sz);\n");
        sb.append("    mesh.position.set(n.x,n.y,n.z);\n");
        sb.append("    mesh.userData={id:n.id,type:getNodeType(n.id),inC:n.inC,outC:n.outC,dc:n.dc,mem:n.mem||'',isn:n.isn||'',origColor:col};\n");
        sb.append("    scene.add(mesh); nodeMeshes.push(mesh);\n");
        sb.append("    nodePositions[n.id]=mesh.position;\n");
        // Label
        sb.append("    var lbl=makeTextSprite(n.id, getLabelColor(n.id), SEED_SET[n.id]?36:24);\n");
        sb.append("    lbl.position.set(n.x, n.y+sz+2, n.z);\n");
        sb.append("    scene.add(lbl); labelSprites.push(lbl);\n");
        sb.append("  }\n\n");

        // Edges
        sb.append("  for(var i=0;i<edges.length;i++){\n");
        sb.append("    var e=edges[i];\n");
        sb.append("    var sp=nodePositions[e.s],tp=nodePositions[e.t];\n");
        sb.append("    if(!sp||!tp) continue;\n");
        sb.append("    var edgeColor=(e.d==='bck')?COL_EDGE_BCK:COL_EDGE_FWD;\n");
        sb.append("    var opacity=(e.d==='bck')?0.5:0.35;\n");
        sb.append("    var mat=new THREE.LineBasicMaterial({color:edgeColor,transparent:true,opacity:opacity});\n");
        sb.append("    var geo=new THREE.BufferGeometry().setFromPoints([sp.clone(),tp.clone()]);\n");
        sb.append("    var line=new THREE.Line(geo,mat);\n");
        sb.append("    scene.add(line); edgeLines.push(line);\n");
        sb.append("  }\n\n");

        sb.append("  spherical.radius=spread*1.8;\n");
        sb.append("  updateCamera();\n");

        // Update counters
        sb.append("  var nc=document.getElementById('nodeCount'); if(nc) nc.textContent=N;\n");
        sb.append("  var ec=document.getElementById('edgeCount'); if(ec) ec.textContent=edges.length;\n");

        sb.append("}\n\n"); // end buildScene

        // Camera
        sb.append("var spherical={theta:0,phi:Math.PI/2,radius:800};\n");
        sb.append("var target=new THREE.Vector3(0,0,0);\n");
        sb.append("function updateCamera(){\n");
        sb.append("  camera.position.x=target.x+spherical.radius*Math.sin(spherical.phi)*Math.cos(spherical.theta);\n");
        sb.append("  camera.position.y=target.y+spherical.radius*Math.cos(spherical.phi);\n");
        sb.append("  camera.position.z=target.z+spherical.radius*Math.sin(spherical.phi)*Math.sin(spherical.theta);\n");
        sb.append("  camera.lookAt(target);\n");
        sb.append("}\n\n");

        // Mouse
        sb.append("var isDragging=false,prevMX=0,prevMY=0;\n");
        sb.append("renderer.domElement.addEventListener('mousedown',function(ev){ if(ev.button===0){isDragging=true;prevMX=ev.clientX;prevMY=ev.clientY;} });\n");
        sb.append("renderer.domElement.addEventListener('mousemove',function(ev){\n");
        sb.append("  if(isDragging){\n");
        sb.append("    spherical.theta-=(ev.clientX-prevMX)*0.005;\n");
        sb.append("    spherical.phi=Math.max(0.05,Math.min(Math.PI-0.05,spherical.phi-(ev.clientY-prevMY)*0.005));\n");
        sb.append("    prevMX=ev.clientX;prevMY=ev.clientY; updateCamera();\n");
        sb.append("  }\n  checkHover(ev);\n});\n");
        sb.append("renderer.domElement.addEventListener('mouseup',function(){isDragging=false;});\n");
        sb.append("renderer.domElement.addEventListener('wheel',function(ev){\n");
        sb.append("  spherical.radius=Math.max(10,spherical.radius*(ev.deltaY>0?1.1:0.9));\n");
        sb.append("  updateCamera(); ev.preventDefault();\n},{passive:false});\n\n");

        // Hover tooltip with full info
        sb.append("var raycaster=new THREE.Raycaster();\n");
        sb.append("var mouseVec=new THREE.Vector2();\n");
        sb.append("var tooltip=document.getElementById('tooltip');\n\n");
        sb.append("function checkHover(ev){\n");
        sb.append("  mouseVec.x=(ev.clientX/W)*2-1; mouseVec.y=-(ev.clientY/H)*2+1;\n");
        sb.append("  raycaster.setFromCamera(mouseVec,camera);\n");
        sb.append("  var hits=raycaster.intersectObjects(nodeMeshes);\n");
        sb.append("  if(hits.length>0){\n");
        sb.append("    var d=hits[0].object.userData;\n");
        sb.append("    tooltip.style.display='block';\n");
        sb.append("    tooltip.style.left=(ev.clientX+15)+'px';\n");
        sb.append("    tooltip.style.top=(ev.clientY-10)+'px';\n");
        sb.append("    tooltip.innerHTML='<b>'+d.id+'</b><br>'\n");
        sb.append("      +'Tipo: <b style=\"color:#ff0\">'+d.type+'</b><br>'\n");
        sb.append("      +'Datacenter: '+d.dc+'<br>'\n");
        sb.append("      +'ISN: '+d.isn+'<br>'\n");
        sb.append("      +'#InCond: <b>'+d.inC+'</b> | #OutCond: <b>'+d.outC+'</b>';\n");
        sb.append("  } else { tooltip.style.display='none'; }\n");
        sb.append("}\n\n");

        // Search
        sb.append("document.getElementById('searchInput').addEventListener('keydown',function(ev){\n");
        sb.append("  if(ev.key==='Enter'){\n");
        sb.append("    var val=this.value.toUpperCase().trim();\n");
        sb.append("    for(var i=0;i<nodeMeshes.length;i++) nodeMeshes[i].material.color.setHex(nodeMeshes[i].userData.origColor);\n");
        sb.append("    if(!val) return;\n");
        sb.append("    var found=false;\n");
        sb.append("    for(var i=0;i<nodeMeshes.length;i++){\n");
        sb.append("      if(nodeMeshes[i].userData.id.indexOf(val)>=0){\n");
        sb.append("        nodeMeshes[i].material.color.setHex(0xffffff);\n");
        sb.append("        if(!found){ target.copy(nodeMeshes[i].position); spherical.radius=80; updateCamera(); found=true; }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    if(!found){tooltip.style.display='block';tooltip.style.left='50%';tooltip.style.top='60px';tooltip.innerHTML='No encontrado: '+val;setTimeout(function(){tooltip.style.display='none';},2000);}\n");
        sb.append("  }\n});\n\n");

        // Resize
        sb.append("window.addEventListener('resize',function(){ W=window.innerWidth;H=window.innerHeight; camera.aspect=W/H;camera.updateProjectionMatrix(); renderer.setSize(W,H); });\n\n");

        // Animate
        sb.append("function animate(){ requestAnimationFrame(animate); renderer.render(scene,camera); }\n");

        // Initial build
        sb.append("buildScene(NODES, EDGES);\n");
        sb.append("animate();\n\n");
    }

    // =========================================================================
    // DEPTH CONTROL HANDLER (general visor)
    // =========================================================================
    private void appendDepthControlHandler(StringBuilder sb, boolean isTree) {
        sb.append("var btnApply=document.getElementById('btnApplyDepth');\n");
        sb.append("if(btnApply) btnApply.addEventListener('click', function(){\n");
        sb.append("  var depthBack=parseInt(document.getElementById('depthBack').value)||0;\n");
        sb.append("  var depthFwd=parseInt(document.getElementById('depthFwd').value)||0;\n");
        sb.append("  console.log('Aplicando profundidad: -'+depthBack+' / +'+depthFwd);\n\n");

        // BFS in JavaScript to recompute visible nodes/edges
        sb.append("  var seeds=[];\n");
        sb.append("  for(var k in SEED_SET) seeds.push(k);\n\n");

        sb.append("  var visibleNodes={};\n");
        sb.append("  for(var si=0;si<seeds.length;si++) visibleNodes[seeds[si]]=true;\n\n");

        // BFS Forward
        sb.append("  // BFS Forward\n");
        sb.append("  var queue=seeds.slice(), visited={};\n");
        sb.append("  for(var si=0;si<seeds.length;si++) visited[seeds[si]]=0;\n");
        sb.append("  while(queue.length>0){\n");
        sb.append("    var cur=queue.shift(); var d=visited[cur];\n");
        sb.append("    if(d<depthFwd){\n");
        sb.append("      var nexts=ALL_GRAPH_FWD[cur]||[];\n");
        sb.append("      for(var ni=0;ni<nexts.length;ni++){\n");
        sb.append("        if(visited[nexts[ni]]===undefined){\n");
        sb.append("          visited[nexts[ni]]=d+1;\n");
        sb.append("          visibleNodes[nexts[ni]]=true;\n");
        sb.append("          queue.push(nexts[ni]);\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n\n");

        // BFS Backward
        sb.append("  // BFS Backward\n");
        sb.append("  queue=seeds.slice(); var visitedB={};\n");
        sb.append("  for(var si=0;si<seeds.length;si++) visitedB[seeds[si]]=0;\n");
        sb.append("  while(queue.length>0){\n");
        sb.append("    var cur=queue.shift(); var d=visitedB[cur];\n");
        sb.append("    if(d<depthBack){\n");
        sb.append("      var preds=ALL_GRAPH_BCK[cur]||[];\n");
        sb.append("      for(var ni=0;ni<preds.length;ni++){\n");
        sb.append("        if(visitedB[preds[ni]]===undefined){\n");
        sb.append("          visitedB[preds[ni]]=d+1;\n");
        sb.append("          visibleNodes[preds[ni]]=true;\n");
        sb.append("          queue.push(preds[ni]);\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n\n");

        // Collect backward-only nodes for edge coloring
        sb.append("  var backwardOnlyNodes={};\n");
        sb.append("  for(var k in visitedB){ if(visited[k]===undefined && !SEED_SET[k]) backwardOnlyNodes[k]=true; }\n\n");

        // Build filtered nodes array
        if (isTree) {
            sb.append("  var filteredNodes=[];\n");
            sb.append("  for(var i=0;i<ALL_TREE_NODES.length;i++){\n");
            sb.append("    if(visibleNodes[ALL_TREE_NODES[i].id]) filteredNodes.push(ALL_TREE_NODES[i]);\n");
            sb.append("  }\n");
        } else {
            sb.append("  var filteredNodes=[];\n");
            sb.append("  for(var k in visibleNodes){\n");
            sb.append("    var meta=ALL_NODES[k];\n");
            sb.append("    if(meta) filteredNodes.push({id:k,inC:meta.inC,outC:meta.outC,dc:meta.dc,mem:meta.mem,isn:meta.isn||''});\n");
            sb.append("    else filteredNodes.push({id:k,inC:0,outC:0,dc:'N/A',mem:'',isn:''});\n");
            sb.append("  }\n");
        }

        // Build filtered edges
        sb.append("  var filteredEdges=[];\n");
        sb.append("  // Add forward edges\n");
        sb.append("  for(var k in visibleNodes){\n");
        sb.append("    var fwd=ALL_GRAPH_FWD[k]||[];\n");
        sb.append("    for(var fi=0;fi<fwd.length;fi++){\n");
        sb.append("      if(visibleNodes[fwd[fi]]){\n");
        sb.append("        var dir=(backwardOnlyNodes[k]&&backwardOnlyNodes[fwd[fi]])?'bck':\n");
        sb.append("               (backwardOnlyNodes[k]&&SEED_SET[fwd[fi]])?'bck':'fwd';\n");
        sb.append("        filteredEdges.push({s:k,t:fwd[fi],d:dir});\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n\n");

        sb.append("  console.log('Filtered: '+filteredNodes.length+' nodos, '+filteredEdges.length+' aristas');\n");
        sb.append("  buildScene(filteredNodes, filteredEdges);\n");
        sb.append("});\n\n");
    }

    // =========================================================================
    // BFS BIDIRECCIONAL (usado por visor general)
    // =========================================================================
    static class BfsResult {
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, String[]> edges = new LinkedHashMap<>();
    }

    private BfsResult bfsBidirectional(Set<String> seedSet, int depth) {
        BfsResult result = new BfsResult();

        // Forward
        {
            Set<String> visited = new HashSet<>(seedSet);
            Queue<String[]> queue = new LinkedList<>();
            for (String s : seedSet) {
                queue.add(new String[]{s, "0"});
                result.nodes.add(s);
            }
            while (!queue.isEmpty()) {
                String[] cur = queue.poll();
                String jn = cur[0];
                int d = Integer.parseInt(cur[1]);
                result.nodes.add(jn);
                if (d < depth) {
                    Set<String> nexts = graphForward.get(jn);
                    if (nexts != null) {
                        for (String next : nexts) {
                            String ek = jn + "|" + next;
                            if (!result.edges.containsKey(ek))
                                result.edges.put(ek, new String[]{jn, next, "fwd"});
                            if (!visited.contains(next)) {
                                visited.add(next);
                                result.nodes.add(next);
                                queue.add(new String[]{next, String.valueOf(d + 1)});
                            }
                        }
                    }
                }
            }
        }

        // Backward
        {
            Set<String> visited = new HashSet<>(seedSet);
            Queue<String[]> queue = new LinkedList<>();
            for (String s : seedSet) queue.add(new String[]{s, "0"});
            while (!queue.isEmpty()) {
                String[] cur = queue.poll();
                String jn = cur[0];
                int d = Integer.parseInt(cur[1]);
                result.nodes.add(jn);
                if (d < depth) {
                    Set<String> preds = graphBackward.get(jn);
                    if (preds != null) {
                        for (String pred : preds) {
                            String ek = pred + "|" + jn;
                            if (!result.edges.containsKey(ek))
                                result.edges.put(ek, new String[]{pred, jn, "bck"});
                            if (!visited.contains(pred)) {
                                visited.add(pred);
                                result.nodes.add(pred);
                                queue.add(new String[]{pred, String.valueOf(d + 1)});
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    // =========================================================================
    // UTILIDADES
    // =========================================================================
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "").replace("'", "\\'");
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
            System.out.println("Uso: java -Xmx4g -cp \".:json-20231013.jar\" JobAnalyzer <ruta_json> [profundidad] [job_semilla] [job_arbol]");
            System.out.println();
            System.out.println("  <ruta_json>    : Archivo JSON con los jobs (requerido)");
            System.out.println("  [profundidad]  : Profundidad BFS para visor 3D general (default: 5)");
            System.out.println("  [job_semilla]  : Job semilla para visor 3D general (default: todos los iniciales)");
            System.out.println("  [job_arbol]    : Job extra para generar su arbol completo de dependencias");
            System.out.println();
            System.out.println("Ejemplo:");
            System.out.println("  java -Xmx4g -cp \".:json-20231013.jar\" JobAnalyzer datos.json 5 HABJG271 HABJG272");
            System.exit(1);
        }

        String jsonPath = args[0];
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String seedJob = args.length > 2 ? args[2] : "";
        String jobArbol = args.length > 3 ? args[3] : "";

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

        // 7. Exportar Visor 3D general
        analyzer.exportThreeJsViewer(outputDir, seedJob, depth);

        // 8. Exportar arbol de un job especifico
        if (!jobArbol.isEmpty()) {
            analyzer.exportJobTree(outputDir, jobArbol);
        }

        // 9. Rutas principales
        analyzer.exportCriticalPaths(outputDir);

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
        if (!jobArbol.isEmpty()) {
            System.out.println("[INFO]   10_ARBOL_" + jobArbol.toUpperCase() + ".txt");
            System.out.println("[INFO]   11_VISOR_ARBOL_" + jobArbol.toUpperCase() + ".html");
        }
        System.out.println("[INFO]   12_RUTAS_PRINCIPALES.txt");
    }
}
