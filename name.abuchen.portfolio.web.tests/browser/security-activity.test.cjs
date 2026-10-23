'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:8097';

async function ready(page) {
  await page.waitForFunction(() => document.querySelector('#dashboard').getAttribute('aria-busy') === 'false'
    && !document.querySelector('#dashboard').hidden);
}

async function refresh(page) {
  const response = page.waitForResponse(item => item.url().includes('/api/dashboard'));
  await page.locator('#refresh').click();
  await response;
  await ready(page);
}

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${baseUrl}/#/holdings`);
    await ready(page);
    await page.locator('.instrument-link').first().click();
    await page.waitForFunction(() => location.hash.startsWith('#/instrument/') && document.querySelector('#instrument-name').textContent);
    await ready(page);
    assert.equal(await page.locator('#security-dividends-body tr').count(), 4, 'demo has four paid dividends');
    assert.equal(await page.locator('#security-transactions-body tr').count(), 4, 'purchase predates the initial selected period');
    assert(await page.locator('#security-dividends-next').isDisabled(), 'one page of payments has no next page');

    const to = await page.locator('#to').inputValue();
    const from = new Date(`${to}T12:00:00Z`);
    from.setUTCFullYear(from.getUTCFullYear() - 2);
    await page.locator('#from').fill(from.toISOString().slice(0, 10));
    const applied = page.waitForResponse(item => item.url().includes('/api/dashboard'));
    await page.locator('#dates button').click();
    await applied;
    await ready(page);
    assert.equal(await page.locator('#security-transactions-body tr').count(), 5, 'wider period adds one purchase without its duplicate cash leg');
    assert.match(await page.locator('#security-transactions-body').innerText(), /700/);

    let shape = 'paged';
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch();
      const data = await response.json();
      if (data.instrument?.activity) {
        const activity = data.instrument.activity;
        if (shape === 'empty') {
          activity.dividends = [];
          activity.transactions = [];
        } else if (shape === 'missing-fx') {
          activity.dividends = [{ ...activity.dividends[0], currency: 'USD', net: 100, reportingNet: null }];
          activity.transactions = [{ ...activity.transactions[0], currency: 'USD', amount: 100, reportingAmount: null }];
        } else {
          activity.dividends = Array.from({ length: 30 }, (_, index) => ({ ...activity.dividends[0], uuid: `payment-${index}` }));
          activity.transactions = Array.from({ length: 60 }, (_, index) => ({ ...activity.transactions[0], uuid: `entry-${index}` }));
        }
      }
      await route.fulfill({ response, body: JSON.stringify(data) });
    });

    await refresh(page);
    assert.equal(await page.locator('#security-dividends-body tr').count(), 25);
    await page.locator('#security-dividends-next').click();
    assert.equal(await page.locator('#security-dividends-body tr').count(), 5);
    assert(await page.locator('#security-dividends-next').isDisabled());
    await page.locator('#security-transactions-next').click();
    await page.locator('#security-transactions-next').click();
    assert.equal(await page.locator('#security-transactions-body tr').count(), 10);
    assert(await page.locator('#security-transactions-next').isDisabled());
    await refresh(page);
    assert.equal(await page.locator('#security-transactions-body tr').count(), 10, 'refresh preserves the activity page');
    assert(await page.locator('#security-transactions-next').isDisabled(), 'request completion preserves pagination bounds');

    shape = 'missing-fx';
    await refresh(page);
    assert.equal(await page.locator('#security-dividends-body tr td').last().innerText(), 'Unavailable');
    assert.match(await page.locator('#security-dividends-body tr td').nth(4).innerText(), /1[.,]00/);
    assert.equal(await page.locator('#security-transactions-body tr td').last().innerText(), 'Unavailable');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'instrument activity tables scroll internally on mobile');

    shape = 'empty';
    await refresh(page);
    assert.match(await page.locator('#security-dividends-body').innerText(), /No dividends/);
    assert.match(await page.locator('#security-transactions-body').innerText(), /No transactions/);
    assert(await page.locator('#security-transactions-previous').isDisabled());
    assert(await page.locator('#security-transactions-next').isDisabled());
    assert.deepEqual(errors, []);
    console.log('Security activity browser checks passed: real demo period/trade filtering, payments, pagination, missing FX, empty states and mobile layout.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
