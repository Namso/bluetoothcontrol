import org.json.JSONArray;
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
import java.util.LinkedHashMap;
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
        JSONObject data = result.toJson();
        String json = data.toString();

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n");
        html.append("<html lang=\"es\">\n");
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\"/>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("<title>Reporte de Malla</title>\n");
        html.append("<style>");
        html.append("body{margin:0;font-family:Arial,sans-serif;background:#0b1220;color:#e5e7eb;} ");
        html.append(".wrap{max-width:1300px;margin:0 auto;padding:18px;} ");
        html.append("h1,h2{margin:0 0 10px 0;} ");
        html.append(".muted{color:#9ca3af;} ");
        html.append(".grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:14px 0;} ");
        html.append(".box{border:1px solid #263449;padding:10px;background:#111a2b;} ");
        html.append(".row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;} ");
        html.append("input,button{background:#0f172a;color:#e5e7eb;border:1px solid #334155;padding:7px 8px;} ");
        html.append("button{cursor:pointer;} ");
        html.append("#graphWrap{position:relative;} ");
        html.append("canvas{width:100%;height:620px;display:block;border:1px solid #263449;background:#060b15;} ");
        html.append(".tooltip{position:absolute;display:none;pointer-events:none;background:#020617;color:#e2e8f0;border:1px solid #334155;padding:4px 6px;font-size:12px;white-space:nowrap;} ");
        html.append("ul{margin:6px 0 0 18px;padding:0;max-height:220px;overflow:auto;} ");
        html.append("li{margin:2px 0;} ");
        html.append("@media(max-width:1100px){.grid{grid-template-columns:1fr;}} ");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<div class=\"wrap\">\n");
        html.append("<h1>Reporte Completo de Dependencias</h1>\n");
        html.append("<p class=\"muted\">Fuente: ").append(escapeHtml(sourcePath)).append(" | Tiempo: ").append(elapsed).append(" ms</p>\n");
        html.append("<div class=\"grid\" id=\"stats\"></div>\n");
        html.append("<div class=\"box\">\n");
        html.append("<h2>Malla completa (render incremental)</h2>\n");
        html.append("<p class=\"muted\">Con job semilla puedes explorar hacia atras (profundidad negativa) y hacia adelante (profundidad positiva).</p>\n");
        html.append("<p class=\"muted\">Colores: semilla rojo, final azul, iniciador amarillo, normal celeste, inicial+final cian, arista negativa violeta.</p>\n");
        html.append("<div class=\"row\">\n");
        html.append("<label>Job semilla:</label><input id=\"seed\" type=\"text\" size=\"18\" value=\"").append(escapeHtml(seedJob)).append("\"/>\n");
        html.append("<label>Desde profundidad:</label><input id=\"depthFrom\" type=\"number\" value=\"-2\" min=\"-20\" max=\"0\"/>\n");
        html.append("<label>Hasta profundidad:</label><input id=\"depthTo\" type=\"number\" value=\"3\" min=\"0\" max=\"20\"/>\n");
        html.append("<label>Max nodos:</label><input id=\"maxNodes\" type=\"number\" value=\"800\" min=\"50\" max=\"5000\"/>\n");
        html.append("<button id=\"renderBtn\">Renderizar</button>\n");
        html.append("</div>\n");
        html.append("<div id=\"graphWrap\">\n");
        html.append("<canvas id=\"graph\" width=\"1280\" height=\"620\"></canvas>\n");
        html.append("<div class=\"tooltip\" id=\"nodeTooltip\"></div>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        html.append("<div class=\"grid\">\n");
        html.append("<div class=\"box\"><h2>Iniciadores</h2><ul id=\"starters\"></ul></div>\n");
        html.append("<div class=\"box\"><h2>Finales</h2><ul id=\"finals\"></ul></div>\n");
        html.append("<div class=\"box\"><h2>Condiciones faltantes</h2><ul id=\"missing\"></ul></div>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        html.append("<script>\n");
        html.append("const DATA=").append(json).append(";\n");
        html.append("const stats=[['Jobs leidos',DATA.totalJobsRead],['Canonicos',DATA.canonicalCount],['Iniciadores',DATA.totalStarters],['Finales',DATA.totalFinals],['Rotas',DATA.totalBrokenReferences],['Faltantes',DATA.totalMissingJobs],['Nodos',DATA.mapNodes.length],['Aristas',DATA.mapEdges.length]];\n");
        html.append("const statsEl=document.getElementById('stats');stats.forEach(s=>{const d=document.createElement('div');d.className='box';d.textContent=s[0]+': '+s[1];statsEl.appendChild(d);});\n");
        html.append("function fill(id,arr,max){const el=document.getElementById(id);arr.slice(0,max).forEach(v=>{const li=document.createElement('li');li.textContent=typeof v==='string'?v:(v.jobname+' | '+v.condition+' | '+v.expectedFrom);el.appendChild(li);});}\n");
        html.append("fill('starters',DATA.starters,1500);fill('finals',DATA.finals,1500);fill('missing',DATA.missingJobs,1500);\n");
        html.append("const graph=document.getElementById('graph');const ctx=graph.getContext('2d');const tooltip=document.getElementById('nodeTooltip');\n");
        html.append("const startersSet=new Set(DATA.starters);const finalsSet=new Set(DATA.finals);\n");
        html.append("const outMap=new Map();const inMap=new Map();DATA.mapEdges.forEach(e=>{if(!outMap.has(e.source))outMap.set(e.source,[]);outMap.get(e.source).push(e.target);if(!inMap.has(e.target))inMap.set(e.target,[]);inMap.get(e.target).push(e.source);});\n");
        html.append("function buildSubset(seed,depthFrom,depthTo,maxNodes){const hasSeed=seed&&seed.length>0&&DATA.mapNodes.indexOf(seed)>=0;const backwardLimit=Math.abs(Math.min(0,depthFrom));const forwardLimit=Math.max(0,depthTo);const nodeSet=new Set();const nodeDepth=new Map();const backwardEdgeSet=new Set();if(hasSeed){nodeSet.add(seed);nodeDepth.set(seed,0);const fQueue=[{n:seed,d:0}];const fBest=new Map();fBest.set(seed,0);while(fQueue.length&&nodeSet.size<maxNodes){const cur=fQueue.shift();if(cur.d>=forwardLimit)continue;const next=outMap.get(cur.n)||[];for(let i=0;i<next.length;i++){const to=next[i];const nd=cur.d+1;const prev=fBest.get(to);if(prev!==undefined&&prev<=nd)continue;fBest.set(to,nd);if(!nodeSet.has(to)&&nodeSet.size<maxNodes)nodeSet.add(to);const oldDepth=nodeDepth.get(to);if(oldDepth===undefined||Math.abs(nd)<Math.abs(oldDepth))nodeDepth.set(to,nd);fQueue.push({n:to,d:nd});if(nodeSet.size+fQueue.length>=maxNodes)break;}}const bQueue=[{n:seed,d:0}];const bBest=new Map();bBest.set(seed,0);while(bQueue.length&&nodeSet.size<maxNodes){const cur=bQueue.shift();if(cur.d>=backwardLimit)continue;const prevNodes=inMap.get(cur.n)||[];for(let i=0;i<prevNodes.length;i++){const from=prevNodes[i];const nd=cur.d+1;const prev=bBest.get(from);if(prev!==undefined&&prev<=nd)continue;bBest.set(from,nd);if(!nodeSet.has(from)&&nodeSet.size<maxNodes)nodeSet.add(from);const signedDepth=-nd;const oldDepth=nodeDepth.get(from);if(oldDepth===undefined||Math.abs(signedDepth)<Math.abs(oldDepth)||oldDepth>0)nodeDepth.set(from,signedDepth);backwardEdgeSet.add(from+'->'+cur.n);bQueue.push({n:from,d:nd});if(nodeSet.size+bQueue.length>=maxNodes)break;}}}else{const q=[];for(let i=0;i<DATA.starters.length&&i<50;i++)q.push({n:DATA.starters[i],d:0});if(q.length===0&&DATA.mapNodes.length>0)q.push({n:DATA.mapNodes[0],d:0});while(q.length&&nodeSet.size<maxNodes){const cur=q.shift();if(nodeSet.has(cur.n)||cur.d>forwardLimit)continue;nodeSet.add(cur.n);nodeDepth.set(cur.n,cur.d);const outs=outMap.get(cur.n)||[];for(let i=0;i<outs.length;i++){if(!nodeSet.has(outs[i]))q.push({n:outs[i],d:cur.d+1});if(nodeSet.size+q.length>=maxNodes)break;}}}const nodes=[...nodeSet];const edges=[];for(let i=0;i<DATA.mapEdges.length;i++){const e=DATA.mapEdges[i];if(nodeSet.has(e.source)&&nodeSet.has(e.target)){const key=e.source+'->'+e.target;edges.push({source:e.source,target:e.target,isBackward:backwardEdgeSet.has(key)});}}return {nodes,edges,nodeDepth,seed:hasSeed?seed:''};}\n");
        html.append("let current={nodes:[],edges:[],pos:new Map(),nodeDepth:new Map(),seed:''};let view={offsetX:40,offsetY:40,scale:1};let dragNode='';let isPanning=false;let lastX=0;let lastY=0;\n");
        html.append("function toScreen(p){return{x:p.x*view.scale+view.offsetX,y:p.y*view.scale+view.offsetY};}\n");
        html.append("function toWorld(x,y){return{x:(x-view.offsetX)/view.scale,y:(y-view.offsetY)/view.scale};}\n");
        html.append("function buildLayout(subset){const n=subset.nodes.length;const pos=new Map();const cols=Math.max(2,Math.floor(Math.sqrt(n)));for(let i=0;i<n;i++){const c=i%cols;const r=Math.floor(i/cols);const x=80+(c/(cols-1||1))*1000+(Math.random()*14-7);const y=70+(r/(Math.ceil(n/cols)-1||1))*500+(Math.random()*14-7);pos.set(subset.nodes[i],{x:x,y:y,vx:0,vy:0});}for(let step=0;step<120;step++){for(let i=0;i<subset.nodes.length;i++){const a=pos.get(subset.nodes[i]);for(let j=i+1;j<subset.nodes.length;j++){const b=pos.get(subset.nodes[j]);let dx=a.x-b.x;let dy=a.y-b.y;let dist=Math.sqrt(dx*dx+dy*dy)+0.1;let f=2600/(dist*dist);a.vx+=dx/dist*f;b.vx-=dx/dist*f;a.vy+=dy/dist*f;b.vy-=dy/dist*f;}}for(let i=0;i<subset.edges.length;i++){const e=subset.edges[i];const a=pos.get(e.source);const b=pos.get(e.target);let dx=b.x-a.x;let dy=b.y-a.y;let dist=Math.sqrt(dx*dx+dy*dy)+0.1;let stretch=(dist-75)*0.02;a.vx+=dx/dist*stretch;a.vy+=dy/dist*stretch;b.vx-=dx/dist*stretch;b.vy-=dy/dist*stretch;}subset.nodes.forEach(name=>{const p=pos.get(name);p.vx*=0.84;p.vy*=0.84;p.x=Math.max(10,Math.min(1200,p.x+p.vx));p.y=Math.max(10,Math.min(580,p.y+p.vy));});}return pos;}\n");
        html.append("function nodeRadius(name){return name===current.seed?8.5:4.5;}\n");
        html.append("function nodeColor(name){if(name===current.seed)return '#ef4444';const isStart=startersSet.has(name);const isFinal=finalsSet.has(name);if(isStart&&isFinal)return '#22d3ee';if(isFinal)return '#60a5fa';if(isStart)return '#facc15';return '#93c5fd';}\n");
        html.append("function nodeType(name){if(name===current.seed)return 'semilla';const isStart=startersSet.has(name);const isFinal=finalsSet.has(name);if(isStart&&isFinal)return 'inicial+final';if(isStart)return 'inicial';if(isFinal)return 'final';return 'normal';}\n");
        html.append("function draw(){ctx.clearRect(0,0,graph.width,graph.height);for(let i=0;i<current.edges.length;i++){const e=current.edges[i];const a=toScreen(current.pos.get(e.source));const b=toScreen(current.pos.get(e.target));ctx.strokeStyle=e.isBackward?'rgba(167,139,250,0.55)':'rgba(56,189,248,0.25)';ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();}ctx.font='11px Arial';ctx.textBaseline='middle';current.nodes.forEach(name=>{const wp=current.pos.get(name);if(!wp)return;const p=toScreen(wp);const r=nodeRadius(name);ctx.fillStyle=nodeColor(name);ctx.beginPath();ctx.arc(p.x,p.y,r,0,Math.PI*2);ctx.fill();ctx.fillStyle='#e5e7eb';ctx.fillText(name,p.x+r+3,p.y);});}\n");
        html.append("function findNode(mx,my){for(let i=current.nodes.length-1;i>=0;i--){const name=current.nodes[i];const wp=current.pos.get(name);if(!wp)continue;const p=toScreen(wp);const r=nodeRadius(name)+3;const dx=mx-p.x;const dy=my-p.y;if((dx*dx+dy*dy)<=r*r)return name;}return ''; }\n");
        html.append("function showTooltip(name,mx,my){if(!name){tooltip.style.display='none';return;}const meta=(DATA.jobsByName&&DATA.jobsByName[name])?DATA.jobsByName[name]:{};const inCount=(meta.inCount===undefined)?'N/A':meta.inCount;const outCount=(meta.outCount===undefined)?'N/A':meta.outCount;const isn=meta.isn||'N/A';const dc=meta.datacenter||'N/A';tooltip.style.display='block';tooltip.textContent='jobname: '+name+' | datacenter: '+dc+' | #inCondition: '+inCount+' | #outCondition: '+outCount+' | #isn: '+isn+' | tipo: '+nodeType(name);tooltip.style.left=(mx+12)+'px';tooltip.style.top=(my+12)+'px';}\n");
        html.append("function render(){const seed=document.getElementById('seed').value.trim();const depthFrom=parseInt(document.getElementById('depthFrom').value,10);const depthTo=parseInt(document.getElementById('depthTo').value,10);const maxNodes=parseInt(document.getElementById('maxNodes').value,10)||800;const safeFrom=isNaN(depthFrom)?-2:Math.max(-20,Math.min(0,depthFrom));const safeTo=isNaN(depthTo)?3:Math.max(0,Math.min(20,depthTo));const subset=buildSubset(seed,safeFrom,safeTo,maxNodes);if(subset.nodes.length===0){current={nodes:[],edges:[],pos:new Map(),nodeDepth:new Map(),seed:''};draw();return;}current={nodes:subset.nodes,edges:subset.edges,pos:buildLayout(subset),nodeDepth:subset.nodeDepth,seed:subset.seed};view={offsetX:40,offsetY:40,scale:1};draw();}\n");
        html.append("graph.addEventListener('mousedown',ev=>{const r=graph.getBoundingClientRect();const mx=ev.clientX-r.left;const my=ev.clientY-r.top;const hit=findNode(mx,my);lastX=mx;lastY=my;if(hit){dragNode=hit;}else{isPanning=true;}});\n");
        html.append("graph.addEventListener('mousemove',ev=>{const r=graph.getBoundingClientRect();const mx=ev.clientX-r.left;const my=ev.clientY-r.top;if(dragNode){const p=current.pos.get(dragNode);if(p){p.x+=(mx-lastX)/view.scale;p.y+=(my-lastY)/view.scale;}lastX=mx;lastY=my;draw();showTooltip(dragNode,mx,my);return;}if(isPanning){view.offsetX+=mx-lastX;view.offsetY+=my-lastY;lastX=mx;lastY=my;draw();return;}const hover=findNode(mx,my);showTooltip(hover,mx,my);});\n");
        html.append("graph.addEventListener('mouseup',()=>{dragNode='';isPanning=false;});graph.addEventListener('mouseleave',()=>{dragNode='';isPanning=false;tooltip.style.display='none';});\n");
        html.append("graph.addEventListener('wheel',ev=>{ev.preventDefault();const r=graph.getBoundingClientRect();const mx=ev.clientX-r.left;const my=ev.clientY-r.top;const before=toWorld(mx,my);const factor=ev.deltaY<0?1.1:0.9;view.scale=Math.max(0.25,Math.min(3,view.scale*factor));const after=toScreen(before);view.offsetX+=mx-after.x;view.offsetY+=my-after.y;draw();},{passive:false});\n");
        html.append("document.getElementById('renderBtn').addEventListener('click',render);render();\n");
        html.append("</script>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        writeUtf8(new File(dir, "reporte_completo.html"), html.toString());
    }

    private static void writeSeedTreeReport(File dir, FlowAnalyzer.AnalysisResult result, String seedJob) throws Exception {
        if (!result.mapNodes.contains(seedJob)) {
            StringBuilder missing = new StringBuilder();
            missing.append("SEMILLA NO ENCONTRADA\n");
            missing.append("job semilla: ").append(seedJob).append("\n");
            missing.append("No existe dentro de los jobs canonicos.\n");
            writeUtf8(new File(dir, "arbol_semilla.txt"), missing.toString());
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
                if (names.size() == 1) {
                    routeNames.add(names.get(0));
                } else {
                    StringBuilder block = new StringBuilder();
                    block.append("{");
                    int limit = Math.min(5, names.size());
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) {
                            block.append("|");
                        }
                        block.append(names.get(i));
                    }
                    if (names.size() > limit) {
                        block.append("|...");
                    }
                    block.append("}");
                    routeNames.add(block.toString());
                }
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