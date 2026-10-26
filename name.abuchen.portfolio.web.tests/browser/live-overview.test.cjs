#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8097';

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    let security, mode = 'valid', price = 100;
    await page.route('**/api/dashboard*', async route => {
      const response = await route.fetch(), data = await response.json();
      security = data.holdings.find(h => h.type === 'security');
      if (security) security.valuationFxRate = 2;
      await route.fulfill({ response, json: data });
    });
    await page.route('**/api/price-updates', async route => {
      if (mode === 'failure') { await route.fulfill({ status: 503, json: {} }); return; }
      const now = new Date().toISOString();
      const quote = { key: security?.uuid, name: 'Test', currency: mode === 'currency' ? 'XXX' : security?.quoteCurrency,
        conId: 42, last: price, status: mode === 'permission' ? 'not_subscribed' : 'active',
        marketDataType: 'delayed', receivedAt: now, history: [] };
      const entry = { quote, stale: mode === 'stale' };
      await route.fulfill({ json: { observedAt: now, liveQuotes: { available: true, connected: true,
        mode: mode === 'demo' ? 'demo' : 'ibkr', generatedAt: now, quotes: mode === 'duplicate' ? [entry, entry] : [entry] } } });
    });
    await page.goto(`${base}/#/overview`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('#total-value-label').textContent.includes('IBKR'));
    const saved = await page.evaluate(() => ({ total: report.totalValue, gain: report.day.gain, series: JSON.stringify(report.series) }));
    const expected = p => page.evaluate(({ p, key }) => {
      const h = report.holdings.find(h => h.uuid === key);
      return money(report.totalValue - h.value + Math.round(p * h.shares / 1e8 * 2 * 100));
    }, { p, key: security.uuid });
    const assertDaily = async p => {
      const expectedDaily = await page.evaluate(({ p, key }) => {
        const h = report.holdings.find(h => h.uuid === key);
        const delta = Math.round(p * h.shares / 1e8 * 2 * 100) - h.value;
        return { gain: money(report.day.gain + delta, true), rate: percent(report.day.rate + delta * report.day.closingValueReturnFactor) };
      }, { p, key: security.uuid });
      assert.equal(await page.locator('#day-gain').innerText(), expectedDaily.gain);
      assert.equal(await page.locator('#day-return').innerText(), expectedDaily.rate);
    };
    assert.equal(await page.locator('#total-value').innerText(), await expected(100));
    await assertDaily(100);
    assert.match(await page.locator('#day-dates').innerText(), /intraday estimate/);
    assert.match(await page.locator('#total-date').innerText(), /1 delayed/);
    price = 103;
    await page.waitForFunction(value => document.querySelector('#total-value').textContent === value, await expected(103));
    await assertDaily(103);
    assert.match(await page.locator('#total-value').getAttribute('class'), /quote-up/);
    price = 101;
    await page.waitForFunction(value => document.querySelector('#total-value').textContent === value, await expected(101));
    await assertDaily(101);
    assert.match(await page.locator('#total-value').getAttribute('class'), /quote-down/);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    assert.equal(await page.locator('#total-value').evaluate(el => getComputedStyle(el).animationName), 'none');
    for (const value of ['stale', 'currency', 'duplicate', 'demo', 'permission', 'failure']) {
      mode = value;
      await page.waitForFunction(() => document.querySelector('#total-value').textContent === money(report.totalValue));
      assert.equal(await page.locator('#total-value-label').innerText(), 'Total portfolio value');
      assert.equal(await page.locator('#day-gain').innerText(), await page.evaluate(() => money(report.day.gain, true)));
      assert.equal(await page.locator('#day-return').innerText(), await page.evaluate(() => percent(report.day.rate)));
      mode = 'valid';
      await page.waitForFunction(() => document.querySelector('#total-value-label').textContent.includes('IBKR'));
    }
    await page.evaluate(() => { report.holdings.forEach(h => h.valuationFxRate = null); renderOverviewLive(); });
    assert.equal(await page.locator('#total-value').innerText(), await page.evaluate(() => money(report.totalValue)));
    await page.evaluate(() => { report.holdings.forEach(h => h.valuationFxRate = 2); report.asOf = '2000-01-01'; renderOverviewLive(); });
    assert.equal(await page.locator('#total-value').innerText(), await page.evaluate(() => money(report.totalValue)));
    assert.equal(await page.locator('#day-gain').innerText(), await page.evaluate(() => money(report.day.gain, true)));
    assert.deepEqual(await page.evaluate(() => ({ total: report.totalValue, gain: report.day.gain, series: JSON.stringify(report.series) })), saved);
    assert.deepEqual(errors, []);
    console.log('Live overview: converted totals, tick updates, safe fallbacks, historical dates and saved performance passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
