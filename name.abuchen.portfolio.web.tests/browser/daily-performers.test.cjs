#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  let page;
  try {
    page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let security, currency, dailyRequests = 0, price = 110, stale = false;
    await page.route('**/api/dashboard*', async route => {
      const response = await route.fetch(), data = await response.json();
      security = data.holdings.find(row => row.type === 'security'); currency = data.currency;
      data.holdings = [{ ...security, shares: 1e8, quoteCurrency: currency, value: 10000, valuationFxRate: 1 }];
      if (new URL(route.request().url()).searchParams.get('rankings') === 'day') {
        dailyRequests++;
        const uuid = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
        data.dailyRankings = { securities: [
          { uuid: security.uuid, name: 'Alpha', gain: 300, rate: .03, closingValueReturnFactor: .0001 },
          ...Array.from({ length: 5 }, (_, i) => ({ uuid: uuid(i + 1), name: `Gain ${i}`, gain: (i + 2) * 100, rate: .01 })),
          ...Array.from({ length: 6 }, (_, i) => ({ uuid: uuid(i + 10), name: `Loss ${i}`, gain: -(i + 1) * 100, rate: -.01 })),
          { uuid: uuid(99), name: 'Unavailable', gain: null, rate: null }
        ], unavailable: 1, neutral: 0 };
      } else data.dailyRankings = null;
      await route.fulfill({ response, json: data });
    });
    await page.route('**/api/price-updates', async route => {
      const now = new Date().toISOString();
      await route.fulfill({ json: { observedAt: now, liveQuotes: { configured: true, available: true, connected: true,
        mode: 'ibkr', state: 'connected', generatedAt: now, quotes: [{ stale, quote: { key: security?.uuid,
          currency, symbol: 'TEST', conId: 123, status: 'active', marketDataType: 'delayed', last: price, receivedAt: now } }] } } });
    });
    await page.goto(`${base}/#/overview`, { waitUntil: 'networkidle' });
    const toggle = page.locator('#daily-performers-toggle'), panel = page.locator('#daily-performers-panel');
    assert(await panel.isHidden()); assert.equal(dailyRequests, 0, 'closed panel does not calculate rankings');
    await toggle.click();
    await page.locator('#daily-gainers-body tr').first().getByRole('link', { name: 'Alpha' }).waitFor();
    assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
    assert.equal(await page.locator('#daily-gainers-body tr').count(), 5);
    assert.equal(await page.locator('#daily-losers-body tr').count(), 5);
    assert.equal(await page.locator('#daily-gainers-body tr').first().locator('td').last().innerText(),
      await page.evaluate(() => money(1300, true)));
    assert.equal(await page.locator('#daily-gainers-body tr').first().locator('td').nth(1).innerText(), await page.evaluate(() => percent(.13)));
    assert.match(await page.locator('#daily-performers-status').innerText(), /Daily change is time-weighted return/);
    assert.match(await page.locator('#daily-performers-status').innerText(), /1 securities unavailable/);
    price = 50;
    await page.locator('#daily-losers-body tr').first().getByRole('link', { name: 'Alpha' }).waitFor();
    assert.equal(await page.locator('#daily-losers-body tr').first().locator('td').last().innerText(),
      await page.evaluate(() => money(-4700, true)));
    assert.equal(await page.locator('#daily-losers-body tr').first().locator('td').nth(1).innerText(), await page.evaluate(() => percent(-.47)), 'daily return follows live quotes');
    assert.equal(dailyRequests, 1, 'ticks reuse the native daily baseline');
    stale = true;
    await page.waitForFunction(() => document.querySelector('#day-live-status').textContent.includes('stale data'));
    assert.match(await page.locator('#daily-losers-body').innerText(), /Alpha/, 'stale last quote remains in use');
    await page.evaluate(() => { report.asOf = '2000-01-01'; renderOverviewLive(); });
    assert.match(await page.locator('#daily-performers-status').innerText(), /saved historical prices/);
    assert.equal(await page.locator('#daily-gainers-body tr').filter({ hasText: 'Alpha' }).locator('td').last().innerText(),
      await page.evaluate(() => money(300, true)));
    await page.locator('#daily-gainers-body').getByRole('link', { name: 'Alpha' }).click();
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    await page.locator('[data-route="overview"]').click();
    await page.locator('#daily-gainers-body').getByRole('link', { name: 'Alpha' }).waitFor();
    await page.waitForFunction(() => report?.dailyRankings != null);
    assert(await panel.isVisible(), 'open state survives navigation');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.evaluate(() => { report.dailyRankings.securities = []; renderOverviewLive(); });
    assert.match(await page.locator('#daily-gainers-body').innerText(), /No gains/);
    assert.match(await page.locator('#daily-losers-body').innerText(), /No losses/);
    await toggle.focus(); await page.keyboard.press('Enter');
    assert(await panel.isHidden()); assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    assert.deepEqual(errors, []);
    console.log('Daily performers: lazy expansion, top/bottom five, live reordering, stale quote retention, historical fallback, empty states, drilldown, mobile and keyboard passed.');
  } finally { if (page) await page.unrouteAll({ behavior: 'ignoreErrors' }); await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
