#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let shape = 'normal';
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(); const data = await response.json();
      const params = new URL(route.request().url()).searchParams;
      if (params.get('from') === '2025-01-14') {
        data.series = shape === 'empty' ? [] : [
          { date: '2025-01-14', value: 10000, rate: 0 },
          { date: '2025-01-31', value: 22000, rate: .1 },
          { date: '2025-02-28', value: 26400, rate: .32 },
          { date: '2025-03-15', value: 21120, rate: .056 }
        ];
        if (shape === 'zero-base') data.series[1].rate = -1;
        if (shape === 'missing') data.series[1].rate = null;
      }
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.goto(`${process.env.BASE_URL || 'http://127.0.0.1:8097'}/#/returns?from=2025-01-14&to=2025-03-15`, { waitUntil: 'networkidle' });
    const row = page.locator('#returns-body tr').first();
    const months = row.locator('button');
    assert.equal(await months.count(), 4, 'three selected months plus selected-year period');
    assert.match(await months.nth(0).innerText(), /10(?:[.,]0+)?%\*/, 'partial opening month');
    assert.match(await months.nth(1).innerText(), /20(?:[.,]0+)?%$/, 'compounds cumulative endpoints, does not subtract returns');
    assert.match(await months.nth(2).innerText(), /20(?:[.,]0+)?%\*/, 'negative partial closing month');
    assert.match(await months.nth(3).innerText(), /5[.,]60?%\*/, 'year selected-period compounded return');
    assert.equal(await row.locator('.return-outside').count(), 9);
    await months.nth(1).click();
    await page.waitForFunction(() => location.hash.startsWith('#/performance') && document.querySelector('#from').value === '2025-01-31');
    assert.equal(await page.locator('#to').inputValue(), '2025-02-28');
    await page.goBack();
    await page.locator('#returns-body .return-cell').first().waitFor();
    assert.equal(await page.locator('#from').inputValue(), '2025-01-14');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'heatmap scrolls internally');
    await page.waitForTimeout(250);
    await page.locator('#returns-calendar .table-scroll').evaluate(element => { element.scrollLeft = 300; });
    await page.locator('[data-route="overview"]').click();
    await page.locator('#total-value').waitFor({ state: 'visible' });
    await page.goBack();
    await page.locator('#returns-calendar').waitFor({ state: 'visible' });
    await page.waitForTimeout(300);
    assert.equal(await page.locator('#returns-calendar .table-scroll').evaluate(element => element.scrollLeft), 300, 'Back restores horizontal calendar position');
    shape = 'zero-base'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await months.nth(1).innerText(), 'N/A', '100 percent loss cannot be used as a divisor');
    shape = 'missing'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await months.nth(0).innerText(), 'N/A');
    assert.equal(await months.nth(1).innerText(), 'N/A');
    shape = 'empty'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#returns-body').innerText(), /No daily performance/);
    assert.deepEqual(errors, []);
    console.log('Returns calendar checks passed: compounding, partial periods, missing data, zero denominator, month drilldown and Back, empty state and mobile layout.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
