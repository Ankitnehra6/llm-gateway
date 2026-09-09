/* LLM Gateway console.
 *
 * Talks to the same public API any client would use — no privileged endpoint, no
 * server-rendered state. What you see here is what an SDK pointed at this gateway
 * would see.
 */

const el = (id) => document.getElementById(id);

const state = {
  lastPrompt: null,
  failures: 0,
};

const POLL_MS = 2000;
const MAX_LOG_ROWS = 40;

const apiKey = () => el("api-key").value;

const authHeaders = () => ({
  Authorization: "Bearer " + apiKey(),
  "Content-Type": "application/json",
});

/* --- formatting ------------------------------------------------------------- */

function formatCount(n) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 10_000) return Math.round(n / 1000) + "k";
  if (n >= 1000) return (n / 1000).toFixed(1) + "k";
  return String(Math.round(n));
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
}

/* --- asking ----------------------------------------------------------------- */

async function ask(prompt) {
  if (!prompt.trim()) return;

  state.lastPrompt = prompt;
  el("repeat").disabled = false;
  el("send").disabled = true;
  el("answer").hidden = false;
  el("badges").innerHTML = "";
  el("answer-text").textContent = "";

  const started = performance.now();

  try {
    if (el("stream-toggle").checked) {
      await askStreaming(prompt, started);
    } else {
      await askBuffered(prompt, started);
    }
  } catch (err) {
    el("answer-text").classList.remove("streaming");
    el("answer-text").textContent = "Request failed: " + err.message;
    el("badges").innerHTML = '<span class="badge badge-warn">error</span>';
    addLog("err", prompt, performance.now() - started, "failed");
  } finally {
    el("send").disabled = false;
    refreshStats();
  }
}

async function askBuffered(prompt, started) {
  const res = await fetch("/v1/chat/completions", {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({ messages: [{ role: "user", content: prompt }] }),
  });

  const body = await res.json();
  const ms = performance.now() - started;

  if (!res.ok) {
    // The API returns RFC 9457 problem details, so there is a real message to show.
    const detail = body.detail || body.title || "request failed";
    el("answer-text").textContent = detail;
    el("badges").innerHTML = `<span class="badge badge-warn">${res.status}</span>`;
    addLog("err", prompt, ms, `${res.status}`);
    return;
  }

  el("answer-text").textContent = body.content;
  renderBadges(body.gateway, body.usage, ms);
  addLog(body.gateway.cache_hit ? "hit" : "miss", prompt, ms, body.gateway.provider);
}

async function askStreaming(prompt, started) {
  const res = await fetch("/v1/chat/completions", {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({ stream: true, messages: [{ role: "user", content: prompt }] }),
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    el("answer-text").textContent = body.detail || body.title || "request failed";
    el("badges").innerHTML = `<span class="badge badge-warn">${res.status}</span>`;
    addLog("err", prompt, performance.now() - started, `${res.status}`);
    return;
  }

  const target = el("answer-text");
  target.classList.add("streaming");

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let firstChunkMs = null;
  let chunks = 0;
  // The gateway attaches its account of the request to the terminating chunk, so a
  // streamed cache hit is distinguishable from a streamed upstream call.
  let gateway = null;

  // SSE frames are separated by a blank line, and a single read can deliver a
  // partial frame, so the tail is carried over rather than parsed early.
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split("\n\n");
    buffer = frames.pop();

    for (const frame of frames) {
      const line = frame.trim();
      if (!line.startsWith("data:")) continue;

      const payload = line.slice(5).trim();
      if (payload === "[DONE]") continue;

      try {
        const chunk = JSON.parse(payload);
        if (chunk.gateway) gateway = chunk.gateway;

        const text = chunk.choices?.[0]?.delta?.content;
        if (text) {
          if (firstChunkMs === null) firstChunkMs = performance.now() - started;
          chunks++;
          target.textContent += text;
        }
      } catch {
        // A frame that is not JSON is not worth failing the whole stream over.
      }
    }
  }

  target.classList.remove("streaming");
  const ms = performance.now() - started;
  const cached = gateway?.cache_hit === true;

  const badges = [
    cached
      ? '<span class="badge badge-hit">cache hit</span>'
      : `<span class="badge badge-miss">${escapeHtml(gateway?.provider ?? "upstream")}</span>`,
    '<span class="badge">streamed</span>',
    `<span class="badge">${chunks} chunks</span>`,
    `<span class="badge">first token ${Math.round(firstChunkMs ?? ms)}ms</span>`,
    `<span class="badge">${Math.round(ms)}ms total</span>`,
  ];
  if (cached && gateway.cache_similarity != null) {
    badges.splice(1, 0, `<span class="badge badge-hit">similarity ${gateway.cache_similarity.toFixed(4)}</span>`);
  }
  if (gateway?.failed_over) {
    badges.splice(1, 0, '<span class="badge badge-warn">failed over</span>');
  }
  el("badges").innerHTML = badges.join("");

  addLog(cached ? "hit" : "miss", prompt, ms, `stream · ${chunks} chunks`);
}

function renderBadges(gateway, usage, ms) {
  const badges = [];

  if (gateway.cache_hit) {
    badges.push('<span class="badge badge-hit">cache hit</span>');
    if (gateway.cache_similarity != null) {
      badges.push(`<span class="badge badge-hit">similarity ${gateway.cache_similarity.toFixed(4)}</span>`);
    }
  } else {
    badges.push(`<span class="badge badge-miss">${escapeHtml(gateway.provider)}</span>`);
  }

  if (gateway.failed_over) {
    badges.push('<span class="badge badge-warn">failed over</span>');
  }

  badges.push(`<span class="badge">${Math.round(ms)}ms</span>`);
  badges.push(`<span class="badge">${usage.total_tokens} tokens</span>`);

  el("badges").innerHTML = badges.join("");
}

function addLog(kind, prompt, ms, detail) {
  const log = el("log");
  log.querySelector(".empty")?.remove();

  const row = document.createElement("div");
  row.className = "log-row";
  row.innerHTML = `
    <span class="log-time">${new Date().toLocaleTimeString()}</span>
    <span class="log-tag ${kind}">${kind === "hit" ? "CACHED" : kind === "miss" ? "UPSTREAM" : "ERROR"}</span>
    <span class="log-prompt" title="${escapeHtml(prompt)}">${escapeHtml(prompt)}</span>
    <span class="log-ms">${escapeHtml(detail)} · ${Math.round(ms)}ms</span>`;

  log.prepend(row);
  while (log.children.length > MAX_LOG_ROWS) log.removeChild(log.lastChild);
}

/* --- stats ------------------------------------------------------------------ */

async function refreshStats() {
  try {
    const res = await fetch("/v1/gateway/stats", { headers: authHeaders(), cache: "no-store" });
    if (!res.ok) throw new Error("stats " + res.status);
    const stats = await res.json();

    state.failures = 0;
    setStatus("ok", "healthy");
    render(stats);
  } catch {
    state.failures++;
    // One missed poll is a blip; several in a row means the gateway is gone.
    if (state.failures >= 3) setStatus("down", "gateway unreachable");
  }
}

function setStatus(cls, text) {
  const status = el("status");
  status.classList.remove("ok", "down");
  status.classList.add(cls);
  el("status-text").textContent = text;
}

function render(stats) {
  const cache = stats.cache;

  el("hit-rate").textContent = cache.hits + cache.misses === 0
    ? "—"
    : (cache.hit_rate * 100).toFixed(0) + "%";
  el("tokens-saved").textContent = formatCount(cache.tokens_saved);
  el("hit-miss").textContent = `${cache.hits} / ${cache.misses}`;
  el("hit-bar").style.width = (cache.hit_rate * 100) + "%";

  el("threshold-hint").textContent = cache.enabled
    ? `similarity ≥ ${cache.similarity_threshold}`
    : "disabled";
  if (!cache.enabled) {
    el("cache-note").textContent =
      "Caching is unavailable: this Redis does not support vector search. " +
      "Every request goes upstream. Use redis-stack-server to enable it.";
  }

  el("providers").innerHTML = stats.providers
    .map(
      (p, i) => `
      <div class="provider">
        <span><span class="provider-order">${i + 1}</span><span class="provider-name">${escapeHtml(p.name)}</span></span>
        <span class="circuit circuit-${p.circuit_state}">${p.circuit_state.replace("_", "-")}</span>
      </div>`
    )
    .join("");

  const tenant = stats.tenant;
  el("tenant-id").textContent = tenant.id;
  el("budget-used").textContent = formatCount(tenant.used);
  el("budget-limit").textContent = formatCount(tenant.limit);

  const usedPct = tenant.limit > 0 ? Math.min(100, (tenant.used / tenant.limit) * 100) : 0;
  const bar = el("budget-bar");
  bar.style.width = usedPct + "%";
  bar.className = "meter-fill meter-budget" + (usedPct >= 100 ? " empty" : usedPct >= 75 ? " low" : "");

  el("model").textContent = stats.default_model;
}

/* --- boot -------------------------------------------------------------------- */

el("ask-form").addEventListener("submit", (e) => {
  e.preventDefault();
  ask(el("prompt").value);
});

el("repeat").addEventListener("click", () => {
  if (state.lastPrompt) ask(state.lastPrompt);
});

el("prompt").addEventListener("keydown", (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
    e.preventDefault();
    ask(el("prompt").value);
  }
});

// Switching identity changes whose budget is shown, so refresh immediately.
el("api-key").addEventListener("change", refreshStats);

el("clear-log").addEventListener("click", () => {
  el("log").innerHTML = '<p class="empty">No requests yet.</p>';
});

/**
 * Prefills the prompt from the URL, so a demo can be linked rather than described:
 * `?q=your+prompt` fills the box, and `&run=1` sends it on load.
 */
function applyUrlPrompt() {
  const params = new URLSearchParams(location.search);
  const q = params.get("q");
  if (!q) return;

  el("prompt").value = q;
  if (params.get("key")) el("api-key").value = params.get("key");
  if (params.get("run") === "1") ask(q);
}

refreshStats();
applyUrlPrompt();
setInterval(refreshStats, POLL_MS);
