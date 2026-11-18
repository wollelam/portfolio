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
    page.on('pageerror', error => errors.push(error.message));
    const benchmark = '00000000-0000-4000-8000-000000000001';
    let unavailable = false;
    await page.route('**/api/dashboard?*', async route => {
      const url = new URL(route.request().url());
      const selected = url.searchParams.get('benchmark');
      url.searchParams.delete('benchmark');
      const response = await route.fetch({ url: url.toString() });
      const data = await response.json();
      data.benchmarks = [{ uuid: benchmark, name: 'Demo index', currency: data.currency }];
      const dates = [data.series[0].date, data.series[1].date, data.series[2].date, data.series.at(-1).date];
      const rates = [0, .04, .08, .12];
      data.series = dates.map((date, index) => ({ ...data.series[index], date, rate: rates[index] }));
      data.period.rate = rates.at(-1);
      if (selected) data.benchmark = {
        uuid: benchmark, name: 'Demo index', rate: .10,
        sourceCurrency: data.currency, reportingCurrency: data.currency,
        series: [{ date: dates[0], rate: 0 }, { date: dates[1], rate: .07 }, { date: dates[3], rate: .10 }],
        ...(unavailable ? { error: 'Benchmark history unavailable' } : {})
      };
      await route.fulfill({ response, json: data });
    });

    await page.goto(base + '/#/performance?currency=CHF&mode=relative');
    await page.locator('#mode-relative[aria-pressed="true"]').waitFor();
    assert.match(await page.locator('#chart-detail').innerText(), /Choose a benchmark/);
    await page.locator('#benchmark').selectOption(benchmark);
    await page.waitForFunction(() => !busy && report?.benchmark);
    assert.equal(await page.locator('#period-rate').innerText(), '+2 pp');
    assert.match(await page.locator('#period-gain').innerText(), /Demo index/);
    assert(await page.locator('#benchmark-legend').isHidden());
    assert(await page.evaluate(() => {
      const chart = document.querySelector('#chart').chartInstance;
      return Math.abs(chart.y(0) - (chart.top + chart.bottom) / 2) < .001
        && chart.points[2].value === null;
    }));

    const chart = page.locator('#chart');
    await chart.focus();
    await page.keyboard.press('Home');
    await page.keyboard.press('ArrowRight');
    assert.match(await page.locator('#chart-detail').innerText(), /-3 pp.*Portfolio: \+4%.*Demo index: \+7%/);
    await page.keyboard.press('ArrowRight');
    assert.match(await page.locator('#chart-detail').innerText(), /Unavailable.*Portfolio: \+8%.*Demo index: Unavailable/);
    await page.keyboard.press('End');
    assert.match(await page.locator('#chart-detail').innerText(), /\+2 pp.*Portfolio: \+12%.*Demo index: \+10%/);

    await page.reload();
    await page.locator('#mode-relative[aria-pressed="true"]').waitFor();
    assert.equal(await page.locator('#period-rate').innerText(), '+2 pp');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.locator('#mode-return').click();
    assert(await page.locator('#benchmark-legend').isVisible());
    await page.locator('#mode-relative').click();

    unavailable = true;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !busy && report.benchmark?.error);
    assert.match(await page.locator('#chart-detail').innerText(), /Relative return is unavailable/);
    assert.match(await page.locator('#benchmark-warning').innerText(), /Benchmark history unavailable/);
    await page.locator('#benchmark').selectOption('');
    await page.waitForFunction(() => !busy && !report.benchmark);
    assert.match(await page.locator('#chart-detail').innerText(), /Choose a benchmark/);
    assert.deepEqual(errors, []);
    console.log('Relative return: zero baseline, signed differences, date gaps, keyboard, reload and unavailable benchmark passed.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
