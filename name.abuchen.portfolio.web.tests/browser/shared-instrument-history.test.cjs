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
    const saved = await page.evaluate(() => ({ point: latestStoredInstrumentPrice(report.instrument),
      price: document.querySelector('#instrument-current-price').textContent,
      status: document.querySelector('#instrument-price-status').textContent }));
    assert.equal(saved.point.price, 12000, 'the combined instrument uses its saved source prices');
    assert.notEqual(saved.price, 'Price unavailable');
    assert.equal(saved.status, 'Saved');
    const broker = await page.evaluate(() => {
      const now = new Date().toISOString(), uuid = report.instrument.uuid;
      report.asOf = report.today;
      report.period.closingDate = report.today;
      report.instrument.quoteDate = report.today;
      report.quoteKeys = { [uuid]: [uuid, 'secondary-uuid'] };
      market = { liveQuotes: { mode: 'ibkr', available: true, connected: true, generatedAt: now, quotes: [
        { stale: false, quote: { key: uuid, currency: 'USD', conId: 42, status: 'active', marketDataType: 'real_time', receivedAt: now, last: 110, previousClose: 100 } },
        { stale: false, quote: { key: 'fx:USD:CHF', symbol: 'USD', currency: 'CHF', secType: 'CASH', conId: 43, status: 'active', marketDataType: 'real_time', receivedAt: now, bid: .9, ask: .9, previousClose: .91 } }
      ] } };
      renderInstrument(report.instrument);
      const primary = instrumentChart.points.at(-1), secondary = instrumentChart.config.series[0].points.at(-1);
      const prior = instrumentChart.points.find(point => point.date === report.day.openingDate);
      const secondaryPrior = instrumentChart.config.series[0].points.find(point => point.date === report.day.openingDate);
      const first = brokerDayPerformance({ uuid, quoteCurrency: 'USD', quoteDate: report.today, shares: 100000000 });
      const second = brokerDayPerformance({ uuid, quoteCurrency: 'USD', quoteDate: '2026-10-30', shares: 200000000 });
      return { primary, secondary, prior, secondaryPrior, first, second };
    });
    assert.deepEqual(broker.primary, broker.secondary, 'both source lines end at the same broker price');
    assert.equal(broker.primary.value, 110);
    assert.deepEqual(broker.prior, broker.secondaryPrior, 'both source lines use the same broker previous close');
    assert.equal(broker.prior.value, 100);
    assert.equal(broker.first.rate, broker.second.rate, 'source quote dates do not change the broker day return');
    assert.equal(broker.second.gain, broker.first.gain * 2, 'gain uses each source quantity');
    await page.locator('[data-instrument-currency="portfolio"]').click();
    assert.equal(await page.locator('#instrument-source-legend').isVisible(), true, 'currency toggle keeps both source histories visible');
    assert.equal(await page.locator('#instrument-chart path.instrument-source-secondary').count(), 1);
    assert.equal((await page.evaluate(() => instrumentChart.config.series[0].points.at(-1).value)), 99, 'both source lines use the same live FX conversion');
    assert.equal((await page.evaluate(() => instrumentChart.config.series[0].points.find(point => point.date === report.day.openingDate).value)), 91, 'broker previous FX close aligns the previous day');
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
