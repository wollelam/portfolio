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
    await page.goto(base + '/#/allocation?group=asset-class');
    await page.getByText('No Asset Classes targets are saved in this portfolio.', { exact: true }).waitFor();
    let negative = false;
    await page.route('**/api/dashboard?*', async route => {
      const response = await route.fetch(), data = await response.json();
      if (new URL(route.request().url()).searchParams.get('exposure') === 'asset-class') {
        const security = data.holdings.find(item => item.type === 'security');
        const equityValue = 30000, bondsValue = negative ? -40000 : 70000;
        data.exposure = [
          { group: 'class:Equity', label: 'Equity', name: 'Example equity', uuid: security.uuid, value: equityValue },
          { group: 'class:Bonds', label: 'Bonds', name: 'Example bonds', value: bondsValue },
          { group: 'unclassified', label: 'Unclassified', name: 'Other', value: 100000 }
        ];
        data.totalValue = equityValue + bondsValue + 100000;
        data.allocationTargets = { categories: [
          { group: 'class:Equity', label: 'Equity', weight: .7 },
          { group: 'class:Bonds', label: 'Bonds', weight: .2 },
          { group: 'class:Cash', label: 'Cash', weight: .1 }
        ] };
      }
      await route.fulfill({ response, json: data });
    });
    await page.reload();
    const drift = page.locator('#allocation-drift');
    const rows = drift.locator('details');
    await rows.first().waitFor();
    const expected = [['20%', '70%', '+50 pp'], ['70%', '30%', '-40 pp'], ['10%', '0%', '-10 pp']];
    for (const [index, values] of expected.entries()) assert.deepEqual(await rows.nth(index).locator('dd').allTextContents(), values);
    await rows.nth(1).locator('summary').click();
    assert(await rows.nth(1).getByRole('link', { name: 'Example equity' }).isVisible());
    await rows.nth(2).locator('summary').click();
    assert.match(await rows.nth(2).innerText(), /No holdings in this class/);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    negative = true;
    await page.locator('#refresh').click();
    await page.getByText(/Actual weights are unavailable because classified net value is zero or negative/).waitFor();
    assert.deepEqual(await drift.locator('[data-drift-metric="gap"] dd').allTextContents(), ['Unavailable', 'Unavailable', 'Unavailable']);
    await page.locator('#allocation-grouping').selectOption('account');
    assert(await drift.isHidden());
    assert.deepEqual(errors, []);
    console.log('Allocation drift: saved targets, descending absolute drift, class drill-down, missing targets, non-positive classified value and mobile passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
