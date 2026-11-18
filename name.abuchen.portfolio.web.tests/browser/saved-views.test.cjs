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
    const initial = await (await page.request.get(base + '/api/dashboard')).json();
    const earlier = new Date(Date.parse(initial.today + 'T12:00:00Z') - 40 * 86400000).toISOString().slice(0, 10);
    const originalFrom = `${Number(earlier.slice(0, 4)) - 1}-12-31`;
    const openingToday = `${Number(initial.today.slice(0, 4)) - 1}-12-31`;
    const chosen = new URLSearchParams({
      view: 'primary', currency: 'CHF', from: originalFrom, to: earlier, preset: 'YTD',
      exclude: initial.scope.options.find(option => option.type === 'Cash accounts').id,
      benchmark: initial.benchmarks[0].uuid, mode: 'relative'
    });
    await page.goto(`${base}/#/performance?${chosen}`);
    await page.waitForFunction(() => !!report && !busy);
    const menu = page.locator('#saved-views'), trigger = menu.locator('summary');
    const save = async (name, dateMode) => {
      await menu.locator('#saved-view-name').fill(name);
      await menu.locator('#saved-view-dates').selectOption(dateMode);
      await menu.locator('#save-view-button').click();
      assert.match(await menu.locator('#saved-view-status').innerText(), /View saved/);
    };
    await trigger.click();
    await page.waitForFunction(() => document.querySelector('#saved-view-dates').value === 'rolling');
    assert(await menu.locator('.topbar-popover').evaluate(node => node.getBoundingClientRect().left >= 0
      && node.getBoundingClientRect().right <= innerWidth));
    await save('Fiscal year', 'rolling');
    await save('Historical comparison', 'fixed');
    await page.reload();
    await page.waitForFunction(() => !!report && !busy);
    await trigger.click();
    await menu.getByRole('button', { name: /^Fiscal year/ }).click();
    await page.waitForFunction(today => !busy && report.period.closingDate === today, initial.today);
    let actual = new URLSearchParams(page.url().split('?')[1]);
    assert.equal(actual.get('from'), openingToday);
    for (const key of ['view', 'currency', 'exclude', 'benchmark', 'mode', 'preset'])
      assert.equal(actual.get(key), chosen.get(key));
    assert(!(await menu.evaluate(element => element.open)));
    await trigger.click();
    await menu.getByRole('button', { name: 'Historical comparison', exact: true }).click();
    await page.waitForFunction(date => !busy && report.period.closingDate === date, earlier);
    actual = new URLSearchParams(page.url().split('?')[1]);
    assert.equal(actual.get('from'), originalFrom);
    await trigger.click();
    await menu.getByRole('button', { name: 'Remove Fiscal year' }).click();
    assert.equal(await menu.getByRole('button', { name: /^Fiscal year/ }).count(), 0);
    await page.reload();
    await page.waitForFunction(() => !!report && !busy);
    await trigger.click();
    await menu.getByRole('button', { name: 'Historical comparison', exact: true }).waitFor();
    assert.equal(await menu.locator('.saved-view-open').count(), 1);
    assert.deepEqual(errors, []);
    console.log('Saved views: browser persistence, exact dates, rolling YTD, retained filters, mobile menu, and removal passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
