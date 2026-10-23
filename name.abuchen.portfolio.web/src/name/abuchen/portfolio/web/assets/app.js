'use strict';

const byId = id => document.getElementById(id);
const text = (id, value) => { byId(id).textContent = value; };
const percent = value => value === null ? 'Unavailable' : new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2, signDisplay: 'exceptZero' }).format(value);
const weight = value => value === null ? '—' : new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 }).format(value);
const dateLabel = value => new Date(value + 'T12:00:00Z').toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const iso = date => date.toISOString().slice(0, 10);
let report;
let mode = 'return';
let selected = 0;
let chartGeometry;
let activePreset = '1Y';
let busy = false;
let requestController;
let refreshConnectionError;
let selectedBenchmark = '';
let selectedInstrument = '';
let market;
let priceRequestPending = false;
let priceActionError;
let marketConnectionError = false;

function money(value, signed = false) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: report.currency, signDisplay: signed ? 'exceptZero' : 'auto' }).format(value / 100);
}

function tone(id, value) {
  byId(id).classList.toggle('positive', value > 0);
  byId(id).classList.toggle('negative', value < 0);
}

function cell(row, value, numeric = false, secondary) {
  const td = document.createElement('td');
  td.textContent = value;
  if (numeric) td.classList.add('numeric');
  if (secondary) {
    const detail = document.createElement('div');
    detail.className = 'table-secondary';
    detail.textContent = secondary;
    td.append(detail);
  }
  row.append(td);
}

function emptyRow(body, columns, message) {
  const row = document.createElement('tr');
  const td = document.createElement('td');
  td.colSpan = columns;
  td.className = 'empty';
  td.textContent = message;
  row.append(td);
  body.append(row);
}

function render() {
  text('portfolio-label', report.label);
  text('valuation-note', `Daily valuations in ${report.currency} · prices from the loaded file`);
  byId('demo-badge').hidden = !report.demo;
  text('total-value', money(report.totalValue));
  text('total-date', `As of ${dateLabel(report.asOf)}`);
  for (const key of ['day', 'week']) {
    const period = report[key];
    text(key + '-gain', money(period.gain, true));
    tone(key + '-gain', period.gain);
    text(key + '-return', percent(period.rate));
    tone(key + '-return', period.rate);
    text(key + '-dates', `${dateLabel(period.openingDate)} → ${dateLabel(period.closingDate)}`);
  }
  const dividends = report.dividends.reduce((sum, dividend) => sum + dividend.net, 0);
  text('dividend-total', money(dividends));
  text('dividend-count', `${report.dividends.length} received payment${report.dividends.length === 1 ? '' : 's'}`);
  text('period-rate', percent(report.period.rate));
  tone('period-rate', report.period.rate);
  text('period-gain', `${money(report.period.gain, true)} investment gain / loss`);
  text('period-dates', `${dateLabel(report.period.openingDate)} — ${dateLabel(report.period.closingDate)}`);
  text('value-change', money(report.period.valueChange, true));
  text('net-flows', money(report.period.netFlows, true));
  text('investment-gain', money(report.period.gain, true));
  byId('from').value = report.period.openingDate;
  byId('to').value = report.period.closingDate;
  const today = new Date();
  const localToday = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  text('day-label', report.asOf === localToday ? 'Today’s performance' : 'Daily performance');
  byId('from').max = report.period.closingDate;
  byId('to').max = localToday;
  for (const button of document.querySelectorAll('[data-period]')) button.setAttribute('aria-pressed', String(button.dataset.period === activePreset));
  byId('warnings').hidden = report.warnings.length === 0;
  text('warning-summary', `${report.warnings.length} data quality notice${report.warnings.length === 1 ? '' : 's'} · review before relying on these figures`);
  const warnings = byId('warning-list');
  warnings.replaceChildren();
  for (const warning of report.warnings) {
    const li = document.createElement('li');
    li.textContent = warning;
    warnings.append(li);
  }
  const holdings = byId('holdings-body');
  holdings.replaceChildren();
  text('holding-count', `${report.holdings.length} positions`);
  for (const holding of report.holdings) {
    const row = document.createElement('tr');
    const nameCell = document.createElement('td');
    if (holding.type === 'security' && holding.uuid) {
      const link = document.createElement('button'); link.type = 'button'; link.className = 'instrument-link';
      link.textContent = holding.name; link.addEventListener('click', async () => {
        selectedInstrument = holding.uuid;
        await load(report.period.openingDate, report.period.closingDate);
        if (report?.instrument?.uuid === selectedInstrument) {
          const panel = byId('instrument-panel');
          panel.tabIndex = -1;
          panel.focus({ preventScroll: true });
          panel.scrollIntoView({ block: 'start' });
        }
      });
      nameCell.append(link);
    } else nameCell.textContent = holding.name;
    row.append(nameCell);
    cell(row, money(holding.value), true);
    cell(row, weight(holding.weight), true);
    cell(row, holding.quoteDate ? dateLabel(holding.quoteDate) : '—');
    holdings.append(row);
  }
  if (!report.holdings.length) emptyRow(holdings, 4, 'No holdings or cash accounts at this valuation date.');
  renderInstrument(report.instrument);
  const income = byId('dividends-body');
  income.replaceChildren();
  text('income-currency', `All amounts in ${report.currency}`);
  for (const dividend of report.dividends) {
    const row = document.createElement('tr');
    cell(row, dateLabel(dividend.date), false, dividend.account);
    cell(row, dividend.security, false, dividend.originalCurrency !== report.currency ? `Original net: ${new Intl.NumberFormat(undefined, { style: 'currency', currency: dividend.originalCurrency }).format(dividend.originalNet / 100)}` : null);
    for (const field of ['gross', 'taxes', 'fees', 'net']) cell(row, money(dividend[field]), true);
    income.append(row);
  }
  if (!report.dividends.length) emptyRow(income, 6, 'No dividend payments in this period.');
  text('revision', `Snapshot ${report.revision.slice(0, 12)} · loaded ${new Date(report.loadedAt).toLocaleString()}`);
  renderBenchmarkOptions();
  renderFreshness();
  setMarket(report.market);
  selected = report.series.length - 1;
  byId('chart-position').max = String(selected);
  byId('chart-position').value = String(selected);
  drawChart();
  byId('dashboard').hidden = false;
}

function renderInstrument(instrument) {
  const panel = byId('instrument-panel');
  panel.hidden = !selectedInstrument;
  if (!selectedInstrument) return;
  if (!instrument) {
    text('instrument-name', 'Instrument unavailable'); text('instrument-message', 'The selected instrument detail is unavailable.');
    for (const id of ['instrument-period', 'instrument-shares', 'instrument-value', 'instrument-ttwror', 'instrument-irr', 'instrument-gain', 'instrument-dividends', 'instrument-currency']) text(id, 'Unavailable');
    byId('instrument-chart').replaceChildren(); return;
  }
  text('instrument-name', instrument.name || 'Instrument unavailable');
  text('instrument-period', `${dateLabel(report.period.openingDate)} – ${dateLabel(report.period.closingDate)} · held quantity`);
  const states = [];
  if (instrument.shares === 0) states.push('No position at the selected closing date.');
  else if (instrument.value === null) states.push('Position held at the selected date; closing valuation is unavailable because a quote is missing.');
  else states.push(`Position at ${dateLabel(report.asOf)} · quote ${instrument.quoteDate ? dateLabel(instrument.quoteDate) : 'unavailable'}.`);
  if (instrument.message) states.push(instrument.message);
  text('instrument-message', states.join(' '));
  text('instrument-shares', new Intl.NumberFormat(undefined, { maximumFractionDigits: 8 }).format(instrument.shares / 1e8));
  text('instrument-value', instrument.value === null ? (instrument.shares === 0 ? 'No position' : 'Unavailable') : money(instrument.value));
  text('instrument-ttwror', percent(instrument.twror));
  text('instrument-irr', percent(instrument.irr));
  text('instrument-gain', instrument.gain === null ? 'Unavailable' : money(instrument.gain, true));
  text('instrument-dividends', money(instrument.dividends));
  text('instrument-currency', instrument.currency || 'currency unavailable');
  const svg = byId('instrument-chart'); svg.replaceChildren();
  if (!instrument.prices?.length) { svg.append(svgElement('text', { x: 90, y: 90, class: 'axis-label' }, 'No stored quotes in the selected period.')); return; }
  const vals = instrument.prices.map(p => p.price / instrument.quoteScale), low = Math.min(...vals), high = Math.max(...vals), span = high - low || Math.abs(high) || 1;
  const times = instrument.prices.map(p => Date.parse(p.date + 'T12:00:00Z')), firstTime = times[0], lastTime = times.at(-1);
  const x = i => 100 + (times[i] - firstTime) / Math.max(1, lastTime - firstTime) * 680, y = v => 145 - (v - low) / span * 112;
  const path = instrument.prices.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p.price / instrument.quoteScale)}`).join(' ');
  const priceFormat = value => new Intl.NumberFormat(undefined, { ...(instrument.currency ? { style: 'currency', currency: instrument.currency } : {}), maximumFractionDigits: 6 }).format(value);
  svg.append(svgElement('text', { x: 90, y: 25, class: 'axis-label' }, priceFormat(high)));
  svg.append(svgElement('text', { x: 90, y: 148, class: 'axis-label' }, priceFormat(low)));
  svg.append(svgElement('text', { x: 100, y: 170, class: 'axis-label' }, dateLabel(instrument.prices[0].date)));
  svg.append(svgElement('text', { x: 780, y: 170, 'text-anchor': 'end', class: 'axis-label' }, dateLabel(instrument.prices.at(-1).date)));
  svg.append(svgElement('path', { d: path, class: 'chart-line' }));
  if (instrument.prices.length === 1) svg.append(svgElement('circle', { cx: x(0), cy: y(vals[0]), r: 4, class: 'cursor-dot' }));
}

function setMarket(data) {
  if (data && (!market || data.observedAt >= market.observedAt)) {
    market = data;
    marketConnectionError = false;
  }
  renderMarket();
}

function renderMarket() {
  const quotes = market?.quotes;
  const fx = market?.fx;
  const showTime = value => new Date(value).toLocaleString();
  const messages = [];
  if (quotes) {
    let status = quotes.lastSuccessAt ? `Last confirmed run: ${showTime(quotes.lastSuccessAt)}` : 'No confirmed quote run is available yet.';
    if (quotes.state === 'queued') status = 'Quote update queued for the existing updater.';
    if (quotes.state === 'running') status = 'The existing quote updater is running…';
    text('quote-status', status);
    text('quote-schedule', quotes.schedule || 'Existing updater schedule');
    if (quotes.providerFailures) messages.push(`${quotes.providerFailures} provider checks failed in the last confirmed run; previous prices for those feeds were retained.`);
    if (quotes.error) messages.push(quotes.error);
    byId('refresh-prices').disabled = busy || priceRequestPending || marketConnectionError || !quotes.canRequest;
    if (priceRequestPending) {
      text('price-action', 'Requesting a guarded price update…');
    } else if (marketConnectionError) {
      text('price-action', 'Could not check the price updater. Existing portfolio data remains available.');
    } else if (priceActionError) {
      text('price-action', priceActionError);
    } else if (!quotes.canRequest && !['queued', 'running'].includes(quotes.state) && quotes.nextAllowedAt && new Date(quotes.nextAllowedAt) > new Date()) {
      text('price-action', `Another price request is available after ${showTime(quotes.nextAllowedAt)}.`);
    } else if (['complete', 'partial'].includes(quotes.state)) {
      text('price-action', 'The updater finished. Saved quotes appear after Drive sync and the next portfolio reload.');
    } else if (['queued', 'running'].includes(quotes.state)) {
      text('price-action', 'Existing prices remain available while the updater checks, validates, and saves any changes.');
    } else {
      text('price-action', quotes.error || 'Request a quote check using the existing updater.');
    }
  }
  const coverage = report?.priceCoverage;
  if (coverage) {
    text('quote-coverage', `At ${dateLabel(coverage.valuationDate)}: ${coverage.securities} securities · ${coverage.missing} missing prices · ${coverage.olderThanSevenDays} older than 7 days${coverage.latestQuoteDate ? ` · latest quote ${dateLabel(coverage.latestQuoteDate)}` : ''}`);
  }
  if (fx) {
    text('fx-status', fx.running ? 'Checking ECB exchange rates…' : fx.lastSuccessAt ? `Last successful check: ${showTime(fx.lastSuccessAt)}` : 'Using saved ECB rates; first online check is pending.');
    text('fx-coverage', `${fx.rateDate ? `ECB data through ${dateLabel(fx.rateDate)}. ` : ''}Automatic checks every 6 hours.${report?.fxVersion && report.fxVersion !== fx.version ? ' Updated rates are waiting to be applied to this report.' : ''}`);
    if (fx.error) messages.push(fx.error);
    if (fx.rateDate && new Date(fx.rateDate + 'T12:00:00Z') < new Date(Date.now() - 8 * 86400000)) messages.push('Saved ECB rates are more than seven days old.');
  } else {
    text('fx-status', 'Using saved workspace rates');
    text('fx-coverage', 'Automatic FX refresh is not configured for this instance.');
  }
  byId('price-warning').hidden = messages.length === 0;
  text('price-warning', messages.join(' '));
}

async function checkMarket() {
  if (document.hidden) return;
  try {
    const response = await fetch('/api/price-updates', { cache: 'no-store' });
    if (!response.ok) throw new Error('Price status unavailable');
    const data = await response.json();
    setMarket(data);
    if (report && data.fx && report.fxVersion !== data.fx.version) load(report.period.openingDate, report.period.closingDate, true);
  } catch {
    marketConnectionError = true;
    renderMarket();
  }
}

function renderBenchmarkOptions() {
  const selector = byId('benchmark');
  const none = document.createElement('option');
  none.value = '';
  none.textContent = 'No benchmark';
  selector.replaceChildren(none);
  for (const benchmark of report.benchmarks || []) {
    const option = document.createElement('option');
    option.value = benchmark.uuid;
    option.textContent = `${benchmark.name} · ${benchmark.currency || 'index units'}`;
    selector.append(option);
  }
  if (selectedBenchmark && !(report.benchmarks || []).some(option => option.uuid === selectedBenchmark)) {
    const missing = document.createElement('option');
    missing.value = selectedBenchmark;
    missing.textContent = 'Selected benchmark unavailable';
    selector.append(missing);
  }
  selector.value = selectedBenchmark;
}

function renderBenchmarkDetails() {
  const benchmark = report.benchmark;
  const shown = mode === 'return' && benchmark && !benchmark.error;
  byId('benchmark-legend').hidden = !shown;
  if (shown) {
    text('legend-portfolio-return', percent(report.period.rate));
    text('legend-benchmark-name', benchmark.name);
    text('legend-benchmark-return', percent(benchmark.rate));
  }
  let description = 'Choose a security or index already in this portfolio. No new quotes are downloaded.';
  if (benchmark) {
    const basis = benchmark.reportingCurrency
      ? `in ${benchmark.reportingCurrency}${benchmark.sourceCurrency !== benchmark.reportingCurrency ? ` (converted from ${benchmark.sourceCurrency})` : ''}`
      : 'in native index units; currency unspecified, no FX adjustment';
    description = `Stored price-series return ${basis}. Distributions are not added separately; a total-return index may already include them.`;
    if (benchmark.closingQuoteDate) description += ` Last quote: ${dateLabel(benchmark.closingQuoteDate)}.`;
    if (mode === 'value') description = 'The benchmark overlay is shown in Return view. ' + description;
  }
  text('benchmark-description', description);
  const warning = benchmark ? [benchmark.error, ...(benchmark.warnings || [])].filter(Boolean).join(' ') : '';
  byId('benchmark-warning').hidden = !warning;
  text('benchmark-warning', warning);
}

function renderFreshness() {
  const refresh = report.refresh;
  const updated = new Date(report.loadedAt).toLocaleString();
  const checked = refresh?.lastCheckedAt ? new Date(refresh.lastCheckedAt).toLocaleTimeString() : null;
  text('refresh-info', `Portfolio updated ${updated}${refresh?.automatic ? ` · checked ${checked} · auto-check every ${refresh.intervalSeconds}s` : ''}${refresh?.pending ? ' · waiting for the file to finish syncing' : ''}`);
  const error = refreshConnectionError || refresh?.error;
  byId('refresh-error').hidden = !error;
  text('refresh-error', error || '');
  text('refresh-mode', refresh?.automatic ? 'Read-only · automatic portfolio refresh' : 'Read-only snapshot');
}

function svgElement(name, attributes, content) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', name);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  if (content !== undefined) element.textContent = content;
  return element;
}

function drawChart() {
  if (!report) return;
  renderBenchmarkDetails();
  const svg = byId('chart');
  svg.replaceChildren();
  svg.append(svgElement('title', { id: 'chart-title' }, mode === 'return' ? 'Portfolio time-weighted return' : 'Portfolio value'));
  svg.append(svgElement('desc', { id: 'chart-description' }, `Daily ${mode === 'return' ? 'cumulative return' : 'value'} from ${report.period.openingDate} to ${report.period.closingDate}. Use the date slider below for exact values.`));
  const points = report.series;
  const value = point => mode === 'return' ? point.rate : point.value;
  const values = points.map(value).filter(v => v !== null && Number.isFinite(v));
  const benchmark = mode === 'return' && report.benchmark && !report.benchmark.error ? report.benchmark : null;
  const benchmarkRates = new Map((benchmark?.series || []).map(point => [point.date, point.rate]));
  for (const rate of benchmarkRates.values()) if (Number.isFinite(rate)) values.push(rate);
  if (!values.length) return;
  let low = Math.min(...values), high = Math.max(...values);
  if (mode === 'return') { low = Math.min(0, low); high = Math.max(0, high); }
  const pad = (high - low || Math.abs(high) || (mode === 'return' ? 0.01 : 100)) * 0.12;
  low -= pad; high += pad;
  const mobile = window.matchMedia('(max-width:760px)').matches;
  const width = mobile ? 500 : 1000;
  const height = mobile ? 280 : 300;
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  const left = mobile ? 65 : 100, right = width - (mobile ? 20 : 30), top = 20, bottom = height - 45;
  const x = index => left + index / Math.max(1, points.length - 1) * (right - left);
  const y = v => bottom - (v - low) / (high - low) * (bottom - top);
  for (let i = 0; i < 5; i++) {
    const v = low + (high - low) * i / 4;
    svg.append(svgElement('line', { x1: left, x2: right, y1: y(v), y2: y(v), class: 'grid-line' }));
    const label = mode === 'return' ? new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 }).format(v) : new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(v / 100);
    svg.append(svgElement('text', { x: left - 16, y: y(v) + 4, 'text-anchor': 'end', class: 'axis-label' }, label));
  }
  if (low < 0 && high > 0) svg.append(svgElement('line', { x1: left, x2: right, y1: y(0), y2: y(0), class: 'zero-line' }));
  let path = '', previousValid = false;
  points.forEach((point, index) => {
    const v = value(point);
    if (v === null || !Number.isFinite(v)) { previousValid = false; return; }
    path += `${previousValid ? 'L' : 'M'}${x(index).toFixed(2)},${y(v).toFixed(2)} `;
    previousValid = true;
  });
  if (points.every(point => Number.isFinite(value(point)))) svg.append(svgElement('path', { d: `${path}L${right},${bottom} L${left},${bottom} Z`, class: 'chart-area' }));
  svg.append(svgElement('path', { d: path, class: 'chart-line' }));
  if (benchmark) {
    let comparisonPath = '', valid = false;
    points.forEach((point, index) => {
      const rate = benchmarkRates.get(point.date);
      if (!Number.isFinite(rate)) { valid = false; return; }
      comparisonPath += `${valid ? 'L' : 'M'}${x(index).toFixed(2)},${y(rate).toFixed(2)} `;
      valid = true;
    });
    svg.append(svgElement('path', { d: comparisonPath, class: 'benchmark-line' }));
    text('chart-title', 'Portfolio and benchmark returns');
    text('chart-description', `Portfolio time-weighted return and ${benchmark.name} stored price-series return over the same selected dates. Use the date slider below for exact values.`);
  }
  const ticks = mobile ? 3 : 5;
  for (let i = 0; i < ticks; i++) {
    const index = Math.round((points.length - 1) * i / (ticks - 1));
    const label = new Date(points[index].date + 'T12:00:00Z').toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
    svg.append(svgElement('text', { x: x(index), y: height - 16, 'text-anchor': 'middle', class: 'axis-label' }, label));
  }
  svg.append(svgElement('line', { id: 'cursor-line', y1: top, y2: bottom, class: 'cursor-line' }));
  svg.append(svgElement('circle', { id: 'cursor-dot', r: 5, class: 'cursor-dot' }));
  chartGeometry = { x, y, value, left, right, width, benchmarkRates };
  updateCursor();
  byId('mode-return').setAttribute('aria-pressed', String(mode === 'return'));
  byId('mode-value').setAttribute('aria-pressed', String(mode === 'value'));
  text('chart-explanation', mode === 'return'
    ? 'Time-weighted return (TTWROR) adjusts for deposits and withdrawals. Currency gains exclude net external flows.'
    : `Portfolio value in ${report.currency}, including cash. Deposits and withdrawals change this line; it is not an investment-return measure.`);
}

function updateCursor() {
  if (!chartGeometry || !report) return;
  const point = report.series[selected];
  const { x, y, value } = chartGeometry;
  const line = byId('cursor-line'), dot = byId('cursor-dot');
  if (!line || !dot) return;
  line.setAttribute('x1', x(selected)); line.setAttribute('x2', x(selected));
  dot.setAttribute('cx', x(selected));
  dot.setAttribute('cy', value(point) === null ? 0 : y(value(point)));
  dot.setAttribute('visibility', value(point) === null ? 'hidden' : 'visible');
  let detail = `${dateLabel(point.date)} · ${mode === 'return' ? percent(point.rate) : money(point.value)}`;
  const benchmarkRate = chartGeometry.benchmarkRates.get(point.date);
  if (Number.isFinite(benchmarkRate)) detail += ` · Series ${percent(benchmarkRate)}`;
  text('chart-detail', detail);
  byId('chart-position').setAttribute('aria-valuetext', detail);
  byId('chart-position').value = String(selected);
}

async function load(from, to, silent = false) {
  if (busy && silent) return;
  requestController?.abort();
  const controller = new AbortController();
  requestController = controller;
  busy = true;
  if (!silent) {
    byId('error').hidden = true;
    text('status', 'Calculating portfolio…');
    byId('dashboard').setAttribute('aria-busy', 'true');
    document.querySelectorAll('button, input[type=date], #benchmark').forEach(element => { element.disabled = true; });
  }
  try {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (selectedBenchmark) params.set('benchmark', selectedBenchmark);
    if (selectedInstrument) params.set('instrument', selectedInstrument);
    const response = await fetch('/api/dashboard' + (params.size ? '?' + params : ''), { cache: 'no-store', signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Could not load portfolio.');
    if (requestController !== controller) return;
    const unchanged = silent && report && report.revision === data.revision && report.loadedAt === data.loadedAt
      && report.period.openingDate === data.period.openingDate && report.period.closingDate === data.period.closingDate
      && report.benchmark?.uuid === data.benchmark?.uuid && report.fxVersion === data.fxVersion
      && report.instrument?.uuid === data.instrument?.uuid;

    refreshConnectionError = null;
    report = data;
    if (unchanged) { renderFreshness(); setMarket(data.market); }
    else render();
    if (!silent) text('status', '');
  } catch (error) {
    if (error.name === 'AbortError' || requestController !== controller) return;
    if (silent && report) {
      refreshConnectionError = 'Could not check for updates. Showing the last loaded portfolio; the connection will be retried.';
      renderFreshness();
    } else {
      text('error', error.message || 'Cannot reach the portfolio server.');
      byId('error').hidden = false;
      text('status', report ? 'The previously loaded figures remain displayed.' : 'Portfolio unavailable.');
    }
  } finally {
    if (requestController === controller) {
      busy = false;
      byId('dashboard').setAttribute('aria-busy', 'false');
      document.querySelectorAll('button, input[type=date], #benchmark').forEach(element => { element.disabled = false; });
      renderMarket();
    }
  }
}

byId('dates').addEventListener('submit', event => {
  event.preventDefault(); activePreset = null;
  load(byId('from').value, byId('to').value);
});
byId('refresh').addEventListener('click', () => load(report?.period.openingDate, report?.period.closingDate));
byId('close-instrument').addEventListener('click', () => { selectedInstrument = ''; renderInstrument(null); });
byId('benchmark').addEventListener('change', event => {
  selectedBenchmark = event.target.value;
  if (selectedBenchmark) mode = 'return';
  load(report?.period.openingDate, report?.period.closingDate);
});
byId('refresh-prices').addEventListener('click', async () => {
  if (priceRequestPending) return;
  priceRequestPending = true;
  priceActionError = null;
  byId('refresh-prices').disabled = true;
  text('price-action', 'Requesting a guarded price update…');
  try {
    const response = await fetch('/api/price-updates', { method: 'POST', headers: { 'X-Portfolio-Action': 'refresh-prices' } });
    const data = await response.json();
    if (data.quotes) setMarket(data);
    else if (!response.ok) throw new Error(data.message || 'Price update unavailable');
  } catch (error) {
    priceActionError = error.message || 'The update could not be requested. Existing prices remain available.';
  } finally {
    priceRequestPending = false;
    renderMarket();
  }
});
for (const button of document.querySelectorAll('[data-period]')) {
  button.addEventListener('click', () => {
    if (!report) return;
    activePreset = button.dataset.period;
    const end = new Date(report.asOf + 'T12:00:00Z');
    let start;
    if (activePreset === 'YTD') start = new Date(Date.UTC(end.getUTCFullYear() - 1, 11, 31, 12));
    else {
      start = new Date(end);
      const months = activePreset === '1Y' ? 12 : Number(activePreset.slice(0, -1));
      const day = start.getUTCDate();
      start.setUTCDate(1); start.setUTCMonth(start.getUTCMonth() - months);
      const lastDay = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate();
      start.setUTCDate(Math.min(day, lastDay));
    }
    load(iso(start), iso(end));
  });
}
for (const measure of ['return', 'value']) byId('mode-' + measure).addEventListener('click', () => { mode = measure; drawChart(); });
byId('chart-position').addEventListener('input', event => { selected = Number(event.target.value); updateCursor(); });
byId('chart').addEventListener('pointermove', event => {
  if (!report || !chartGeometry) return;
  const bounds = event.currentTarget.getBoundingClientRect();
  const x = (event.clientX - bounds.left) / bounds.width * chartGeometry.width;
  const ratio = Math.max(0, Math.min(1, (x - chartGeometry.left) / (chartGeometry.right - chartGeometry.left)));
  selected = Math.round(ratio * (report.series.length - 1)); updateCursor();
});
window.matchMedia('(max-width:760px)').addEventListener('change', drawChart);
function checkForUpdates() {
  if (report && !document.hidden) load(report.period.openingDate, report.period.closingDate, true);
}
window.setInterval(checkForUpdates, 30000);
window.setInterval(checkMarket, 10000);
document.addEventListener('visibilitychange', checkForUpdates);
document.addEventListener('visibilitychange', checkMarket);
load();
