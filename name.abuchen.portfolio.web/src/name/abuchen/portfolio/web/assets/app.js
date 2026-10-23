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
let instrumentSelected = 0;
let hasChartSelection = false;
let instrumentChart;
let activePreset = '1Y';
let busy = false;
let requestController;
let refreshConnectionError;
let selectedBenchmark = '';
let selectedInstrument = '';
let rankingBasis = 'rate';
let securityActivity;
let activityKey = '';
const activityPages = { dividends: 0, transactions: 0 };
const activityPageSize = 25;
let currentRoute = 'overview';
let currentRouteKey = '';
let routeGeneration = 0;
let restoringScroll = false;
let selectedLiveQuote = '';
let liveQuoteChart;
let liveRowsKey = '';
let liveMode;
let liveFrozenHistory;
let liveHoveredTime;
let livePollBusy = false;
let liveConnectionError = false;
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

const routeMeta = {
  overview: ['THE BIG PICTURE', 'Portfolio overview'],
  performance: ['GROWTH OVER TIME', 'Portfolio performance'],
  holdings: ['WHERE YOU’RE INVESTED', 'Holdings & cash'],
  dividends: ['INCOME FROM YOUR INVESTMENTS', 'Received dividends'],
  performers: ['SECURITY PERFORMANCE', 'Top gainers & decliners'],
  'live-quotes': ['QUOTE STREAM PILOT', 'Live quotes'],
  instrument: ['INSTRUMENT DETAIL', 'Instrument detail']
};

function routeUrl() {
  const params = new URLSearchParams();
  if (byId('from').value) params.set('from', byId('from').value);
  if (byId('to').value) params.set('to', byId('to').value);
  if (selectedBenchmark) params.set('benchmark', selectedBenchmark);
  if (mode !== 'return') params.set('mode', mode);
  if (activePreset) params.set('preset', activePreset);
  if (currentRoute === 'live-quotes' && selectedLiveQuote) params.set('quote', selectedLiveQuote);
  if (rankingBasis !== 'rate') params.set('basis', rankingBasis);
  const route = currentRoute === 'instrument' ? `instrument/${encodeURIComponent(selectedInstrument)}` : currentRoute;
  return `#/${route}${params.size ? `?${params}` : ''}`;
}

function saveEntryState() {
  if (restoringScroll || !history.state?.appEntry) return;
  history.replaceState({ ...history.state, routeKey: location.hash, scrollY: window.scrollY, selected, instrumentSelected }, '', location.href);
}

function syncRouteUrl() {
  const url = routeUrl();
  history.replaceState({ ...history.state, appEntry: true, routeKey: url,
    scrollY: restoringScroll ? (history.state?.scrollY || 0) : window.scrollY, selected, instrumentSelected }, '', url);
  const filters = url.includes('?') ? url.slice(url.indexOf('?')) : '';
  for (const anchor of document.querySelectorAll('a[href^="#/"]')) anchor.setAttribute('href', anchor.hash.split('?')[0] + filters);
}

function showRoute() {
  const quotePage = currentRoute === 'live-quotes';
  document.body.classList.toggle('quote-page', quotePage);
  text('portfolio-label', quotePage ? 'Independent, read-only quote watchlist' : report?.label || 'Loading portfolio…');
  text('refresh', quotePage ? '↻ Refresh quotes' : '↻ Refresh dashboard');
  document.querySelector('.snapshot-badge').textContent = quotePage ? 'QUOTE PILOT' : 'LOCAL SNAPSHOT';
  const meta = routeMeta[currentRoute] || routeMeta.overview;
  text('page-eyebrow', meta[0]); text('page-title', currentRoute === 'instrument' && report?.instrument?.name ? report.instrument.name : meta[1]);
  for (const section of document.querySelectorAll('[data-page]')) {
    section.hidden = section.dataset.page !== currentRoute || section.id === 'warnings' && !report?.warnings?.length;
  }
  byId('date-controls').hidden = ['overview', 'live-quotes'].includes(currentRoute);
  for (const link of document.querySelectorAll('nav [data-route]')) {
    const active = link.dataset.route === currentRoute || currentRoute === 'instrument' && link.dataset.route === 'holdings';
    link.classList.toggle('nav-active', active);
    if (active) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
}

function parseRoute() {
  const match = location.hash.match(/^#\/(overview|performance|holdings|dividends|performers|live-quotes|instrument\/([^?]+))(?:\?(.*))?$/);
  if (!match) return { page: 'overview', instrument: '', params: new URLSearchParams() };
  const page = match[1].startsWith('instrument/') ? 'instrument' : match[1];
  let instrument = '';
  try { instrument = page === 'instrument' ? decodeURIComponent(match[2]) : ''; } catch { instrument = ''; }
  return { page, instrument, params: new URLSearchParams(match[3] || '') };
}

async function enterRoute() {
  const key = `${location.hash}|${history.state?.entryId || ''}`;
  if (key === currentRouteKey) return;
  currentRouteKey = key;
  const generation = ++routeGeneration;
  const saved = history.state || {};
  const restore = saved.routeKey === location.hash ? Number(saved.scrollY || 0) : 0;
  clearTimeout(scrollTimer);
  restoringScroll = true;
  const route = parseRoute();
  currentRoute = route.page; selectedInstrument = route.instrument;
  selectedLiveQuote = route.params.get('quote') || selectedLiveQuote;
  selectedBenchmark = route.params.get('benchmark') || '';
  mode = route.params.get('mode') === 'value' ? 'value' : 'return';
  activePreset = route.params.get('preset') || null;
  rankingBasis = route.params.get('basis') === 'gain' ? 'gain' : 'rate';
  byId('ranking-basis').value = rankingBasis;
  const from = route.params.get('from'), to = route.params.get('to');
  if (from) byId('from').value = from;
  if (to) byId('to').value = to;
  if (report && (!from || !to)) {
    byId('from').value = from || report.period.openingDate;
    byId('to').value = to || report.period.closingDate;
  }
  if (!history.state?.appEntry) history.replaceState({ appEntry: true, canAppBack: false, routeKey: location.hash }, '', location.href);
  if (Number.isInteger(saved.selected)) { selected = saved.selected; hasChartSelection = true; }
  instrumentSelected = Number.isInteger(saved.instrumentSelected) ? saved.instrumentSelected : instrumentSelected;
  showRoute();
  const data = await load(from || byId('from').value, to || byId('to').value);
  if (generation !== routeGeneration) return;
  showRoute();
  syncRouteUrl();
  currentRouteKey = `${location.hash}|${history.state?.entryId || ''}`;
  selected = Number.isInteger(saved.selected) ? saved.selected : selected;
  if (report) { updateCursor(); }
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (generation !== routeGeneration) return;
    restoringScroll = true; window.scrollTo(0, restore);
    setTimeout(() => {
      if (generation !== routeGeneration) return;
      restoringScroll = false;
      saveEntryState();
    }, 220);
  }));
  return data;
}

function navigate(href) {
  const url = new URL(href, location.href);
  let hash = url.hash;
  if (!hash.includes('?') && location.hash.includes('?')) hash += location.hash.slice(location.hash.indexOf('?'));
  if (!hash.startsWith('#/')) return;
  saveEntryState();
  const newState = { appEntry: true, canAppBack: true, routeKey: hash, scrollY: 0, selected, instrumentSelected,
    entryId: `${Date.now()}-${Math.random()}` };
  history.pushState(newState, '', hash);
  currentRouteKey = '';
  enterRoute();
}

document.addEventListener('click', event => {
  if (event.target.closest('a.skip[href="#main"]')) {
    event.preventDefault();
    byId('main').tabIndex = -1;
    byId('main').focus();
    return;
  }
  const anchor = event.target.closest('a[href^="#/"]');
  if (!anchor || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  if (anchor.id === 'instrument-back' && history.state?.canAppBack) { saveEntryState(); history.back(); return; }
  navigate(anchor.href);
});
window.addEventListener('popstate', enterRoute);
window.addEventListener('hashchange', enterRoute);
window.history.scrollRestoration = 'manual';
let scrollTimer;
window.addEventListener('scroll', () => {
  if (restoringScroll) return;
  clearTimeout(scrollTimer); scrollTimer = setTimeout(saveEntryState, 120);
}, { passive: true });

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
      const link = document.createElement('a'); link.className = 'instrument-link';
      const filters = location.hash.includes('?') ? location.hash.slice(location.hash.indexOf('?')) : '';
      link.href = `#/instrument/${encodeURIComponent(holding.uuid)}${filters}`;
      link.textContent = holding.name;
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
  renderRankings(report.rankings);
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
  drawChart();
  byId('dashboard').hidden = false;
  showRoute();
}

const liveTime = value => new Date(value).toLocaleTimeString(undefined, { hour12: false });
const livePriceFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 });

function renderLivePrice(cell, value, currency, field) {
  const previous = cell.dataset.value ? Number(cell.dataset.value) : null;
  const valid = Number.isFinite(value) && value > 0;
  const changed = valid && previous !== null && value !== previous;
  if (!cell.firstElementChild) {
    const arrow = document.createElement('span');
    arrow.className = 'quote-direction'; arrow.setAttribute('aria-hidden', 'true');
    cell.replaceChildren(arrow, document.createElement('span'));
  }
  if (changed) {
    cell.dataset.direction = value > previous ? 'up' : 'down';
    cell.dataset.changedAt = String(performance.now());
    cell.classList.remove('quote-up', 'quote-down');
    void cell.offsetWidth; // Restart the pulse for every new tick, including successive moves in one direction.
    cell.classList.add(`quote-${cell.dataset.direction}`);
  } else if (!valid || performance.now() - Number(cell.dataset.changedAt || 0) > 1300) {
    cell.dataset.direction = '';
    cell.classList.remove('quote-up', 'quote-down');
  }
  cell.dataset.value = valid ? String(value) : '';
  const direction = cell.dataset.direction;
  cell.firstElementChild.textContent = direction === 'up' ? '↑' : direction === 'down' ? '↓' : String.fromCharCode(160);
  const formatted = valid ? livePriceFormat.format(value) : '—';
  cell.lastElementChild.textContent = formatted;
  cell.classList.add('numeric', 'live-price');
  cell.setAttribute('aria-label', `${field}: ${formatted} ${currency}${direction ? `, ${direction} since previous update` : ''}`);
}


function renderLiveQuotes() {
  const feed = market?.liveQuotes;
  if (!feed) return;
  if (liveMode !== feed.mode) { liveMode = feed.mode; liveRowsKey = ''; liveFrozenHistory = null; liveHoveredTime = null; }
  const simulated = feed.mode === 'demo';
  text('live-source', simulated ? 'SIMULATED · NO IBKR CONNECTION' : 'IBKR QUOTE WATCHLIST');
  text('live-description', simulated
    ? 'These are generated sample prices, not IBKR market data. They are separate from your portfolio valuations.'
    : 'Quote-only watchlist. Prices are separate from saved portfolio valuations. Gateway login and market-data permissions determine availability.');
  text('live-status', !feed.configured ? 'Quote collector not configured' : simulated
    ? feed.available ? 'Simulation running' : 'Simulation paused' : feed.connected ? 'Connected to IBKR Gateway' : 'Waiting for IBKR Gateway');
  text('live-heartbeat', feed.generatedAt ? `Collector updated ${liveTime(feed.generatedAt)} · this page checks once per second while visible` : 'Waiting for the collector’s first snapshot.');
  const error = liveConnectionError ? 'Connection to the web server is unavailable. Last received quotes are shown.' : feed.error;
  byId('live-error').hidden = !error;
  text('live-error', error || '');
  const entries = feed.quotes || [];
  if (!entries.some(entry => entry.quote.key === selectedLiveQuote)) {
    selectedLiveQuote = entries[0]?.quote.key || '';
    liveFrozenHistory = null;
  }
  const key = JSON.stringify(entries.map(entry => entry.quote.key));
  const body = byId('live-quotes-body');
  if (key !== liveRowsKey) {
    liveRowsKey = key;
    body.replaceChildren();
    for (const { quote } of entries) {
      const row = document.createElement('tr');
      row.dataset.quote = quote.key;
      const name = document.createElement('td');
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'instrument-link';
      button.addEventListener('click', () => {
        selectedLiveQuote = quote.key; liveFrozenHistory = null; liveHoveredTime = null;
        syncRouteUrl(); renderLiveQuotes();
      });
      name.append(button); row.append(name);
      for (let i = 0; i < 6; i++) cell(row, '');
      body.append(row);
    }
    if (!entries.length) emptyRow(body, 7, 'No quotes received yet.');
  }
  const typeNames = { real_time: 'Real-time', delayed: 'Delayed', frozen: 'Frozen', delayed_frozen: 'Delayed frozen', unknown: 'Awaiting data type' };
  const states = { waiting: 'Waiting for quotes', unavailable: 'Unavailable', unresolved: 'Contract not resolved', not_subscribed: 'Not subscribed' };
  entries.forEach(({ quote, stale }, index) => {
    const cells = body.rows[index].cells;
    cells[0].firstChild.textContent = quote.name;
    cells[0].firstChild.setAttribute('aria-pressed', String(quote.key === selectedLiveQuote));
    cells[1].textContent = quote.currency;
    ['last', 'bid', 'ask'].forEach((field, i) => renderLivePrice(cells[i + 2], quote[field], quote.currency, field));
    cells[5].textContent = quote.receivedAt ? liveTime(quote.receivedAt) : '—';
    cells[5].title = quote.receivedAt || '';
    cells[6].textContent = `${simulated ? 'Simulated ' : ''}${typeNames[quote.marketDataType] || 'Unknown'}${quote.status !== 'active' ? ` · ${states[quote.status] || 'Unavailable'}` : stale ? ' · stale' : ''}`;
  });
  const selectedQuote = entries.find(entry => entry.quote.key === selectedLiveQuote)?.quote;
  text('live-selected-name', selectedQuote ? `${selectedQuote.name} · ${selectedQuote.currency}` : 'Observed last price');
  const history = liveFrozenHistory || selectedQuote?.history || [];
  let selectedIndex = history.length - 1;
  if (liveHoveredTime) {
    const found = history.findIndex(point => point.time === liveHoveredTime);
    if (found >= 0) selectedIndex = found;
  }
  const format = value => new Intl.NumberFormat(undefined, { style: 'currency', currency: selectedQuote?.currency || 'USD', maximumFractionDigits: 6 }).format(value);
  liveQuoteChart = new TimeSeriesChart(byId('live-chart'));
  liveQuoteChart.draw({ points: history.map(point => ({ date: point.time, value: point.value })), format,
    formatTime: time => liveTime(time), formatDate: liveTime, selected: selectedIndex,
    output: byId('live-chart-detail'), title: simulated ? 'Simulated observed quote prices' : 'Observed IBKR last prices',
    empty: 'No last-trade price history available for this instrument.',
    onSelect: index => {
      if (!liveFrozenHistory) liveFrozenHistory = history.slice();
      liveHoveredTime = liveFrozenHistory[index]?.time;
      byId('live-follow').disabled = false;
      text('live-chart-mode', 'Chart paused for inspection; the watchlist continues updating.');
    } });
  byId('live-follow').disabled = !liveFrozenHistory || history.length === 0;
  text('live-chart-mode', liveFrozenHistory ? 'Chart paused for inspection; the watchlist continues updating.' : 'Following the latest observed price.');
}

async function checkLiveQuotes() {
  if (document.hidden || currentRoute !== 'live-quotes' || livePollBusy) return;
  livePollBusy = true;
  try {
    const response = await fetch('/api/price-updates', { cache: 'no-store' });
    if (!response.ok) throw new Error('Quote feed unavailable');
    liveConnectionError = false;
    setMarket(await response.json());
  } catch {
    liveConnectionError = true;
    renderLiveQuotes();
  } finally { livePollBusy = false; }
}

byId('live-follow').addEventListener('click', () => { liveFrozenHistory = null; liveHoveredTime = null; renderLiveQuotes(); });

function renderRankings(rankings) {
  const key = rankingBasis;
  const rows = rankings?.securities || [];
  const valid = rows.filter(row => Number.isFinite(row[key]));
  const compare = direction => (a, b) => direction * (a[key] - b[key])
    || (a.name || '').localeCompare(b.name || '') || a.uuid.localeCompare(b.uuid);
  const fill = (id, items, empty) => {
    const body = byId(id);
    body.replaceChildren();
    if (!items.length) { emptyRow(body, 3, empty); return; }
    for (const item of items) {
      const row = document.createElement('tr');
      const name = document.createElement('td');
      const link = document.createElement('a');
      const filters = location.hash.includes('?') ? location.hash.slice(location.hash.indexOf('?')) : '';
      link.className = 'instrument-link';
      link.href = `#/instrument/${encodeURIComponent(item.uuid)}${filters}`;
      link.textContent = item.name || 'Unnamed security';
      name.append(link);
      row.append(name);
      cell(row, percent(item.rate), true);
      cell(row, item.gain == null ? 'Unavailable' : money(item.gain, true), true);
      body.append(row);
    }
  };
  fill('gainers-body', valid.filter(row => row[key] > 0).sort(compare(-1)).slice(0, 5),
    `No positive ${key === 'rate' ? 'returns' : 'gains'} in this period.`);
  fill('decliners-body', valid.filter(row => row[key] < 0).sort(compare(1)).slice(0, 5),
    `No negative ${key === 'rate' ? 'returns' : 'gains'} in this period.`);
  const neutral = valid.filter(row => row[key] === 0).length;
  text('ranking-summary', `${dateLabel(report.period.openingDate)} → ${dateLabel(report.period.closingDate)} · ${valid.length} securities with results · ${rows.length - valid.length} unavailable for this measure · ${neutral} unchanged`);
  for (const heading of document.querySelectorAll('[data-ranking-currency]')) heading.textContent = `Gain / loss (${report.currency})`;
}

function originalMoney(value, currency) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(value / 100);
}

function renderSecurityActivity(activity) {
  const key = `${selectedInstrument}:${report.period.openingDate}:${report.period.closingDate}`;
  if (key !== activityKey) {
    activityKey = key;
    activityPages.dividends = activityPages.transactions = 0;
  }
  securityActivity = activity || { dividends: [], transactions: [] };
  renderActivityTable('dividends');
  renderActivityTable('transactions');
}

function renderActivityTable(kind) {
  const items = securityActivity?.[kind] || [];
  const pageCount = Math.max(1, Math.ceil(items.length / activityPageSize));
  activityPages[kind] = Math.max(0, Math.min(pageCount - 1, activityPages[kind]));
  const body = byId(`security-${kind}-body`);
  body.replaceChildren();
  const start = activityPages[kind] * activityPageSize;
  for (const entry of items.slice(start, start + activityPageSize)) {
    const row = document.createElement('tr');
    const formatted = amount => originalMoney(amount, entry.currency);
    if (kind === 'dividends') {
      cell(row, dateLabel(entry.date), false, entry.account);
      for (const field of ['gross', 'taxes', 'fees', 'net']) cell(row, formatted(entry[field]), true);
      cell(row, entry.reportingNet == null ? 'Unavailable' : money(entry.reportingNet), true);
    } else {
      cell(row, dateLabel(entry.date.slice(0, 10)), false, entry.date.slice(11, 16));
      cell(row, entry.type, false, entry.owner);
      cell(row, entry.shares == null ? '—' : new Intl.NumberFormat(undefined, { maximumFractionDigits: 8 }).format(entry.shares / 1e8), true);
      cell(row, formatted(entry.amount), true);
      cell(row, formatted(entry.taxes), true);
      cell(row, formatted(entry.fees), true);
      cell(row, entry.reportingAmount == null ? 'Unavailable' : money(entry.reportingAmount), true);
    }
    body.append(row);
  }
  if (!items.length) emptyRow(body, kind === 'dividends' ? 6 : 7,
    kind === 'dividends' ? 'No dividends were paid in the selected period.' : 'No transactions in the selected period.');
  text(`security-${kind}-count`, `${items.length} ${kind === 'dividends' ? 'payments' : 'transactions'}`);
  text(`security-${kind}-page`, items.length ? `${start + 1}–${Math.min(start + activityPageSize, items.length)} of ${items.length}` : '0 results');
  byId(`security-${kind}-previous`).disabled = activityPages[kind] === 0;
  byId(`security-${kind}-next`).disabled = activityPages[kind] + 1 >= pageCount;
  for (const label of document.querySelectorAll('[data-activity-currency]')) label.textContent = `In ${report.currency}`;
}

function renderInstrument(instrument) {
  const panel = byId('instrument-panel');
  panel.hidden = !selectedInstrument;
  if (!selectedInstrument) return;
  renderSecurityActivity(instrument?.activity);
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
  instrumentChart = new TimeSeriesChart(byId('instrument-chart'));
  const format = value => new Intl.NumberFormat(undefined, { ...(instrument.currency ? { style: 'currency', currency: instrument.currency } : {}), maximumFractionDigits: 6 }).format(value);
  instrumentChart.draw({ points: (instrument.prices || []).map(p => ({ date: p.date, value: p.price / instrument.quoteScale })),
    format, output: byId('instrument-chart-detail'), selected: instrumentSelected, empty: 'No stored quotes in the selected period.',
    title: `Stored price history in ${instrument.currency || 'instrument units'}`,
    onSelect: index => { instrumentSelected = index; saveEntryState(); } });
}

function setMarket(data) {
  if (data && (!market || data.observedAt >= market.observedAt)) {
    market = data;
    marketConnectionError = false;
  }
  renderMarket();
  if (currentRoute === 'live-quotes') renderLiveQuotes();
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
    if (currentRoute !== 'live-quotes' && report && data.fx && report.fxVersion !== data.fx.version) load(report.period.openingDate, report.period.closingDate, true);
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

function seriesTime(value) {
  return Date.parse(value.length === 10 ? value + 'T12:00:00Z' : value);
}

class TimeSeriesChart {
  constructor(svg) {
    this.svg = svg;
    if (svg.chartInstance) return svg.chartInstance;
    svg.chartInstance = this;
    svg.tabIndex = 0;
    svg.addEventListener('pointermove', event => this.pointerSelect(event));
    svg.addEventListener('pointerdown', event => { if (event.pointerType !== 'mouse') this.pointerSelect(event); });
    svg.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || !this.points?.length) return;
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? this.points.length - 1 : this.index + (event.key === 'ArrowLeft' ? -1 : 1);
      this.select(Math.max(0, Math.min(this.points.length - 1, next)));
    });
    return this;
  }

  draw(config) {
    this.config = config; this.points = config.points || [];
    this.index = Math.max(0, Math.min(this.points.length - 1, config.selected || 0));
    const svg = this.svg; svg.replaceChildren();
    svg.setAttribute('aria-labelledby', `${svg.id}-title ${svg.id}-description`);
    svg.append(svgElement('title', { id: `${svg.id}-title` }, config.title || 'Time series'));
    svg.append(svgElement('desc', { id: `${svg.id}-description` }, config.description || 'Move the pointer over the chart to inspect exact values. Use arrow keys to move between dates.'));
    const mobile = window.matchMedia('(max-width:760px)').matches;
    this.width = config.width || (mobile ? 500 : 800); this.height = config.height || (mobile ? 270 : 300);
    svg.setAttribute('viewBox', `0 0 ${this.width} ${this.height}`);
    this.left = mobile ? 70 : 85; this.right = this.width - (mobile ? 18 : 25); this.top = 22; this.bottom = this.height - 40;
    const times = this.points.map(p => seriesTime(p.date));
    this.first = Math.min(...times); this.last = Math.max(...times);
    this.x = time => this.left + (time - this.first) / Math.max(1, this.last - this.first) * (this.right - this.left);
    const series = config.series || [];
    const values = [...this.points.map(p => p.value), ...series.flatMap(s => s.points.map(p => p.value))]
      .filter(v => Number.isFinite(v));
    if (!this.points.length || !values.length) {
      svg.append(svgElement('text', { x: this.width / 2, y: this.height / 2, 'text-anchor': 'middle', class: 'axis-label' }, config.empty || 'No chart data available.'));
      if (config.output) config.output.textContent = config.empty || 'No chart data available.';
      return;
    }
    let low = Math.min(...values), high = Math.max(...values);
    if (config.includeZero) { low = Math.min(low, 0); high = Math.max(high, 0); }
    const pad = (high - low || Math.abs(high) || 1) * 0.12; low -= pad; high += pad;
    this.y = value => this.bottom - (value - low) / (high - low) * (this.bottom - this.top);
    for (let i = 0; i < 5; i++) {
      const value = low + (high - low) * i / 4;
      svg.append(svgElement('line', { x1: this.left, x2: this.right, y1: this.y(value), y2: this.y(value), class: 'grid-line' }));
      svg.append(svgElement('text', { x: this.left - 8, y: this.y(value) + 4, 'text-anchor': 'end', class: 'axis-label' }, config.format(value)));
    }
    if (low < 0 && high > 0) svg.append(svgElement('line', { x1: this.left, x2: this.right, y1: this.y(0), y2: this.y(0), class: 'zero-line' }));
    this.drawPath(this.points, 'chart-line');
    for (const item of series) this.drawPath(item.points, item.className || 'benchmark-line');
    const ticks = mobile ? 3 : 5;
    for (let i = 0; i < ticks; i++) {
      const time = this.first + (this.last - this.first) * i / (ticks - 1);
      const date = new Date(time).toISOString().slice(0, 10);
      const label = config.formatTime ? config.formatTime(time) : new Date(date + 'T12:00:00Z').toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
      svg.append(svgElement('text', { x: this.x(time), y: this.height - 12, 'text-anchor': 'middle', class: 'axis-label' }, label));
    }
    if (config.area && this.points.every(point => Number.isFinite(point.value))) {
      let area = this.points.map((point, index) => `${index ? 'L' : 'M'}${this.x(times[index]).toFixed(2)},${this.y(point.value).toFixed(2)} `).join('');
      area += `L${this.x(this.last)},${this.bottom} L${this.x(this.first)},${this.bottom} Z`;
      svg.append(svgElement('path', { d: area, class: 'chart-area' }));
    }
    this.cursor = svgElement('line', { y1: this.top, y2: this.bottom, class: 'cursor-line' }); svg.append(this.cursor);
    this.dot = svgElement('circle', { r: 5, class: 'cursor-dot' }); svg.append(this.dot);
    if (this.points.length === 1 && Number.isFinite(this.points[0].value)) svg.append(svgElement('circle', { cx: this.x(times[0]), cy: this.y(this.points[0].value), r: 3, class: 'cursor-dot' }));
    this.timeMap = times; this.drawSelection();
  }

  drawPath(points, className) {
    let path = '', previous = false;
    for (const point of points) {
      const value = point.value, time = seriesTime(point.date);
      if (!Number.isFinite(value)) { previous = false; continue; }
      path += `${previous ? 'L' : 'M'}${this.x(time).toFixed(2)},${this.y(value).toFixed(2)} `; previous = true;
    }
    if (path) this.svg.append(svgElement('path', { d: path, class: className }));
  }

  pointerSelect(event) {
    if (!this.points?.length || !this.timeMap?.length) return;
    const bounds = this.svg.getBoundingClientRect();
    const viewX = (event.clientX - bounds.left) / bounds.width * this.width;
    const time = this.first + Math.max(0, Math.min(1, (viewX - this.left) / (this.right - this.left))) * (this.last - this.first);
    let nearest = 0;
    for (let i = 1; i < this.timeMap.length; i++) if (Math.abs(this.timeMap[i] - time) < Math.abs(this.timeMap[nearest] - time)) nearest = i;
    this.select(nearest);
  }

  select(index) { this.index = index; this.drawSelection(); this.config.onSelect?.(index); }

  drawSelection() {
    if (!this.points?.length || !this.timeMap?.length || !this.cursor) return;
    const point = this.points[this.index], time = this.timeMap[this.index], x = this.x(time);
    this.cursor.setAttribute('x1', x); this.cursor.setAttribute('x2', x);
    if (Number.isFinite(point.value)) {
      this.dot.setAttribute('cx', x); this.dot.setAttribute('cy', this.y(point.value)); this.dot.setAttribute('visibility', 'visible');
    } else this.dot.setAttribute('visibility', 'hidden');
    const detail = `${(this.config.formatDate || dateLabel)(point.date)} · ${Number.isFinite(point.value) ? this.config.format(point.value) : 'Unavailable'}`;
    const seriesDetails = (this.config.series || []).map(series => {
      const sample = series.points.find(candidate => candidate.date === point.date);
      return sample && Number.isFinite(sample.value) ? `${series.name}: ${this.config.format(sample.value)}` : null;
    }).filter(Boolean);
    const exact = [detail, ...seriesDetails].join(' · ');
    if (this.config.output) this.config.output.textContent = exact;
    if (this.config.slider) { this.config.slider.value = String(this.index); this.config.slider.setAttribute('aria-valuetext', exact); }
  }
}

let portfolioChart;
function drawChart() {
  if (!report) return;
  renderBenchmarkDetails();
  if (!hasChartSelection) { selected = report.series.length - 1; hasChartSelection = true; }
  selected = Math.max(0, Math.min(report.series.length - 1, selected));
  const points = report.series.map(point => ({ date: point.date, value: mode === 'return' ? point.rate : point.value }));
  const series = mode === 'return' && report.benchmark && !report.benchmark.error ? [{
    name: report.benchmark.name, className: 'benchmark-line', points: report.benchmark.series.map(point => ({ date: point.date, value: point.rate }))
  }] : [];
  const formatter = mode === 'return' ? percent : value => money(value);
  portfolioChart = new TimeSeriesChart(byId('chart'));
  portfolioChart.draw({ points, series, format: formatter, includeZero: mode === 'return', area: true, selected,
    slider: byId('chart-position'), output: byId('chart-detail'), title: mode === 'return' ? 'Portfolio time-weighted return' : 'Portfolio value',
    description: `Daily ${mode === 'return' ? 'cumulative return' : 'value'} from ${report.period.openingDate} to ${report.period.closingDate}.`,
    onSelect: index => { selected = index; saveEntryState(); } });
  byId('chart-position').max = String(Math.max(0, points.length - 1));
  byId('mode-return').setAttribute('aria-pressed', String(mode === 'return'));
  byId('mode-value').setAttribute('aria-pressed', String(mode === 'value'));
  text('chart-explanation', mode === 'return'
    ? 'Time-weighted return (TTWROR) adjusts for deposits and withdrawals. Currency gains exclude net external flows.'
    : `Portfolio value in ${report.currency}, including cash. Deposits and withdrawals change this line; it is not an investment-return measure.`);
}

function updateCursor() {
  if (portfolioChart && report?.series.length) portfolioChart.select(Math.max(0, Math.min(report.series.length - 1, selected)));
}

async function load(from, to, silent = false) {
  if (busy && silent) return null;
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
    if (currentRoute === 'performers') params.set('rankings', 'true');
    const response = await fetch('/api/dashboard' + (params.size ? '?' + params : ''), { cache: 'no-store', signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Could not load portfolio.');
    if (requestController !== controller) return null;
    const unchanged = silent && report && report.revision === data.revision && report.loadedAt === data.loadedAt
      && report.period.openingDate === data.period.openingDate && report.period.closingDate === data.period.closingDate
      && report.benchmark?.uuid === data.benchmark?.uuid && report.fxVersion === data.fxVersion
      && report.instrument?.uuid === data.instrument?.uuid;

    refreshConnectionError = null;
    report = data;
    if (unchanged) { renderFreshness(); setMarket(data.market); }
    else render();
    if (!silent) text('status', '');
    return data;
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
    return null;
  } finally {
    if (requestController === controller) {
      busy = false;
      byId('dashboard').setAttribute('aria-busy', 'false');
      document.querySelectorAll('button, input[type=date], #benchmark').forEach(element => { element.disabled = false; });
      renderMarket();
      if (selectedInstrument) { renderActivityTable('dividends'); renderActivityTable('transactions'); }
      if (currentRoute === 'live-quotes') renderLiveQuotes();
    }
  }
}

for (const kind of ['dividends', 'transactions']) {
  for (const [action, step] of [['previous', -1], ['next', 1]]) {
    byId(`security-${kind}-${action}`).addEventListener('click', () => { activityPages[kind] += step; renderActivityTable(kind); });
  }
}

byId('dates').addEventListener('submit', event => {
  event.preventDefault(); activePreset = null;
  syncRouteUrl();
  load(byId('from').value, byId('to').value);
});
byId('refresh').addEventListener('click', () => currentRoute === 'live-quotes' ? checkLiveQuotes() : load(report?.period.openingDate, report?.period.closingDate));
byId('benchmark').addEventListener('change', event => {
  selectedBenchmark = event.target.value;
  if (selectedBenchmark) mode = 'return';
  syncRouteUrl();
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
    else if (activePreset === '1D') start = new Date(report.day.openingDate + 'T12:00:00Z');
    else if (activePreset === 'MTD') start = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), 0, 12));
    else {
      start = new Date(end);
      const months = activePreset === '1Y' ? 12 : Number(activePreset.slice(0, -1));
      const day = start.getUTCDate();
      start.setUTCDate(1); start.setUTCMonth(start.getUTCMonth() - months);
      const lastDay = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate();
      start.setUTCDate(Math.min(day, lastDay));
    }
    byId('from').value = iso(start); byId('to').value = iso(end);
    syncRouteUrl();
    load(iso(start), iso(end));
  });
}
byId('ranking-basis').addEventListener('change', event => { rankingBasis = event.target.value === 'gain' ? 'gain' : 'rate'; syncRouteUrl(); renderRankings(report?.rankings); });
for (const measure of ['return', 'value']) byId('mode-' + measure).addEventListener('click', () => { mode = measure; syncRouteUrl(); drawChart(); });
byId('chart-position').addEventListener('input', event => { selected = Number(event.target.value); updateCursor(); saveEntryState(); });
window.matchMedia('(max-width:760px)').addEventListener('change', () => { drawChart(); if (currentRoute === 'live-quotes') renderLiveQuotes(); });
function checkForUpdates() {
  if (report && !document.hidden && currentRoute !== 'live-quotes') load(report.period.openingDate, report.period.closingDate, true);
}
window.setInterval(checkLiveQuotes, 1000);
window.setInterval(checkForUpdates, 30000);
window.setInterval(checkMarket, 10000);
document.addEventListener('visibilitychange', checkForUpdates);
document.addEventListener('visibilitychange', checkMarket);
document.addEventListener('visibilitychange', checkLiveQuotes);
if (!location.hash) history.replaceState({ appEntry: true, canAppBack: false, routeKey: '#/overview' }, '', '#/overview');
enterRoute();
