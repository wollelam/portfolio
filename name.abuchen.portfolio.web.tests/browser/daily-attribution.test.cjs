'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 850 } });
    const errors = [];
    let security, today, live = false;
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/dashboard?*', async route => {
      const response = await route.fetch();
      const data = await response.json();
      assert.equal(new URL(route.request().url()).searchParams.get('attribution'), 'true');
      assert(data.dailyAttribution?.eligible.length);
      security = data.holdings.find(item => item.type === 'security').uuid;
      today = data.today;
      data.day.gain = -1000;
      data.holdings = [
        { uuid: security, type: 'security', name: 'Test security', quoteCurrency: 'USD', quoteDate: today, shares: 2e8, value: 17600, valuationFxRate: .8 },
        { type: 'cash', name: 'Test cash', quoteCurrency: 'USD', value: 4000, valuationFxRate: .8 }
      ];
      data.totalValue = 21600;
      data.dailyAttribution = { positions: 3, eligible: [
        { uuid: security, quoteCurrency: 'USD', quoteDate: today, shares: 2e8, openingPrice: 100, closingPrice: 110, openingFxRate: .9, closingFxRate: .8 },
        { uuid: null, quoteCurrency: 'USD', shares: 1e8, openingPrice: 50, closingPrice: 50, openingFxRate: .9, closingFxRate: .8 },
        { uuid: null, quoteCurrency: 'NOK', shares: 1e8, openingPrice: 30, closingPrice: 30, openingFxRate: .1, closingFxRate: null }
      ] };
      await route.fulfill({ response, json: data });
    });
    await page.route('**/api/price-updates', route => {
      const now = new Date().toISOString();
      const quote = fields => ({ stale: false, quote: { status: 'active', marketDataType: 'real_time', receivedAt: now, ...fields } });
      const quotes = live ? [
        quote({ key: security, symbol: 'TEST', currency: 'USD', conId: 1, last: 120 }),
        quote({ key: 'fx:USD:EUR', symbol: 'USD', currency: 'EUR', secType: 'CASH', conId: 2, bid: .85, ask: .85 })
      ] : [];
      return route.fulfill({ json: { observedAt: now, liveQuotes: { mode: 'ibkr', available: live, connected: live, generatedAt: now, quotes } } });
    });
    await page.goto(base + '/#/overview');
    const expected = async amounts => {
      const ids = ['price', 'fx', 'other', 'total'];
      const values = await page.evaluate(values => values.map(value => money(value, true)), amounts);
      await page.waitForFunction(total => document.querySelector('#day-breakdown-total').textContent === total, values.at(-1));
      for (const [index, id] of ids.entries()) assert.equal(await page.locator('#day-breakdown-' + id).innerText(), values[index]);
      assert.equal(await page.locator('#day-gain').innerText(), values.at(-1));
    };
    await page.waitForFunction(() => !!report && !busy);
    await expected([1800, -2700, -100, -1000]);
    assert.match(await page.locator('#day-breakdown-coverage').innerText(), /2 of 3/);
    live = true;
    await expected([3600, -1450, -100, 2050]);
    assert.match(await page.locator('#day-breakdown-coverage').innerText(), /2 of 3/);
    await page.evaluate(() => { report.dailyAttribution.eligible = []; renderOverviewLive(); });
    assert.equal(await page.locator('#day-breakdown-price').innerText(), '—');
    assert.equal(await page.locator('#day-breakdown-fx').innerText(), '—');
    assert.equal(await page.locator('#day-breakdown-other').innerText(), await page.locator('#day-gain').innerText());
    await page.locator('.daily-breakdown summary').click();
    assert.equal(await page.locator('.daily-breakdown details').getAttribute('open'), '');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(errors, []);
    console.log('Daily attribution: saved and live price/FX, cash, missing coverage and reconciliation passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
