import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowAnalysisBatch {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: java FlowAnalysisBatch <ruta-json> [carpeta-salida] [job-semilla]");
            return;
        }

        String jsonPath = args[0];
        String outputDir = args.length > 1 ? args[1] : "analisis";
        String seedJob = args.length > 2 ? args[2].trim() : "";

        FlowAnalyzer analyzer = new FlowAnalyzer();
        long startedAt = System.currentTimeMillis();
        FlowAnalyzer.AnalysisResult result = analyzer.analyzeFile(jsonPath);
        long elapsed = System.currentTimeMillis() - startedAt;

        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("No se pudo crear carpeta de salida: " + outputDir);
        }

        writeJsonResult(dir, result, elapsed);
        writeTextReport(dir, result, elapsed, jsonPath);
        writeLists(dir, result);
        writeHtmlReport(dir, result, elapsed, jsonPath, seedJob);
        writeMainPathReport(dir, result);
        if (seedJob.length() > 0) {
            writeSeedTreeReport(dir, result, seedJob);
        }

        System.out.println("Analisis completado en " + elapsed + " ms");
        System.out.println("Archivos generados en: " + dir.getAbsolutePath());
    }

    private static void writeJsonResult(File dir, FlowAnalyzer.AnalysisResult result, long elapsed) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("elapsedMs", elapsed);
        payload.put("data", result.toJson());
        writeUtf8(new File(dir, "resultado_completo.json"), payload.toString(2));
    }

    private static void writeTextReport(File dir, FlowAnalyzer.AnalysisResult result, long elapsed, String sourcePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("REPORTE COMPLETO DE MALLA\n");
        sb.append("Fuente JSON: ").append(sourcePath).append("\n");
        sb.append("Tiempo analisis (ms): ").append(elapsed).append("\n");
        sb.append("Jobs leidos: ").append(result.totalJobsRead).append("\n");
        sb.append("Jobs canonicos: ").append(result.canonicalCount).append("\n");
        sb.append("Jobs iniciadores: ").append(result.totalStarters).append("\n");
        sb.append("Jobs finales: ").append(result.totalFinals).append("\n");
        sb.append("Referencias rotas: ").append(result.totalBrokenReferences).append("\n");
        sb.append("Condiciones faltantes: ").append(result.totalMissingJobs).append("\n");
        sb.append("Nodos malla: ").append(result.mapNodes.size()).append("\n");
        sb.append("Aristas malla: ").append(result.mapEdges.size()).append("\n\n");

        sb.append("TOP CRITICOS POR ENTRADA\n");
        for (FlowAnalyzer.ScoredJob row : result.topInbound) {
            sb.append(row.jobname).append(" | ").append(row.datacenter).append(" | ").append(row.score).append("\n");
        }
        sb.append("\nTOP CRITICOS POR SALIDA\n");
        for (FlowAnalyzer.ScoredJob row : result.topOutbound) {
            sb.append(row.jobname).append(" | ").append(row.datacenter).append(" | ").append(row.score).append("\n");
        }

        writeUtf8(new File(dir, "reporte_completo.txt"), sb.toString());
    }

    private static void writeLists(File dir, FlowAnalyzer.AnalysisResult result) throws Exception {
        writeLines(new File(dir, "iniciadores.txt"), result.starters);
        writeLines(new File(dir, "finales.txt"), result.finals);
        writeLines(new File(dir, "jobs_faltantes.txt"), result.missingJobs);
        writeLines(new File(dir, "nodos_malla.txt"), result.mapNodes);

        StringBuilder brokenCsv = new StringBuilder();
        brokenCsv.append("jobname,datacenter,condition,expectedFrom\n");
        for (FlowAnalyzer.BrokenReference row : result.brokenReferences) {
            brokenCsv.append(csv(row.jobname)).append(',')
                .append(csv(row.datacenter)).append(',')
                .append(csv(row.condition)).append(',')
                .append(csv(row.expectedFrom)).append("\n");
        }
        writeUtf8(new File(dir, "referencias_rotas.csv"), brokenCsv.toString());

        StringBuilder edgesCsv = new StringBuilder();
        edgesCsv.append("source,target\n");
        for (FlowAnalyzer.Edge row : result.mapEdges) {
            edgesCsv.append(csv(row.source)).append(',').append(csv(row.target)).append("\n");
        }
        writeUtf8(new File(dir, "aristas_malla.csv"), edgesCsv.toString());

        StringBuilder inCsv = new StringBuilder();
        inCsv.append("jobname,score\n");
        for (Map.Entry<String, Integer> row : result.inboundScore.entrySet()) {
            inCsv.append(csv(row.getKey())).append(',').append(row.getValue().intValue()).append("\n");
        }
        writeUtf8(new File(dir, "score_entrada.csv"), inCsv.toString());

        StringBuilder outCsv = new StringBuilder();
        outCsv.append("jobname,score\n");
        for (Map.Entry<String, Integer> row : result.outboundScore.entrySet()) {
            outCsv.append(csv(row.getKey())).append(',').append(row.getValue().intValue()).append("\n");
        }
        writeUtf8(new File(dir, "score_salida.csv"), outCsv.toString());
    }

    private static void writeHtmlReport(File dir, FlowAnalyzer.AnalysisResult result, long elapsed, String sourcePath, String seedJob) throws Exception {
        String json = result.toJson().toString();
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"es\"><head><meta charset=\"UTF-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>");
        html.append("<title>Reporte de Malla</title><style>");
        html.append("body{margin:0;font-family:Arial,sans-serif;background:#0b1220;color:#e5e7eb}.wrap{max-width:1300px;margin:0 auto;padding:18px}.muted{color:#9ca3af}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:14px 0}.box{border:1px solid #263449;padding:10px;background:#111a2b}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}input,button{background:#0f172a;color:#e5e7eb;border:1px solid #334155;padding:7px 8px}button{cursor:pointer}#graphWrap{position:relative}canvas{width:100%;height:620px;display:block;border:1px solid #263449;background:#060b15}.tooltip{position:absolute;display:none;pointer-events:none;background:#020617;color:#e2e8f0;border:1px solid #334155;padding:4px 6px;font-size:12px;white-space:nowrap}ul{margin:6px 0 0 18px;padding:0;max-height:220px;overflow:auto}@media(max-width:1100px){.grid{grid-template-columns:1fr}}</style></head><body>");
        html.append("<div class=\"wrap\"><h1>Reporte Completo de Dependencias</h1><p class=\"muted\">Fuente: ").append(escapeHtml(sourcePath)).append(" | Tiempo: ").append(elapsed).append(" ms</p><div class=\"grid\" id=\"stats\"></div>");
        html.append("<div class=\"box\"><h2>Malla completa</h2><p class=\"muted\">Colores: semilla rojo, final azul, iniciador amarillo, normal celeste, inicial+final cian, arista hacia atras violeta.</p>");
        html.append("<div class=\"row\"><label>Job semilla:</label><input id=\"seed\" type=\"text\" size=\"18\" value=\"").append(escapeHtml(seedJob)).append("\"/><label>Profundidad atras:</label><input id=\"depthBack\" type=\"number\" value=\"2\" min=\"0\" max=\"20\"/><label>Profundidad adelante:</label><input id=\"depthForward\" type=\"number\" value=\"3\" min=\"0\" max=\"20\"/><label>Max nodos:</label><input id=\"maxNodes\" type=\"number\" value=\"800\" min=\"50\" max=\"5000\"/><button id=\"renderBtn\">Renderizar</button></div>");
        html.append("<div id=\"graphWrap\"><canvas id=\"graph\" width=\"1280\" height=\"620\"></canvas><div class=\"tooltip\" id=\"nodeTooltip\"></div></div></div>");
        html.append("<div class=\"grid\"><div class=\"box\"><h2>Iniciadores</h2><ul id=\"starters\"></ul></div><div class=\"box\"><h2>Finales</h2><ul id=\"finals\"></ul></div><div class=\"box\"><h2>Condiciones faltantes</h2><ul id=\"missing\"></ul></div></div></div>");
        html.append("<script>const DATA=").append(json).append(";const stats=[['Jobs leidos',DATA.totalJobsRead],['Canonicos',DATA.canonicalCount],['Iniciadores',DATA.totalStarters],['Finales',DATA.totalFinals],['Rotas',DATA.totalBrokenReferences],['Faltantes',DATA.totalMissingJobs],['Nodos',DATA.mapNodes.length],['Aristas',DATA.mapEdges.length]];const statsEl=document.getElementById('stats');stats.forEach(s=>{const d=document.createElement('div');d.className='box';d.textContent=s[0]+': '+s[1];statsEl.appendChild(d);});function fill(id,arr){const el=document.getElementById(id);arr.slice(0,1500).forEach(v=>{const li=document.createElement('li');li.textContent=typeof v==='string'?v:(v.jobname+' | '+v.condition);el.appendChild(li);});}fill('starters',DATA.starters);fill('finals',DATA.finals);fill('missing',DATA.missingJobs);const graph=document.getElementById('graph');const ctx=graph.getContext('2d');const tooltip=document.getElementById('nodeTooltip');const startersSet=new Set(DATA.starters);const finalsSet=new Set(DATA.finals);const outMap=new Map(),inMap=new Map();DATA.mapEdges.forEach(e=>{if(!outMap.has(e.source))outMap.set(e.source,[]);outMap.get(e.source).push(e.target);if(!inMap.has(e.target))inMap.set(e.target,[]);inMap.get(e.target).push(e.source);});function buildSubset(seed,b,f,max){const hasSeed=seed&&DATA.mapNodes.indexOf(seed)>=0;const nodes=new Set(),backward=new Set();if(hasSeed){nodes.add(seed);const fq=[{n:seed,d:0}],fb=new Map();fb.set(seed,0);while(fq.length&&nodes.size<=max){const cur=fq.shift();if(cur.d>=f)continue;(outMap.get(cur.n)||[]).forEach(to=>{const nd=cur.d+1,p=fb.get(to);if(p!==undefined&&p<=nd)return;fb.set(to,nd);if(nodes.size<max||nodes.has(to))nodes.add(to);fq.push({n:to,d:nd});});}const bq=[{n:seed,d:0}],bb=new Map();bb.set(seed,0);while(bq.length&&nodes.size<=max){const cur=bq.shift();if(cur.d>=b)continue;(inMap.get(cur.n)||[]).forEach(from=>{const nd=cur.d+1,p=bb.get(from);if(p!==undefined&&p<=nd)return;bb.set(from,nd);if(nodes.size<max||nodes.has(from))nodes.add(from);backward.add(from+'->'+cur.n);bq.push({n:from,d:nd});});}}else{const q=[];for(let i=0;i<DATA.starters.length&&i<50;i++)q.push({n:DATA.starters[i],d:0});while(q.length&&nodes.size<max){const cur=q.shift();if(nodes.has(cur.n)||cur.d>f)continue;nodes.add(cur.n);(outMap.get(cur.n)||[]).forEach(x=>q.push({n:x,d:cur.d+1}));}}const edges=[];DATA.mapEdges.forEach(e=>{if(nodes.has(e.source)&&nodes.has(e.target))edges.push({source:e.source,target:e.target,isBackward:backward.has(e.source+'->'+e.target)});});return{nodes:[...nodes],edges:edges,seed:hasSeed?seed:''};}let current={nodes:[],edges:[],pos:new Map(),seed:''};let view={offsetX:40,offsetY:40,scale:1};let dragNode='';let isPanning=false;let lastX=0,lastY=0;function toScreen(p){return{x:p.x*view.scale+view.offsetX,y:p.y*view.scale+view.offsetY};}function toWorld(x,y){return{x:(x-view.offsetX)/view.scale,y:(y-view.offsetY)/view.scale};}function layout(sub){const pos=new Map();const cols=Math.max(2,Math.floor(Math.sqrt(sub.nodes.length)));sub.nodes.forEach((n,i)=>{const c=i%cols,r=Math.floor(i/cols);pos.set(n,{x:80+(c/(cols-1||1))*1000,y:70+(r/(Math.ceil(sub.nodes.length/cols)-1||1))*500,vx:0,vy:0});});for(let s=0;s<100;s++){for(let i=0;i<sub.nodes.length;i++){const a=pos.get(sub.nodes[i]);for(let j=i+1;j<sub.nodes.length;j++){const b=pos.get(sub.nodes[j]);let dx=a.x-b.x,dy=a.y-b.y,dist=Math.sqrt(dx*dx+dy*dy)+0.1,f=2200/(dist*dist);a.vx+=dx/dist*f;b.vx-=dx/dist*f;a.vy+=dy/dist*f;b.vy-=dy/dist*f;}}sub.edges.forEach(e=>{const a=pos.get(e.source),b=pos.get(e.target);let dx=b.x-a.x,dy=b.y-a.y,dist=Math.sqrt(dx*dx+dy*dy)+0.1,t=(dist-75)*0.02;a.vx+=dx/dist*t;a.vy+=dy/dist*t;b.vx-=dx/dist*t;b.vy-=dy/dist*t;});sub.nodes.forEach(n=>{const p=pos.get(n);p.vx*=0.84;p.vy*=0.84;p.x=Math.max(10,Math.min(1200,p.x+p.vx));p.y=Math.max(10,Math.min(580,p.y+p.vy));});}return pos;}function radius(n){return n===current.seed?8.5:4.5;}function color(n){if(n===current.seed)return '#ef4444';const s=startersSet.has(n),f=finalsSet.has(n);if(s&&f)return '#22d3ee';if(f)return '#60a5fa';if(s)return '#facc15';return '#93c5fd';}function type(n){if(n===current.seed)return 'semilla';if(startersSet.has(n))return 'inicial';if(finalsSet.has(n))return 'final';return 'normal';}function draw(){ctx.clearRect(0,0,graph.width,graph.height);current.edges.forEach(e=>{const a=toScreen(current.pos.get(e.source)),b=toScreen(current.pos.get(e.target));ctx.strokeStyle=e.isBackward?'rgba(167,139,250,0.55)':'rgba(56,189,248,0.25)';ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();});ctx.font='11px Arial';ctx.textBaseline='middle';current.nodes.forEach(n=>{const p=toScreen(current.pos.get(n)),r=radius(n);ctx.fillStyle=color(n);ctx.beginPath();ctx.arc(p.x,p.y,r,0,Math.PI*2);ctx.fill();ctx.fillStyle='#e5e7eb';ctx.fillText(n,p.x+r+3,p.y);});}function hit(mx,my){for(let i=current.nodes.length-1;i>=0;i--){const n=current.nodes[i],p=toScreen(current.pos.get(n));const rr=radius(n)+3,dx=mx-p.x,dy=my-p.y;if(dx*dx+dy*dy<=rr*rr)return n;}return '';}function show(name,mx,my){if(!name){tooltip.style.display='none';return;}const m=(DATA.jobsByName&&DATA.jobsByName[name])?DATA.jobsByName[name]:{};tooltip.style.display='block';tooltip.textContent='jobname: '+name+' | datacenter: '+(m.datacenter||'N/A')+' | #inCondition: '+(m.inCount===undefined?'N/A':m.inCount)+' | #outCondition: '+(m.outCount===undefined?'N/A':m.outCount)+' | #isn: '+(m.isn||'N/A')+' | tipo: '+type(name);tooltip.style.left=(mx+12)+'px';tooltip.style.top=(my+12)+'px';}function render(){const seed=document.getElementById('seed').value.trim();const b=Math.max(0,Math.min(20,parseInt(document.getElementById('depthBack').value,10)||2));const f=Math.max(0,Math.min(20,parseInt(document.getElementById('depthForward').value,10)||3));const max=Math.max(50,Math.min(5000,parseInt(document.getElementById('maxNodes').value,10)||800));const sub=buildSubset(seed,b,f,max);if(sub.nodes.length===0){current={nodes:[],edges:[],pos:new Map(),seed:''};draw();return;}current={nodes:sub.nodes,edges:sub.edges,pos:layout(sub),seed:sub.seed};view={offsetX:40,offsetY:40,scale:1};draw();}graph.addEventListener('mousedown',e=>{const r=graph.getBoundingClientRect(),mx=e.clientX-r.left,my=e.clientY-r.top;lastX=mx;lastY=my;if(e.button===1){e.preventDefault();isPanning=true;return;}const h=hit(mx,my);if(h&&e.button===0){dragNode=h;}else if(e.button===0){isPanning=true;}});graph.addEventListener('mousemove',e=>{const r=graph.getBoundingClientRect(),mx=e.clientX-r.left,my=e.clientY-r.top;if(dragNode){const p=current.pos.get(dragNode);if(p){p.x+=(mx-lastX)/view.scale;p.y+=(my-lastY)/view.scale;}lastX=mx;lastY=my;draw();show(dragNode,mx,my);return;}if(isPanning){view.offsetX+=mx-lastX;view.offsetY+=my-lastY;lastX=mx;lastY=my;draw();return;}show(hit(mx,my),mx,my);});graph.addEventListener('mouseup',()=>{dragNode='';isPanning=false;});graph.addEventListener('mouseleave',()=>{dragNode='';isPanning=false;tooltip.style.display='none';});graph.addEventListener('auxclick',e=>{if(e.button===1)e.preventDefault();});graph.addEventListener('wheel',e=>{e.preventDefault();const r=graph.getBoundingClientRect(),mx=e.clientX-r.left,my=e.clientY-r.top,before=toWorld(mx,my),factor=e.deltaY<0?1.1:0.9;view.scale=Math.max(0.25,Math.min(3,view.scale*factor));const after=toScreen(before);view.offsetX+=mx-after.x;view.offsetY+=my-after.y;draw();},{passive:false});document.getElementById('renderBtn').addEventListener('click',render);render();</script></body></html>");
        writeUtf8(new File(dir, "reporte_completo.html"), html.toString());
    }

    private static void writeSeedTreeReport(File dir, FlowAnalyzer.AnalysisResult result, String seedJob) throws Exception {
        if (!result.mapNodes.contains(seedJob)) {
            writeUtf8(new File(dir, "arbol_semilla.txt"), "SEMILLA NO ENCONTRADA\njob semilla: " + seedJob + "\n");
            return;
        }

        Map<String, Set<String>> incoming = buildIncoming(result.mapEdges);
        Map<String, Set<String>> outgoing = buildOutgoing(result.mapEdges);
        Set<String> startersSet = new HashSet<String>(result.starters);

        LinkedHashSet<String> backwardNodes = new LinkedHashSet<String>();
        Map<String, Integer> backwardDepth = new HashMap<String, Integer>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(seedJob);
        backwardDepth.put(seedJob, Integer.valueOf(0));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            backwardNodes.add(current);
            int depth = backwardDepth.get(current).intValue();
            Set<String> parents = incoming.get(current);
            if (parents == null) {
                continue;
            }
            for (String parent : parents) {
                int candidateDepth = depth - 1;
                Integer old = backwardDepth.get(parent);
                if (old == null || candidateDepth < old.intValue()) {
                    backwardDepth.put(parent, Integer.valueOf(candidateDepth));
                    queue.add(parent);
                }
            }
        }

        LinkedHashSet<String> forwardNodes = new LinkedHashSet<String>();
        Deque<String> forwardQ = new ArrayDeque<String>();
        forwardQ.add(seedJob);
        while (!forwardQ.isEmpty()) {
            String current = forwardQ.poll();
            if (!forwardNodes.add(current)) {
                continue;
            }
            Set<String> children = outgoing.get(current);
            if (children == null) {
                continue;
            }
            for (String child : children) {
                if (!forwardNodes.contains(child)) {
                    forwardQ.add(child);
                }
            }
        }

        List<String> reachedStarters = new ArrayList<String>();
        for (String node : backwardNodes) {
            if (startersSet.contains(node)) {
                reachedStarters.add(node);
            }
        }
        Collections.sort(reachedStarters);

        List<Map.Entry<String, Integer>> orderedBackward = new ArrayList<Map.Entry<String, Integer>>(backwardDepth.entrySet());
        Collections.sort(orderedBackward, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int cmp = Integer.compare(a.getValue().intValue(), b.getValue().intValue());
                if (cmp != 0) {
                    return cmp;
                }
                return a.getKey().compareTo(b.getKey());
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("REPORTE DE ARBOL DESDE SEMILLA\n");
        sb.append("Semilla: ").append(seedJob).append("\n");
        sb.append("Nodos alcanzados hacia atras (hasta iniciadores): ").append(backwardNodes.size()).append("\n");
        sb.append("Iniciadores alcanzados: ").append(reachedStarters.size()).append("\n");
        sb.append("Nodos alcanzados hacia adelante: ").append(forwardNodes.size()).append("\n\n");
        sb.append("INICIADORES ALCANZADOS\n");
        for (String starter : reachedStarters) {
            sb.append(starter).append("\n");
        }
        sb.append("\nARBOL HACIA ATRAS (depth negativo)\n");
        for (Map.Entry<String, Integer> row : orderedBackward) {
            sb.append(row.getKey()).append(" | depth=").append(row.getValue().intValue()).append("\n");
        }
        sb.append("\nSUBARBOL HACIA ADELANTE\n");
        List<String> orderedForward = new ArrayList<String>(forwardNodes);
        Collections.sort(orderedForward);
        for (String node : orderedForward) {
            sb.append(node).append("\n");
        }
        writeUtf8(new File(dir, "arbol_semilla.txt"), sb.toString());
    }

    private static void writeMainPathReport(File dir, FlowAnalyzer.AnalysisResult result) throws Exception {
        List<List<String>> topRoutes = estimateMainRoutes(result, 5);
        StringBuilder sb = new StringBuilder();
        sb.append("RUTAS PRINCIPALES ESTIMADAS\n");
        sb.append("Metodo: aproximacion por SCC + camino mas largo en DAG condensado.\n");
        sb.append("Se reportan maximo 5 rutas candidatas.\n\n");
        if (topRoutes.isEmpty()) {
            sb.append("No se pudo estimar una ruta principal.\n");
        } else {
            for (int i = 0; i < topRoutes.size(); i++) {
                List<String> route = topRoutes.get(i);
                sb.append("Ruta ").append(i + 1).append(" | largo aproximado=").append(route.size()).append("\n");
                for (int j = 0; j < route.size(); j++) {
                    sb.append(route.get(j));
                    if (j < route.size() - 1) {
                        sb.append(" -> ");
                    }
                }
                sb.append("\n\n");
            }
        }
        writeUtf8(new File(dir, "ruta_principal_estimacion.txt"), sb.toString());
    }

    private static List<List<String>> estimateMainRoutes(FlowAnalyzer.AnalysisResult result, int maxRoutes) {
        Map<String, Integer> indexByNode = new HashMap<String, Integer>();
        for (int i = 0; i < result.mapNodes.size(); i++) {
            indexByNode.put(result.mapNodes.get(i), Integer.valueOf(i));
        }

        List<Set<Integer>> adj = new ArrayList<Set<Integer>>();
        for (int i = 0; i < result.mapNodes.size(); i++) {
            adj.add(new LinkedHashSet<Integer>());
        }
        for (FlowAnalyzer.Edge e : result.mapEdges) {
            Integer from = indexByNode.get(e.source);
            Integer to = indexByNode.get(e.target);
            if (from != null && to != null) {
                adj.get(from.intValue()).add(to);
            }
        }

        TarjanState st = new TarjanState(result.mapNodes.size(), adj);
        for (int i = 0; i < result.mapNodes.size(); i++) {
            if (st.index[i] == -1) {
                tarjan(i, st);
            }
        }

        int sccCount = st.components.size();
        List<Set<Integer>> dag = new ArrayList<Set<Integer>>();
        int[] weight = new int[sccCount];
        for (int i = 0; i < sccCount; i++) {
            dag.add(new LinkedHashSet<Integer>());
            weight[i] = st.components.get(i).size();
        }

        for (int i = 0; i < result.mapNodes.size(); i++) {
            int sccFrom = st.compIndex[i];
            for (Integer next : adj.get(i)) {
                int sccTo = st.compIndex[next.intValue()];
                if (sccFrom != sccTo) {
                    dag.get(sccFrom).add(Integer.valueOf(sccTo));
                }
            }
        }

        int[] indeg = new int[sccCount];
        for (int i = 0; i < sccCount; i++) {
            for (Integer to : dag.get(i)) {
                indeg[to.intValue()]++;
            }
        }

        Deque<Integer> q = new ArrayDeque<Integer>();
        for (int i = 0; i < sccCount; i++) {
            if (indeg[i] == 0) {
                q.add(Integer.valueOf(i));
            }
        }

        List<Integer> topo = new ArrayList<Integer>();
        while (!q.isEmpty()) {
            int cur = q.poll().intValue();
            topo.add(Integer.valueOf(cur));
            for (Integer to : dag.get(cur)) {
                int idx = to.intValue();
                indeg[idx]--;
                if (indeg[idx] == 0) {
                    q.add(Integer.valueOf(idx));
                }
            }
        }

        int[] best = new int[sccCount];
        int[] nextHop = new int[sccCount];
        for (int i = 0; i < sccCount; i++) {
            best[i] = weight[i];
            nextHop[i] = -1;
        }
        for (int i = topo.size() - 1; i >= 0; i--) {
            int node = topo.get(i).intValue();
            for (Integer to : dag.get(node)) {
                int t = to.intValue();
                int candidate = weight[node] + best[t];
                if (candidate > best[node]) {
                    best[node] = candidate;
                    nextHop[node] = t;
                }
            }
        }

        List<Integer> starts = new ArrayList<Integer>();
        for (int i = 0; i < sccCount; i++) {
            starts.add(Integer.valueOf(i));
        }
        Collections.sort(starts, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Integer.compare(best[b.intValue()], best[a.intValue()]);
            }
        });

        List<List<String>> routes = new ArrayList<List<String>>();
        Set<String> seen = new HashSet<String>();
        for (Integer s : starts) {
            if (routes.size() >= maxRoutes) {
                break;
            }
            List<Integer> sccRoute = new ArrayList<Integer>();
            int cur = s.intValue();
            Set<Integer> loopGuard = new HashSet<Integer>();
            while (cur >= 0 && !loopGuard.contains(Integer.valueOf(cur))) {
                loopGuard.add(Integer.valueOf(cur));
                sccRoute.add(Integer.valueOf(cur));
                cur = nextHop[cur];
            }

            List<String> routeNames = new ArrayList<String>();
            for (Integer scc : sccRoute) {
                List<String> names = new ArrayList<String>();
                for (Integer idx : st.components.get(scc.intValue())) {
                    names.add(result.mapNodes.get(idx.intValue()));
                }
                Collections.sort(names);
                routeNames.add(names.get(0));
            }

            String signature = join(routeNames, "->");
            if (!seen.contains(signature)) {
                seen.add(signature);
                routes.add(routeNames);
            }
        }
        return routes;
    }

    private static class TarjanState {
        int[] index;
        int[] low;
        boolean[] onStack;
        int[] compIndex;
        int cursor = 0;
        Deque<Integer> stack = new ArrayDeque<Integer>();
        List<Set<Integer>> adj;
        List<List<Integer>> components = new ArrayList<List<Integer>>();

        TarjanState(int n, List<Set<Integer>> adj) {
            this.adj = adj;
            this.index = new int[n];
            this.low = new int[n];
            this.onStack = new boolean[n];
            this.compIndex = new int[n];
            for (int i = 0; i < n; i++) {
                index[i] = -1;
                low[i] = -1;
                compIndex[i] = -1;
            }
        }
    }

    private static void tarjan(int v, TarjanState st) {
        st.index[v] = st.cursor;
        st.low[v] = st.cursor;
        st.cursor++;
        st.stack.push(Integer.valueOf(v));
        st.onStack[v] = true;
        for (Integer wObj : st.adj.get(v)) {
            int w = wObj.intValue();
            if (st.index[w] == -1) {
                tarjan(w, st);
                st.low[v] = Math.min(st.low[v], st.low[w]);
            } else if (st.onStack[w]) {
                st.low[v] = Math.min(st.low[v], st.index[w]);
            }
        }
        if (st.low[v] == st.index[v]) {
            List<Integer> comp = new ArrayList<Integer>();
            while (true) {
                int w = st.stack.pop().intValue();
                st.onStack[w] = false;
                st.compIndex[w] = st.components.size();
                comp.add(Integer.valueOf(w));
                if (w == v) {
                    break;
                }
            }
            st.components.add(comp);
        }
    }

    private static Map<String, Set<String>> buildOutgoing(List<FlowAnalyzer.Edge> edges) {
        Map<String, Set<String>> map = new HashMap<String, Set<String>>();
        for (FlowAnalyzer.Edge edge : edges) {
            Set<String> set = map.get(edge.source);
            if (set == null) {
                set = new LinkedHashSet<String>();
                map.put(edge.source, set);
            }
            set.add(edge.target);
        }
        return map;
    }

    private static Map<String, Set<String>> buildIncoming(List<FlowAnalyzer.Edge> edges) {
        Map<String, Set<String>> map = new HashMap<String, Set<String>>();
        for (FlowAnalyzer.Edge edge : edges) {
            Set<String> set = map.get(edge.target);
            if (set == null) {
                set = new LinkedHashSet<String>();
                map.put(edge.target, set);
            }
            set.add(edge.source);
        }
        return map;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static void writeLines(File file, List<String> lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        writeUtf8(file, sb.toString());
    }

    private static void writeUtf8(File file, String value) throws Exception {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            writer.write(value);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}