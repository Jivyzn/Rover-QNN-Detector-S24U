const MODEL_URL = './models/best.onnx';
const INPUT_SIZE = 736;
const LABELS = [
  'hammer',
  'tennis ball',
  'traffic cone',
  'black balloon',
  'blue balloon',
  'pink balloon',
  'white balloon',
  'yellow balloon'
];
const COLORS = ['#d9ff45', '#57e6ff', '#ffb347', '#f7f7f7', '#5c8dff', '#ff63cf', '#d7dce3', '#ffe65c'];

let session = null;
let currentImage = null;
let imageFileName = '';
let inputSpec = { batch: 4, channels: 3, width: INPUT_SIZE, height: INPUT_SIZE, layout: 'NCHW', shape: [4, 3, INPUT_SIZE, INPUT_SIZE] };
let modelPromise = null;
let running = false;
let runStartedAt = 0;
let timerHandle = null;

const el = id => document.getElementById(id);
const modelStatus = el('modelStatus');
const loadModelBtn = el('loadModelBtn');
const imageInput = el('imageInput');
const runBtn = el('runBtn');
const canvas = el('canvas');
const ctx = canvas.getContext('2d');
const dropZone = el('dropZone');
const emptyState = el('emptyState');
const confSlider = el('confSlider');
const iouSlider = el('iouSlider');

function setStatus(text, isError = false) {
  modelStatus.textContent = text;
  modelStatus.style.color = isError ? '#ff7a7a' : '';
}

function setRuntimeMessage(text, isError = false) {
  const list = el('detectionList');
  list.innerHTML = `<p${isError ? ' style="color:#ff7a7a"' : ''}>${escapeHtml(text)}</p>`;
}

function updateRunButton() {
  if (running) {
    runBtn.disabled = true;
    return;
  }
  if (!session) {
    runBtn.disabled = true;
    runBtn.textContent = 'Waiting for model…';
    return;
  }
  if (!currentImage) {
    runBtn.disabled = true;
    runBtn.textContent = 'Choose an image first';
    return;
  }
  runBtn.disabled = false;
  runBtn.textContent = 'Run detection';
}

// Render the class list immediately. If these cards are visible, app.js itself is alive.
LABELS.forEach((label, i) => {
  const card = document.createElement('div');
  card.className = 'class-card';
  card.innerHTML = `<span class="class-id">${i}</span><strong>${label}</strong>`;
  el('classGrid').appendChild(card);
});

confSlider.addEventListener('input', () => el('confValue').value = Number(confSlider.value).toFixed(2));
iouSlider.addEventListener('input', () => el('iouValue').value = Number(iouSlider.value).toFixed(2));
loadModelBtn.addEventListener('click', () => loadModel(true));
runBtn.addEventListener('click', runDetection);
imageInput.addEventListener('change', event => {
  const file = event.target.files?.[0];
  if (file) loadImageFile(file);
});

['dragenter', 'dragover'].forEach(type => dropZone.addEventListener(type, event => {
  event.preventDefault();
  dropZone.classList.add('drag');
}));
['dragleave', 'drop'].forEach(type => dropZone.addEventListener(type, event => {
  event.preventDefault();
  dropZone.classList.remove('drag');
}));
dropZone.addEventListener('drop', event => {
  const file = event.dataTransfer?.files?.[0];
  if (file?.type?.startsWith('image/')) loadImageFile(file);
});

window.addEventListener('error', event => {
  setRuntimeMessage(`Page error: ${event.message}`, true);
});
window.addEventListener('unhandledrejection', event => {
  const reason = event.reason?.message || String(event.reason || 'Unknown promise rejection');
  setRuntimeMessage(`Runtime error: ${reason}`, true);
});

async function loadModel(force = false) {
  if (session && !force) return session;
  if (modelPromise && !force) return modelPromise;

  modelPromise = (async () => {
    loadModelBtn.disabled = true;
    loadModelBtn.textContent = 'Loading model…';
    setStatus('Loading best.onnx… this file is large, so the first visit can take a while.');
    setRuntimeMessage('Downloading and initialising the ONNX model…');
    updateRunButton();

    try {
      if (typeof ort === 'undefined') throw new Error('ONNX Runtime Web did not load from the CDN. Refresh the page or disable content blocking for this site.');

      ort.env.wasm.wasmPaths = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/';
      // GitHub Pages does not provide cross-origin isolation headers. One WASM thread is the
      // reliability path here; forcing multiple threads can break SharedArrayBuffer/worker setup.
      ort.env.wasm.numThreads = 1;
      ort.env.wasm.proxy = false;

      session = await ort.InferenceSession.create(MODEL_URL, {
        executionProviders: ['wasm'],
        graphOptimizationLevel: 'all'
      });

      const inputName = session.inputNames[0];
      const outputName = session.outputNames[0];
      const metadata = session.inputMetadata?.[0];
      inputSpec = resolveInputSpec(metadata);
      const outMeta = session.outputMetadata?.[0];
      const outShape = outMeta?.shape ? formatShape(outMeta.shape) : 'dynamic';

      setStatus(`Model ready · ${inputName} ${formatShape(inputSpec.shape)} → ${outputName} ${outShape} · WASM`);
      loadModelBtn.textContent = 'Model loaded ✓';
      setRuntimeMessage(currentImage ? 'Model ready. Running detection…' : 'Model ready. Choose an image to run detection.');
      updateRunButton();
      return session;
    } catch (error) {
      console.error(error);
      session = null;
      setStatus(`Model load failed: ${error.message}`, true);
      loadModelBtn.disabled = false;
      loadModelBtn.textContent = 'Retry model load';
      setRuntimeMessage(`Model load failed: ${error.message}`, true);
      updateRunButton();
      throw error;
    } finally {
      modelPromise = null;
    }
  })();

  return modelPromise;
}

function loadImageFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    const image = new Image();
    image.onload = async () => {
      currentImage = image;
      imageFileName = file.name;
      drawBaseImage();
      emptyState.textContent = 'Image ready.';
      updateRunButton();
      try {
        await loadModel(false);
        await runDetection();
      } catch (_) {
        // A detailed error is already shown on the page.
      }
    };
    image.onerror = () => setRuntimeMessage('Could not decode that image file.', true);
    image.src = reader.result;
  };
  reader.onerror = () => setRuntimeMessage('Could not read that image file.', true);
  reader.readAsDataURL(file);
}

function drawBaseImage() {
  if (!currentImage) return;
  canvas.width = currentImage.naturalWidth;
  canvas.height = currentImage.naturalHeight;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(currentImage, 0, 0);
  dropZone.classList.remove('empty');
  emptyState.style.display = 'none';
}

function startRunTimer() {
  runStartedAt = performance.now();
  clearInterval(timerHandle);
  timerHandle = setInterval(() => {
    const seconds = (performance.now() - runStartedAt) / 1000;
    runBtn.textContent = `Running… ${seconds.toFixed(1)}s`;
    el('inferenceTime').textContent = `${seconds.toFixed(1)} s running`;
  }, 200);
}

function stopRunTimer() {
  clearInterval(timerHandle);
  timerHandle = null;
}

async function runDetection() {
  if (running) return;
  if (!currentImage) {
    setRuntimeMessage('Choose an image first.', true);
    updateRunButton();
    return;
  }
  if (!session) {
    try { await loadModel(false); } catch (_) { return; }
  }

  running = true;
  runBtn.disabled = true;
  runBtn.textContent = 'Preparing…';
  setRuntimeMessage(`Preparing ${inputSpec.width}×${inputSpec.height} input. The ONNX graph requires batch ${inputSpec.batch}.`);
  startRunTimer();

  try {
    // Let the browser paint the running state before doing the large synchronous preprocessing loop.
    await new Promise(resolve => requestAnimationFrame(() => resolve()));

    const pre = preprocess(currentImage, inputSpec.width, inputSpec.height);
    const inputName = session.inputNames[0];
    const batched = buildInputTensor(pre.tensor, inputSpec);
    const tensor = new ort.Tensor('float32', batched, inputSpec.shape);

    setRuntimeMessage(`Running ONNX inference on WASM · input ${formatShape(inputSpec.shape)}…`);
    const start = performance.now();
    const outputs = await session.run({ [inputName]: tensor });
    const elapsed = performance.now() - start;

    const outputName = session.outputNames[0];
    const output = outputs[outputName];
    if (!output) throw new Error(`Model did not return expected output '${outputName}'. Returned: ${Object.keys(outputs).join(', ')}`);

    const conf = Number(confSlider.value);
    const iouThreshold = Number(iouSlider.value);
    const decoded = decodeOutput(output, conf, iouThreshold, pre, currentImage.naturalWidth, currentImage.naturalHeight);

    drawDetections(decoded.detections);
    renderDetectionList(decoded.detections, decoded.maxScore, conf);

    el('inferenceTime').textContent = `${elapsed.toFixed(1)} ms`;
    el('detectionCount').textContent = String(decoded.detections.length);
    el('outputShape').textContent = `[${output.dims.join(', ')}]`;
    el('maxScore').textContent = Number.isFinite(decoded.maxScore) ? `${(decoded.maxScore * 100).toFixed(1)}%` : '—';
  } catch (error) {
    console.error(error);
    setRuntimeMessage(`Inference failed: ${error.message}`, true);
    el('detectionCount').textContent = '0';
  } finally {
    stopRunTimer();
    running = false;
    updateRunButton();
  }
}

function resolveInputSpec(metadata) {
  const rawShape = metadata?.shape ? Array.from(metadata.shape) : [];
  if (rawShape.length !== 4) {
    // The actual best.onnx currently reports [4,3,736,736]. This fallback is deliberately
    // batch 4 so older ORT builds cannot silently send batch 1 again.
    return { batch: 4, channels: 3, width: INPUT_SIZE, height: INPUT_SIZE, layout: 'NCHW', shape: [4, 3, INPUT_SIZE, INPUT_SIZE] };
  }

  const dim = (value, fallback) => {
    const n = Number(value);
    return Number.isInteger(n) && n > 0 ? n : fallback;
  };
  const batch = dim(rawShape[0], 4);

  if (dim(rawShape[1], -1) === 3) {
    const height = dim(rawShape[2], INPUT_SIZE);
    const width = dim(rawShape[3], INPUT_SIZE);
    return { batch, channels: 3, width, height, layout: 'NCHW', shape: [batch, 3, height, width] };
  }
  if (dim(rawShape[3], -1) === 3) {
    const height = dim(rawShape[1], INPUT_SIZE);
    const width = dim(rawShape[2], INPUT_SIZE);
    return { batch, channels: 3, width, height, layout: 'NHWC', shape: [batch, height, width, 3] };
  }
  throw new Error(`Unsupported model input shape ${formatShape(rawShape)}. Expected RGB NCHW or NHWC.`);
}

function buildInputTensor(singleNchw, spec) {
  const area = spec.width * spec.height;
  const singleElements = area * 3;
  if (singleNchw.length !== singleElements) throw new Error(`Preprocess produced ${singleNchw.length} floats; expected ${singleElements}.`);

  const out = new Float32Array(singleElements * spec.batch);
  if (spec.layout === 'NCHW') {
    for (let b = 0; b < spec.batch; b++) out.set(singleNchw, b * singleElements);
    return out;
  }

  for (let b = 0; b < spec.batch; b++) {
    const base = b * singleElements;
    for (let i = 0; i < area; i++) {
      out[base + i * 3] = singleNchw[i];
      out[base + i * 3 + 1] = singleNchw[area + i];
      out[base + i * 3 + 2] = singleNchw[area * 2 + i];
    }
  }
  return out;
}

function formatShape(shape) { return `[${Array.from(shape || []).join(', ')}]`; }

function preprocess(image, targetW, targetH) {
  const scale = Math.min(targetW / image.naturalWidth, targetH / image.naturalHeight);
  const resizedW = Math.round(image.naturalWidth * scale);
  const resizedH = Math.round(image.naturalHeight * scale);
  const padX = Math.floor((targetW - resizedW) / 2);
  const padY = Math.floor((targetH - resizedH) / 2);

  const off = document.createElement('canvas');
  off.width = targetW;
  off.height = targetH;
  const octx = off.getContext('2d', { willReadFrequently: true });
  octx.imageSmoothingEnabled = true;
  octx.imageSmoothingQuality = 'high';
  octx.fillStyle = 'rgb(114,114,114)';
  octx.fillRect(0, 0, targetW, targetH);
  octx.drawImage(image, padX, padY, resizedW, resizedH);

  const pixels = octx.getImageData(0, 0, targetW, targetH).data;
  const area = targetW * targetH;
  const tensor = new Float32Array(area * 3);
  for (let i = 0; i < area; i++) {
    const p = i * 4;
    tensor[i] = pixels[p] / 255.0;
    tensor[area + i] = pixels[p + 1] / 255.0;
    tensor[area * 2 + i] = pixels[p + 2] / 255.0;
  }
  return { tensor, scale, padX, padY, targetW, targetH };
}

function decodeOutput(output, confThreshold, iouThreshold, letterbox, sourceW, sourceH) {
  const dims = Array.from(output.dims, Number);
  const data = output.data;
  if (dims.length !== 3 || dims[0] < 1) throw new Error(`Unsupported output shape [${dims.join(', ')}]`);

  const a = dims[1];
  const b = dims[2];
  let detections = [];
  let maxScore = -Infinity;

  if (a === 6 || b === 6) {
    const decoded = decodeEndToEnd(data, a, b, a === 6, confThreshold, letterbox, sourceW, sourceH);
    detections = decoded.detections;
    maxScore = decoded.maxScore;
  } else if (a === 4 + LABELS.length || b === 4 + LABELS.length) {
    const decoded = decodeRawYolo(data, a, b, a === 4 + LABELS.length, confThreshold, letterbox, sourceW, sourceH);
    maxScore = decoded.maxScore;
    detections = classAwareNms(decoded.detections, iouThreshold, 300);
  } else {
    throw new Error(`Unsupported detector output [${dims.join(', ')}]. Expected [B,12,N], [B,N,12], [B,N,6] or [B,6,N].`);
  }

  return { detections: detections.sort((x, y) => y.score - x.score), maxScore };
}

function decodeEndToEnd(data, a, b, channelsFirst, conf, lb, sourceW, sourceH) {
  const rows = channelsFirst ? b : a;
  const out = [];
  let maxScore = -Infinity;
  for (let i = 0; i < rows; i++) {
    const read = c => channelsFirst ? data[c * rows + i] : data[i * 6 + c];
    let x1 = Number(read(0)), y1 = Number(read(1)), x2 = Number(read(2)), y2 = Number(read(3));
    const score = Number(read(4));
    const classId = Math.round(Number(read(5)));
    if (Number.isFinite(score)) maxScore = Math.max(maxScore, score);
    if (!Number.isFinite(score) || score < conf || classId < 0 || classId >= LABELS.length) continue;
    if (Math.max(Math.abs(x1), Math.abs(y1), Math.abs(x2), Math.abs(y2)) <= 2) {
      x1 *= lb.targetW; x2 *= lb.targetW; y1 *= lb.targetH; y2 *= lb.targetH;
    }
    const box = unletterbox(x1, y1, x2, y2, lb, sourceW, sourceH);
    if (box) out.push({ ...box, score, classId, label: LABELS[classId] });
  }
  return { detections: out, maxScore };
}

function decodeRawYolo(data, a, b, channelsFirst, conf, lb, sourceW, sourceH) {
  const channels = 4 + LABELS.length;
  const predictions = channelsFirst ? b : a;
  const out = [];
  let maxScore = -Infinity;
  const read = (i, c) => channelsFirst ? data[c * predictions + i] : data[i * channels + c];

  for (let i = 0; i < predictions; i++) {
    let cx = Number(read(i, 0)), cy = Number(read(i, 1)), w = Number(read(i, 2)), h = Number(read(i, 3));
    if (![cx, cy, w, h].every(Number.isFinite) || w <= 0 || h <= 0) continue;

    let classId = -1;
    let score = -Infinity;
    for (let c = 0; c < LABELS.length; c++) {
      const s = Number(read(i, 4 + c));
      if (Number.isFinite(s)) maxScore = Math.max(maxScore, s);
      if (Number.isFinite(s) && s > score) { score = s; classId = c; }
    }
    if (score < conf || classId < 0) continue;

    if (Math.max(Math.abs(cx), Math.abs(cy), Math.abs(w), Math.abs(h)) <= 2) {
      cx *= lb.targetW; w *= lb.targetW; cy *= lb.targetH; h *= lb.targetH;
    }
    const box = unletterbox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, lb, sourceW, sourceH);
    if (box) out.push({ ...box, score, classId, label: LABELS[classId] });
  }
  return { detections: out, maxScore };
}

function unletterbox(x1, y1, x2, y2, lb, sourceW, sourceH) {
  x1 = (x1 - lb.padX) / lb.scale;
  y1 = (y1 - lb.padY) / lb.scale;
  x2 = (x2 - lb.padX) / lb.scale;
  y2 = (y2 - lb.padY) / lb.scale;
  x1 = clamp(x1, 0, sourceW - 1); y1 = clamp(y1, 0, sourceH - 1);
  x2 = clamp(x2, 0, sourceW - 1); y2 = clamp(y2, 0, sourceH - 1);
  if (x2 <= x1 || y2 <= y1) return null;
  return { x1, y1, x2, y2 };
}

function classAwareNms(detections, iouThreshold, maxDetections) {
  const kept = [];
  const grouped = new Map();
  for (const d of detections) {
    if (!grouped.has(d.classId)) grouped.set(d.classId, []);
    grouped.get(d.classId).push(d);
  }
  for (const group of grouped.values()) {
    group.sort((a, b) => b.score - a.score);
    while (group.length && kept.length < maxDetections) {
      const best = group.shift();
      kept.push(best);
      for (let i = group.length - 1; i >= 0; i--) if (iou(best, group[i]) > iouThreshold) group.splice(i, 1);
    }
  }
  return kept.sort((a, b) => b.score - a.score).slice(0, maxDetections);
}

function iou(a, b) {
  const ix1 = Math.max(a.x1, b.x1), iy1 = Math.max(a.y1, b.y1);
  const ix2 = Math.min(a.x2, b.x2), iy2 = Math.min(a.y2, b.y2);
  const iw = Math.max(0, ix2 - ix1), ih = Math.max(0, iy2 - iy1);
  const inter = iw * ih;
  const areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
  const areaB = (b.x2 - b.x1) * (b.y2 - b.y1);
  return inter / Math.max(1e-9, areaA + areaB - inter);
}

function drawDetections(detections) {
  drawBaseImage();
  const lineWidth = Math.max(2, Math.round(Math.min(canvas.width, canvas.height) / 350));
  const fontSize = Math.max(14, Math.round(Math.min(canvas.width, canvas.height) / 35));
  ctx.lineWidth = lineWidth;
  ctx.font = `700 ${fontSize}px system-ui`;
  ctx.textBaseline = 'top';

  detections.forEach(d => {
    const color = COLORS[d.classId % COLORS.length];
    const w = d.x2 - d.x1, h = d.y2 - d.y1;
    ctx.strokeStyle = color;
    ctx.strokeRect(d.x1, d.y1, w, h);
    const label = `${d.label} ${(d.score * 100).toFixed(1)}%`;
    const width = ctx.measureText(label).width + 12;
    const y = Math.max(0, d.y1 - fontSize - 8);
    ctx.fillStyle = color;
    ctx.fillRect(d.x1, y, width, fontSize + 8);
    ctx.fillStyle = '#090b0e';
    ctx.fillText(label, d.x1 + 6, y + 4);
  });
}

function renderDetectionList(detections, maxScore, conf) {
  if (!detections.length) {
    const maxText = Number.isFinite(maxScore) ? `${(maxScore * 100).toFixed(2)}%` : 'not readable';
    setRuntimeMessage(`No objects above ${(conf * 100).toFixed(0)}%. Highest class score in batch 0 was ${maxText}. Try a known rover image or lower confidence if needed.`);
    return;
  }
  el('detectionList').innerHTML = detections.map(d =>
    `<div class="detection-item"><span>${escapeHtml(d.label)}</span><span>${(d.score * 100).toFixed(1)}%</span></div>`
  ).join('');
}

function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }
function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, ch => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[ch]));
}

// Auto-load. This removes the old two-step state where the Run button could look dead until
// the model had been manually loaded first.
loadModel(false).catch(() => {});
