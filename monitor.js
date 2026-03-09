const SAMPLE = [
  {
    memname: 'HACCETB6',
    isn: '1',
    versionserial: 1,
    datacenter: 'Altair',
    inCond: ['HABJD270-HABJG271'],
    outCond: ['HABJG271-HABJG272'],
    jobname: 'HABJG271',
  },
  {
    memname: 'BGEXTUYA',
    isn: '2',
    versionserial: 1,
    datacenter: 'Altair',
    inCond: ['HABJG271-HABJG272'],
    outCond: ['HABJG272-HABJG273', 'HABJG272-HABJG274'],
    jobname: 'HABJG272',
  },
];

const ui = {
  wsUrl: document.getElementById('wsUrl'),
  connectBtn: document.getElementById('connectBtn'),
  sendBtn: document.getElementById('sendBtn'),
  status: document.getElementById('status'),
  fileInput: document.getElementById('fileInput'),
  jsonInput: document.getElementById('jsonInput'),
  stats: document.getElementById('stats'),
  graph: document.getElementById('graph'),
  topIn: document.getElementById('topIn'),
  topOut: document.getElementById('topOut'),
  broken: document.getElementById('broken'),
};

ui.jsonInput.value = JSON.stringify(SAMPLE, null, 2);

let socket = null;

function setStatus(text, ok) {
  ui.status.textContent = text;
  ui.status.className = ok ? 'ok' : 'err';
}

function connect() {
  try {
    socket = new WebSocket(ui.wsUrl.value.trim());
    setStatus('Conectando...', false);

    socket.onopen = function () {
      setStatus('Conectado', true);
    };

    socket.onclose = function () {
      setStatus('Desconectado', false);
    };

    socket.onerror = function () {
      setStatus('Error de conexion', false);
    };

    socket.onmessage = function (event) {
      const msg = JSON.parse(event.data);
      if (msg.type === 'analysisResult') {
        render(msg.data);
      } else if (msg.type === 'error') {
        setStatus('Error: ' + msg.message, false);
      }
    };
  } catch (err) {
    setStatus('Error: ' + err.message, false);
  }
}

function sendAnalyze() {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    setStatus('Primero conectate al WebSocket', false);
    return;
  }
  const raw = ui.jsonInput.value.trim();
  if (!raw) {
    setStatus('No hay JSON para analizar', false);
    return;
  }
  socket.send(
    JSON.stringify({
      type: 'analyze',
      payload: raw,
    })
  );
}

function render(data) {
  ui.stats.innerHTML = '';
  const stats = [
    ['Jobs leidos', data.totalJobsRead],
    ['Jobs canonicos', data.canonicalCount],
    ['Iniciadores', data.starters.length],
    ['Finales', data.finals.length],
    ['Rotas', data.brokenReferences.length],
    ['Faltantes', data.missingJobs.length],
  ];
  stats.forEach(function (entry) {
    const div = document.createElement('div');
    div.className = 'box';
    div.textContent = entry[0] + ': ' + entry[1];
    ui.stats.appendChild(div);
  });

  fillList(ui.topIn, data.topInbound.slice(0, 12), function (r) {
    return r.jobname + ' (' + r.datacenter + ') -> ' + r.score;
  });
  fillList(ui.topOut, data.topOutbound.slice(0, 12), function (r) {
    return r.jobname + ' (' + r.datacenter + ') -> ' + r.score;
  });
  fillList(ui.broken, data.brokenReferences.slice(0, 25), function (r) {
    return r.jobname + ' espera ' + r.condition + ' y falta ' + r.expectedFrom;
  });

  drawGraph(data.mapNodes, data.mapEdges, data.starters, data.finals);
}

function fillList(ul, items, formatter) {
  ul.innerHTML = '';
  items.forEach(function (item) {
    const li = document.createElement('li');
    li.textContent = formatter(item);
    ul.appendChild(li);
  });
}

function drawGraph(nodes, edges, starters, finals) {
  const svg = ui.graph;
  while (svg.firstChild) {
    svg.removeChild(svg.firstChild);
  }

  const startSet = new Set(starters);
  const finalSet = new Set(finals);
  const middle = nodes.filter(function (n) {
    return !startSet.has(n) && !finalSet.has(n);
  });

  const pos = new Map();
  placeColumn(nodes.filter((n) => startSet.has(n)), 120, pos);
  placeColumn(middle, 550, pos);
  placeColumn(nodes.filter((n) => finalSet.has(n)), 950, pos);

  edges.forEach(function (e) {
    if (!pos.has(e.source) || !pos.has(e.target)) {
      return;
    }
    const a = pos.get(e.source);
    const b = pos.get(e.target);
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'M ' + (a.x + 70) + ' ' + (a.y + 14) + ' C ' + (a.x + 190) + ' ' + (a.y + 14) + ', ' + (b.x - 120) + ' ' + (b.y + 14) + ', ' + b.x + ' ' + (b.y + 14));
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke', '#22d3ee');
    path.setAttribute('stroke-opacity', '0.45');
    path.setAttribute('stroke-width', '1.2');
    svg.appendChild(path);
  });

  nodes.forEach(function (name) {
    if (!pos.has(name)) {
      return;
    }
    const p = pos.get(name);
    const group = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', p.x);
    rect.setAttribute('y', p.y);
    rect.setAttribute('width', '70');
    rect.setAttribute('height', '28');
    rect.setAttribute('fill', '#0f172a');
    rect.setAttribute('stroke', '#475569');
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    text.setAttribute('x', p.x + 35);
    text.setAttribute('y', p.y + 18);
    text.setAttribute('font-size', '9');
    text.setAttribute('fill', '#e2e8f0');
    text.setAttribute('text-anchor', 'middle');
    text.textContent = name.slice(0, 10);
    group.appendChild(rect);
    group.appendChild(text);
    svg.appendChild(group);
  });
}

function placeColumn(items, x, pos) {
  const count = Math.max(1, items.length);
  const gap = 470 / count;
  items.forEach(function (name, idx) {
    pos.set(name, { x: x, y: 22 + idx * gap });
  });
}

ui.fileInput.addEventListener('change', function (event) {
  const file = event.target.files && event.target.files[0];
  if (!file) {
    return;
  }
  file.text().then(function (content) {
    ui.jsonInput.value = content;
  });
});

ui.connectBtn.addEventListener('click', connect);
ui.sendBtn.addEventListener('click', sendAnalyze);