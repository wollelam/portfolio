'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    args: ['--no-sandbox']
  });
  try {
    const page = await browser.newPage();
    await page.addInitScript(() => {
      // Drive the polls explicitly and shorten the market timeout for this test.
      const setInterval = window.setInterval.bind(window);
      window.setInterval = (callback, ...args) =>
        ['checkLiveQuotes', 'checkMarket', 'checkForUpdates'].includes(callback.name) ? 0 : setInterval(callback, ...args);
      const timeout = AbortSignal.timeout.bind(AbortSignal);
      AbortSignal.timeout = milliseconds => timeout(milliseconds === 10000 ? 250 : milliseconds);
    });
    let failDashboard = false;
    await page.route('**/api/dashboard?*', async route => {
      if (failDashboard) {
        await route.fulfill({ status: 503, json: { message: 'Dashboard temporarily unavailable' } });
        return;
      }
      const response = await route.fetch();
      const data = await response.json();
      data.views = ['primary', 'secondary'];
      data.market = null;
      data.warnings = [];
      await route.fulfill({ response, json: data });
    });
    let marketRequests = 0;
    let releaseStalledRequest;
    const stalledRequest = new Promise(resolve => { releaseStalledRequest = resolve; });
    await page.route('**/api/price-updates', async route => {
      marketRequests++;
      if (marketRequests === 1) {
        await stalledRequest;
        await route.abort().catch(() => {});
        return;
      }
      await route.fulfill({ json: { observedAt: '2026-01-01T00:00:00Z', quotes: { canRequest: false } } });
    });
    await page.goto(base + '/#/overview?currency=CHF');
    await page.waitForFunction(() => report && !busy);
    assert(await page.locator('#view-selector').isVisible());
    assert(await page.locator('#currency-selector').isVisible());

    for (const width of [320, 390, 900]) {
      await page.setViewportSize({ width, height: 850 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        `The overview fits at ${width}px`);
      assert(await page.locator('#total-value').evaluate(value => value.scrollWidth <= value.clientWidth),
        `The total fits in its card at ${width}px`);
      for (const selector of ['#instrument-date-menu', '#market-menu', '#portfolio-scope', '#warnings']) {
        const summary = page.locator(selector + ' summary');
        await summary.click();
        const bounds = await page.locator(selector + ' .topbar-popover').boundingBox();
        assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= width,
          `${selector} fits at ${width}px: ${JSON.stringify(bounds)}`);
        await page.keyboard.press('Escape');
        assert(await summary.evaluate(element => document.activeElement === element));
      }
    }

    assert(await page.locator('#refresh-prices').isDisabled());
    assert(await page.locator('#mark-warnings-read').isDisabled());
    failDashboard = true;
    await page.evaluate(() => load(undefined, undefined, true));
    assert(await page.locator('#refresh-prices').isDisabled());
    assert(await page.locator('#mark-warnings-read').isDisabled());

    await page.evaluate(() => Promise.all([checkMarket(), checkLiveQuotes()]));
    assert.equal(marketRequests, 1, 'Both polls share the stalled GET');
    assert(await page.evaluate(() => liveConnectionError && marketConnectionError));
    await page.evaluate(() => Promise.all([checkMarket(), checkLiveQuotes()]));
    assert.equal(marketRequests, 2, 'The next poll starts a fresh GET');
    assert(await page.evaluate(() => !liveConnectionError && !marketConnectionError));
    releaseStalledRequest();
    console.log('Mobile toolbar, failed dashboard refresh, and market polling recovery passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
