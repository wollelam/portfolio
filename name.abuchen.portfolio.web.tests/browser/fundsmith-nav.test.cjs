#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const { resolve } = require('node:path');

const base = process.env.BASE_URL || 'http://127.0.0.1:8098';
const assets = resolve(__dirname, '../../name.abuchen.portfolio.web/src/name/abuchen/portfolio/web/assets');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    for (const name of ['index.html', 'app.js', 'app.css']) {
      await page.route(name === 'index.html' ? `${base}/` : `**/${name}`,
        route => route.fulfill({ path: resolve(assets, name), contentType: name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html' }));
    }
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => report?.holdings?.some(item => item.uuid));
    const result = await page.evaluate(() => {
      const holding = report.holdings.find(item => item.uuid);
      const uuid = holding.uuid;
      holding.quoteCurrency = 'GBX';
      holding.quoteDate = report.today;
      report.fundsmithIds = [uuid];
      market = { ...(market || {}), liveQuotes: { mode: 'disabled' },
        fundsmith: { date: report.today, isin: 'GB00B4M93C53', currency: 'GBX', price: '668.27' } };
      renderHoldingsLive();
      const cell = document.querySelector(`.holding-live-price[data-uuid="${uuid}"]`);
      const published = { price: cell.textContent, value: cell.parentElement.querySelector('.holding-live-value').textContent };
      report.instrument = { ...report.instrument, uuid, currency: 'GBX', quoteDate: report.today,
        quoteScale: 1e8, prices: [], sourcePrices: { Primary: [{ date: report.today, price: 65000000000 }] } };
      currentRoute = 'instrument';
      renderInstrumentPriceStatus();
      const instrument = { price: document.querySelector('#instrument-current-price').textContent,
        status: document.querySelector('#instrument-price-status').textContent };
      market.fundsmith.date = '2000-01-01';
      renderInstrumentPriceStatus();
      const fallback = document.querySelector('#instrument-price-status').textContent;
      return { published, instrument, fallback };
    });
    assert.match(result.published.price, /668[.,]27/);
    assert.match(result.published.price, /Fundsmith published NAV/);
    assert.notEqual(result.published.value, '—');
    assert.match(result.instrument.price, /668[.,]27/);
    assert.equal(result.instrument.status, 'Published');
    assert.equal(result.fallback, 'Saved', 'an older published NAV cannot replace a newer saved quote');
    console.log('Fundsmith published NAV is shown for mapped holdings and instruments, with dated fallback.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
