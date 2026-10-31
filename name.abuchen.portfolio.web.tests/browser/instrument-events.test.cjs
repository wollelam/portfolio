'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8098';
const assets = path.resolve(__dirname, '../../name.abuchen.portfolio.web/src/name/abuchen/portfolio/web/assets');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); const errors = [], csp = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.text().includes('Content Security Policy')) csp.push(message.text()); });
    for (const name of ['index.html', 'app.js', 'app.css']) await page.route(name === 'index.html' ? `${base}/` : `**/${name}`, route => route.fulfill({ path: path.join(assets, name), contentType: name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html' }));
    await page.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    await page.locator('[data-allocation-color="performance"]').click();
    await page.waitForFunction(() => !busy && report.dailyRankings);
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('[data-allocation-color="performance"]').getAttribute('aria-pressed'), 'true');
    assert(await page.locator('#allocation-performance-legend').isVisible());
    assert.deepEqual(await page.evaluate(() => [allocationPerformanceColor(null), allocationPerformanceColor(0), allocationPerformanceColor(.02), allocationPerformanceColor(.03)]), ['#526479', '#414554', '#228554', '#16a05a']);
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    await page.locator('#holdings-body a.instrument-link').first().click();
    await page.waitForFunction(() => report?.instrument?.prices?.length && !busy);
    // Controlled event/return fixture verifies rendering independently of portfolio contents.
    await page.evaluate(() => {
      const i = report.instrument, date = report.today;
      i.events = [{ date, kind: 'dividend', label: 'Dividend payment: gross EUR 12, net EUR 10, taxes EUR 2' }, { date, kind: 'buy', label: 'Buy 5 shares' }, { date, kind: 'sell', label: 'Sell 2 shares' }, { date, kind: 'corporate', label: 'Stock split 2:1' }];
      i.totalReturn = [{ date: report.period.openingDate, value: 0 }, { date, value: .125 }];
      renderInstrument(i);
    });
    assert.equal(await page.locator('.instrument-event').count(), 4);
    await page.locator('.event-dividend').focus();
    assert.match(await page.locator('#instrument-event-detail').innerText(), /gross EUR 12.*taxes EUR 2/);
    await page.keyboard.press('Enter');
    await page.locator('[data-event-kind="buy,sell"]').uncheck();
    assert.equal(await page.locator('.instrument-event').count(), 2);
    await page.locator('[data-instrument-chart="return"]').click();
    assert.equal(await page.evaluate(() => instrumentChart.points.at(-1).value), .125);
    assert.match(await page.locator('#instrument-chart-explanation').innerText(), /time-weighted.*saved prices/);
    assert(await page.locator('#instrument-currency-choice').isHidden());
    await page.locator('[data-instrument-chart="price"]').click();
    const result = await page.evaluate(() => {
      const i = report.instrument, old = new Date(Date.parse(report.today + 'T12:00:00Z') - 86400000).toISOString();
      i.quoteDate = old.slice(0, 10);
      market = { liveQuotes: { mode: 'ibkr', available: true, connected: true, generatedAt: new Date().toISOString(), quotes: [{ stale: true, quote: { key: i.uuid, currency: i.currency, conId: 42, status: 'active', marketDataType: 'real_time', receivedAt: old, last: 98765 } }] } };
      renderInstrument(i);
      const staleAdded = instrumentChart.points.some(point => point.value === 98765);
      market.liveQuotes.quotes[0].quote.receivedAt = new Date().toISOString(); renderInstrument(i);
      return { staleAdded, freshValue: instrumentChart.points.at(-1).value };
    });
    assert.equal(result.staleAdded, false); assert.equal(result.freshValue, 98765);
    await page.locator('[data-instrument-chart="return"]').click();
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('[data-instrument-chart="return"]').getAttribute('aria-pressed'), 'true');
    assert.deepEqual(errors, []); assert.deepEqual(csp, []);
    console.log('Instrument markers, total-return toggle, stale quote dates and heatmap persistence passed.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
