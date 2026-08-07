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
let lastDetections = [];

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

LABELS.forEach((label, i) => {
  const card = document.createElement('div');
  card.className = 'class-card';
  card.innerHTML = `<span class="class-id">${i}</span><strong>${label}</strong>`;
  el('classGrid').appendChild(card);
});

confSlider.addEventListener('input', () => el('confValue').value = Number(confSlider.value).toFixed(2));
iouSlider.addEventListener('input', () => el('iouValue').value = Number(iouSlider.value).toFixed(2));

loadModelBtn.addEventListener('click', loadModel);
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

async function loadModel() {
  if (session) return;
  loadModelBtn.disabled = true;
  modelStatus.textContent = 'Loading raw ONNX model… first load can take a bit because the file is large.';

  try {
    ort.env.wasm.wasmPaths = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/';
    ort.env.wasm.numThreads = Math.max(1, Math.min(4, navigator.hardwareConcurrency || 1));

    // WASM is the compatibility path. It runs on basically any modern browser that can load the model.
    session = await ort.InferenceSession.create(MODEL_URL, {
      executionProviders: ['wasm'],
      graphOptimizationLevel: 'all'
    });

    const inputName = session.inputNames[0];
    const outputName = session.outputNames[0];
    modelStatus.textContent = `Model ready · input: ${inputName} · output: ${outputName}`;
    loadModelBtn.textContent = 'Model loaded';
    runBtn.disabled = !currentImage;
  } catch (error) {
    console.error(error);
    modelStatus.textContent = `Could not load ./models/best.onnx — ${error.message}`;
    loadModelBtn.disabled = false;
  }
}

function loadImageFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    const image = new Image();
    image.onload = () => {
      currentImage = image;
      imageFileName = file.name;
      drawBaseImage();
      runBtn.disabled = !session;
      emptyState.textContent = session ? 'Image ready.' : 'Image ready — load the model next.';
    };
    image.src = reader.result;
  };
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

async function runDetection() {
  if (!session || !currentImage) return;
  runBtn.disabled = true;
  runBtn.textContent = 'Running…';

  try {
    const pre = preprocess(currentImage, INPUT_SIZE, INPUT_SIZE);
    const inputName = session.inputNames[0];
    const tensor = new ort.Tensor('float32', pre.tensor, [1, 3, INPUT_SIZE, INPUT_SIZE]);

    const start = performance.now();
    const outputs = await session.run({ [inputName]: tensor });
    const elapsed = performance.now() - start;

    const outputName = session.outputNames[0];
    const output = outputs[outputName];
    const conf = Number(confSlider.value);
    const iou = Number(iouSlider.value);

    lastDetections = decodeOutput(output, conf, iou, pre, currentImage.naturalWidth, currentImage.naturalHeight);
    drawDetections(lastDetections);
    renderDetectionList(lastDetections);

    el('inferenceTime').textContent = `${elapsed.toFixed(1)} ms`;
    el('detectionCount').textContent = String(lastDetections.length);
    el('outputShape').textContent = `[${output.dims.join(', ')}]`;
  } catch (error) {
    console.error(error);
    el('detectionList').innerHTML = `<p>Inference failed: ${escapeHtml(error.message)}</p>`;
  } finally {
    runBtn.disabled = false;
    runBtn.textContent = 'Run detection';
  }
}

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
  octx.fillStyle = 'rgb(114,114,114)';
  octx.fillRect(0, 0, targetW, targetH);
  octx.drawImage(image, padX, padY, resizedW, resizedH);

  const pixels = octx.getImageData(0, 0, targetW, targetH).data;
  const area = targetW * targetH;
  const tensor = new Float32Array(area * 3);

  for (let i = 0; i < area; i++) {
    const p = i * 4;
    tensor[i] = pixels[p] / 255;
    tensor[area + i] = pixels[p + 1] / 255;
    tensor[area * 2 + i] = pixels[p + 2] / 255;
  }

  return { tensor, scale, padX, padY, targetW, targetH };
}

function decodeOutput(output, confThreshold, iouThreshold, letterbox, sourceW, sourceH) {
  const dims = output.dims.map(Number);
  const data = output.data;
  if (dims.length !== 3 || dims[0] !== 1) {
    throw new Error(`Unsupported output shape [${dims.join(', ')}]`);
  }

  const a = dims[1];
  const b = dims[2];
  let detections;

  if (a === 6 || b === 6) {
    detections = decodeEndToEnd(data, a, b, a === 6, confThreshold, letterbox, sourceW, sourceH);
  } else if (a === 4 + LABELS.length || b === 4 + LABELS.length) {
    detections = decodeRawYolo(data, a, b, a === 4 + LABELS.length, confThreshold, letterbox, sourceW, sourceH);
    detections = classAwareNms(detections, iouThreshold, 300);
  } else {
    throw new Error(`I don't know this output shape yet: [${dims.join(', ')}]`);
  }

  return detections.sort((x, y) => y.score - x.score);
}

function decodeEndToEnd(data, a, b, channelsFirst, conf, lb, sourceW, sourceH) {
  const rows = channelsFirst ? b : a;
  const out = [];
  for (let i = 0; i < rows; i++) {
    const read = c => channelsFirst ? data[c * rows + i] : data[i * 6 + c];
    let x1 = read(0), y1 = read(1), x2 = read(2), y2 = read(3);
    const score = read(4);
    const classId = Math.round(read(5));
    if (!Number.isFinite(score) || score < conf || classId < 0 || classId >= LABELS.length) continue;

    if (Math.max(Math.abs(x1), Math.abs(y1), Math.abs(x2), Math.abs(y2)) <= 2) {
      x1 *= lb.targetW; x2 *= lb.targetW; y1 *= lb.targetH; y2 *= lb.targetH;
    }
    const box = unletterbox(x1, y1, x2, y2, lb, sourceW, sourceH);
    if (box) out.push({ ...box, score, classId, label: LABELS[classId] });
  }
  return out;
}

function decodeRawYolo(data, a, b, channelsFirst, conf, lb, sourceW, sourceH) {
  const channels = 4 + LABELS.length;
  const predictions = channelsFirst ? b : a;
  const out = [];

  const read = (i, c) => channelsFirst ? data[c * predictions + i] : data[i * channels + c];

  for (let i = 0; i < predictions; i++) {
    let cx = read(i, 0), cy = read(i, 1), w = read(i, 2), h = read(i, 3);
    if (![cx, cy, w, h].every(Number.isFinite) || w <= 0 || h <= 0) continue;

    let classId = -1;
    let score = -Infinity;
    for (let c = 0; c < LABELS.length; c++) {
      const s = read(i, 4 + c);
      if (Number.isFinite(s) && s > score) { score = s; classId = c; }
    }
    if (score < conf || classId < 0) continue;

    if (Math.max(Math.abs(cx), Math.abs(cy), Math.abs(w), Math.abs(h)) <= 2) {
      cx *= lb.targetW; w *= lb.targetW; cy *= lb.targetH; h *= lb.targetH;
    }

    const box = unletterbox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, lb, sourceW, sourceH);
    if (box) out.push({ ...box, score, classId, label: LABELS[classId] });
  }
  return out;
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
      for (let i = group.length - 1; i >= 0; i--) {
        if (iou(best, group[i]) > iouThreshold) group.splice(i, 1);
      }
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

function renderDetectionList(detections) {
  if (!detections.length) {
    el('detectionList').innerHTML = `<p>No objects above the current confidence threshold in ${escapeHtml(imageFileName || 'this image')}.</p>`;
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
