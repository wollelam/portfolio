#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
    let security, currency, price = 100;
    await page.route('**/api/dashboard*', async route => {
      const response = await route.fetch(), data = await response.json();
      security = data.holdings.find(h => h.type === 'security'); currency = data.currency;
      Object.assign(security, { value: 10000, shares: 1e8, quoteCurrency: currency, valuationFxRate: 1 });
      await route.fulfill({ response, json: data });
    });
    await page.route('**/api/price-updates', async route => {
      const now = new Date().toISOString();
      await route.fulfill({ json: { observedAt: now, liveQuotes: { available: true, connected: true, mode: 'ibkr', generatedAt: now,
        quotes: [{ stale: false, quote: { key: security?.uuid, currency, conId: 123, status: 'active',
          marketDataType: 'real_time', last: price, receivedAt: now } }] } } });
    });
    await page.goto(`${base}/#/overview`, { waitUntil: 'networkidle' });
    const toggle = page.getByRole('button', { name: 'Discreet mode', exact: true });
    await page.waitForFunction(() => document.querySelector('#total-value-label').textContent.includes('IBKR'));
    assert.match(await page.locator('#total-value').innerText(), /\d/);
    await toggle.click();
    assert.equal(await page.locator('#total-value').innerText(), '••••••');
    assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#total-value').getAttribute('aria-label'), 'Portfolio value hidden');
    await page.evaluate(() => {
      window.totalLeaks = [];
      new MutationObserver(() => {
        if (localStorage.getItem('portfolio.discreetMode') === 'true' && /\d/.test(document.querySelector('#total-value').textContent)) totalLeaks.push('visible amount');
      }).observe(document.querySelector('#total-value'), { childList: true, subtree: true, characterData: true });
    });
    price = 120;
    await page.waitForFunction(() => market.liveQuotes.quotes[0].quote.last === 120);
    assert.equal(await page.locator('#total-value').innerText(), '••••••');
    assert.equal(await page.locator('#total-value').getAttribute('data-value'), '');
    assert(!/quote-up|quote-down/.test(await page.locator('#total-value').getAttribute('class')));
    assert.match(await page.locator('#day-gain').innerText(), /\d/);
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !busy);
    assert.deepEqual(await page.evaluate(() => totalLeaks), []);
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('#total-value').innerText(), '••••••');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await toggle.focus(); await page.keyboard.press('Space');
    assert.equal(await toggle.getAttribute('aria-pressed'), 'false');
    await page.waitForFunction(() => document.querySelector('#total-value').textContent === money(report.totalValue + 2000));
    await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#total-value').innerText(), /\d/);
    const blocked = await browser.newPage(); blocked.on('pageerror', e => errors.push(e.message));
    await blocked.addInitScript(() => Object.defineProperty(window, 'localStorage', { get() { throw new Error('storage blocked'); } }));
    await blocked.goto(`${base}/#/overview`, { waitUntil: 'networkidle' });
    await blocked.getByRole('button', { name: 'Discreet mode', exact: true }).click();
    assert.equal(await blocked.locator('#total-value').innerText(), '••••••');
    assert.deepEqual(errors, []);
    console.log('Discreet mode: masking, ticks, refresh, persistence, keyboard, mobile and blocked storage passed.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
