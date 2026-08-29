/* ═══════════════════════════════════════════════════════
   THREADMIXER — app.js
   Polling, Canvas Visualizer, DOM updates, UI logic
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';

  const POLL_MS = 1000;

  /* ── State ── */
  let ultimoLog   = [];
  let carregando  = false;
  let estadosCache = {};

  /* ═══ CLOCK ═══ */
  function startClock() {
    const el = document.getElementById('clock-display');
    if (!el) return;
    function tick() {
      const now = new Date();
      el.textContent = now.toLocaleTimeString('pt-BR', { hour12: false });
    }
    tick();
    setInterval(tick, 1000);
  }

  /* ═══ TOPBAR MINI VISUALIZER (Canvas) ═══ */
  (function initTopbarViz() {
    const canvas = document.getElementById('topbar-viz');
    if (!canvas) return;
    const ctx    = canvas.getContext('2d');
    const W      = canvas.width;
    const H      = canvas.height;
    const BARS   = 20;
    let heights  = new Array(BARS).fill(0).map(() => Math.random());
    let targets  = heights.slice();

    function lerp(a, b, t) { return a + (b - a) * t; }

    function draw() {
      ctx.clearRect(0, 0, W, H);
      const w = W / BARS;

      for (let i = 0; i < BARS; i++) {
        heights[i] = lerp(heights[i], targets[i], 0.15);
        const h = heights[i] * H;
        const x = i * w + 1;
        const grad = ctx.createLinearGradient(0, H - h, 0, H);
        grad.addColorStop(0, 'rgba(0,245,255,0.9)');
        grad.addColorStop(1, 'rgba(0,245,255,0.2)');
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, H - h, w - 2, h, [2, 2, 0, 0]);
        ctx.fill();
      }
    }

    function updateTargets() {
      const allPlaying = Object.values(estadosCache).every(v => String(v).toUpperCase() === 'TOCANDO');
      const anyPlaying = Object.values(estadosCache).some(v => String(v).toUpperCase() === 'TOCANDO');
      const energy = anyPlaying ? (allPlaying ? 1.0 : 0.55) : 0.05;
      for (let i = 0; i < BARS; i++) {
        targets[i] = Math.random() * energy * 0.7 + energy * 0.3;
      }
    }

    function loop() {
      draw();
      requestAnimationFrame(loop);
    }
    setInterval(updateTargets, 120);
    loop();
  })();

  /* ═══ MASTER CANVAS VISUALIZER ═══ */
  (function initMasterCanvas() {
    const canvas = document.getElementById('master-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');

    function resize() {
      canvas.width  = canvas.offsetWidth  * window.devicePixelRatio;
      canvas.height = canvas.offsetHeight * window.devicePixelRatio;
      ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
    }
    window.addEventListener('resize', resize);
    resize();

    const BANDS = 48;
    const heights  = new Array(BANDS).fill(0);
    const targets  = new Array(BANDS).fill(0);
    const peaks    = new Array(BANDS).fill(0);
    const peakHold = new Array(BANDS).fill(0);

    function lerp(a, b, t) { return a + (b - a) * t; }

    const COLORS = [
      ['rgba(0,245,255,0.9)', 'rgba(0,245,255,0.1)'],
      ['rgba(191,0,255,0.9)', 'rgba(191,0,255,0.1)'],
      ['rgba(0,255,136,0.9)', 'rgba(0,255,136,0.1)'],
    ];

    function updateTargets() {
      const states = Object.values(estadosCache);
      const playing = states.filter(v => String(v).toUpperCase() === 'TOCANDO').length;
      for (let i = 0; i < BANDS; i++) {
        const t = i / BANDS;
        const curve = Math.sin(t * Math.PI) * 0.5 + 0.5;
        const noise = Math.random() * 0.4;
        targets[i] = playing === 0 ? noise * 0.03 :
                     (curve * 0.5 + noise * 0.5) * (playing / 3);
      }
    }

    function draw() {
      const W = canvas.offsetWidth;
      const H = canvas.offsetHeight;
      ctx.clearRect(0, 0, W, H);

      const barW = W / BANDS;

      for (let i = 0; i < BANDS; i++) {
        heights[i] = lerp(heights[i], targets[i], 0.18);
        const h    = heights[i] * (H - 8);
        const x    = i * barW + 1;
        const bw   = barW - 2;
        const colorIdx = Math.floor(i / BANDS * 3);
        const [top, bot] = COLORS[colorIdx] || COLORS[0];

        const grad = ctx.createLinearGradient(0, H - h, 0, H);
        grad.addColorStop(0, top);
        grad.addColorStop(1, bot);
        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, H - h, bw, h, [2, 2, 0, 0]);
        ctx.fill();

        /* Peak dot */
        if (heights[i] > peaks[i]) {
          peaks[i]    = heights[i];
          peakHold[i] = 30;
        }
        if (peakHold[i] > 0) peakHold[i]--;
        else peaks[i] = lerp(peaks[i], 0, 0.05);

        const py = H - peaks[i] * (H - 8) - 2;
        ctx.fillStyle = top;
        ctx.fillRect(x, py, bw, 2);
      }

      /* Center scan line */
      const scanGrad = ctx.createLinearGradient(0, 0, W, 0);
      scanGrad.addColorStop(0,   'transparent');
      scanGrad.addColorStop(0.3, 'rgba(0,245,255,0.06)');
      scanGrad.addColorStop(0.7, 'rgba(191,0,255,0.06)');
      scanGrad.addColorStop(1,   'transparent');
      ctx.fillStyle = scanGrad;
      ctx.fillRect(0, 0, W, H);
    }

    function loop() {
      draw();
      requestAnimationFrame(loop);
    }
    setInterval(updateTargets, 100);
    loop();
  })();

  /* ═══ THREAD DOTS ═══ */
  const dotMap = { Bateria: 'td-bateria', Baixo: 'td-baixo', Synth: 'td-synth' };

  function updateThreadDots(estados) {
    for (const [nome, estado] of Object.entries(estados)) {
      const key = Object.keys(dotMap).find(k => k.toLowerCase() === nome.toLowerCase()) || nome;
      const elId = dotMap[key];
      if (!elId) continue;
      const el = document.getElementById(elId);
      if (!el) continue;
      const s = String(estado).toUpperCase();
      el.classList.remove('active', 'paused');
      if (s === 'TOCANDO')   el.classList.add('active');
      if (s === 'PAUSADO')   el.classList.add('paused');
    }
  }

  /* ═══ TOAST ═══ */
  function showToast(msg) {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = msg;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 3100);
  }

  /* ═══ CHANNEL CARD BUILDER ═══ */
  function iconSVG(nome) {
    const n = nome.toLowerCase();
    if (n === 'bateria') return `<svg class="ch-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><ellipse cx="12" cy="13" rx="9" ry="4.5"/><path d="M3 13v3.5c0 2.5 4 4.5 9 4.5s9-2 9-4.5V13"/><ellipse cx="12" cy="8" rx="9" ry="4.5"/><line x1="6.5" y1="4.5" x2="4.5" y2="2.5" stroke-linecap="round"/><line x1="17.5" y1="4.5" x2="19.5" y2="2.5" stroke-linecap="round"/></svg>`;
    if (n === 'baixo')   return `<svg class="ch-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M9 3h6l1 6H8L9 3z" stroke-linejoin="round"/><rect x="6" y="9" width="12" height="8" rx="1"/><line x1="9" y1="17" x2="9" y2="21" stroke-linecap="round"/><line x1="15" y1="17" x2="15" y2="21" stroke-linecap="round"/><line x1="7" y1="21" x2="17" y2="21" stroke-linecap="round"/></svg>`;
    if (n === 'synth')   return `<svg class="ch-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="2" y="8" width="20" height="13" rx="2"/><path d="M6 8V5a1 1 0 011-1h10a1 1 0 011 1v3"/><rect x="5" y="12" width="2" height="5" rx="0.5" fill="currentColor" stroke="none"/><rect x="9" y="12" width="2" height="5" rx="0.5" fill="currentColor" stroke="none"/><rect x="13" y="12" width="2" height="5" rx="0.5" fill="currentColor" stroke="none"/><rect x="17" y="12" width="2" height="5" rx="0.5" fill="currentColor" stroke="none"/></svg>`;
    return `<svg class="ch-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/></svg>`;
  }

  function intervalo(nome) {
    const n = nome.toLowerCase();
    if (n === 'bateria') return '500ms';
    if (n === 'baixo')   return '800ms';
    if (n === 'synth')   return '650ms';
    return '???';
  }

  function buildCard(nome, estado) {
    const tocando = String(estado).toUpperCase() === 'TOCANDO';
    const nomeMin = nome.toLowerCase();
    const acao    = tocando ? 'pausar' : 'retomar';
    const div     = document.createElement('div');
    div.className = `channel-strip ${tocando ? 'ch--playing' : 'ch--paused'}`;
    div.id        = `ch-${nomeMin}`;
    div.dataset.instrumento = nomeMin;
    div.dataset.estado      = estado;

    div.innerHTML = `
      <div class="ch-header">
        <div class="ch-icon-wrap">${iconSVG(nome)}</div>
        <div class="ch-name-wrap">
          <span class="ch-name">${nome}</span>
          <span class="ch-badge ${tocando ? 'badge--playing' : 'badge--paused'}">${tocando ? 'TOCANDO' : 'PAUSADO'}</span>
        </div>
      </div>
      <div class="ch-eq" aria-hidden="true">
        <span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span>
        <span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span>
        <span class="eq-bar"></span><span class="eq-bar"></span>
      </div>
      <div class="ch-thread-info">
        <span class="thread-interval">${intervalo(nome)}</span>
        <span class="thread-interval-label">ciclo de thread</span>
      </div>
      <form class="ch-form" method="post" action="/comando">
        <input type="hidden" name="comando" value="${nomeMin} ${acao}"/>
        <button type="submit" class="ch-toggle-btn ${tocando ? 'btn--pause' : 'btn--play'}"
                aria-label="Alternar ${nome}">
          <svg class="btn-icon btn-icon--play" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><polygon points="5,3 19,12 5,21"/></svg>
          <svg class="btn-icon btn-icon--pause" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <rect x="6" y="4" width="4" height="16" rx="1"/>
            <rect x="14" y="4" width="4" height="16" rx="1"/>
          </svg>
          <span class="btn-label">${tocando ? 'PAUSAR' : 'RETOMAR'}</span>
        </button>
      </form>`;
    return div;
  }

  function updateCard(card, nome, estado) {
    const tocando = String(estado).toUpperCase() === 'TOCANDO';
    const nomeMin = nome.toLowerCase();
    const acao    = tocando ? 'pausar' : 'retomar';

    card.className = `channel-strip ${tocando ? 'ch--playing' : 'ch--paused'}`;
    card.dataset.estado = estado;

    const badge = card.querySelector('.ch-badge');
    if (badge) {
      badge.textContent = tocando ? 'TOCANDO' : 'PAUSADO';
      badge.className = `ch-badge ${tocando ? 'badge--playing' : 'badge--paused'}`;
    }
    const inp = card.querySelector('input[name="comando"]');
    if (inp) inp.value = `${nomeMin} ${acao}`;
    const btn = card.querySelector('.ch-toggle-btn');
    if (btn) {
      btn.className = `ch-toggle-btn ${tocando ? 'btn--pause' : 'btn--play'}`;
      const lbl = btn.querySelector('.btn-label');
      if (lbl) lbl.textContent = tocando ? 'PAUSAR' : 'RETOMAR';
    }
  }

  /* ═══ RENDER: CHANNELS ═══ */
  function renderInstrumentos(estados) {
    const container = document.getElementById('instrumentos');
    if (!container) return;

    const vistos = new Set();
    for (const [nome, estado] of Object.entries(estados)) {
      const id = 'ch-' + nome.toLowerCase();
      vistos.add(id);
      let card = document.getElementById(id);
      if (!card) {
        card = buildCard(nome, estado);
        container.appendChild(card);
      } else {
        updateCard(card, nome, estado);
      }
    }
    Array.from(container.children).forEach(c => { if (!vistos.has(c.id)) c.remove(); });
  }

  /* ═══ RENDER: LOG ═══ */
  function classifyLine(linha) {
    const l = linha.toLowerCase();
    if (l.includes('retomado') || l.includes('iniciou'))   return 'log--play';
    if (l.includes('pausado'))                              return 'log--pause';
    if (l.includes('encerrou'))                             return 'log--end';
    if (l.includes('iniciou') || l.includes('reprodu'))    return 'log--start';
    return '';
  }

  function renderLog(log) {
    const lista = document.getElementById('log-lista');
    if (!lista) return;

    const atFim = lista.scrollHeight - lista.scrollTop - lista.clientHeight < 50;

    /* Diff: se log novo começa com mesmo prefixo, só prepend novos itens */
    let prefixMatch = true;
    if (ultimoLog.length > 0) {
      for (let i = 0; i < Math.min(ultimoLog.length, log.length); i++) {
        if (ultimoLog[i] !== log[i]) { prefixMatch = false; break; }
      }
    } else { prefixMatch = false; }

    if (prefixMatch && log.length > ultimoLog.length) {
      const novas = log.slice(ultimoLog.length);
      novas.forEach(linha => {
        const li = document.createElement('li');
        li.className = `log-item log-new ${classifyLine(linha)}`;
        li.textContent = linha;
        lista.appendChild(li);
        setTimeout(() => li.classList.remove('log-new'), 600);
      });
    } else if (!prefixMatch) {
      lista.innerHTML = '';
      log.forEach(linha => {
        const li = document.createElement('li');
        li.className = `log-item ${classifyLine(linha)}`;
        li.textContent = linha;
        lista.appendChild(li);
      });
    }

    ultimoLog = log.slice();

    const cnt = document.getElementById('log-count');
    if (cnt) cnt.textContent = `${log.length} eventos`;

    if (atFim) lista.scrollTop = lista.scrollHeight;
  }

  /* ═══ CONNECTION ═══ */
  function setOnline(online) {
    const pill   = document.getElementById('conn-pill');
    const banner = document.getElementById('banner-offline');
    if (pill)   { pill.dataset.online = String(online); const t = pill.querySelector('.conn-text'); if (t) t.textContent = online ? 'ONLINE' : 'OFFLINE'; }
    if (banner) banner.hidden = online;
  }

  /* ═══ FETCH ═══ */
  async function buscarStatus() {
    try {
      const resp = await fetch('/api/status', { cache: 'no-store' });
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      const dados = await resp.json();
      setOnline(true);
      if (dados?.estados) {
        estadosCache = dados.estados;
        renderInstrumentos(dados.estados);
        updateThreadDots(dados.estados);
      }
      if (Array.isArray(dados?.log)) renderLog(dados.log);
    } catch {
      setOnline(false);
    }
  }

  async function enviarComando(comando, botao) {
    if (!comando || carregando) return;
    carregando = true;
    if (botao) botao.disabled = true;
    try {
      await fetch('/comando', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'comando=' + encodeURIComponent(comando),
      });
      showToast('✓ ' + comando);
    } catch {
      setOnline(false);
      showToast('✗ Erro ao enviar comando');
    } finally {
      await buscarStatus();
      if (botao) botao.disabled = false;
      carregando = false;
    }
  }

  /* ═══ EVENTS ═══ */
  document.addEventListener('submit', ev => {
    const form = ev.target.closest('.cmd-form');
    if (!form) return;
    ev.preventDefault();
    const inp   = form.querySelector('[name="comando"]');
    const valor = inp ? inp.value.trim() : '';
    if (!valor) return;
    const botao = form.querySelector('button[type="submit"]');
    enviarComando(valor, botao);
    if (inp && inp.type === 'text') inp.value = '';
  });

  document.addEventListener('DOMContentLoaded', () => {
    /* Terminal toggle */
    const btnToggle  = document.getElementById('toggle-avancado');
    const painelAdv  = document.getElementById('painel-avancado');
    if (btnToggle && painelAdv) {
      btnToggle.addEventListener('click', () => {
        const aberto = btnToggle.getAttribute('aria-expanded') === 'true';
        btnToggle.setAttribute('aria-expanded', String(!aberto));
        painelAdv.hidden = aberto;
        const span = btnToggle.querySelector('span');
        if (span) span.textContent = aberto ? 'EXPANDIR' : 'RECOLHER';
        if (!aberto) {
          const inp = painelAdv.querySelector('input[type="text"]');
          if (inp) inp.focus();
        }
      });
    }

    /* Log scroll to end */
    const btnRolar = document.getElementById('log-clear-view');
    if (btnRolar) {
      btnRolar.addEventListener('click', () => {
        const lista = document.getElementById('log-lista');
        if (lista) lista.scrollTop = lista.scrollHeight;
      });
    }

    /* Seed ultimoLog from server-rendered HTML */
    const listaInicial = document.getElementById('log-lista');
    if (listaInicial) {
      ultimoLog = Array.from(listaInicial.querySelectorAll('li')).map(li => li.textContent);
      listaInicial.scrollTop = listaInicial.scrollHeight;
    }

    /* Start clock */
    startClock();

    /* Initial fetch + polling */
    buscarStatus();
    setInterval(buscarStatus, POLL_MS);
  });

})();
