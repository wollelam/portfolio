#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    let shape = 'mixed';
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(), data = await response.json();
      const security = data.holdings.find(item => item.uuid);
      const position = (name, value, type = 'security') => ({ ...security, name, value, type, uuid: type === 'cash' ? null : security.uuid });
      if (shape === 'mixed') data.holdings = [position('Alpha', 60000), position('Cash', 20000, 'cash'), position('Short', -10000), position('Beta', 30000)];
      if (shape === 'crowded') data.holdings = Array.from({ length: 48 }, (_, i) => position(`Crowded ${String(i).padStart(2, '0')}`, (i % 2 ? -1 : 1) * (i + 1) * 100, i % 3 === 0 ? 'cash' : 'security'));
      if (shape === 'zero-net') data.holdings = [position('Long', 10000), position('Short', -10000)];
      if (shape === 'single') data.holdings = [position('Single', 10000)];
      if (shape === 'empty') data.holdings = [];
      data.totalValue = data.holdings.reduce((sum, item) => sum + item.value, 0);
      data.holdings.forEach(item => { item.weight = data.totalValue ? item.value / data.totalValue : null; });
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    assert.equal(await page.locator('.allocation-tile').count(), 4);
    assert.equal(await page.locator('#allocation-largest').innerText(), 'Alpha');
    assert.match(await page.locator('#allocation-largest-weight').innerText(), /50% of gross/);
    assert.match(await page.locator('#allocation-cash-weight').innerText(), /20% of net/);
    assert.equal(await page.locator('#allocation-top-five').innerText(), '100%');
    assert(await page.locator('#date-controls').isHidden(), 'allocation has no date range');
    const originalRange = await page.locator('#from').inputValue();
    const latest = await page.locator('#allocation-date').inputValue();
    const selectedDate = new Date(Date.parse(`${latest}T12:00:00Z`) - 30 * 86400000).toISOString().slice(0, 10);
    await page.locator('#allocation-position').evaluate(slider => { slider.value = Number(slider.max) - 30; slider.dispatchEvent(new Event('input', { bubbles: true })); slider.dispatchEvent(new Event('change', { bubbles: true })); });
    await page.waitForFunction(date => report.asOf === date && !document.querySelector('#allocation-date').disabled, selectedDate);
    assert.equal(await page.locator('#from').inputValue(), originalRange, 'scrubbing preserves performance range');
    assert(page.url().includes(`asof=${selectedDate}`));
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('#allocation-date').inputValue(), selectedDate, 'reload restores allocation date');

    const areas = await page.locator('#allocation-chart').evaluate(svg => {
      const [,,width,height] = svg.getAttribute('viewBox').split(' ').map(Number);
      return [...svg.querySelectorAll('.allocation-tile')].map(group => {
        const rect = group.querySelector('rect');
        return { value: Number(group.dataset.allocationValue), share: (Number(rect.getAttribute('width')) + 4) * (Number(rect.getAttribute('height')) + 4) / (width * height) };
      });
    });
    for (const tile of areas) assert(Math.abs(tile.share - Math.abs(tile.value) / 120000) < 1e-9, 'area represents absolute exposure');
    const negative = page.locator('.allocation-tile[data-allocation-value="-10000"]');
    assert.match(await negative.locator('rect').getAttribute('fill'), /allocation-negative/);
    await negative.focus(); assert.match(await page.locator('#allocation-detail').innerText(), /Short.*8[.,]3% of gross.*-10% net/);
    await page.locator('.allocation-tile[data-allocation-value="60000"]').focus();
    await page.keyboard.press('Enter');
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    await page.locator('#instrument-back').click();
    await page.locator('.allocation-tile').first().waitFor();
    assert(page.url().includes('#/allocation'));
    assert.equal(await page.locator('#allocation-date').inputValue(), selectedDate, 'Back restores allocation date');
    shape = 'crowded'; await page.reload({ waitUntil: 'networkidle' });
    const tiles = await page.locator('.allocation-tile').evaluateAll(groups => groups.map(group => ({ value: Number(group.dataset.allocationValue), label: group.getAttribute('aria-label') })));
    assert.equal(tiles.length, 24, '20 largest plus four sign/type groups');
    const all = Array.from({ length: 48 }, (_, i) => (i % 2 ? -1 : 1) * (i + 1) * 100);
    assert.equal(tiles.reduce((sum, item) => sum + item.value, 0), all.reduce((a, b) => a + b, 0), 'grouping preserves net value');
    assert.equal(tiles.reduce((sum, item) => sum + Math.abs(item.value), 0), all.reduce((a, b) => a + Math.abs(b), 0), 'grouping preserves gross exposure');
    assert(tiles.some(item => item.label.includes('Other negative cash positions')));
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'allocation scrolls internally on mobile');
    shape = 'zero-net'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#allocation-cash-weight').innerText(), /unavailable at zero value/);
    await page.locator('.allocation-tile').first().focus();
    assert.match(await page.locator('#allocation-detail').innerText(), /net weight unavailable/);
    shape = 'single'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('.allocation-tile').count(), 1);
    assert.equal(await page.locator('#allocation-top-five').innerText(), '100%');
    shape = 'empty'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('.allocation-tile').count(), 0);
    assert.match(await page.locator('#allocation-detail').innerText(), /No non-zero holdings/);
    // Use the actual synthetic demo engine to verify historical valuation changes, rather than mocked tiles.
    const native = await browser.newPage();
    await native.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    const currentValue = await native.evaluate(() => report.totalValue);
    await native.locator('#allocation-position').evaluate(slider => { slider.value = Number(slider.max) - 30; slider.dispatchEvent(new Event('input', { bubbles: true })); slider.dispatchEvent(new Event('change', { bubbles: true })); });
    await native.waitForFunction(() => report.asOf === document.querySelector('#allocation-date').value && !document.querySelector('#allocation-date').disabled);
    assert.notEqual(await native.evaluate(() => report.totalValue), currentValue, 'native holdings are revalued at the scrubbed date');
    await native.locator('#allocation-position').focus(); await native.keyboard.press('Home');
    await native.waitForFunction(() => report.asOf === report.inceptionDate && !document.querySelector('#allocation-date').disabled);
    assert.equal(await native.locator('.allocation-tile').count(), 0, 'history begins before the first transaction');
    await native.locator('#allocation-today').click();
    await native.waitForFunction(() => report.asOf === report.today && !document.querySelector('#allocation-date').disabled);
    assert.equal(await native.evaluate(() => report.totalValue), currentValue);
    await native.close();
    assert.deepEqual(errors, []);
    console.log('Allocation checks passed: proportional area, signed positions, cash/net/gross metrics, keyboard drilldown and Back, top-20 grouping reconciliation, mobile, zero-net/single/empty states.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
