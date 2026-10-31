#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    let mode = 'normal', securityId, securityName;
    const cashId = '00000000-0000-0000-0000-000000000001';
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
    await page.route('**/api/icon?*', route => mode === 'broken'
      ? route.fulfill({ status: 404, body: '' })
      : route.fulfill({ status: 200, contentType: 'image/png', headers: { 'Cache-Control': 'no-store' }, body: png }));
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(), data = await response.json();
      const security = data.holdings.find(item => item.uuid);
      securityId = security.uuid; securityName = security.name;
      const logo = id => mode === 'missing' ? null : `/api/icon?id=${id}&v=${(mode === 'broken' ? 'b' : 'a').repeat(64)}`;
      data.icons = mode === 'missing' ? {} : { [securityId]: logo(securityId), [cashId]: logo(cashId) };
      for (const item of data.holdings) item.icon = logo(item.uuid || cashId);
      data.rankings = { securities: [{ uuid: securityId, name: securityName, rate: 0.2, gain: 10000 }], unavailable: 0, neutral: 0 };
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => [...document.querySelectorAll('#holdings-body img')].every(image => image.complete && image.naturalWidth > 0));
    assert.equal(await page.locator('#holdings-body img').count(), 2, 'security and cash logos');
    assert.equal(await page.getByRole('link', { name: securityName, exact: true }).count(), 1, 'decorative logo leaves link name intact');
    assert.equal(await page.locator('#holdings-body td').first().innerText(), securityName, 'logos do not alter row text or sorting');
    await page.getByRole('link', { name: securityName, exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#instrument-icon img')?.naturalWidth > 0);
    assert.equal(await page.locator('#instrument-name').innerText(), securityName);
    await page.locator('#instrument-back').click();
    await page.locator('#holdings-body').waitFor({ state: 'visible' });
    assert(page.url().includes('#/holdings'));
    await page.goto(`${base}/#/performers`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('#gainers-body img')?.naturalWidth > 0);
    await page.goto(`${base}/#/allocation`, { waitUntil: 'networkidle' });
    assert(await page.locator('#allocation-chart image').count() > 0, 'allocation tiles reuse embedded logos');
    const tileImage = page.locator('#allocation-chart image').first();
    assert.match(await tileImage.getAttribute('href'), /^\/api\/icon\?id=/);
    if (process.env.SCREENSHOT) await page.screenshot({ path: process.env.SCREENSHOT, fullPage: true });
    mode = 'broken';
    await page.goto(`${base}/#/holdings`, { waitUntil: 'networkidle' });
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelectorAll('#holdings-body .asset-icon').length === 2
      && !document.querySelector('#holdings-body img, #holdings-body .asset-icon.has-logo'));
    assert.equal(await page.locator('#holdings-body .asset-icon').count(), 2, 'failed images retain initials');
    assert.equal(await page.locator('#holdings-body .asset-icon.has-logo').count(), 0);
    mode = 'missing'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('#holdings-body img').count(), 0);
    assert.equal(await page.locator('#holdings-body .asset-icon').first().getAttribute('data-initials'), securityName.slice(0, 2).toLocaleUpperCase());
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'logos preserve mobile layout');
    assert.deepEqual(errors, []);
    console.log('Embedded logos, cash logos, instrument navigation, allocation, missing/broken fallbacks and mobile layout passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
