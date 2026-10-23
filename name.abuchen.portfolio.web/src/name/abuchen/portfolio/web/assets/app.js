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
    cell(row, holding.name);
    cell(row, money(holding.value), true);
    cell(row, weight(holding.weight), true);
    cell(row, holding.quoteDate ? dateLabel(holding.quoteDate) : '—');
    holdings.append(row);
  }
  if (!report.holdings.length) emptyRow(holdings, 4, 'No holdings or cash accounts at this valuation date.');
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
  selected = report.series.length - 1;
  byId('chart-position').max = String(selected);
  byId('chart-position').value = String(selected);
  drawChart();
  byId('dashboard').hidden = false;
}

function svgElement(name, attributes, content) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', name);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  if (content !== undefined) element.textContent = content;
  return element;
}

function drawChart() {
  if (!report) return;
  const svg = byId('chart');
  svg.replaceChildren();
  svg.append(svgElement('title', { id: 'chart-title' }, mode === 'return' ? 'Portfolio time-weighted return' : 'Portfolio value'));
  svg.append(svgElement('desc', { id: 'chart-description' }, `Daily ${mode === 'return' ? 'cumulative return' : 'value'} from ${report.period.openingDate} to ${report.period.closingDate}. Use the date slider below for exact values.`));
  const points = report.series;
  const value = point => mode === 'return' ? point.rate : point.value;
  const values = points.map(value).filter(v => v !== null && Number.isFinite(v));
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
  if (values.length === points.length) svg.append(svgElement('path', { d: `${path}L${right},${bottom} L${left},${bottom} Z`, class: 'chart-area' }));
  svg.append(svgElement('path', { d: path, class: 'chart-line' }));
  const ticks = mobile ? 3 : 5;
  for (let i = 0; i < ticks; i++) {
    const index = Math.round((points.length - 1) * i / (ticks - 1));
    const label = new Date(points[index].date + 'T12:00:00Z').toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
    svg.append(svgElement('text', { x: x(index), y: height - 16, 'text-anchor': 'middle', class: 'axis-label' }, label));
  }
  svg.append(svgElement('line', { id: 'cursor-line', y1: top, y2: bottom, class: 'cursor-line' }));
  svg.append(svgElement('circle', { id: 'cursor-dot', r: 5, class: 'cursor-dot' }));
  chartGeometry = { x, y, value, left, right, width };
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
  const detail = `${dateLabel(point.date)} · ${mode === 'return' ? percent(point.rate) : money(point.value)}`;
  text('chart-detail', detail);
  byId('chart-position').setAttribute('aria-valuetext', detail);
  byId('chart-position').value = String(selected);
}

async function load(from, to) {
  if (busy) return;
  busy = true;
  byId('error').hidden = true;
  text('status', 'Calculating portfolio…');
  byId('dashboard').setAttribute('aria-busy', 'true');
  document.querySelectorAll('button, input[type=date]').forEach(element => { element.disabled = true; });
  try {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const response = await fetch('/api/dashboard' + (params.size ? '?' + params : ''), { cache: 'no-store' });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Could not load portfolio.');
    report = data;
    render();
    text('status', '');
  } catch (error) {
    text('error', error.message || 'Cannot reach the portfolio server.');
    byId('error').hidden = false;
    text('status', report ? 'The previously loaded figures remain displayed.' : 'Portfolio unavailable.');
  } finally {
    busy = false;
    byId('dashboard').setAttribute('aria-busy', 'false');
    document.querySelectorAll('button, input[type=date]').forEach(element => { element.disabled = false; });
  }
}

byId('dates').addEventListener('submit', event => {
  event.preventDefault(); activePreset = null;
  load(byId('from').value, byId('to').value);
});
byId('refresh').addEventListener('click', () => load(report?.period.openingDate, report?.period.closingDate));
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
load();
