'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 850 }, locale: 'en-US' });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/#/portfolio-comparison');
    await page.getByText('Portfolio comparison requires both a Primary and a Secondary portfolio.').waitFor();
    const initial = await page.evaluate(() => ({ from: report.period.openingDate, to: report.period.closingDate }));
    await page.route('**/api/dashboard?*', async route => {
      const response = await route.fetch(), data = await response.json();
      const params = new URL(route.request().url()).searchParams;
      assert.equal(params.get('comparison'), 'true');
      assert.equal(params.get('currency'), 'CHF');
      assert.equal(params.get('from'), initial.from);
      assert.equal(params.get('to'), initial.to);
      data.views = ['primary', 'secondary', 'combined'];
      data.comparison = [
        { view: 'primary', openingValue: 10000, closingValue: 12000, period: { gain: 1000, netFlows: 1000, rate: .1 }, maximumDrawdown: .1 },
        { view: 'secondary', openingValue: 6000, closingValue: 5200, period: { gain: -800, netFlows: 0, rate: -.13 }, maximumDrawdown: .15 },
        { view: 'combined', openingValue: 16000, closingValue: 17200, period: { gain: 200, netFlows: 1000, rate: .02 }, maximumDrawdown: .05 }
      ];
      await route.fulfill({ response, json: data });
    });
    await page.goto(`${base}/#/portfolio-comparison?currency=CHF&from=${initial.from}&to=${initial.to}`);
    const table = page.getByRole('table', { name: 'Primary, Secondary and Combined portfolio comparison' });
    await table.waitFor();
    assert.equal(await table.getByRole('columnheader').count(), 4);
    const returnRow = table.getByRole('row').filter({ has: page.getByRole('rowheader', { name: 'Return', exact: true }) });
    assert.deepEqual(await returnRow.getByRole('cell').allTextContents(), ['+10%', '-13%', '+2%']);
    const drawdown = table.getByRole('row').filter({ has: page.getByRole('rowheader', { name: 'Maximum drawdown', exact: true }) });
    assert.deepEqual(await drawdown.getByRole('cell').allTextContents(), ['-10%', '-15%', '-5%']);
    assert(await page.locator('#view-selector').isHidden());
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(errors, []);
    console.log('Portfolio comparison: shared dates and currency, pooled metrics, single-source state and mobile passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
