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
    const performance = await page.evaluate(() => {
      const uuid = report.fundsmithIds[0];
      report.currency = 'GBX'; report.asOf = report.today;
      report.totalValue = 100000;
      report.day = { ...report.day, gain: 200, rate: .002, closingValueReturnFactor: .00001 };
      report.holdings = [{ type: 'security', uuid, quoteCurrency: 'GBX', quoteDate: '2026-11-04',
        shares: 2e8, value: 100000, valuationFxRate: 1 }];
      market.fundsmith.date = report.today;
      market.fundsmith.price = '687.42';
      const snapshot = () => {
        renderOverviewLive();
        return { total: document.querySelector('#total-value').textContent,
          gain: document.querySelector('#day-gain').textContent,
          rate: document.querySelector('#day-return').textContent,
          status: document.querySelector('#day-live-status').textContent };
      };
      const current = snapshot();
      market.fundsmith.date = '2026-11-03';
      const older = snapshot();
      market.fundsmith.date = report.today;
      report.asOf = '2026-11-04';
      const historical = snapshot();
      report.asOf = report.today;
      market.liveQuotes = { mode: 'ibkr', available: true, connected: true, generatedAt: new Date().toISOString(),
        quotes: [{ stale: false, quote: { key: uuid, currency: 'GBX', conId: 123, status: 'active',
          marketDataType: 'real_time', last: 700, receivedAt: new Date().toISOString() } }] };
      const broker = snapshot();
      return { current, older, historical, broker,
        expectedCurrent: money(137484), expectedGain: money(37684, true),
        expectedSaved: money(100000), expectedBroker: money(140000) };
    });
    assert.equal(performance.current.total, performance.expectedCurrent);
    assert.equal(performance.current.gain, performance.expectedGain);
    assert.match(performance.current.status, /Fundsmith NAV dated/);
    assert.equal(performance.older.total, performance.expectedSaved, 'older NAV cannot reprice the position');
    assert.equal(performance.historical.total, performance.expectedSaved, 'historical valuation stays saved');
    assert.equal(performance.broker.total, performance.expectedBroker, 'mapped IBKR quote takes precedence');
    console.log('Fundsmith NAV affects today’s indicative total and gain, with dated, historical and IBKR fallbacks.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
