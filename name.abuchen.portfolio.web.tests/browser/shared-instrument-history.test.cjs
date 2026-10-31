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
    let instrumentUuid;
    let sourceMode = 'shared';
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.route(`${base}/`, route => route.fulfill({ status: 200, contentType: 'text/html', body: readFileSync(resolve(assets, 'index.html')) }));
    await page.route(`${base}/app.js`, route => route.fulfill({ status: 200, contentType: 'application/javascript', body: readFileSync(resolve(assets, 'app.js')) }));
    await page.route(`${base}/app.css`, route => route.fulfill({ status: 200, contentType: 'text/css', body: readFileSync(resolve(assets, 'app.css')) }));
    await page.route('**/api/dashboard**', async route => {
      const url = new URL(route.request().url());
      const fixtureUrl = new URL(url); fixtureUrl.searchParams.delete('view'); fixtureUrl.searchParams.delete('currency');
      const response = await route.fetch({ url: fixtureUrl.toString() }), data = await response.json();
      data.views = ['primary', 'secondary', 'combined'];
      if (url.searchParams.has('instrument') && data.instrument) {
        const start = data.period.openingDate, end = data.period.closingDate;
        data.instrument.currency = 'USD';
        if (sourceMode === 'shared') {
          data.instrument.sourcePrices = {
            Primary: [{ date: start, price: 10000, reportingPrice: 9000 }, { date: end, price: 12000, reportingPrice: 10800 }],
            Secondary: [{ date: start, price: 11000, reportingPrice: 9900 }, { date: end, price: 13000, reportingPrice: 11700 }]
          };
          data.instrument.prices = [];
        } else {
          data.instrument.sourcePrices = {};
          data.instrument.prices = [{ date: start, price: 10000, reportingPrice: 9000 }, { date: end, price: 12000, reportingPrice: 10800 }];
        }
      } else {
        instrumentUuid = data.holdings?.find(holding => holding.uuid)?.uuid;
      }
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.goto(`${base}/#/holdings?view=combined&currency=CHF`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#dashboard:not([hidden])');
    assert(instrumentUuid, 'fixture portfolio has an instrument to open');
    await page.goto(`${base}/#/instrument/${encodeURIComponent(instrumentUuid)}?view=combined&currency=CHF`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#dashboard:not([hidden])');
    await page.waitForFunction(() => document.querySelector('#dashboard')?.getAttribute('aria-busy') === 'false');
    const legend = page.locator('#instrument-source-legend');
    assert.equal(await legend.isVisible(), true, 'shared price chart identifies each source');
    assert.match(await legend.innerText(), /Primary/);
    assert.match(await legend.innerText(), /Secondary/);
    assert.equal(await page.locator('#instrument-chart path.instrument-source-primary').count(), 1);
    assert.equal(await page.locator('#instrument-chart path.instrument-source-secondary').count(), 1);
    await page.locator('[data-instrument-currency="portfolio"]').click();
    assert.equal(await page.locator('#instrument-source-legend').isVisible(), true, 'currency toggle keeps both source histories visible');
    assert.equal(await page.locator('#instrument-chart path.instrument-source-secondary').count(), 1);
    sourceMode = 'single';
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#dashboard:not([hidden])');
    await page.waitForFunction(() => document.querySelector('#dashboard')?.getAttribute('aria-busy') === 'false');
    assert.equal(await page.locator('#instrument-source-legend').isVisible(), false, 'an empty source map keeps the single-source price history');
    assert.equal(await page.locator('#instrument-chart path.chart-line').count(), 1);
    console.log('Combined instrument source price histories render as separate series and survive currency toggling.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
