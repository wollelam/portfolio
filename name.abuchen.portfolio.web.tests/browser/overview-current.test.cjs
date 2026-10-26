#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
    const initial = await (await page.request.get(`${base}/api/dashboard`)).json();
    const today = initial.today;
    const day = offset => new Date(Date.parse(today + 'T12:00:00Z') + offset * 86400000).toISOString().slice(0, 10);
    const historical = day(-7), from = day(-40);
    const excluded = initial.scope.options.find(o => o.type === 'Cash accounts').id;
    const overviewRequests = [];
    let tomorrow = false;
    await page.route('**/api/dashboard*', async route => {
      const url = new URL(route.request().url());
      const isOverview = await page.evaluate(() => currentRoute === 'overview');
      if (isOverview) overviewRequests.push(url.searchParams.get('to'));
      const response = await route.fetch(), data = await response.json();
      if (tomorrow && isOverview) {
        data.today = day(1); data.asOf = day(1); data.period.closingDate = day(1); data.day.closingDate = day(1);
      }
      await route.fulfill({ response, json: data });
    });
    await page.goto(`${base}/#/overview?from=${from}&to=${historical}&exclude=${excluded}`, { waitUntil: 'networkidle' });
    assert.equal(await page.evaluate(() => report.asOf), today, 'historical overview bookmark resolves to server today');
    assert.equal(new URLSearchParams(page.url().split('?')[1]).get('to'), today);
    assert.equal(new URLSearchParams(page.url().split('?')[1]).get('exclude'), excluded);
    assert(!overviewRequests.includes(historical), 'overview never requests a historical closing valuation');
    await page.locator('[data-route="performance"]').click();
    await page.locator('#to').fill(historical);
    await page.locator('#dates button[type="submit"]').click();
    await page.waitForFunction(date => report.asOf === date, historical);
    assert.equal(await page.evaluate(() => currentRoute), 'performance');
    await page.locator('[data-route="overview"]').click();
    await page.waitForFunction(() => currentRoute === 'overview' && report.asOf === report.today);
    assert.doesNotMatch(await page.locator('#day-live-status').innerText(), /Historical view/);
    await page.goBack();
    await page.waitForFunction(date => currentRoute === 'performance' && report.asOf === date, historical);
    await page.goForward();
    await page.waitForFunction(() => currentRoute === 'overview' && report.asOf === report.today);
    tomorrow = true;
    await page.evaluate(() => checkForUpdates());
    await page.waitForFunction(date => report.asOf === date && report.today === date, day(1));
    assert.equal(new URLSearchParams(page.url().split('?')[1]).get('to'), day(1), 'overview follows server date after midnight');
    assert(overviewRequests.every(value => value === null), 'server chooses current date without an explicit to parameter');
    assert.deepEqual(errors, []);
    console.log('Current overview: historical bookmarks, filtered navigation, Back/Forward and server midnight rollover passed.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
