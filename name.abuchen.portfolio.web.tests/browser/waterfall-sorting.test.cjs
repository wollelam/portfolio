#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8097';

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${base}/#/waterfall`, { waitUntil: 'networkidle' });
    await page.locator('#waterfall-chart .waterfall-bar').first().waitFor();
    assert(await page.locator('#waterfall-chart .waterfall-total').count() >= 2);
    await page.locator('#waterfall-chart .waterfall-bar').first().focus();
    assert.match(await page.locator('#waterfall-detail').innerText(), /Opening|Initial/i);
    await page.selectOption('#waterfall-mode', 'contributions');
    await page.locator('#waterfall-chart [role="link"]').first().waitFor();
    await page.waitForTimeout(300);
    const securityBar = page.locator('#waterfall-chart [role="link"]').first();
    await securityBar.focus();
    assert.match(await page.locator('#waterfall-detail').innerText(), /Running total/);
    await securityBar.press('Enter');
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    await page.locator('#instrument-back').click();
    await page.locator('#waterfall-chart [role="link"]').first().waitFor();
    assert.equal(await page.locator('#waterfall-mode').inputValue(), 'contributions');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'waterfall scrolls internally on mobile');
    await page.setViewportSize({ width: 1280, height: 900 });

    // Synthetic values exercise numeric ordering, ties, missing data and lists longer than the old five-row cap.
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(); const data = await response.json();
      const security = data.holdings.find(row => row.uuid);
      data.holdings = [
        { ...security, name: 'Zeta', value: 900, weight: .1, quoteDate: null },
        { ...security, name: 'Alpha', value: 10000, weight: .3, quoteDate: data.asOf },
        { ...security, name: 'Beta', value: 2000, weight: .2, quoteDate: data.asOf }
      ];
      data.rankings = { securities: Array.from({ length: 65 }, (_, i) => ({ uuid: security.uuid, name: `Winner ${String(i).padStart(2, '0')}`, rate: (i + 1) / 100, gain: (65 - i) * 100 }))
        .concat(Array.from({ length: 65 }, (_, i) => ({ uuid: security.uuid, name: `Loser ${String(i).padStart(2, '0')}`, rate: -(i + 1) / 100, gain: -(65 - i) * 100 }))), unavailable: 0, neutral: 0 };
      if (data.instrument) data.instrument.activity = {
        dividends: Array.from({ length: 32 }, (_, i) => ({ uuid: String(i), date: data.asOf, account: 'Synthetic', currency: 'EUR', gross: (32 - i) * 100, net: (32 - i) * 100, taxes: 0, fees: 0, reportingNet: i === 0 ? null : (32 - i) * 100 })), transactions: []
      };
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.locator('[data-route="holdings"]').click();
    const holdings = page.locator('#holdings-body'); await holdings.getByText('Alpha', { exact: true }).waitFor();
    const headings = page.locator('#holdings th');
    await headings.nth(1).getByRole('button').click();
    assert.match(await holdings.locator('tr').first().innerText(), /^Zeta/);
    await headings.nth(1).getByRole('button').click();
    assert.match(await holdings.locator('tr').first().innerText(), /^Alpha/);
    assert.equal(await headings.nth(1).getAttribute('aria-sort'), 'descending');
    await headings.nth(3).getByRole('button').click();
    assert.match(await holdings.locator('tr').last().innerText(), /^Zeta/);
    await headings.nth(3).getByRole('button').click();
    assert.match(await holdings.locator('tr').last().innerText(), /^Zeta/, 'missing dates remain last descending');
    await page.locator('#security-search').fill('Beta');
    assert.equal(await holdings.locator('tr').count(), 1);
    await holdings.getByRole('link', { name: 'Beta' }).click();
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    const dividendHeaders = page.locator('#security-dividends-body').locator('xpath=ancestor::table').locator('th');
    await dividendHeaders.nth(1).getByRole('button').click();
    assert.match(await page.locator('#security-dividends-body tr').first().locator('td').nth(1).innerText(), /1[.,]00/);
    await page.locator('#security-dividends-next').click();
    assert.equal(await page.locator('#security-dividends-body tr').count(), 7, 'sort applies before pagination');
    await dividendHeaders.nth(5).getByRole('button').click();
    assert.match(await page.locator('#security-dividends-page').innerText(), /^1–25/);
    await page.locator('#security-dividends-next').click();
    assert.equal(await page.locator('#security-dividends-body tr').last().locator('td').last().innerText(), 'Unavailable');
    await page.waitForTimeout(250);
    await page.locator('#instrument-back').click();
    await holdings.getByRole('link', { name: 'Beta' }).waitFor();
    await page.goForward();
    await page.locator('#security-dividends-body tr').nth(6).waitFor();
    assert.equal(await page.locator('#security-dividends-body tr').count(), 7, 'Forward restores the instrument activity page');
    await page.waitForTimeout(250);
    await page.locator('#instrument-back').click();
    await holdings.getByRole('link', { name: 'Beta' }).waitFor();
    assert.equal(await page.locator('#security-search').inputValue(), 'Beta');
    await page.locator('#clear-security-search').click();
    assert.equal(await holdings.locator('tr').count(), 3);
    await page.locator('[data-route="performers"]').click();
    await page.locator('#gainers-body tr').nth(9).waitFor();
    assert.equal(await page.locator('#gainers-body tr').count(), 10);
    await page.selectOption('#ranking-limit', '25');
    assert.equal(await page.locator('#gainers-body tr').count(), 25);
    await page.selectOption('#ranking-limit', 'all');
    assert.equal(await page.locator('#gainers-body tr').count(), 65);
    assert.equal(await page.locator('#decliners-body tr').count(), 65);
    await page.selectOption('#ranking-basis', 'gain');
    assert.match(await page.locator('#gainers-body tr').first().innerText(), /^Winner 00/);
    await page.locator('#gainers-body').locator('xpath=ancestor::table').getByRole('button', { name: 'Security' }).click();
    await page.locator('#gainers-body tr').nth(8).getByRole('link').click();
    await page.locator('#instrument-name').waitFor({ state: 'visible' });
    await page.locator('#instrument-back').click();
    await page.locator('#gainers-body tr').nth(64).waitFor();
    assert.equal(await page.locator('#ranking-limit').inputValue(), 'all');
    assert.equal(await page.locator('#ranking-basis').inputValue(), 'gain');
    await page.reload({ waitUntil: 'networkidle' });
    await page.locator('#gainers-body tr').nth(64).waitFor();
    assert.equal(await page.locator('#ranking-limit').inputValue(), 'all', 'deep link restores entry count');
    assert.deepEqual(errors, []);
    console.log('Waterfall and table checks passed: native bridge, contribution links, keyboard inspection, mobile overflow, numeric/null sorting, filtering, full-list activity sorting, 65 performers per side, Back and reload.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
