"use strict";

const form = document.querySelector("#controls");
const requestState = document.querySelector("#request-state");
const summaryStrip = document.querySelector("#summary-strip");
const strategyCards = document.querySelector("#strategy-cards");
const chartLegend = document.querySelector("#chart-legend");
const comparisonBars = document.querySelector("#comparison-bars");
const traceBody = document.querySelector("#trace-body");
const tracePolicy = document.querySelector("#trace-policy");
const partitionGrid = document.querySelector("#partition-grid");
const canvas = document.querySelector("#lag-chart");

const formatters = {
  partitions: (value) => value,
  consumers: (value) => value,
  restarting_consumers: (value) => value,
  messages_per_second: (value) => `${compact(value)}/s`,
  processing_per_consumer: (value) => `${compact(value)}/s`,
  restart_seconds: (value) => `${value}s`,
  session_timeout_seconds: (value) => `${value}s`,
  state_per_partition_mib: (value) => `${value} MiB`,
  handoff_bandwidth_mbps: (value) => `${value} Mb/s`,
  deploy_second: (value) => `+${value}s`,
  run_seconds: (value) => `${value}s`,
  seed: (value) => value,
};

let response = null;
let selectedPolicy = "classic_eager";
let requestSequence = 0;
let debounceTimer = null;

function compact(value) {
  return Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

function integer(value) {
  return Intl.NumberFormat("en").format(value);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function updateOutputs() {
  for (const input of form.elements) {
    if (!(input instanceof HTMLInputElement)) continue;
    const output = form.querySelector(`[data-for="${input.name}"]`);
    if (output) output.value = formatters[input.name]?.(Number(input.value)) ?? input.value;
  }
}

function queryString() {
  return new URLSearchParams(new FormData(form)).toString();
}

function selectedStrategy() {
  return response?.strategies.find((item) => item.policy === selectedPolicy)
    ?? response?.strategies[0];
}

function renderSummary() {
  const eager = response.strategies.find((item) => item.policy === "classic_eager");
  const cooperative = response.strategies.find((item) => item.policy === "cooperative_sticky");
  const staticMember = response.strategies.find((item) => item.policy === "static_membership");
  const incremental = response.strategies.find((item) => item.policy === "incremental_warm_handoff");
  summaryStrip.innerHTML = `
    <div><span>eager stop</span><strong>${(eager.metrics.stopTheWorldMs / 1000).toFixed(1)}s</strong></div>
    <div><span>eager peak lag</span><strong>${integer(eager.metrics.peakLagMessages)}</strong></div>
    <div><span>cooperative churn</span><strong>${cooperative.metrics.assignmentChurnPercent}%</strong></div>
    <div><span>static / warm handoff</span><strong>${staticMember.metrics.rebalances} / ${incremental.metrics.peakLagMessages} lag</strong></div>
  `;
}

function renderStrategies() {
  strategyCards.innerHTML = response.strategies.map((strategy, index) => {
    const metrics = strategy.metrics;
    const selected = strategy.policy === selectedPolicy;
    return `
      <button
        class="strategy-card ${selected ? "selected" : ""}"
        data-policy="${escapeHtml(strategy.policy)}"
        style="--strategy:${escapeHtml(strategy.color)}"
        aria-pressed="${selected}"
      >
        <span class="strategy-number">${String(index + 1).padStart(2, "0")}</span>
        <span class="strategy-kicker">${escapeHtml(strategy.kicker)}</span>
        <strong>${escapeHtml(strategy.name)}${strategy.recommended ? "<small>preferred here</small>" : ""}</strong>
        <span class="strategy-description">${escapeHtml(strategy.description)}</span>
        <span class="strategy-metrics">
          <span><em>revoked</em><b>${metrics.partitionsRevoked}</b></span>
          <span><em>peak lag</em><b>${compact(metrics.peakLagMessages)}</b></span>
          <span><em>recovery</em><b>${metrics.recoverySeconds}s</b></span>
          <span><em>p99</em><b>${compact(metrics.endToEndP99Ms)} ms</b></span>
        </span>
        <span class="strategy-tradeoff">${escapeHtml(strategy.tradeoff)}</span>
      </button>
    `;
  }).join("");

  strategyCards.querySelectorAll("[data-policy]").forEach((card) => {
    card.addEventListener("click", () => {
      selectedPolicy = card.dataset.policy;
      renderStrategies();
      renderSelected();
      drawChart();
    });
  });
}

function renderComparison() {
  const maximum = Math.max(...response.strategies.map((item) => item.metrics.peakLagMessages), 1);
  comparisonBars.innerHTML = response.strategies.map((strategy) => {
    const metrics = strategy.metrics;
    const width = Math.max(1, metrics.peakLagMessages / maximum * 100);
    return `
      <button data-comparison-policy="${escapeHtml(strategy.policy)}" style="--strategy:${escapeHtml(strategy.color)}">
        <span><strong>${escapeHtml(strategy.name)}</strong><em>${integer(metrics.peakLagMessages)} lag</em></span>
        <i><b style="width:${width}%"></b></i>
        <small>${metrics.partitionsRevoked} revoked · ${metrics.duplicateMessages} duplicate-risk records · ${metrics.stateTransferMiB} MiB state</small>
      </button>
    `;
  }).join("");
  comparisonBars.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => {
      selectedPolicy = button.dataset.comparisonPolicy;
      renderStrategies();
      renderSelected();
      drawChart();
    });
  });
}

function renderLegend() {
  chartLegend.innerHTML = response.strategies.map((strategy) => `
    <button data-legend-policy="${escapeHtml(strategy.policy)}">
      <i style="--strategy:${escapeHtml(strategy.color)}"></i>${escapeHtml(strategy.name)}
    </button>
  `).join("");
  chartLegend.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => {
      selectedPolicy = button.dataset.legendPolicy;
      renderStrategies();
      renderSelected();
      drawChart();
    });
  });
}

function createPartitions() {
  partitionGrid.innerHTML = Array.from({ length: 48 }, (_, index) => (
    `<span title="partition ${index}">${index}</span>`
  )).join("");
}

function renderPartitions(strategy) {
  const displayed = Math.min(48, response.config.partitions);
  if (partitionGrid.children.length !== displayed) {
    partitionGrid.innerHTML = Array.from({ length: displayed }, (_, index) => (
      `<span title="partition ${index}">${index}</span>`
    )).join("");
  }
  const revokedShare = strategy.metrics.partitionsRevoked / response.config.partitions;
  const revoked = Math.round(displayed * revokedShare);
  [...partitionGrid.children].forEach((partition, index) => {
    const shuffled = (index * 17 + 7) % displayed;
    partition.className = "";
    if (shuffled < revoked) partition.classList.add("revoked");
    else if (strategy.policy === "incremental_warm_handoff" && index % 7 === 0) {
      partition.classList.add("handoff");
    } else partition.classList.add("owned");
  });
}

function renderSelected() {
  const strategy = selectedStrategy();
  if (!strategy) return;
  const metrics = strategy.metrics;
  const retainedGeneration = metrics.rebalances === 0;
  document.querySelector("#hero-lag").textContent = integer(metrics.peakLagMessages);
  document.querySelector("#hero-revoked").textContent = metrics.partitionsRevoked;
  document.querySelector("#hero-stop").textContent = `${(metrics.stopTheWorldMs / 1000).toFixed(1)}s`;
  document.querySelector("#hero-recovery").textContent = `${metrics.recoverySeconds}s`;
  document.querySelector("#console-status").textContent = retainedGeneration
    ? "identity retained"
    : strategy.policy === "incremental_warm_handoff" ? "warm handoff" : "rebalance";
  document.querySelector("#console-log").textContent = retainedGeneration
    ? "41 retained · returning member reclaimed identity"
    : strategy.policy === "classic_eager"
      ? "41 → 42 · all partitions revoked"
      : `41 → 42 · ${metrics.partitionsRevoked} partitions transfer`;
  requestState.textContent = retainedGeneration
    ? `${response.config.consumers} members · generation 41 retained`
    : `${response.config.consumers} members · generation 41 → 42`;
  tracePolicy.textContent = strategy.name;
  renderPartitions(strategy);
  renderTrace(strategy);
}

function renderTrace(strategy) {
  traceBody.innerHTML = strategy.events.map((event) => `
    <tr>
      <td><code>+${(event.timestampMs / 1000).toFixed(0)}s</code></td>
      <td><code>${escapeHtml(event.memberId)}</code></td>
      <td><span class="generation">g${event.generation}</span></td>
      <td>p${String(event.partition).padStart(2, "0")}</td>
      <td>${escapeHtml(event.action)}</td>
      <td><span class="assignment ${escapeHtml(event.assignmentState)}">${escapeHtml(event.assignmentState)}</span></td>
      <td>${integer(event.lagMessages)}</td>
      <td class="${event.endToEndP99Ms > 1000 ? "danger" : ""}">${event.endToEndP99Ms} ms</td>
      <td>${escapeHtml(event.duplicateRisk)}</td>
    </tr>
  `).join("");
}

function prepareCanvas() {
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.round(rect.width * ratio);
  canvas.height = Math.round(rect.height * ratio);
  const context = canvas.getContext("2d");
  context.scale(ratio, ratio);
  return { context, width: rect.width, height: rect.height };
}

function drawChart() {
  if (!response) return;
  const { context, width, height } = prepareCanvas();
  const inset = { top: 18, right: 18, bottom: 34, left: 58 };
  const plotWidth = width - inset.left - inset.right;
  const plotHeight = height - inset.top - inset.bottom;
  const maximum = Math.max(
    1,
    ...response.strategies.flatMap((strategy) => strategy.timeline.map((point) => point.lagMessages)),
  );
  const y = (value) => inset.top + plotHeight - value / maximum * plotHeight;

  context.clearRect(0, 0, width, height);
  context.font = "10px ui-monospace, monospace";
  context.textBaseline = "middle";
  [0, 0.25, 0.5, 0.75, 1].forEach((fraction) => {
    const value = maximum * fraction;
    const position = y(value);
    context.beginPath();
    context.strokeStyle = "rgba(20,20,20,.12)";
    context.moveTo(inset.left, position);
    context.lineTo(width - inset.right, position);
    context.stroke();
    context.fillStyle = "#777";
    context.textAlign = "right";
    context.fillText(compact(value), inset.left - 9, position);
  });

  const deployX = inset.left + response.config.deploySecond / (response.config.runSeconds - 1) * plotWidth;
  context.beginPath();
  context.setLineDash([6, 5]);
  context.strokeStyle = "#171717";
  context.moveTo(deployX, inset.top);
  context.lineTo(deployX, inset.top + plotHeight);
  context.stroke();
  context.setLineDash([]);
  context.fillStyle = "#171717";
  context.textAlign = "left";
  context.fillText("deploy", Math.min(deployX + 6, width - 48), inset.top + 10);

  response.strategies.forEach((strategy) => {
    context.beginPath();
    context.strokeStyle = strategy.color;
    context.lineWidth = strategy.policy === selectedPolicy ? 4 : 2;
    context.globalAlpha = strategy.policy === selectedPolicy ? 1 : 0.48;
    strategy.timeline.forEach((point, index) => {
      const x = inset.left + point.second / (response.config.runSeconds - 1) * plotWidth;
      const position = y(point.lagMessages);
      if (index === 0) context.moveTo(x, position);
      else context.lineTo(x, position);
    });
    context.stroke();
  });
  context.globalAlpha = 1;

  [0, 0.25, 0.5, 0.75, 1].forEach((fraction) => {
    const x = inset.left + plotWidth * fraction;
    context.fillStyle = "#777";
    context.textAlign = fraction === 0 ? "left" : fraction === 1 ? "right" : "center";
    context.fillText(`${Math.round((response.config.runSeconds - 1) * fraction)}s`, x, height - 12);
  });
}

function renderAll() {
  renderSummary();
  renderStrategies();
  renderComparison();
  renderLegend();
  renderSelected();
  drawChart();
}

async function loadSimulation() {
  const sequence = ++requestSequence;
  requestState.textContent = "running model…";
  requestState.classList.remove("error");
  try {
    const result = await fetch(`/api/simulate?${queryString()}`, {
      headers: { Accept: "application/json" },
    });
    if (!result.ok) throw new Error(`HTTP ${result.status}`);
    const payload = await result.json();
    if (sequence !== requestSequence) return;
    response = payload;
    requestState.textContent = `${payload.config.consumers} members · generation 41 → 42`;
    renderAll();
  } catch (error) {
    if (sequence !== requestSequence) return;
    requestState.textContent = `model unavailable · ${error.message}`;
    requestState.classList.add("error");
  }
}

form.addEventListener("input", () => {
  updateOutputs();
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(loadSimulation, 130);
});

form.addEventListener("reset", () => {
  setTimeout(() => {
    updateOutputs();
    loadSimulation();
  }, 0);
});

new ResizeObserver(drawChart).observe(canvas);
createPartitions();
updateOutputs();
loadSimulation();
