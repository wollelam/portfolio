#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
    let broken = false;
    await page.route('**/api/icon?*', route => route.fulfill({ status: broken ? 404 : 200, contentType: 'image/png', body: broken ? '' : png }));
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(), data = await response.json();
      const security = data.holdings.find(item => item.uuid);
      const logo = `/api/icon?id=${security.uuid}&v=${(broken ? 'b' : 'a').repeat(64)}`;
      data.icons = { [security.uuid]: logo };
      data.holdings = Array.from({ length: 20 }, (_, i) => ({ ...security, name: `Synthetic position ${i + 1}`, value: (i + 1) ** 3 * 100, icon: logo }));
      data.totalValue = data.holdings.reduce((sum, item) => sum + item.value, 0);
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    assert(await page.locator('#allocation-chart .chart-logo').count() > 0);
    const layoutCases = await page.evaluate(() => {
      const svg = svgElement('svg', {}), defs = svgElement('defs', {}); svg.append(defs);
      const item = report.holdings[0];
      return [[200, 180], [200, 100], [200, 68], [48, 48], [39, 39], [120, 180], [900, 45]].map(([width, height], index) => {
        const group = svgElement('g', {});
        renderAllocationLabel(group, defs, { item, x: 0, y: 0, width, height }, index + 100, .123);
        svg.append(group);
        const image = group.querySelector('image'), box = group.querySelector('.chart-logo-box');
        return { name: Boolean(group.querySelector('.allocation-name')), percentage: Boolean(group.querySelector('.allocation-percentage')),
          size: box ? Number(box.getAttribute('width')) : 0, imageSize: image ? Number(image.getAttribute('width')) : 0,
          inside: !box || (Number(box.getAttribute('x')) >= 8 && Number(box.getAttribute('y')) >= 8
            && Number(box.getAttribute('x')) + Number(box.getAttribute('width')) <= width - 8
            && Number(box.getAttribute('y')) + Number(box.getAttribute('height')) <= height - 8) };
      });
    });
    assert.deepEqual(layoutCases.slice(0, 5).map(({ name, percentage, size }) => ({ name, percentage, size })), [
      { name: true, percentage: true, size: 40 },
      { name: false, percentage: true, size: 40 },
      { name: false, percentage: false, size: 40 },
      { name: false, percentage: false, size: 32 },
      { name: false, percentage: false, size: 0 }
    ], 'hide name, then percentage, then shrink logo, then hide logo');
    assert(layoutCases.every(item => item.inside && (!item.size || item.imageSize >= 16)), 'minimum readable logo fits inside each tile');
    const logoBox = page.locator('#allocation-chart .chart-logo-box').first();
    assert.equal(await logoBox.evaluate(node => getComputedStyle(node).fill), 'rgb(255, 255, 255)');
    await page.locator('#allocation-chart .allocation-tile').last().focus();
    assert.match(await page.locator('#allocation-detail').innerText(), /Synthetic position.*gross exposure.*net portfolio weight/);
    if (process.env.SCREENSHOT) await page.screenshot({ path: process.env.SCREENSHOT, fullPage: true });
    await page.goto(`${base}/#/waterfall`, { waitUntil: 'networkidle' });
    await page.locator('[data-waterfall-mode="contributions"]').click();
    const logo = page.locator('#waterfall-chart .chart-logo').first();
    await logo.waitFor();
    const bar = page.locator('#waterfall-chart .waterfall-bar').filter({ has: page.locator('.chart-logo') }).first();
    await bar.focus();
    assert.doesNotMatch(await page.locator('#waterfall-tooltip').innerText(), /Running total/);
    assert.equal(await page.locator('#waterfall-chart title, #waterfall-chart [title]').count(), 0);
    assert.equal(await logo.locator('rect').evaluate(node => getComputedStyle(node).fill), 'rgb(255, 255, 255)');
    assert.equal(await logo.locator('rect').evaluate(node => getComputedStyle(node).strokeWidth), '1px', 'focus outlines the bar without thickening the logo frame');
    await logo.click();
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    await page.locator('#instrument-back').click();
    await page.locator('#waterfall-chart .chart-logo').first().waitFor();
    broken = true;
    await page.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !document.querySelector('#allocation-chart .chart-logo'));
    assert(await page.locator('#allocation-chart .allocation-name').count() > 0, 'broken logos retain useful text');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'chart scrolls internally on mobile');
    assert.deepEqual(errors, []);
    console.log('Chart logo boxes, allocation priority and minimum size, waterfall hover/drilldown, broken images and mobile passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
