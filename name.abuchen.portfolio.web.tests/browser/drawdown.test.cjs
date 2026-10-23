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
      const response = await route.fetch(), data = await response.json();
      data.drawdown = { maximum: shape === 'flat' ? 0 : .2, current: shape === 'flat' ? 0 : -.1, longestWithoutNewHigh: 17,
        peakDate: '2025-01-01', troughDate: '2025-01-18', series: shape === 'empty' ? [] : [
          { date: '2025-01-01', rate: 0 }, { date: '2025-01-18', rate: shape === 'flat' ? 0 : -.2 }, { date: '2025-01-31', rate: shape === 'flat' ? 0 : -.1 }
        ] };
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.goto(`${process.env.BASE_URL || 'http://127.0.0.1:8097'}/#/drawdown`, { waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-maximum').innerText(), /20%/);
    assert.match(await page.locator('#drawdown-current').innerText(), /10%/);
    assert.equal(await page.locator('#drawdown-duration').innerText(), '17 calendar days');
    assert.match(await page.locator('#drawdown-summary').innerText(), /peak.*trough/);
    const chart = page.locator('#drawdown-chart');
    const zero = await chart.locator('.zero-line').getAttribute('y1');
    assert((await chart.locator('.chart-area').getAttribute('d')).endsWith(`,${zero} Z`), 'drawdown area fills to the zero peak baseline');
    await chart.focus(); await chart.press('Home');
    const first = await page.locator('#drawdown-detail').innerText();
    await chart.press('End'); assert.notEqual(await page.locator('#drawdown-detail').innerText(), first);
    await chart.press('ArrowLeft'); const selected = await page.locator('#drawdown-detail').innerText();
    assert.match(selected, /20%/);
    await page.locator('[data-route="overview"]').click();
    await page.locator('#total-value').waitFor({ state: 'visible' });
    await page.goBack(); await chart.waitFor({ state: 'visible' });
    assert.equal(await page.locator('#drawdown-detail').innerText(), selected, 'Back restores inspected date');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    shape = 'flat'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-summary').innerText(), /No decline/);
    shape = 'empty'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-detail').innerText(), /No drawdown data/);
    assert.deepEqual(errors, []);
    console.log('Drawdown checks passed: decline metrics, shared chart keyboard controls, selection restored on Back, flat/empty periods and mobile layout.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
