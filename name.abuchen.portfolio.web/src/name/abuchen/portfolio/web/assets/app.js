'use strict';

// Decorative logos never change link names, sorting or keyboard navigation.
function assetIcon(name, url) {
  const icon = document.createElement('span');
  icon.className = 'asset-icon'; icon.setAttribute('aria-hidden', 'true');
  icon.dataset.initials = (name || '?').trim().slice(0, 2).toLocaleUpperCase();
  if (url && /^\/api\/icon\?id=[0-9a-f-]{36}(?:&v=[0-9a-f]{64})?$/i.test(url)) {
    const image = document.createElement('img');
    image.alt = ''; image.width = 24; image.height = 24;
    image.loading = 'lazy'; image.decoding = 'async';
    image.addEventListener('error', () => { image.remove(); icon.classList.remove('has-logo'); });
    icon.classList.add('has-logo'); image.src = url; icon.append(image);
  }
  return icon;
}


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
let drawdownSelected = 0;
let drawdownChart;
let hasChartSelection = false;
let instrumentChart;
let activePreset = '1Y';
let busy = false;
let requestController;
let refreshConnectionError;
let selectedBenchmark = '';
let selectedInstrument = '';
let rankingBasis = 'rate';
let rankingLimit = '10';
let waterfallMode = 'calculation';
let tableSorts = {};
let securitySearch = '';
let securityActivity;
let activityKey = '';
const activityPages = { dividends: 0, transactions: 0 };
const activityPageSize = 25;
let excludedGroups = new Set();
let allocationDate = '';
let allocationFrameController;
let allocationFrameKey = '';
let allocationFramePromise;
const allocationFrames = new Map();
let allocationTransition = 0;
let allocationTimer;
let allocationPlaying = false;
let allocationPlaybackTimer;
let allocationPlaybackGeneration = 0;
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

// Sort raw values, retain input order for ties, and keep missing values last in either direction.
const originalAmount = field => row => [row.currency, row[field]];
const sortColumns = {
  'holdings-body': ['name', 'value', 'weight', 'quoteDate'],
  'dividends-body': ['date', 'security', 'gross', 'taxes', 'fees', 'net'],
  'gainers-body': ['name', 'rate', 'gain'],
  'decliners-body': ['name', 'rate', 'gain'],
  'security-dividends-body': ['date', originalAmount('gross'), originalAmount('taxes'), originalAmount('fees'), originalAmount('net'), 'reportingNet'],
  'security-transactions-body': ['date', row => `${row.type} ${row.owner}`, 'shares', originalAmount('amount'), originalAmount('taxes'), originalAmount('fees'), 'reportingAmount'],
  'live-quotes-body': [row => row.quote.name, row => row.quote.currency, row => [row.quote.currency, row.quote.last], row => liveDailyChange(row.quote), row => [row.quote.currency, row.quote.bid], row => [row.quote.currency, row.quote.ask], row => [row.quote.currency, row.quote.dayHigh], row => [row.quote.currency, row.quote.dayLow], row => row.quote.receivedAt, row => row.quote.marketDataType],
  'waterfall-body': ['label', 'amount']
};
function compareValues(a, b) {
  if (Array.isArray(a)) return compareValues(a[0], b[0]) || compareValues(a[1], b[1]);
  return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b));
}
function sortedRows(id, rows) {
  const sort = tableSorts[id];
  if (!sort) return rows;
  const field = sortColumns[id][sort.column];
  const value = row => typeof field === 'function' ? field(row) : row[field];
  const missing = v => v == null || (Array.isArray(v) && v[1] == null);
  return rows.map((row, index) => ({ row, index })).sort((a, b) => {
    const av = value(a.row), bv = value(b.row);
    if (missing(av) || missing(bv)) return Number(missing(av)) - Number(missing(bv)) || a.index - b.index;
    return (sort.direction === 'asc' ? 1 : -1) * compareValues(av, bv) || a.index - b.index;
  }).map(item => item.row);
}
function sortLabel(heading, label) { (heading.querySelector('button') || heading).textContent = label; }
function updateSortHeaders() {
  for (const [id] of Object.entries(sortColumns)) {
    const headers = byId(id).closest('table').querySelectorAll('thead th');
    headers.forEach((heading, column) => {
      const active = tableSorts[id]?.column === column;
      heading.setAttribute('aria-sort', active ? (tableSorts[id].direction === 'asc' ? 'ascending' : 'descending') : 'none');
    });
  }
}
for (const [id, columns] of Object.entries(sortColumns)) {
  byId(id).closest('table').querySelectorAll('thead th').forEach((heading, column) => {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'sort-button';
    button.textContent = heading.textContent;
    if (typeof columns[column] === 'function' && id.startsWith('security-') && column > 1) button.title = 'Original amounts are grouped by currency, then sorted by amount.';
    button.addEventListener('click', () => {
      const previous = tableSorts[id];
      tableSorts[id] = { column, direction: previous?.column === column && previous.direction === 'asc' ? 'desc' : 'asc' };
      if (id.startsWith('security-')) activityPages[id.includes('transactions') ? 'transactions' : 'dividends'] = 0;
      syncRouteUrl(); updateSortHeaders();
      if (id === 'live-quotes-body') renderLiveQuotes(); else if (report) render();
    });
    heading.replaceChildren(button);
  });
}

const routeMeta = {
  overview: ['THE BIG PICTURE', 'Portfolio overview'],
  performance: ['GROWTH OVER TIME', 'Portfolio performance'],
  drawdown: ['THE PATH BETWEEN THE HIGHS', 'Portfolio drawdown'],
  returns: ['THE RHYTHM OF YOUR RETURNS', 'Returns calendar'],
  waterfall: ['EXPLAIN THE CHANGE', 'Performance waterfall'],
  allocation: ['THE SHAPE OF YOUR PORTFOLIO', 'Allocation & concentration'],
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
  if (currentRoute === 'allocation' && allocationDate) params.set('asof', allocationDate);
  if (securitySearch) params.set('q', securitySearch);
  if (excludedGroups.size) params.set('exclude', [...excludedGroups].sort().join(','));
  if (rankingLimit !== '10') params.set('entries', rankingLimit);
  if (waterfallMode !== 'calculation') params.set('waterfall', waterfallMode);
  if (Object.keys(tableSorts).length) params.set('sort', Object.entries(tableSorts).map(([id, sort]) => `${id}:${sort.column}:${sort.direction}`).join(','));
  const route = currentRoute === 'instrument' ? `instrument/${encodeURIComponent(selectedInstrument)}` : currentRoute;
  return `#/${route}${params.size ? `?${params}` : ''}`;
}

function saveEntryState() {
  if (restoringScroll || !history.state?.appEntry) return;
  const tableScrolls = {};
  for (const scroller of document.querySelectorAll('.table-scroll')) {
    const id = scroller.querySelector('tbody[id], svg[id]')?.id;
    if (id && scroller.scrollLeft) tableScrolls[id] = scroller.scrollLeft;
  }
  history.replaceState({ ...history.state, routeKey: location.hash, scrollY: window.scrollY, selected, instrumentSelected, drawdownSelected, tableScrolls, activityPages: { ...activityPages } }, '', location.href);
}

function syncRouteUrl() {
  const url = routeUrl();
  history.replaceState({ ...history.state, appEntry: true, routeKey: url,
    scrollY: restoringScroll ? (history.state?.scrollY || 0) : window.scrollY, selected, instrumentSelected, drawdownSelected }, '', url);
  const filters = url.includes('?') ? url.slice(url.indexOf('?')) : '';
  for (const anchor of document.querySelectorAll('a[href^="#/"]')) anchor.setAttribute('href', anchor.hash.split('?')[0] + filters);
  if (currentRoute === 'allocation' && report) byId('allocation-holdings').setAttribute('href', allocationTarget('holdings'));
}

function showRoute() {
  hideWaterfallTooltip();
  const quotePage = currentRoute === 'live-quotes';
  byId('portfolio-scope').hidden = quotePage;
  document.body.classList.toggle('quote-page', quotePage);
  text('portfolio-label', quotePage ? 'Independent, read-only quote watchlist' : report?.label || 'Loading portfolio…');
  text('refresh', quotePage ? '↻ Refresh quotes' : '↻ Refresh dashboard');
  document.querySelector('.snapshot-badge').textContent = quotePage ? 'QUOTE PILOT' : 'LOCAL SNAPSHOT';
  const meta = routeMeta[currentRoute] || routeMeta.overview;
  text('page-eyebrow', meta[0]); text('page-title', currentRoute === 'instrument' && report?.instrument?.name ? report.instrument.name : meta[1]);
  for (const section of document.querySelectorAll('[data-page]')) {
    section.hidden = section.dataset.page !== currentRoute || section.id === 'warnings' && !report?.warnings?.length;
  }
  byId('security-filter').hidden = !['holdings', 'performers'].includes(currentRoute);
  byId('date-controls').hidden = ['overview', 'live-quotes', 'allocation'].includes(currentRoute);
  for (const link of document.querySelectorAll('nav [data-route]')) {
    const active = link.dataset.route === currentRoute || currentRoute === 'instrument' && link.dataset.route === 'holdings';
    link.classList.toggle('nav-active', active);
    if (active) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
}

function parseRoute() {
  const match = location.hash.match(/^#\/(overview|performance|holdings|allocation|dividends|performers|waterfall|returns|drawdown|live-quotes|instrument\/([^?]+))(?:\?(.*))?$/);
  if (!match) return { page: 'overview', instrument: '', params: new URLSearchParams() };
  const page = match[1].startsWith('instrument/') ? 'instrument' : match[1];
  let instrument = '';
  try { instrument = page === 'instrument' ? decodeURIComponent(match[2]) : ''; } catch { instrument = ''; }
  return { page, instrument, params: new URLSearchParams(match[3] || '') };
}

async function enterRoute() {
  stopAllocationPlayback();
  const key = `${location.hash}|${history.state?.entryId || ''}`;
  if (key === currentRouteKey) return;
  currentRouteKey = key;
  const generation = ++routeGeneration;
  const saved = history.state || {};
  const restore = saved.routeKey === location.hash ? Number(saved.scrollY || 0) : 0;
  clearTimeout(scrollTimer);
  clearTimeout(allocationTimer); allocationTimer = null;
  restoringScroll = true;
  const route = parseRoute();
  currentRoute = route.page; selectedInstrument = route.instrument;
  if (currentRoute === 'allocation') allocationDate = route.params.get('asof') || allocationDate || route.params.get('to') || report?.today || '';
  selectedLiveQuote = route.params.get('quote') || selectedLiveQuote;
  selectedBenchmark = route.params.get('benchmark') || '';
  mode = route.params.get('mode') === 'value' ? 'value' : 'return';
  activePreset = route.params.get('preset') || null;
  rankingBasis = route.params.get('basis') === 'gain' ? 'gain' : 'rate';
  updateChoiceButtons();
  excludedGroups = new Set((route.params.get('exclude') || '').split(',').filter(Boolean));
  securitySearch = (route.params.get('q') || '').slice(0, 120);
  byId('security-search').value = securitySearch;
  rankingLimit = ['10', '25', '50', 'all'].includes(route.params.get('entries')) ? route.params.get('entries') : '10';
  byId('ranking-limit').value = rankingLimit;
  waterfallMode = route.params.get('waterfall') === 'contributions' ? 'contributions' : 'calculation';
  updateChoiceButtons();
  tableSorts = {};
  for (const value of (route.params.get('sort') || '').slice(0, 1000).split(',')) {
    const [id, column, direction] = value.split(':');
    if (Object.hasOwn(sortColumns, id) && /^\d+$/.test(column || '') && Number(column) < sortColumns[id].length && ['asc', 'desc'].includes(direction)) tableSorts[id] = { column: Number(column), direction };
  }
  updateSortHeaders();
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
  drawdownSelected = Number.isInteger(saved.drawdownSelected) ? saved.drawdownSelected : drawdownSelected;
  showRoute();
  const data = await load(from || byId('from').value, to || byId('to').value);
  if (generation !== routeGeneration) return;
  if (currentRoute === 'instrument' && saved.activityPages) {
    for (const kind of ['dividends', 'transactions']) {
      activityPages[kind] = Number.isInteger(saved.activityPages[kind]) ? saved.activityPages[kind] : 0;
      renderActivityTable(kind);
    }
  }
  showRoute();
  syncRouteUrl();
  currentRouteKey = `${location.hash}|${history.state?.entryId || ''}`;
  selected = Number.isInteger(saved.selected) ? saved.selected : selected;
  if (report) { updateCursor(); }
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (generation !== routeGeneration) return;
    restoringScroll = true; window.scrollTo(0, restore);
    for (const [id, left] of Object.entries(saved.tableScrolls || {})) {
      if (Number.isFinite(left)) byId(id)?.closest('.table-scroll')?.scrollTo({ left });
    }
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
  const newState = { appEntry: true, canAppBack: true, routeKey: hash, scrollY: 0, selected, instrumentSelected, drawdownSelected,
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
  if (currentRoute !== 'allocation') {
    byId('from').value = report.period.openingDate; byId('to').value = report.period.closingDate;
  } else if (!byId('from').value || !byId('to').value) {
    const [year, month, day] = report.today.split('-').map(Number);
    byId('to').value = report.today;
    byId('from').value = iso(new Date(Date.UTC(year - 1, month - 1, Math.min(day, new Date(Date.UTC(year - 1, month, 0)).getUTCDate()), 12)));
  }
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
  renderPortfolioScope();
  const holdings = byId('holdings-body');
  holdings.replaceChildren();
  const visibleHoldings = report.holdings.filter(matchesSecurity);
  text('holding-count', `${visibleHoldings.length} of ${report.holdings.length} positions`);
  for (const holding of sortedRows('holdings-body', visibleHoldings)) {
    const row = document.createElement('tr');
    const nameCell = document.createElement('td');
    nameCell.append(assetIcon(holding.name, holding.icon || report.icons?.[holding.uuid]));
    if (holding.type === 'security' && holding.uuid) {
      const link = document.createElement('a'); link.className = 'instrument-link';
      const filters = location.hash.includes('?') ? location.hash.slice(location.hash.indexOf('?')) : '';
      link.href = `#/instrument/${encodeURIComponent(holding.uuid)}${filters}`;
      link.textContent = holding.name;
      nameCell.append(link);
    } else nameCell.append(document.createTextNode(holding.name));
    row.append(nameCell);
    cell(row, money(holding.value), true);
    cell(row, weight(holding.weight), true);
    cell(row, holding.quoteDate ? dateLabel(holding.quoteDate) : '—');
    const livePrice = document.createElement('td'), liveValue = document.createElement('td');
    livePrice.className = 'holding-live-price numeric'; liveValue.className = 'holding-live-value numeric';
    livePrice.dataset.uuid = holding.uuid || ''; liveValue.dataset.uuid = holding.uuid || '';
    row.append(livePrice, liveValue);
    holdings.append(row);
  }
  if (!visibleHoldings.length) emptyRow(holdings, 6, securitySearch ? 'No positions match your search.' : 'No holdings or cash accounts at this valuation date.');
  renderHoldingsLive();
  renderOverviewLive();
  renderInstrument(report.instrument);
  renderRankings(report.rankings);
  renderWaterfall(report.waterfall);
  renderReturnsCalendar();
  renderDrawdown();
  renderAllocation();
  const income = byId('dividends-body');
  income.replaceChildren();
  text('income-currency', `All amounts in ${report.currency}`);
  for (const dividend of sortedRows('dividends-body', report.dividends)) {
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

function renderQuotePulse(cell, value, valid) {
  const previous = cell.dataset.value ? Number(cell.dataset.value) : null;
  const changed = valid && previous !== null && value !== previous;
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
}

function renderLivePrice(cell, value, currency, field) {
  const valid = Number.isFinite(value) && value > 0;
  if (!cell.firstElementChild) {
    const arrow = document.createElement('span');
    arrow.className = 'quote-direction'; arrow.setAttribute('aria-hidden', 'true');
    cell.replaceChildren(arrow, document.createElement('span'));
  }
  renderQuotePulse(cell, value, valid);
  const direction = cell.dataset.direction;
  cell.firstElementChild.textContent = direction === 'up' ? '↑' : direction === 'down' ? '↓' : String.fromCharCode(160);
  const formatted = valid ? livePriceFormat.format(value) : '—';
  cell.lastElementChild.textContent = formatted;
  cell.classList.add('numeric', 'live-price');
  cell.setAttribute('aria-label', `${field}: ${formatted} ${currency}${direction ? `, ${direction} since previous update` : ''}`);
}


function renderPortfolioScope() {
  const options = report.scope?.options || [];
  text('scope-summary', excludedGroups.size ? `Portfolio filters · ${excludedGroups.size} excluded` : 'Portfolio filters · all groups included');
  const holder = byId('scope-options');
  const key = JSON.stringify([options, [...excludedGroups].sort()]);
  if (holder.dataset.key === key) return;
  holder.dataset.key = key; holder.replaceChildren();
  for (const type of [...new Set(options.map(option => option.type))]) {
    const fieldset = document.createElement('fieldset'), legend = document.createElement('legend');
    legend.textContent = type; fieldset.append(legend);
    for (const option of options.filter(option => option.type === type)) {
      const label = document.createElement('label'), checkbox = document.createElement('input');
      checkbox.type = 'checkbox'; checkbox.checked = excludedGroups.has(option.id); checkbox.value = option.id;
      checkbox.addEventListener('change', () => {
        stopAllocationPlayback();
        if (checkbox.checked) excludedGroups.add(option.id); else excludedGroups.delete(option.id);
        syncRouteUrl(); load(byId('from').value, byId('to').value);
      });
      label.append(checkbox, document.createTextNode(`Exclude ${option.name}`)); fieldset.append(label);
    }
    holder.append(fieldset);
  }
}
byId('scope-reset').addEventListener('click', () => {
  stopAllocationPlayback(); excludedGroups.clear(); syncRouteUrl(); load(byId('from').value, byId('to').value);
});

function holdingBrokerQuote(holding) {
  const feed = market?.liveQuotes;
  if (report.asOf !== report.today) return { message: 'Historical date' };
  if (liveConnectionError || !feed?.available || !feed.connected || feed.mode !== 'ibkr') return { message: feed?.mode === 'demo' ? 'Simulation only' : 'Not connected' };
  const matches = (feed.quotes || []).filter(entry => entry.quote.key === holding.uuid);
  if (matches.length !== 1) return { message: 'Not mapped' };
  const entry = matches[0], quote = entry.quote;
  if (quote.currency !== holding.quoteCurrency || !Number.isSafeInteger(quote.conId) || quote.conId <= 0) return { message: 'Identity mismatch' };
  if (quote.status === 'not_subscribed') return { message: 'Market-data permission needed' };
  if (quote.status === 'unresolved') return { message: 'Contract unresolved' };
  if (quote.status === 'waiting') return { message: 'Waiting for quote' };
  const received = Date.parse(quote.receivedAt), heartbeat = Date.parse(feed.generatedAt);
  if (entry.stale || quote.status !== 'active' || !Number.isFinite(received) || !Number.isFinite(heartbeat)
      || Date.now() - received > 60000 || Date.now() - heartbeat > 8000) return { message: 'Stale / unavailable' };
  if (!['real_time', 'delayed', 'frozen', 'delayed_frozen'].includes(quote.marketDataType)
      || !Number.isFinite(quote.last) || quote.last <= 0 || !Number.isSafeInteger(holding.shares)) return { message: 'Price unavailable' };
  return { quote, message: `${quote.marketDataType.replaceAll('_', ' ')} · ${quote.currency} · ${liveTime(quote.receivedAt)}` };
}

function liveFxPair(from, to) {
  if (from === to) return { rate: 1, delayed: false };
  const feed = market?.liveQuotes;
  if (report.asOf !== report.today || liveConnectionError || !feed?.available || !feed.connected || feed.mode !== 'ibkr') return null;
  const matches = (feed.quotes || []).filter(({ quote: q }) => q.key === `fx:${from}:${to}` || q.key === `fx:${to}:${from}`);
  if (matches.length !== 1) return null;
  const entry = matches[0], q = entry.quote;
  if (q.secType !== 'CASH' || !Number.isSafeInteger(q.conId) || q.conId <= 0
      || q.key !== `fx:${q.symbol}:${q.currency}` || !['real_time', 'delayed'].includes(q.marketDataType)
      || entry.stale || q.status !== 'active' || !Number.isFinite(Date.parse(q.receivedAt))
      || !Number.isFinite(Date.parse(feed.generatedAt)) || Date.now() - Date.parse(q.receivedAt) > 60000
      || Date.now() - Date.parse(feed.generatedAt) > 8000 || !Number.isFinite(q.bid) || q.bid <= 0
      || !Number.isFinite(q.ask) || q.ask < q.bid) return null;
  const midpoint = q.bid / 2 + q.ask / 2;
  const rate = q.symbol === from ? midpoint : 1 / midpoint;
  return Number.isFinite(rate) && rate > 0 ? { rate, delayed: q.marketDataType === 'delayed' } : null;
}

function liveFxRate(currency) {
  const from = currency === 'GBX' ? 'GBP' : currency;
  const to = report.currency === 'GBX' ? 'GBP' : report.currency;
  if (!from || !to || from === to) return null;
  let result = liveFxPair(from, to);
  if (!result) {
    const source = liveFxPair(from, 'USD'), target = liveFxPair(to, 'USD');
    if (source && target) result = { rate: source.rate / target.rate, delayed: source.delayed || target.delayed };
  }
  if (!result) return null;
  const rate = result.rate * (report.currency === 'GBX' ? 100 : 1) / (currency === 'GBX' ? 100 : 1);
  return Number.isFinite(rate) && rate > 0 ? { ...result, rate } : null;
}

function renderOverviewLive() {
  if (!report) return;
  let total = report.totalValue, covered = 0, delayed = 0, fxCovered = 0, delayedFx = 0;
  for (const holding of report.holdings) {
    const broker = holding.type === 'security' ? holdingBrokerQuote(holding) : {};
    const savedRate = holding.valuationFxRate;
    const fx = report.asOf === report.today && holding.quoteCurrency !== report.currency ? liveFxRate(holding.quoteCurrency) : null;
    const rate = fx?.rate ?? savedRate;
    if (!Number.isFinite(rate) || rate <= 0) continue;
    let value;
    if (broker.quote) value = Math.round(broker.quote.last * holding.shares / 1e8 * rate * 100);
    else if (fx && Number.isFinite(savedRate) && savedRate > 0) value = Math.round(holding.value / savedRate * rate);
    else continue;
    if (!Number.isSafeInteger(value) || !Number.isSafeInteger(total + value - holding.value)) continue;
    total += value - holding.value;
    if (broker.quote) {
      covered++;
      if (broker.quote.marketDataType !== 'real_time') delayed++;
    }
    if (fx) { fxCovered++; if (fx.delayed) delayedFx++; }
  }
  const totalCell = byId('total-value');
  const basis = JSON.stringify([report.asOf, report.currency, report.scope?.excluded || []]);
  if (totalCell.dataset.basis !== basis) {
    totalCell.dataset.basis = basis;
    totalCell.dataset.value = '';
    totalCell.dataset.changedAt = '';
    totalCell.classList.remove('quote-up', 'quote-down');
  }
  totalCell.classList.add('live-price');
  renderQuotePulse(totalCell, total, Number.isSafeInteger(total));
  const intraday = report.asOf === report.today && (covered > 0 || fxCovered > 0);
  const delta = intraday ? total - report.totalValue : 0;
  const gain = report.day.gain + delta;
  const factor = report.day.closingValueReturnFactor;
  const rate = delta === 0 ? report.day.rate : Number.isFinite(report.day.rate) && Number.isFinite(factor)
    ? report.day.rate + delta * factor : null;
  text('day-gain', money(gain, true)); tone('day-gain', gain);
  text('day-return', percent(Number.isFinite(rate) ? rate : null)); tone('day-return', rate);
  text('day-label', report.asOf === report.today ? (intraday ? 'Today’s performance · IBKR' : 'Today’s performance') : 'Daily performance');
  text('day-dates', `${dateLabel(report.day.openingDate)} → ${dateLabel(report.day.closingDate)}${intraday ? ' · intraday estimate' : ''}`);
  text('total-value', money(total));
  text('total-value-label', covered || fxCovered ? 'Portfolio value with IBKR quotes' : 'Total portfolio value');
  text('total-date', covered || fxCovered
    ? `${covered} position${covered === 1 ? '' : 's'} repriced · ${delayed ? `${delayed} delayed / frozen · ` : ''}${fxCovered ? `${fxCovered} positions with IBKR FX midpoint${delayedFx ? ' (includes delayed)' : ''}; saved FX elsewhere` : 'saved FX'}; stored prices for remaining positions. Charts and longer-period performance use saved prices.`
    : `As of ${dateLabel(report.asOf)} · stored prices`);
}

function renderHoldingsLive() {
  if (!report || currentRoute !== 'holdings') return;
  let covered = 0;
  const holdings = new Map(report.holdings.filter(item => item.uuid).map(item => [item.uuid, item]));
  for (const cell of document.querySelectorAll('.holding-live-price')) {
    const holding = holdings.get(cell.dataset.uuid);
    const value = cell.parentElement.querySelector('.holding-live-value');
    if (!holding) { cell.textContent = '—'; value.textContent = '—'; continue; }
    const broker = holdingBrokerQuote(holding);
    if (!broker.quote) { cell.textContent = broker.message; cell.classList.remove('quote-up', 'quote-down'); cell.dataset.value = ''; value.textContent = '—'; continue; }
    covered++;
    let tick = cell.querySelector('.holding-live-tick'), meta = cell.querySelector('small');
    if (!tick) {
      tick = document.createElement('span'); tick.className = 'holding-live-tick'; meta = document.createElement('small');
      cell.replaceChildren(tick, meta);
    }
    renderLivePrice(tick, broker.quote.last, broker.quote.currency, 'IBKR price');
    meta.textContent = broker.message;
    const amount = broker.quote.last * holding.shares / 1e8;
    value.textContent = Number.isFinite(amount) ? new Intl.NumberFormat(undefined, { style: 'currency', currency: broker.quote.currency }).format(amount) : 'Unavailable';
  }
  text('holdings-live-status', `${covered} of ${holdings.size} securities have a fresh, explicitly mapped IBKR quote. Broker position values use stored quantities and instrument currencies. Saved portfolio performance is unchanged.`);
}

function liveDailyChange(quote) {
  if (!Number.isFinite(quote.last) || quote.last <= 0 || !Number.isFinite(quote.previousClose) || quote.previousClose <= 0) return null;
  const change = quote.last / quote.previousClose - 1;
  return Number.isFinite(change) ? change : null;
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
  const entries = sortedRows('live-quotes-body', feed.quotes || []);
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
      for (let i = 0; i < 9; i++) cell(row, '');
      body.append(row);
    }
    if (!entries.length) emptyRow(body, 10, 'No quotes received yet.');
  }
  const typeNames = { real_time: 'Real-time', delayed: 'Delayed', frozen: 'Frozen', delayed_frozen: 'Delayed frozen', unknown: 'Awaiting data type' };
  const states = { waiting: 'Waiting for quotes', unavailable: 'Unavailable', unresolved: 'Contract not resolved', not_subscribed: 'Not subscribed' };
  entries.forEach(({ quote, stale }, index) => {
    const cells = body.rows[index].cells;
    cells[0].firstChild.textContent = quote.name;
    cells[0].firstChild.setAttribute('aria-pressed', String(quote.key === selectedLiveQuote));
    cells[1].textContent = quote.currency;
    ['last', 'bid', 'ask'].forEach((field, i) => renderLivePrice(cells[i === 0 ? 2 : i + 3], quote[field], quote.currency, field));
    const change = liveDailyChange(quote), changeCell = cells[3];
    changeCell.classList.add('numeric', 'live-daily-change', 'live-price');
    renderQuotePulse(changeCell, change, change !== null);
    changeCell.textContent = change === null ? '—' : new Intl.NumberFormat(undefined, { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: 'exceptZero' }).format(change);
    changeCell.classList.toggle('positive', change > 0);
    changeCell.classList.toggle('negative', change < 0);
    changeCell.title = change === null ? 'Previous close or last trade unavailable' : `Compared with IBKR previous close: ${livePriceFormat.format(quote.previousClose)} ${quote.currency}${stale || liveConnectionError ? ' · stale quote' : ''}`;
    renderLivePrice(cells[6], quote.dayHigh, quote.currency, 'Daily high');
    renderLivePrice(cells[7], quote.dayLow, quote.currency, 'Daily low');
    cells[8].textContent = quote.receivedAt ? liveTime(quote.receivedAt) : '—';
    cells[8].title = quote.receivedAt || '';
    cells[9].textContent = `${simulated ? 'Simulated ' : ''}${typeNames[quote.marketDataType] || 'Unknown'}${quote.status !== 'active' ? ` · ${states[quote.status] || 'Unavailable'}` : stale ? ' · stale' : ''}`;
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
  if (document.hidden || !['live-quotes', 'holdings', 'overview'].includes(currentRoute) || livePollBusy) return;
  livePollBusy = true;
  try {
    const response = await fetch('/api/price-updates', { cache: 'no-store' });
    if (!response.ok) throw new Error('Quote feed unavailable');
    liveConnectionError = false;
    setMarket(await response.json());
  } catch {
    liveConnectionError = true;
    if (currentRoute === 'live-quotes') renderLiveQuotes();
    renderHoldingsLive();
    renderOverviewLive();
  } finally { livePollBusy = false; }
}

byId('live-follow').addEventListener('click', () => { liveFrozenHistory = null; liveHoveredTime = null; renderLiveQuotes(); });

function allocationTarget(route) {
  const params = new URLSearchParams(routeUrl().split('?')[1] || '');
  const date = report.asOf || allocationDate;
  params.set('to', date); params.delete('asof');
  if (!params.get('from') || params.get('from') >= date) params.set('from', iso(new Date(Date.parse(date + 'T12:00:00Z') - 86400000)));
  return `#/${route}?${params}`;
}
function renderAllocationTimeline() {
  if (currentRoute !== 'allocation') return;
  if (!allocationDate) allocationDate = report.asOf;
  const date = byId('allocation-date'), slider = byId('allocation-position');
  date.min = report.inceptionDate; date.max = report.today; date.value = allocationDate;
  const first = Date.parse(report.inceptionDate + 'T12:00:00Z');
  slider.max = String(Math.max(0, Math.round((Date.parse(report.today + 'T12:00:00Z') - first) / 86400000)));
  slider.value = String(Math.max(0, Math.round((Date.parse(allocationDate + 'T12:00:00Z') - first) / 86400000)));
  slider.setAttribute('aria-valuetext', dateLabel(allocationDate));
  text('allocation-start', dateLabel(report.inceptionDate)); text('allocation-end', dateLabel(report.today));
  text('allocation-date-status', `Showing allocation at ${dateLabel(report.asOf)}`);
}
function selectAllocationDate(date, immediate = false, playback = false) {
  if (!playback) stopAllocationPlayback();
  if (!report || date < report.inceptionDate || date > report.today || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return;
  allocationDate = date;
  byId('allocation-date').value = date;
  byId('allocation-position').value = String(Math.round((Date.parse(date + 'T12:00:00Z') - Date.parse(report.inceptionDate + 'T12:00:00Z')) / 86400000));
  byId('allocation-position').setAttribute('aria-valuetext', dateLabel(date));
  syncRouteUrl(); clearTimeout(allocationTimer); allocationTimer = null;
  if (report.asOf === date && !busy) return;
  text('allocation-date-status', `Loading ${dateLabel(date)}; chart still shows ${dateLabel(report.asOf)}`);
  if (playback) return showBufferedAllocation(date);
  const refresh = () => { allocationTimer = null; return currentRoute === 'allocation' ? load() : null; };
  if (immediate) return refresh(); else allocationTimer = setTimeout(refresh, 250);
}
function allocationCacheIdentity() {
  return JSON.stringify([report.revision, report.fxVersion, [...excludedGroups].sort()]);
}
async function fetchAllocationFrames(date) {
  const key = allocationCacheIdentity();
  if (allocationFrameKey !== key) { allocationFrames.clear(); allocationFrameKey = key; }
  if (allocationFrames.size && date < [...allocationFrames.keys()].sort()[0]) allocationFrames.clear();
  if (allocationFrames.has(date)) return allocationFrames.get(date);
  if (allocationFramePromise) {
    await allocationFramePromise;
    if (allocationFrames.has(date)) return allocationFrames.get(date);
  }
  const controller = new AbortController(); allocationFrameController = controller;
  const end = iso(new Date(Math.min(Date.parse(report.today + 'T12:00:00Z'), Date.parse(date + 'T12:00:00Z') + 30 * 86400000)));
  const params = new URLSearchParams({ frames: 'true', from: date, to: end });
  if (excludedGroups.size) params.set('exclude', [...excludedGroups].sort().join(','));
  const pending = (async () => {
    const response = await fetch(`/api/dashboard?${params}`, { cache: 'no-store', signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Allocation history unavailable.');
    if (controller.signal.aborted || key !== allocationCacheIdentity()
        || data.revision !== report.revision || data.fxVersion !== report.fxVersion
        || JSON.stringify(data.scope?.excluded || []) !== JSON.stringify([...excludedGroups].sort())) throw new Error('Portfolio changed during playback; refresh before playing again.');
    for (const frame of data.frames) allocationFrames.set(frame.date, frame);
    // Bound memory for long histories and stop retaining frames from earlier windows.
    if (allocationFrames.size > 186) {
      const oldest = [...allocationFrames.keys()].sort().slice(0, allocationFrames.size - 186);
      oldest.forEach(day => allocationFrames.delete(day));
    }
  })();
  allocationFramePromise = pending;
  try { await pending; }
  finally {
    if (allocationFrameController === controller) { allocationFrameController = null; allocationFramePromise = null; }
  }
  return allocationFrames.get(date);
}
async function showBufferedAllocation(date) {
  const generation = allocationPlaybackGeneration;
  try {
    const frame = await fetchAllocationFrames(date);
    if (!allocationPlaying || generation !== allocationPlaybackGeneration || currentRoute !== 'allocation') return null;
    if (!frame) throw new Error('Allocation date unavailable.');
    report = { ...report, asOf: frame.date, totalValue: frame.totalValue, holdings: frame.holdings };
    allocationTransition = Number(byId('allocation-speed').value);
    renderAllocation(); allocationTransition = 0;
    // Keep a window of daily native valuations ahead of the player.
    const ahead = iso(new Date(Math.min(Date.parse(report.today + 'T12:00:00Z'), Date.parse(date + 'T12:00:00Z') + 15 * 86400000)));
    if (!allocationFramePromise && !allocationFrames.has(ahead)) {
      fetchAllocationFrames(ahead).catch(error => {
        if (error.name !== 'AbortError' && allocationPlaying && generation === allocationPlaybackGeneration) {
          stopAllocationPlayback(); text('allocation-date-status', error.message);
        }
      });
    }
    return frame;
  } catch (error) {
    if (error.name !== 'AbortError') { text('error', error.message || 'Could not load allocation history.'); byId('error').hidden = false; }
    return null;
  }
}

function stopAllocationPlayback() {
  const wasPlaying = allocationPlaying;
  allocationPlaying = false; allocationPlaybackGeneration++;
  clearTimeout(allocationPlaybackTimer); allocationPlaybackTimer = null;
  allocationFrameController?.abort(); allocationFrameController = null; allocationFramePromise = null;
  if (wasPlaying && report && currentRoute === 'allocation') { allocationDate = report.asOf; renderAllocationTimeline(); }
  text('allocation-play', '▶ Play'); byId('allocation-play').setAttribute('aria-pressed', 'false');
}
function nextAllocationDate(date) {
  const next = new Date(date + 'T12:00:00Z'), pace = byId('allocation-pace').value;
  if (pace === 'day') next.setUTCDate(next.getUTCDate() + 1);
  else {
    const day = next.getUTCDate(); next.setUTCDate(1);
    next.setUTCMonth(next.getUTCMonth() + (pace === 'year' ? 12 : 1));
    next.setUTCDate(Math.min(day, new Date(Date.UTC(next.getUTCFullYear(), next.getUTCMonth() + 1, 0)).getUTCDate()));
  }
  return iso(next) > report.today ? report.today : iso(next);
}
byId('allocation-play').addEventListener('click', () => {
  if (allocationPlaying) { stopAllocationPlayback(); allocationDate = report.asOf; renderAllocationTimeline(); syncRouteUrl(); return; }
  if (!report || busy || currentRoute !== 'allocation') return;
  allocationPlaying = true; const generation = ++allocationPlaybackGeneration;
  text('allocation-play', 'Ⅱ Pause'); byId('allocation-play').setAttribute('aria-pressed', 'true');
  const advance = async (restart = false) => {
    if (!allocationPlaying || generation !== allocationPlaybackGeneration || currentRoute !== 'allocation') return;
    const next = restart ? report.inceptionDate : nextAllocationDate(allocationDate || report.asOf);
    const data = await selectAllocationDate(next, true, true);
    if (!allocationPlaying || generation !== allocationPlaybackGeneration) return;
    if (!data) { allocationDate = report.asOf; renderAllocationTimeline(); stopAllocationPlayback(); return; }
    if (next >= report.today) { stopAllocationPlayback(); return; }
    allocationPlaybackTimer = setTimeout(advance, Number(byId('allocation-speed').value));
  };
  advance((allocationDate || report.asOf) >= report.today);
});
document.addEventListener('visibilitychange', () => { if (document.hidden) { stopAllocationPlayback(); if (currentRoute === 'allocation') syncRouteUrl(); } });

byId('allocation-position').addEventListener('input', event => selectAllocationDate(iso(new Date(Date.parse(report.inceptionDate + 'T12:00:00Z') + Number(event.target.value) * 86400000))));
byId('allocation-position').addEventListener('change', () => { if (allocationTimer) selectAllocationDate(allocationDate, true); });
byId('allocation-date').addEventListener('change', event => { if (event.target.checkValidity()) selectAllocationDate(event.target.value, true); });
byId('allocation-today').addEventListener('click', () => selectAllocationDate(report.today, true));

// Binary treemap partitions absolute native valuations; it does not calculate investment returns.
function allocationTiles(items, x, y, width, height, result = []) {
  if (!items.length) return result;
  if (items.length === 1) { result.push({ item: items[0], x, y, width, height }); return result; }
  const total = items.reduce((sum, item) => sum + Math.abs(item.value), 0);
  let subtotal = Math.abs(items[0].value), split = 1;
  while (split < items.length - 1 && Math.abs(subtotal + Math.abs(items[split].value) - total / 2) < Math.abs(subtotal - total / 2)) subtotal += Math.abs(items[split++].value);
  const fraction = subtotal / total;
  if (width >= height) {
    allocationTiles(items.slice(0, split), x, y, width * fraction, height, result);
    allocationTiles(items.slice(split), x + width * fraction, y, width * (1 - fraction), height, result);
  } else {
    allocationTiles(items.slice(0, split), x, y, width, height * fraction, result);
    allocationTiles(items.slice(split), x, y + height * fraction, width, height * (1 - fraction), result);
  }
  return result;
}
// Logos share the same white backing in both SVG charts.
function chartLogo(url, x, y, size) {
  if (!url || !/^\/api\/icon\?id=[0-9a-f-]{36}(?:&v=[0-9a-f]{64})?$/i.test(url)) return null;
  const group = svgElement('g', { class: 'chart-logo', 'aria-hidden': true });
  const inset = Math.max(4, size * .12);
  group.append(svgElement('rect', { x, y, width: size, height: size, rx: Math.min(7, size / 5), class: 'chart-logo-box' }));
  const image = svgElement('image', { href: url, x: x + inset, y: y + inset, width: size - inset * 2, height: size - inset * 2, preserveAspectRatio: 'xMidYMid meet' });
  image.addEventListener('error', () => group.remove());
  group.append(image);
  return group;
}

function allocationLabelLayout(width, height, hasLogo) {
  const availableWidth = Math.max(0, width - 16), availableHeight = Math.max(0, height - 16);
  if (!hasLogo) return { size: 0, name: availableWidth >= 80 && availableHeight >= 50,
    percentage: availableWidth >= 50 && availableHeight >= 18, amount: availableWidth >= 160 && availableHeight >= 144 };
  // Remove text before reducing the 40px logo box; 24px leaves a readable 16px image.
  const size = Math.min(40, availableWidth, availableHeight);
  if (size < 24) return { size: 0, name: false, percentage: false, amount: false };
  const percentage = size === 40 && availableWidth >= 54 && availableHeight >= 64;
  const name = percentage && availableWidth >= 100 && availableHeight >= 96;
  return { size, name, percentage, amount: name && availableWidth >= 160 && availableHeight >= 144 };
}

function renderAllocationLabel(group, defs, tile, index, share) {
  const item = tile.item, logo = item.icon || report.icons?.[item.uuid];
  const layout = allocationLabelLayout(tile.width, tile.height, Boolean(logo));
  const clip = svgElement('clipPath', { id: `allocation-clip-${index}` });
  clip.append(svgElement('rect', { x: tile.x + 8, y: tile.y + 8, width: Math.max(0, tile.width - 16), height: Math.max(0, tile.height - 16) }));
  defs.append(clip);
  const labels = svgElement('g', { 'clip-path': `url(#allocation-clip-${index})`, 'aria-hidden': true });
  const characters = Math.max(1, Math.floor((tile.width - 24) / 7.5));
  const words = item.name.match(new RegExp(`.{1,${characters}}(?:\\s|$)|\\S{1,${characters}}`, 'g')) || [item.name];
  const lineCount = layout.name ? Math.min(words.length, tile.height >= 144 ? 2 : 1) : 0;
  const blocks = [layout.size, lineCount * 18, layout.percentage ? 16 : 0, layout.amount ? 16 : 0].filter(Boolean);
  const totalHeight = blocks.reduce((sum, value) => sum + value, 0) + Math.max(0, blocks.length - 1) * 8;
  let top = tile.y + (tile.height - totalHeight) / 2;
  const center = tile.x + tile.width / 2;
  if (layout.size) {
    const image = chartLogo(logo, center - layout.size / 2, top, layout.size);
    if (image) labels.append(image);
    top += layout.size + 8;
  }
  words.slice(0, lineCount).forEach((word, line) => {
    const label = line === lineCount - 1 && words.length > lineCount ? `${word.trim().slice(0, characters - 1)}…` : word.trim();
    labels.append(svgElement('text', { x: center, y: top + 14 + line * 18, 'text-anchor': 'middle', class: 'allocation-name' }, label));
  });
  if (lineCount) top += lineCount * 18 + 8;
  if (layout.percentage) {
    labels.append(svgElement('text', { x: center, y: top + 13, 'text-anchor': 'middle', class: 'allocation-size allocation-percentage' }, weight(share)));
    top += 24;
  }
  if (layout.amount) labels.append(svgElement('text', { x: center, y: top + 13, 'text-anchor': 'middle', class: 'allocation-size allocation-amount' }, money(item.value)));
  group.append(labels);
}

function renderAllocation() {
  renderAllocationTimeline();
  const positions = (report.holdings || []).filter(item => Number.isFinite(item.value) && item.value !== 0)
    .slice().sort((a, b) => Math.abs(b.value) - Math.abs(a.value) || a.name.localeCompare(b.name));
  const gross = positions.reduce((sum, item) => sum + Math.abs(item.value), 0);
  const cash = positions.filter(item => item.type === 'cash').reduce((sum, item) => sum + item.value, 0);
  const largest = positions[0];
  text('allocation-largest', largest?.name || 'No positions');
  text('allocation-largest-weight', largest ? `${weight(Math.abs(largest.value) / gross)} of gross exposure` : 'No non-zero holdings');
  text('allocation-top-five', gross ? weight(positions.slice(0, 5).reduce((sum, item) => sum + Math.abs(item.value), 0) / gross) : '—');
  text('allocation-cash', money(cash));
  text('allocation-cash-weight', report.totalValue === 0 ? 'Net portfolio weight unavailable at zero value' : `${weight(cash / report.totalValue)} of net portfolio value`);
  text('allocation-summary', `${dateLabel(report.asOf)} · ${positions.length} non-zero positions · Gross exposure ${money(gross)} · Net portfolio value ${money(report.totalValue)}`);
  const svg = byId('allocation-chart');
  const previousTiles = new Map([...svg.querySelectorAll('.allocation-tile')].map(group => {
    const rect = group.querySelector('rect');
    return [group.dataset.key, { x: Number(rect.getAttribute('x')) - 2, y: Number(rect.getAttribute('y')) - 2,
      width: Number(rect.getAttribute('width')) + 4, height: Number(rect.getAttribute('height')) + 4 }];
  }));
  svg.replaceChildren();
  const mobile = window.matchMedia('(max-width:760px)').matches;
  const width = Math.max(650, Math.min(1100, window.innerWidth - (mobile ? 84 : 370))), height = 420;
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.style.minWidth = `${width}px`;
  text('allocation-detail', gross ? 'Hover or focus a position to inspect it. Select a security to open its details.' : 'No non-zero holdings at this valuation date.');
  if (!gross) {
    svg.append(svgElement('text', { x: width / 2, y: height / 2, 'text-anchor': 'middle', class: 'axis-label' }, 'No non-zero holdings at this valuation date.')); return;
  }
  const items = positions.slice(0, 20), groups = new Map();
  for (const item of positions.slice(20)) {
    const key = `${item.type === 'cash' ? 'cash' : 'securities'}:${item.value < 0 ? 'negative' : 'positive'}`;
    const group = groups.get(key) || { name: `Other ${item.value < 0 ? 'negative' : 'positive'} ${item.type === 'cash' ? 'cash positions' : 'securities'}`, type: item.type, value: 0, count: 0 };
    group.value += item.value; group.count++; groups.set(key, group);
  }
  items.push(...groups.values());
  const defs = svgElement('defs', {}), pattern = svgElement('pattern', { id: 'allocation-negative', width: 10, height: 10, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(35)' });
  pattern.append(svgElement('rect', { width: 10, height: 10, fill: '#934052' }), svgElement('line', { x1: 0, x2: 0, y1: 0, y2: 10, stroke: '#bb6374', 'stroke-width': 3 }));
  defs.append(pattern); svg.append(defs);
  const palette = ['#286e62', '#3c6382', '#56618d', '#695b83', '#2b7581'];
  allocationTiles(items, 0, 0, width, height).forEach((tile, index) => {
    const item = tile.item, share = Math.abs(item.value) / gross, netWeight = report.totalValue === 0 ? null : item.value / report.totalValue;
    const description = `${item.name}: ${money(item.value)} · ${weight(share)} of gross exposure · ${netWeight == null ? 'net weight unavailable' : `${weight(netWeight)} net portfolio weight`}${item.count ? ` · ${item.count} grouped positions` : ''}`;
    const group = svgElement('g', { tabindex: 0, role: item.uuid ? 'link' : 'img', class: 'allocation-tile', 'aria-label': `${description}${item.uuid ? '. Open security details' : ''}`, 'data-allocation-value': item.value });
    group.dataset.key = item.uuid || item.id || item.name;
    const fill = item.value < 0 ? 'url(#allocation-negative)' : item.type === 'cash' ? '#526479' : palette[index % palette.length];
    group.append(svgElement('title', {}, description), svgElement('rect', { x: tile.x + 2, y: tile.y + 2, width: Math.max(0, tile.width - 4), height: Math.max(0, tile.height - 4), rx: 7, fill }));
    renderAllocationLabel(group, defs, tile, index, share);
    const previous = previousTiles.get(group.dataset.key);
    if (allocationTransition && previous && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      group.style.transformOrigin = `${tile.x}px ${tile.y}px`;
      group.animate([{ transform: `translate(${previous.x - tile.x}px, ${previous.y - tile.y}px) scale(${previous.width / tile.width}, ${previous.height / tile.height})` },
        { transform: 'none' }], { duration: allocationTransition, easing: 'linear' });
    }
    const inspect = () => text('allocation-detail', description);
    group.addEventListener('pointerenter', inspect); group.addEventListener('focus', inspect);
    if (item.uuid) {
      const open = () => navigate(allocationTarget(`instrument/${encodeURIComponent(item.uuid)}`));
      group.addEventListener('click', open);
      group.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } });
    }
    svg.append(group);
  });
}

function renderDrawdown() {
  const data = report.drawdown;
  text('drawdown-maximum', data?.maximum == null ? 'Unavailable' : percent(-data.maximum));
  text('drawdown-current', data?.current == null ? 'Unavailable' : percent(data.current));
  text('drawdown-duration', data?.longestWithoutNewHigh == null ? 'Unavailable' : `${data.longestWithoutNewHigh} calendar days`);
  tone('drawdown-maximum', -(data?.maximum || 0)); tone('drawdown-current', data?.current || 0);
  text('drawdown-summary', !data ? 'Drawdown unavailable for this period.' : data.maximum === 0
    ? 'No decline from a high within the selected period.'
    : `Largest decline: peak ${dateLabel(data.peakDate)} → trough ${dateLabel(data.troughDate)}. Both dates are inside the selected period.`);
  drawdownChart = new TimeSeriesChart(byId('drawdown-chart'));
  drawdownChart.draw({ points: (data?.series || []).map(point => ({ date: point.date, value: point.rate })),
    format: percent, includeZero: true, area: true, areaBaseline: 0, output: byId('drawdown-detail'), selected: drawdownSelected,
    title: 'Portfolio time-weighted drawdown', empty: 'No drawdown data available.',
    onSelect: index => { drawdownSelected = index; saveEntryState(); } });
}

// Compounding cumulative native TTWROR endpoints preserves the engine's flow adjustment.
function intervalReturn(opening, closing) {
  if (!Number.isFinite(opening?.rate) || !Number.isFinite(closing?.rate) || opening.rate <= -1) return null;
  const value = (1 + closing.rate) / (1 + opening.rate) - 1;
  return Number.isFinite(value) ? value : null;
}
function renderReturnsCalendar() {
  const points = report.series || [], body = byId('returns-body');
  body.replaceChildren();
  const months = new Map(), years = new Map();
  for (let i = 1; i < points.length; i++) {
    const point = points[i], month = point.date.slice(0, 7), year = point.date.slice(0, 4);
    if (!months.has(month)) months.set(month, { opening: points[i - 1], closing: point });
    else months.get(month).closing = point;
    if (!years.has(year)) years.set(year, { opening: points[i - 1], closing: point });
    else years.get(year).closing = point;
  }
  const result = intervalReturn(points[0], points.at(-1));
  const available = [...months.values()].map(period => intervalReturn(period.opening, period.closing)).filter(Number.isFinite);
  const positive = available.filter(value => value > 0).length;
  text('returns-summary', `${report.period.openingDate} → ${report.period.closingDate} · ${percent(result)} for the selected period · ${positive} positive, ${available.filter(value => value < 0).length} negative and ${available.filter(value => value === 0).length} flat months (including partial months)`);
  const paintCell = (row, period, key, annual = false) => {
    const td = document.createElement('td');
    if (!period) { td.textContent = '—'; td.className = 'return-outside'; row.append(td); return; }
    const value = intervalReturn(period.opening, period.closing);
    const [year, month] = key.split('-').map(Number);
    const expectedStart = annual ? `${year - 1}-12-31` : iso(new Date(Date.UTC(year, month - 1, 0)));
    const expectedEnd = annual ? `${year}-12-31` : iso(new Date(Date.UTC(year, month, 0)));
    const partial = period.opening.date !== expectedStart || period.closing.date !== expectedEnd;
    const button = document.createElement('button'); button.type = 'button'; button.className = 'return-cell';
    button.textContent = value == null ? 'N/A' : `${percent(value)}${partial ? '*' : ''}`;
    button.title = `${key}: ${period.opening.date} → ${period.closing.date}${partial ? ' (partial period)' : ''}. Open performance chart.`;
    button.setAttribute('aria-label', `${key}: ${value == null ? 'Unavailable' : percent(value)}${partial ? ', partial period' : ''}. Open performance chart`);
    if (value != null) {
      button.classList.add(value > 0 ? 'return-profit' : value < 0 ? 'return-loss' : 'return-flat');
      button.style.setProperty('--return-strength', String(Math.min(.8, .12 + Math.abs(value) * 3)));
    }
    button.addEventListener('click', () => {
      const params = new URLSearchParams(routeUrl().split('?')[1] || '');
      params.set('from', period.opening.date); params.set('to', period.closing.date); params.delete('preset');
      navigate(`#/performance?${params}`);
    });
    td.append(button); row.append(td);
  };
  for (const [year, period] of [...years].reverse()) {
    const row = document.createElement('tr'), heading = document.createElement('th');
    heading.scope = 'row'; heading.textContent = year; row.append(heading);
    for (let month = 1; month <= 12; month++) {
      const key = `${year}-${String(month).padStart(2, '0')}`; paintCell(row, months.get(key), key);
    }
    paintCell(row, period, year, true); body.append(row);
  }
  if (!years.size) emptyRow(body, 14, 'No daily performance points available in this period.');
}

function updateChoiceButtons() {
  for (const button of document.querySelectorAll('[data-ranking-basis]')) button.setAttribute('aria-pressed', String(button.dataset.rankingBasis === rankingBasis));
  for (const button of document.querySelectorAll('[data-waterfall-mode]')) button.setAttribute('aria-pressed', String(button.dataset.waterfallMode === waterfallMode));
}
function hideWaterfallTooltip() { byId('waterfall-tooltip').hidden = true; }
function showWaterfallTooltip(label, event, group) {
  const tooltip = byId('waterfall-tooltip');
  tooltip.textContent = label; tooltip.hidden = false;
  const bar = group.getBoundingClientRect(), box = tooltip.getBoundingClientRect();
  const x = event?.clientX ?? bar.left + bar.width / 2;
  const y = event?.clientY ?? bar.top;
  tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
  tooltip.style.top = `${Math.max(8, Math.min(y + 14, innerHeight - box.height - 8))}px`;
}
window.addEventListener('scroll', hideWaterfallTooltip, true);
window.addEventListener('resize', hideWaterfallTooltip);
document.addEventListener('keydown', event => { if (event.key === 'Escape') hideWaterfallTooltip(); });

function renderWaterfall(data) {
  hideWaterfallTooltip();
  const svg = byId('waterfall-chart'), body = byId('waterfall-body');
  svg.replaceChildren(); body.replaceChildren();
  const entries = data?.entries || [];
  text('waterfall-summary', data?.message || `${report.period.openingDate} → ${report.period.closingDate} · ${report.currency} · ${waterfallMode === 'calculation' ? 'Opening value to closing value' : 'Contributions to investment gain / loss'}`);
  text('waterfall-detail', entries.length ? 'Hover or focus a bar to inspect its exact amount.' : 'No breakdown available.');
  if (!entries.length) { emptyRow(body, 2, data?.message || 'No breakdown available.'); return; }
  let running = 0;
  const bars = entries.map(entry => {
    const start = entry.kind === 'CHANGE' ? running : 0;
    const end = entry.kind === 'CHANGE' ? running + entry.amount : entry.amount;
    if (entry.kind === 'START' || entry.kind === 'CHANGE') running = end;
    return { ...entry, start, end };
  });
  const low = Math.min(0, ...bars.flatMap(bar => [bar.start, bar.end]));
  const high = Math.max(0, ...bars.flatMap(bar => [bar.start, bar.end]));
  const span = high - low || 1;
  const width = Math.max(900, entries.length * 112 + 120), height = 420, left = 115, right = width - 25;
  const y = value => 285 - (value - low) / span * 240;
  const step = (right - left) / bars.length;
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.style.width = '100%'; svg.style.minWidth = `${width}px`; svg.style.maxWidth = 'none';
  svg.setAttribute('aria-label', 'Performance waterfall in portfolio reporting currency');
  for (let i = 0; i <= 4; i++) {
    const value = low + span * i / 4, py = y(value);
    svg.append(svgElement('line', { x1: left, x2: right, y1: py, y2: py, class: 'waterfall-grid' }));
    svg.append(svgElement('text', { x: left - 10, y: py + 4, 'text-anchor': 'end', class: 'waterfall-axis' }, money(value)));
  }
  bars.forEach((bar, index) => {
    const x = left + index * step + step * .2, barWidth = step * .6;
    const group = svgElement('g', { tabindex: '0', role: bar.uuid ? 'link' : 'img', class: 'waterfall-bar',
      'aria-label': `${bar.label}: ${money(bar.amount, bar.kind === 'CHANGE')}${bar.uuid ? '. Open security details' : ''}` });
    group.setAttribute('aria-describedby', 'waterfall-tooltip');
    const total = bar.kind !== 'CHANGE';
    group.append(svgElement('rect', { x, y: Math.min(y(bar.start), y(bar.end)) - (bar.start === bar.end ? 1 : 0), width: barWidth,
      height: Math.max(2, Math.abs(y(bar.start) - y(bar.end))), class: total ? 'waterfall-total' : bar.amount >= 0 ? 'waterfall-positive' : 'waterfall-negative' }));
    const logo = chartLogo(report.icons?.[bar.uuid], x + (barWidth - 32) / 2, 301, 32);
    if (logo) group.append(logo);
    const words = bar.label.match(/.{1,17}(?:\s|$)|\S{1,17}/g) || [bar.label];
    words.slice(0, 4).forEach((word, line) => group.append(svgElement('text', { x: x + barWidth / 2, y: (logo ? 351 : 315) + line * 17, 'text-anchor': 'middle', class: 'waterfall-label' }, word.trim())));
    const description = `${bar.label}: ${money(bar.amount, !total)}`;
    const inspect = event => { text('waterfall-detail', description); showWaterfallTooltip(description, event, group); };
    group.addEventListener('pointerenter', inspect);
    group.addEventListener('pointermove', inspect);
    group.addEventListener('pointerleave', hideWaterfallTooltip);
    group.addEventListener('focus', () => inspect());
    group.addEventListener('blur', hideWaterfallTooltip);
    if (bar.uuid) {
      const open = () => navigate(`#/instrument/${encodeURIComponent(bar.uuid)}`);
      group.addEventListener('click', open);
      group.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } });
    }
    svg.append(group);
    if (index < bars.length - 1) svg.append(svgElement('line', { x1: x + barWidth, x2: left + (index + 1) * step + step * .2, y1: y(bar.end), y2: y(bar.end), class: 'waterfall-connector' }));
  });
  for (const entry of sortedRows('waterfall-body', entries)) {
    const row = document.createElement('tr'); cell(row, entry.label); cell(row, money(entry.amount, entry.kind === 'CHANGE'), true); body.append(row);
  }
}

function matchesSecurity(row) { return !securitySearch || (row.name || '').toLocaleLowerCase().includes(securitySearch.toLocaleLowerCase().trim()); }

function renderRankings(rankings) {
  const key = rankingBasis;
  const rows = (rankings?.securities || []).filter(matchesSecurity);
  const valid = rows.filter(row => Number.isFinite(row[key]));
  const compare = direction => (a, b) => direction * (a[key] - b[key])
    || (a.name || '').localeCompare(b.name || '') || a.uuid.localeCompare(b.uuid);
  const fill = (id, items, empty) => {
    const body = byId(id);
    body.replaceChildren();
    if (!items.length) { emptyRow(body, 3, empty); return; }
    for (const item of sortedRows(id, items)) {
      const row = document.createElement('tr');
      const name = document.createElement('td');
      const link = document.createElement('a');
      const filters = location.hash.includes('?') ? location.hash.slice(location.hash.indexOf('?')) : '';
      link.className = 'instrument-link';
      link.href = `#/instrument/${encodeURIComponent(item.uuid)}${filters}`;
      link.textContent = item.name || 'Unnamed security';
      name.append(assetIcon(item.name, report.icons?.[item.uuid]), link);
      row.append(name);
      cell(row, percent(item.rate), true);
      cell(row, item.gain == null ? 'Unavailable' : money(item.gain, true), true);
      body.append(row);
    }
  };
  fill('gainers-body', valid.filter(row => row[key] > 0).sort(compare(-1)).slice(0, rankingLimit === 'all' ? undefined : Number(rankingLimit)),
    `No positive ${key === 'rate' ? 'returns' : 'gains'} in this period.`);
  fill('decliners-body', valid.filter(row => row[key] < 0).sort(compare(1)).slice(0, rankingLimit === 'all' ? undefined : Number(rankingLimit)),
    `No negative ${key === 'rate' ? 'returns' : 'gains'} in this period.`);
  const neutral = valid.filter(row => row[key] === 0).length;
  text('ranking-summary', `${dateLabel(report.period.openingDate)} → ${dateLabel(report.period.closingDate)} · Showing ${Math.min(valid.filter(row => row[key] > 0).length, rankingLimit === 'all' ? Infinity : Number(rankingLimit))} gainers and ${Math.min(valid.filter(row => row[key] < 0).length, rankingLimit === 'all' ? Infinity : Number(rankingLimit))} decliners · ${valid.length} securities with results · ${rows.length - valid.length} unavailable for this measure · ${neutral} unchanged`);
  for (const heading of document.querySelectorAll('[data-ranking-currency]')) sortLabel(heading, `Gain / loss (${report.currency})`);
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
  const items = sortedRows(`security-${kind}-body`, securityActivity?.[kind] || []);
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
  for (const label of document.querySelectorAll('[data-activity-currency]')) sortLabel(label, `In ${report.currency}`);
}

function renderInstrument(instrument) {
  const panel = byId('instrument-panel');
  panel.hidden = !selectedInstrument;
  if (!selectedInstrument) return;
  byId('instrument-icon').replaceChildren(assetIcon(instrument?.name, report.icons?.[instrument?.uuid]));
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
  renderHoldingsLive();
  renderOverviewLive();
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
    for (const item of series) this.drawPath(item.points, item.className || 'benchmark-line', item.step);
    const ticks = mobile ? 3 : 5;
    for (let i = 0; i < ticks; i++) {
      const time = this.first + (this.last - this.first) * i / (ticks - 1);
      const date = new Date(time).toISOString().slice(0, 10);
      const label = config.formatTime ? config.formatTime(time) : new Date(date + 'T12:00:00Z').toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
      svg.append(svgElement('text', { x: this.x(time), y: this.height - 12, 'text-anchor': 'middle', class: 'axis-label' }, label));
    }
    if (config.area && this.points.every(point => Number.isFinite(point.value))) {
      let area = this.points.map((point, index) => `${index ? 'L' : 'M'}${this.x(times[index]).toFixed(2)},${this.y(point.value).toFixed(2)} `).join('');
      const baseline = Number.isFinite(config.areaBaseline) ? this.y(config.areaBaseline) : this.bottom;
      area += `L${this.x(this.last)},${baseline} L${this.x(this.first)},${baseline} Z`;
      svg.append(svgElement('path', { d: area, class: 'chart-area' }));
    }
    this.cursor = svgElement('line', { y1: this.top, y2: this.bottom, class: 'cursor-line' }); svg.append(this.cursor);
    this.dot = svgElement('circle', { r: 5, class: 'cursor-dot' }); svg.append(this.dot);
    if (this.points.length === 1 && Number.isFinite(this.points[0].value)) svg.append(svgElement('circle', { cx: this.x(times[0]), cy: this.y(this.points[0].value), r: 3, class: 'cursor-dot' }));
    this.timeMap = times; this.drawSelection();
  }

  drawPath(points, className, step = false) {
    let path = '', previous = false;
    for (const point of points) {
      const value = point.value, time = seriesTime(point.date);
      if (!Number.isFinite(value)) { previous = false; continue; }
      path += previous && step ? `H${this.x(time).toFixed(2)} V${this.y(value).toFixed(2)} ` : `${previous ? 'L' : 'M'}${this.x(time).toFixed(2)},${this.y(value).toFixed(2)} `; previous = true;
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
    const exact = [detail, ...seriesDetails, this.config.extraDetail?.(this.index)].filter(Boolean).join(' · ');
    if (this.config.output) this.config.output.textContent = exact;
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
  }] : mode === 'value' ? [{ name: 'Opening value + net contributions', className: 'capital-line', step: true, points: report.series.map(point => ({ date: point.date, value: point.investedCapital })) }] : [];
  byId('value-legend').hidden = mode !== 'value';
  const formatter = mode === 'return' ? percent : value => money(value);
  portfolioChart = new TimeSeriesChart(byId('chart'));
  portfolioChart.draw({ points, series, format: formatter, includeZero: mode === 'return', area: true, selected,
    extraDetail: index => mode === 'value' && Number.isFinite(report.series[index]?.investedCapital) ? `Investment gain / loss: ${money(report.series[index].value - report.series[index].investedCapital, true)}` : '',
    output: byId('chart-detail'), title: mode === 'return' ? 'Portfolio time-weighted return' : 'Portfolio value',
    description: `Daily ${mode === 'return' ? 'cumulative return' : 'value'} from ${report.period.openingDate} to ${report.period.closingDate}.`,
    onSelect: index => { selected = index; saveEntryState(); } });
  byId('mode-return').setAttribute('aria-pressed', String(mode === 'return'));
  byId('mode-value').setAttribute('aria-pressed', String(mode === 'value'));
  text('chart-explanation', mode === 'return'
    ? 'Time-weighted return (TTWROR) adjusts for deposits and withdrawals. Currency gains exclude net external flows.'
    : `Portfolio value in ${report.currency}, including cash. The dashed line starts at the opening value and adds external deposits minus withdrawals, including securities delivered in or out at their recorded value. The gap is investment gain / loss for this period; trades and dividends are not new contributions.`);
}

function updateCursor() {
  if (portfolioChart && report?.series.length) portfolioChart.select(Math.max(0, Math.min(report.series.length - 1, selected)));
}

async function load(from, to, silent = false) {
  if (currentRoute === 'allocation' && allocationDate) {
    to = allocationDate; from = iso(new Date(Date.parse(to + 'T12:00:00Z') - 86400000));
  }
  if (busy && silent) return null;
  requestController?.abort();
  const controller = new AbortController();
  requestController = controller;
  busy = true;
  if (!silent) {
    byId('error').hidden = true;
    text('status', 'Calculating portfolio…');
    byId('dashboard').setAttribute('aria-busy', 'true');
    document.querySelectorAll('button, input[type=date], #benchmark').forEach(element => { if (element.id !== 'allocation-play' || !allocationPlaying) element.disabled = true; });
  }
  try {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (selectedBenchmark) params.set('benchmark', selectedBenchmark);
    if (excludedGroups.size) params.set('exclude', [...excludedGroups].sort().join(','));
    if (selectedInstrument) params.set('instrument', selectedInstrument);
    if (currentRoute === 'performers') params.set('rankings', 'true');
    if (currentRoute === 'waterfall') params.set('waterfall', waterfallMode);
    const response = await fetch('/api/dashboard' + (params.size ? '?' + params : ''), { cache: 'no-store', signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Could not load portfolio.');
    if (requestController !== controller) return null;
    const unchanged = silent && report && report.revision === data.revision && report.loadedAt === data.loadedAt
      && report.period.openingDate === data.period.openingDate && report.period.closingDate === data.period.closingDate
      && report.benchmark?.uuid === data.benchmark?.uuid && report.fxVersion === data.fxVersion
      && report.instrument?.uuid === data.instrument?.uuid
      && JSON.stringify(report.scope?.excluded) === JSON.stringify(data.scope?.excluded);

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
    byId(`security-${kind}-${action}`).addEventListener('click', () => { activityPages[kind] += step; renderActivityTable(kind); saveEntryState(); });
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
    else if (activePreset === 'MAX') start = new Date(Math.min(Date.parse(report.inceptionDate + 'T12:00:00Z'), end.getTime() - 86400000));
    else if (activePreset === 'MTD') start = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), 0, 12));
    else {
      start = new Date(end);
      const months = Number(activePreset.slice(0, -1)) * (activePreset.endsWith('Y') ? 12 : 1);
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
for (const button of document.querySelectorAll('[data-ranking-basis]')) button.addEventListener('click', () => { rankingBasis = button.dataset.rankingBasis; updateChoiceButtons(); syncRouteUrl(); renderRankings(report?.rankings); });
for (const measure of ['return', 'value']) byId('mode-' + measure).addEventListener('click', () => { mode = measure; syncRouteUrl(); drawChart(); });
window.matchMedia('(max-width:760px)').addEventListener('change', () => { drawChart(); if (currentRoute === 'live-quotes') renderLiveQuotes(); if (currentRoute === 'allocation') renderAllocation(); });
function checkForUpdates() {
  if (allocationPlaying) return;
  if (report && !document.hidden && currentRoute !== 'live-quotes' && !allocationPlaying) load(report.period.openingDate, report.period.closingDate, true);
}
window.setInterval(checkLiveQuotes, 1000);
window.setInterval(checkForUpdates, 30000);
window.setInterval(checkMarket, 10000);
document.addEventListener('visibilitychange', checkForUpdates);
document.addEventListener('visibilitychange', checkMarket);
document.addEventListener('visibilitychange', checkLiveQuotes);
if (!location.hash) history.replaceState({ appEntry: true, canAppBack: false, routeKey: '#/overview' }, '', '#/overview');
enterRoute();

byId('ranking-limit').addEventListener('change', event => { rankingLimit = event.target.value; syncRouteUrl(); renderRankings(report?.rankings); });
for (const button of document.querySelectorAll('[data-waterfall-mode]')) button.addEventListener('click', () => {
  if (waterfallMode === button.dataset.waterfallMode) return;
  waterfallMode = button.dataset.waterfallMode; updateChoiceButtons(); syncRouteUrl(); load(byId('from').value, byId('to').value);
});

byId('security-search').addEventListener('input', event => { securitySearch = event.target.value.slice(0, 120); syncRouteUrl(); if (report) render(); });
byId('clear-security-search').addEventListener('click', () => { securitySearch = ''; byId('security-search').value = ''; syncRouteUrl(); if (report) render(); byId('security-search').focus(); });
