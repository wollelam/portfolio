'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 850 }, locale: 'en-US' });
    const errors = [];
    let rankedRequests = 0, flat = false;
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/dashboard?*', async route => {
      const response = await route.fetch(), data = await response.json();
      const ranking = new URL(route.request().url()).searchParams.get('rankings');
      const security = data.holdings.find(item => item.type === 'security');
      const example = { securities: [
        { uuid: security.uuid, name: 'Example security', rate: .1, gain: 100 },
        { uuid: 'other', name: 'Offset security', rate: -.05, gain: -50 }
      ] };
      if (ranking === 'day') {
        data.day.gain = 1000;
        data.day.rate = .1;
        data.dailyRankings = example;
      }
      if (ranking === 'true') {
        rankedRequests++;
        data.period.gain = flat ? 0 : 1000;
        data.period.rate = flat ? 0 : .1;
        data.rankings = example;
      }
      await route.fulfill({ response, json: data });
    });

    await page.goto(base + '/#/overview');
    await page.locator('#daily-gainers-body tr').getByText('Example security').waitFor();
    assert.equal(await page.locator('#daily-gainers-body td').last().innerText(), '+1 pp');
    assert.equal(await page.locator('#daily-losers-body td').last().innerText(), '-0.5 pp');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.goto(base + '/#/performance');
    await page.waitForFunction(() => !!report && !busy);
    assert.equal(rankedRequests, 0);
    const details = page.locator('#performance-impact');
    await details.locator('summary').click();
    await details.getByText('Example security').waitFor();
    assert.equal(rankedRequests, 1);
    assert.equal(await page.locator('#performance-impact-body tr').first().locator('td').last().innerText(), '+1 pp');

    flat = true;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !busy && report.period.gain === 0);
    assert.equal(await page.locator('#performance-impact-body tr').first().locator('td').last().innerText(), 'Unavailable');

    await page.goto(base + '/#/performers');
    await page.locator('#gainers-body').getByText('Example security').waitFor();
    assert.equal(await page.locator('#gainers-body td').last().innerText(), 'Unavailable');
    assert.deepEqual(errors, []);
    console.log('Portfolio impact: percentage points, negative impact, zero gain, Overview, Performance and Performers passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
