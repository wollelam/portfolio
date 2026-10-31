#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8097';

async function openDateMenu(page) {
  const menu = page.locator('#instrument-date-menu');
  if (!(await menu.evaluate(element => element.open))) await menu.locator('summary').click();
}

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    const security = await page.evaluate(() => report.holdings.find(item => item.uuid));
    const savedValue = await page.evaluate(() => report.totalValue);
    let feedCase = 'valid', price = 100;
    await page.route('**/api/price-updates', async route => {
      if (feedCase === 'failure') { await route.fulfill({ status: 503, body: '{}' }); return; }
      const response = await route.fetch(), data = await response.json(), now = new Date().toISOString();
      const quote = { key: security.uuid, name: 'Synthetic mapped security', symbol: 'TEST', currency: feedCase === 'currency' ? 'USD' : security.quoteCurrency,
        conId: 12345, marketDataType: 'real_time', status: 'active', receivedAt: feedCase === 'stale' ? new Date(Date.now() - 61000).toISOString() : now,
        last: price, bid: price - .1, ask: price + .1, history: [] };
      data.observedAt = now;
      data.liveQuotes = { configured: true, available: true, mode: feedCase === 'demo' ? 'demo' : 'ibkr', connected: true, state: 'connected', generatedAt: now,
        quotes: [{ quote, stale: false }] };
      if (feedCase === 'duplicate') data.liveQuotes.quotes.push({ quote, stale: false });
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.waitForFunction(() => document.querySelector('.holding-live-tick')?.dataset.value === '100');
    const row = page.locator('#holdings-body tr').filter({ has: page.locator('.holding-live-price[data-uuid="' + security.uuid + '"]') });
    const expected = new Intl.NumberFormat('en-US', { style: 'currency', currency: security.quoteCurrency }).format(100 * security.shares / 1e8);
    assert.equal(await row.locator('.holding-live-value').innerText(), expected);
    price = 101;
    await page.waitForFunction(() => document.querySelector('.holding-live-tick')?.dataset.direction === 'up');
    assert.equal(await page.evaluate(() => report.totalValue), savedValue, 'broker quotes cannot change saved valuations');
    feedCase = 'stale';
    await row.locator('.holding-live-price small').getByText(/stale/).waitFor();
    assert.notEqual(await row.locator('.holding-live-value').innerText(), '—');
    for (const [mode, message] of [['demo', 'Simulation only'], ['currency', 'Identity mismatch'], ['duplicate', 'Not mapped']]) {
      feedCase = mode;
      await row.locator('.holding-live-price').getByText(message, { exact: true }).waitFor();
      assert.equal(await row.locator('.holding-live-value').innerText(), '—');
    }
    feedCase = 'valid'; await page.waitForFunction(() => document.querySelector('.holding-live-tick')?.dataset.value === '101');
    const yesterday = await page.evaluate(() => iso(new Date(Date.parse(report.today + 'T12:00:00Z') - 86400000)));
    await openDateMenu(page);
    await page.locator('#to').fill(yesterday); await page.locator('#dates button[type="submit"]').click();
    await row.locator('.holding-live-price').getByText('Historical date', { exact: true }).waitFor();

    await page.locator('#portfolio-scope summary').click();
    const portfolio = await page.evaluate(() => report.scope.options.find(item => item.type === 'Securities accounts'));
    const cash = await page.evaluate(() => report.scope.options.find(item => item.type === 'Cash accounts'));
    await page.locator(`#scope-options input[value="${portfolio.id}"]`).check();
    await page.waitForFunction(id => report.scope.excluded.includes(id) && !report.holdings.some(item => item.uuid), portfolio.id);
    assert(page.url().includes('exclude='));
    await page.locator(`#scope-options input[value="${cash.id}"]`).check();
    await page.waitForFunction(() => report.scope.excluded.length === 2 && report.totalValue === 0);
    await page.locator('[data-route="performance"]').click();
    await page.waitForFunction(() => report.totalValue === 0 && report.period.gain === 0);
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('#scope-options input:checked').count(), 2, 'scope survives reload and navigation');
    await page.locator('#portfolio-scope summary').click(); await page.locator('#scope-reset').click();
    await page.waitForFunction(() => report.scope.excluded.length === 0 && report.totalValue > 0);
    assert(!page.url().includes('exclude='));
    await page.goBack();
    await page.waitForFunction(() => report.scope.excluded.length === 2 && report.totalValue === 0);

    const player = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    player.on('pageerror', error => errors.push(error.message));
    await player.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    let frameRequests = 0, fullRequests = 0;
    player.on('request', request => {
      if (request.url().includes('/api/dashboard')) {
        if (new URL(request.url()).searchParams.has('frames')) frameRequests++; else fullRequests++;
      }
    });
    await player.selectOption('#allocation-speed', '50');
    await player.locator('#allocation-play').click();
    await player.waitForFunction(() => report.asOf >= iso(new Date(Date.parse(report.inceptionDate + 'T12:00:00Z') + 10 * 86400000)) && report.asOf < report.today);
    await player.waitForFunction(() => [...document.querySelectorAll('.allocation-tile')].some(tile => tile.getAnimations().length > 0));
    await player.locator('#allocation-play').click();
    const paused = await player.evaluate(() => report.asOf);
    await player.waitForTimeout(300);
    assert.equal(await player.evaluate(() => report.asOf), paused, 'Pause immediately stops frame updates');
    assert.equal(fullRequests, 0, 'playback never recalculates full performance dashboards');
    assert(frameRequests <= 3 && frameRequests > 0, 'daily valuations arrive in bounded batches');
    await player.emulateMedia({ reducedMotion: 'reduce' });
    await player.locator('#allocation-play').click();
    await player.waitForFunction(date => report.asOf > date, paused);
    assert.equal(await player.locator('.allocation-tile').evaluateAll(tiles => tiles.reduce((sum, tile) => sum + tile.getAnimations().length, 0)), 0);
    await player.locator('[data-route="holdings"]').click();
    await player.waitForTimeout(200);
    assert.equal(await player.locator('#allocation-play').getAttribute('aria-pressed'), 'false');
    assert.deepEqual(errors, []);
    console.log('Native group filtering/history, mapped live holdings and rejection boundaries, batched smooth playback, Pause and reduced motion passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
