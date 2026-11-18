'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 850 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const grouping = page.locator('#allocation-grouping');
    const rows = page.locator('#exposure-rows .exposure-row');
    await page.goto(base + '/#/allocation?group=account');
    await rows.first().waitFor();
    assert.equal(await grouping.inputValue(), 'account');
    assert(await page.locator('#allocation-treemap').isHidden());
    assert(await page.evaluate(() => report.exposure.reduce((sum, item) => sum + item.value, 0) === report.totalValue));

    const securityGroup = rows.filter({ has: page.locator('a.instrument-link') }).first();
    await securityGroup.locator('summary').click();
    assert(await securityGroup.locator('a.instrument-link').first().isVisible());
    await securityGroup.locator('a.instrument-link').first().click();
    await page.waitForFunction(() => currentRoute === 'instrument' && report?.instrument && !busy);
    await page.goBack();
    await page.waitForFunction(() => currentRoute === 'allocation' && report?.exposureGrouping === 'account' && !busy);
    assert.equal(await grouping.inputValue(), 'account');

    await grouping.selectOption('asset-class');
    await page.waitForFunction(() => report?.exposureGrouping === 'asset-class' && !busy);
    assert.match(await rows.first().innerText(), /Unclassified/);
    assert.match(page.url(), /group=asset-class/);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));

    const frame = page.waitForResponse(response => {
      const params = new URL(response.url()).searchParams;
      return params.get('frames') === 'true' && params.get('exposure') === 'asset-class';
    });
    await page.locator('#allocation-play').click();
    assert((await frame).ok());
    await page.waitForFunction(() => report.asOf < report.today && Array.isArray(report.exposure));
    assert(await page.evaluate(() => report.exposure.reduce((sum, item) => sum + item.value, 0) === report.totalValue));
    if (await page.locator('#allocation-play').getAttribute('aria-pressed') === 'true') await page.locator('#allocation-play').click();

    await grouping.selectOption('position');
    assert(await page.locator('#allocation-treemap').isVisible());
    assert(!(new URLSearchParams(page.url().split('?')[1]).has('group')));
    assert.deepEqual(errors, []);
    console.log('Ranked exposure: grouped snapshot reconciliation, drill-down, Back, mobile and grouped playback passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
