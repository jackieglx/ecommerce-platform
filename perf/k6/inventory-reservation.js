import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8082').replace(/\/$/, '');
const RUN_ID = __ENV.RUN_ID;
const TEST_PROFILE = __ENV.TEST_PROFILE || 'limited_stock';
const SKU_MODE = __ENV.SKU_MODE || 'single';
const TARGET_RPS = integerEnv('TARGET_RPS', 10, 1);
const DURATION = __ENV.DURATION || '10s';
const INITIAL_STOCK = integerEnv('INITIAL_STOCK', 100, 1);
const PRE_ALLOCATED_VUS = integerEnv('PRE_ALLOCATED_VUS', 20, 1);
const MAX_VUS = integerEnv('MAX_VUS', 100, PRE_ALLOCATED_VUS);

const SINGLE_SKU = 'loadtest-hot-sku-001';
const SHARDED_SKUS = [
  'loadtest-shard-00-001',
  'loadtest-shard-01-001',
  'loadtest-shard-02-001',
  'loadtest-shard-03-001',
  'loadtest-shard-04-001',
  'loadtest-shard-05-001',
  'loadtest-shard-06-001',
  'loadtest-shard-07-001',
];

validateConfiguration();

const reservationAttempts = new Counter('reservation_attempts');
const reservationCompleted = new Counter('reservation_completed');
const reservationReserved = new Counter('reservation_reserved');
const reservationDuplicate = new Counter('reservation_duplicate');
const reservationSoldOut = new Counter('reservation_sold_out');
const reservationFailed = new Counter('reservation_failed');
const reservationUnknown = new Counter('reservation_unknown');
const reservationParseError = new Counter('reservation_parse_error');
const reservationContractError = new Counter('reservation_contract_error');
const httpTechnicalErrorRate = new Rate('http_technical_error_rate');

const allRequestDuration = new Trend('all_request_duration', true);
const reservedDuration = new Trend('reserved_duration', true);
const soldOutDuration = new Trend('sold_out_duration', true);
const duplicateDuration = new Trend('duplicate_duration', true);
const failedDuration = new Trend('failed_duration', true);
const selloutElapsed = new Trend('inventory_sellout_elapsed_ms', true);

export const options = {
  scenarios: {
    inventory_reservations: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '30s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_technical_error_rate: ['rate<0.001'],
    reservation_duplicate: ['count==0'],
    reservation_failed: ['count==0'],
    reservation_unknown: ['count==0'],
    reservation_parse_error: ['count==0'],
    reservation_contract_error: ['count==0'],
  },
};

export function setup() {
  const health = http.get(`${BASE_URL}/actuator/health`, { timeout: '5s' });
  if (health.status !== 200) {
    throw new Error(`Inventory health check failed: HTTP ${health.status}`);
  }
  return { startedAtEpochMs: Date.now() };
}

export default function (data) {
  const vu = exec.vu.idInTest;
  const iteration = exec.vu.iterationInScenario;
  const identity = `load-${RUN_ID}-vu${pad(vu, 4)}-iter${pad(iteration, 6)}`;
  const skuId = SKU_MODE === 'single'
    ? SINGLE_SKU
    : SHARDED_SKUS[exec.scenario.iterationInTest % SHARDED_SKUS.length];

  reservationAttempts.add(1);
  const response = http.post(
    `${BASE_URL}/api/v1/flashsale/reservations`,
    JSON.stringify({ skuId, qty: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': identity,
        'Idempotency-Key': identity,
      },
      timeout: '10s',
      tags: { endpoint: 'inventory_flashsale_reservation', sku_mode: SKU_MODE },
    },
  );

  reservationCompleted.add(1);
  allRequestDuration.add(response.timings.duration);

  const technicalError = response.status !== 200;
  httpTechnicalErrorRate.add(technicalError);
  if (technicalError) {
    return;
  }

  let body;
  try {
    body = response.json();
  } catch (_) {
    reservationParseError.add(1);
    return;
  }

  if (!body || typeof body.status !== 'string') {
    reservationUnknown.add(1);
    reservationContractError.add(1);
    return;
  }

  switch (body.status) {
    case 'RESERVED':
      if (typeof body.orderId !== 'string' || body.orderId.trim() === '') {
        reservationContractError.add(1);
        failedDuration.add(response.timings.duration);
        return;
      }
      reservationReserved.add(1);
      reservedDuration.add(response.timings.duration);
      break;
    case 'SOLD_OUT':
      reservationSoldOut.add(1);
      soldOutDuration.add(response.timings.duration);
      selloutElapsed.add(Date.now() - data.startedAtEpochMs);
      break;
    case 'DUPLICATE':
      reservationDuplicate.add(1);
      duplicateDuration.add(response.timings.duration);
      break;
    case 'FAILED':
      reservationFailed.add(1);
      failedDuration.add(response.timings.duration);
      break;
    default:
      reservationUnknown.add(1);
      reservationContractError.add(1);
  }
}

export function handleSummary(data) {
  const resultDir = `perf/results/${RUN_ID}`;
  const result = buildResult(data);
  const text = renderTextSummary(result);
  return {
    stdout: text,
    [`${resultDir}/k6-summary.txt`]: text,
    [`${resultDir}/k6-summary.json`]: JSON.stringify(data, null, 2),
    [`${resultDir}/k6-result.json`]: JSON.stringify(result, null, 2),
  };
}

function buildResult(data) {
  const count = (name) => metricValue(data, name, 'count', 0);
  const rate = (name) => metricValue(data, name, 'rate', 0);
  const trend = (name) => ({
    p50Ms: metricValue(data, name, 'p(50)', null),
    p95Ms: metricValue(data, name, 'p(95)', null),
    p99Ms: metricValue(data, name, 'p(99)', null),
  });
  const dropped = count('dropped_iterations');

  return {
    runId: RUN_ID,
    generatedAt: new Date().toISOString(),
    parameters: {
      baseUrl: BASE_URL,
      testProfile: TEST_PROFILE,
      skuMode: SKU_MODE,
      targetRps: TARGET_RPS,
      duration: DURATION,
      durationSeconds: durationSeconds(DURATION),
      initialStock: INITIAL_STOCK,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      skuIds: SKU_MODE === 'single' ? [SINGLE_SKU] : SHARDED_SKUS,
    },
    throughput: {
      targetQps: TARGET_RPS,
      actualSentQps: rate('reservation_attempts'),
      actualCompletedQps: rate('reservation_completed'),
      reservedQps: rate('reservation_reserved'),
      attempts: count('reservation_attempts'),
      completed: count('reservation_completed'),
      droppedIterations: dropped,
      insufficientVus: dropped > 0,
    },
    businessResults: {
      reserved: count('reservation_reserved'),
      soldOut: count('reservation_sold_out'),
      duplicate: count('reservation_duplicate'),
      failed: count('reservation_failed'),
      unknown: count('reservation_unknown'),
      parseError: count('reservation_parse_error'),
      contractError: count('reservation_contract_error'),
    },
    latency: {
      overall: trend('all_request_duration'),
      reserved: trend('reserved_duration'),
      soldOut: trend('sold_out_duration'),
      duplicate: trend('duplicate_duration'),
      failed: trend('failed_duration'),
    },
    httpTechnicalErrorRate: rate('http_technical_error_rate'),
    firstObservedSoldOutMs: metricValue(data, 'inventory_sellout_elapsed_ms', 'min', null),
  };
}

function renderTextSummary(result) {
  const t = result.throughput;
  const b = result.businessResults;
  const l = result.latency;
  const latencyLine = (label, value) =>
    `${label}: p50=${format(value.p50Ms)} ms p95=${format(value.p95Ms)} ms p99=${format(value.p99Ms)} ms`;
  return [
    '',
    `Inventory reservation load test: ${result.runId}`,
    `profile=${result.parameters.testProfile} skuMode=${result.parameters.skuMode}`,
    `targetQPS=${t.targetQps} actualSentQPS=${format(t.actualSentQps)} actualCompletedQPS=${format(t.actualCompletedQps)} RESERVED_QPS=${format(t.reservedQps)}`,
    `attempts=${t.attempts} completed=${t.completed} droppedIterations=${t.droppedIterations} insufficientVUs=${t.insufficientVus}`,
    `RESERVED=${b.reserved} SOLD_OUT=${b.soldOut} DUPLICATE=${b.duplicate} FAILED=${b.failed} UNKNOWN=${b.unknown} PARSE_ERROR=${b.parseError} CONTRACT_ERROR=${b.contractError}`,
    `HTTP technical error rate=${format(result.httpTechnicalErrorRate * 100, 4)}%`,
    latencyLine('overall', l.overall),
    latencyLine('RESERVED', l.reserved),
    latencyLine('SOLD_OUT', l.soldOut),
    `first observed SOLD_OUT=${format(result.firstObservedSoldOutMs)} ms after setup`,
    '',
  ].join('\n');
}

function metricValue(data, metricName, valueName, fallback) {
  const metric = data.metrics[metricName];
  if (!metric || !metric.values || metric.values[valueName] === undefined) {
    return fallback;
  }
  return metric.values[valueName];
}

function integerEnv(name, fallback, minimum) {
  const raw = __ENV[name];
  const value = raw === undefined || raw === '' ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer >= ${minimum}, got '${raw}'`);
  }
  return value;
}

function validateConfiguration() {
  if (!RUN_ID || !/^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$/.test(RUN_ID)) {
    throw new Error('RUN_ID is required and must match [A-Za-z0-9][A-Za-z0-9_-]{2,47}');
  }
  if (!['limited_stock', 'success_capacity'].includes(TEST_PROFILE)) {
    throw new Error(`Unsupported TEST_PROFILE '${TEST_PROFILE}'`);
  }
  if (!['single', 'sharded'].includes(SKU_MODE)) {
    throw new Error(`Unsupported SKU_MODE '${SKU_MODE}'`);
  }
  durationSeconds(DURATION);
}

function durationSeconds(value) {
  const match = /^(\d+)(s|m|h)$/.exec(value);
  if (!match || Number(match[1]) <= 0) {
    throw new Error(`DURATION must use a positive k6 duration such as 30s, 2m, or 1h; got '${value}'`);
  }
  const multiplier = match[2] === 'h' ? 3600 : match[2] === 'm' ? 60 : 1;
  return Number(match[1]) * multiplier;
}

function pad(value, width) {
  return String(value).padStart(width, '0');
}

function format(value, decimals = 2) {
  return value === null || value === undefined ? 'n/a' : Number(value).toFixed(decimals);
}
