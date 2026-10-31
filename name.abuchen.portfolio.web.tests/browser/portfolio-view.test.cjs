#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const dashboardViews = [];
    await page.route('**/api/dashboard**', async route => {
      const url = new URL(route.request().url());
      dashboardViews.push(url.searchParams.get('view'));
      const fixtureUrl = new URL(url); fixtureUrl.searchParams.set('view', 'ole');
      const response = await route.fetch({ url: fixtureUrl.toString() }), data = await response.json();
      data.views = ['ole', 'ying', 'combined'];
      data.quoteKeys = {};
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.goto(`${base}/#/holdings?view=ole&from=2025-01-01&to=2025-12-31`, { waitUntil: 'networkidle' });
    const selector = page.locator('#view-selector');
    assert.equal(await selector.locator('option').count(), 3);
    await selector.selectOption('ying');
    await page.waitForFunction(() => location.hash.includes('view=ying'));
    await page.waitForFunction(() => document.querySelector('#dashboard')?.getAttribute('aria-busy') === 'false');
    assert.match(page.url(), /#\/holdings\?/);
    assert.match(page.url(), /view=ying/);
    assert.match(page.url(), /from=2025-01-01/);
    assert.match(page.url(), /to=2025-12-31/);
    assert(dashboardViews.length >= 2 && dashboardViews.every(view => view === 'ole' || view === 'ying'), 'each dashboard request carries the selected view');
    await page.evaluate(() => fetchAllocationFrames('2025-12-30'));
    assert(dashboardViews.includes('ying'), 'frame requests carry the selected view');
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await selector.inputValue(), 'ying', 'view selection restores from the hash');
    console.log('Portfolio view selector, URL persistence, and dashboard/frame request parameters passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
