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
    await page.route('**/api/dashboard?*', async route => {
      const response = await route.fetch();
      const report = await response.json();
      const params = new URL(route.request().url()).searchParams;
      assert.equal(params.get('income'), 'true');
      assert(report.dividendIncome);
      const payment = (date, security, net) => ({
        date, security, account: 'Cash', originalCurrency: report.currency, originalNet: net,
        gross: net, taxes: 0, fees: 0, net
      });
      report.dividends = [
        payment('2025-02-07', 'Alpha', 1000),
        payment('2025-02-11', 'Beta', 500),
        payment('2025-04-02', 'Alpha', 2300),
        payment('2025-05-08', 'Correction', -200)
      ];
      report.dividendIncome.net = 18000;
      await route.fulfill({ response, json: report });
    });
    await page.goto(base + '/#/dividends?from=2025-01-31&to=2025-05-12');
    const months = page.locator('#dividend-months button');
    await months.first().waitFor();
    assert.equal(await months.count(), 4, 'the opening valuation month is excluded when the first day is in February');
    assert.equal(await page.locator('#dividend-period-net').innerText(), await page.evaluate(() => money(3600)));
    assert.equal(await page.locator('#dividend-trailing-net').innerText(), await page.evaluate(() => money(18000)));
    assert(await months.nth(1).isDisabled(), 'a month without payments remains visible');
    assert.equal(await page.locator('#dividend-months .dividend-bar.negative').count(), 1);
    const payers = page.locator('#dividend-payers button');
    assert.equal(await payers.count(), 2);
    assert.match(await payers.first().innerText(), /^Alpha/);
    await months.first().click();
    assert.equal(await months.first().getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#dividends-body tr').count(), 2);
    await payers.first().click();
    assert.equal(await payers.first().getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#dividends-body tr').count(), 1);
    assert.match(await page.locator('#dividend-payment-summary').innerText(), /1 of 4 payments.*February 2025.*Alpha/);
    await months.last().click();
    assert.match(await page.locator('#dividends-body').innerText(), /No payments match/);
    await page.locator('#dividend-clear').click();
    assert.equal(await page.locator('#dividends-body tr').count(), 4);
    assert(await page.locator('#dividend-clear').isHidden());
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(errors, []);
    console.log('Dividend income: totals, signed months, gap months, top payers and combined ledger filters passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
