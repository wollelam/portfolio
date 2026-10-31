'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const assets = resolve(__dirname, '../../name.abuchen.portfolio.web/src/name/abuchen/portfolio/web/assets');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const requests = [];
    let fixtureData;
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.route(`${base}/`, route => route.fulfill({ status: 200, contentType: 'text/html', body: readFileSync(resolve(assets, 'index.html')) }));
    await page.route(`${base}/app.js`, route => route.fulfill({ status: 200, contentType: 'application/javascript', body: readFileSync(resolve(assets, 'app.js')) }));
    await page.route(`${base}/app.css`, route => route.fulfill({ status: 200, contentType: 'text/css', body: readFileSync(resolve(assets, 'app.css')) }));
    await page.route('**/api/dashboard**', async route => {
      const url = new URL(route.request().url());
      requests.push({ currency: url.searchParams.get('currency'), frames: url.searchParams.get('frames') === 'true' });
      if (url.searchParams.get('frames') === 'true' && fixtureData) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...fixtureData, frames: [] }) });
        return;
      }
      const fixtureUrl = new URL(url); fixtureUrl.searchParams.delete('view'); fixtureUrl.searchParams.delete('currency');
      const response = await route.fetch({ url: fixtureUrl.toString() });
      const data = await response.json();
      data.views = ['primary', 'secondary', 'combined'];
      fixtureData = data;
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.goto(`${base}/#/allocation?view=primary&currency=CHF`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#dashboard:not([hidden])');
    const selectedDates = await Promise.all(['from', 'to'].map(id => page.locator(`#${id}`).inputValue()));
    const selector = page.locator('#currency-selector');
    assert.equal(await selector.isVisible(), true, 'supported reporting currency selector is globally visible');
    assert.equal(await selector.inputValue(), 'CHF');

    await selector.selectOption('USD');
    await page.waitForFunction(() => new URLSearchParams(location.hash.split('?')[1]).get('currency') === 'USD');
    await page.waitForFunction(() => document.querySelector('#dashboard')?.getAttribute('aria-busy') === 'false');
    assert.match(page.url(), /#\/allocation\?/);
    assert.match(page.url(), /view=primary/);
    assert.match(page.url(), new RegExp(`from=${selectedDates[0]}`));
    assert.match(page.url(), new RegExp(`to=${selectedDates[1]}`));
    assert(requests.some(request => request.currency === 'USD'), 'dashboard request carries selected currency');

    await page.evaluate(() => fetchAllocationFrames(document.querySelector('#to').value));
    assert(requests.some(request => request.currency === 'USD' && request.frames), 'allocation frame request carries selected currency');
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await selector.inputValue(), 'USD', 'currency selection restores from the hash');
    console.log('Reporting currency selector, route persistence, and dashboard/frame parameters passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
