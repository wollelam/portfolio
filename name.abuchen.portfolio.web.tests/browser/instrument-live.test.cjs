#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
const assets = path.resolve(__dirname, '../../name.abuchen.portfolio.web/src/name/abuchen/portfolio/web/assets');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    for (const name of ['index.html', 'app.js', 'app.css']) {
      await page.route(name === 'index.html' ? `${base}/` : `**/${name}*`, route => route.fulfill({ path: path.join(assets, name), contentType: name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html' }));
    }
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    await page.locator('#holdings-body a.instrument-link').first().click();
    await page.waitForFunction(() => currentRoute === 'instrument' && report?.instrument?.prices?.length);
    const result = await page.evaluate(() => {
      const i = report.instrument, now = new Date().toISOString();
      report.asOf = report.today;
      report.period.closingDate = report.today;
      market = { observedAt: now, liveQuotes: { mode: 'ibkr', available: true, connected: true, generatedAt: now,
        quotes: [{ stale: false, quote: { key: i.uuid, currency: i.currency, conId: 42, status: 'active', marketDataType: 'real_time', receivedAt: now, previousClose: 100, last: 123.45 } }] } };
      renderInstrument(i);
      const live = { label: document.querySelector('#instrument-price-status').textContent, price: document.querySelector('#instrument-current-price').textContent, point: instrumentChart.points.at(-1), change: document.querySelector('#instrument-day-change').textContent, positive: document.querySelector('#instrument-day-change').classList.contains('positive') };
      market.liveQuotes.quotes[0].quote.last = 125.5; renderInstrument(i);
      const updated = instrumentChart.points.at(-1).value;
      market.liveQuotes.quotes[0].quote.last = 90; renderInstrument(i);
      const negative = document.querySelector('#instrument-day-change').classList.contains('negative');
      market.liveQuotes.quotes[0].quote.previousClose = null; renderInstrument(i);
      const missing = document.querySelector('#instrument-day-change').textContent;
      report.asOf = '2000-01-01'; renderInstrument(i);
      return { live, updated, negative, missing, fallbackChange: document.querySelector('#instrument-day-change').textContent, fallback: document.querySelector('#instrument-price-status').textContent, points: instrumentChart.points.length, saved: i.prices.length };
    });
    assert.equal(result.live.label, 'Live');
    assert(result.live.price.includes('123'));
    assert.equal(result.live.point.value, 123.45);
    assert.equal(result.updated, 125.5);
    assert.match(result.live.change, /23[.,]45/);
    assert(result.live.positive);
    assert(result.negative);
    assert.equal(result.missing, '—');
    assert.equal(result.fallbackChange, '—');
    assert.equal(result.fallback, 'Saved');
    assert.equal(result.points, result.saved);
    assert.deepEqual(errors, []);
    console.log('Instrument live quote, chart updates and historical fallback passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
