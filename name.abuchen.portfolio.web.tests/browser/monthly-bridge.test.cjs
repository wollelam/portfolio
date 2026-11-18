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
      const data = await response.json();
      const params = new URL(route.request().url()).searchParams;
      if (params.get('from') === '2025-01-15' && !params.has('waterfall')) {
        data.series = [
          { date: '2025-01-15', value: 10000, investedCapital: 10000, rate: 0 },
          { date: '2025-01-31', value: 14500, investedCapital: 13000, rate: .12 },
          { date: '2025-02-01', value: 14000, investedCapital: 13000, rate: .08 },
          { date: '2025-02-28', value: 11900, investedCapital: 11000, rate: .02 },
          { date: '2025-03-06', value: 12100, investedCapital: 11000, rate: .04 }
        ];
      }
      await route.fulfill({ response, json: data });
    });
    await page.goto(base + '/#/monthly-bridge?view=primary&currency=CHF&from=2025-01-15&to=2025-03-06');
    const months = page.locator('#monthly-bridge-months > li');
    await months.first().waitFor();
    assert.equal(await months.count(), 3);
    assert.deepEqual(await months.locator('h2').allTextContents(), ['March 2025*', 'February 2025', 'January 2025*']);
    const expected = [[11900, 12100, 0, 200], [14500, 11900, -2000, -600], [10000, 14500, 3000, 1500]];
    for (const [index, values] of expected.entries()) {
      const amounts = await page.evaluate(values => values.map((value, index) => money(value, index >= 2)), values);
      assert.deepEqual(await months.nth(index).locator('dd').allTextContents(), amounts);
    }
    const january = months.last();
    assert.equal(await january.locator('[data-bridge-metric="flows"] .positive').evaluate(bar => bar.style.width), '50%');
    const february = months.nth(1);
    assert(await february.locator('[data-bridge-metric="flows"] .negative').evaluate(bar => parseFloat(bar.style.left) < 50));
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await february.getByRole('link').click();
    await page.waitForFunction(() => currentRoute === 'waterfall' && !busy && report?.waterfall);
    const params = new URLSearchParams(page.url().split('?')[1]);
    assert.equal(params.get('from'), '2025-01-31');
    assert.equal(params.get('to'), '2025-02-28');
    assert.equal(params.get('currency'), 'CHF');
    assert.equal(params.get('view'), 'primary');
    await page.goBack();
    await months.first().waitFor();
    assert.equal(await months.count(), 3);
    assert.deepEqual(errors, []);
    console.log('Monthly bridge: opening and closing values, signed flows and gains, partial months, mobile and waterfall drill-down passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
