#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, locale: 'en-US' });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let shape = 'normal';
    const contributionRequests = [];
    await page.route('**/api/dashboard**', async route => {
      const response = await route.fetch(), data = await response.json();
      const params = new URL(route.request().url()).searchParams;
      if (params.get('waterfall') === 'contributions') {
        contributionRequests.push(params);
        data.waterfall = { entries: [
          { kind: 'START', amount: 999999, label: 'Opening value' },
          { kind: 'CHANGE', amount: -600, label: 'Smaller decliner' },
          { kind: 'CHANGE', amount: -3000, label: 'Largest decliner' },
          { kind: 'CHANGE', amount: 500, label: 'Largest offset' },
          { kind: 'END', amount: -3100, label: 'Closing value' }
        ] };
      }
      const sample = {
        normal: [[1, 0], [18, -.2], [31, -.1]],
        episodes: [[1, 0], [2, -.1], [3, -.06], [7, 0], [8, 0], [9, -.24], [10, -.12], [13, 0], [16, -.15], [21, -.13]],
        gaps: [[1, 0], [2, -.1], [3, null], [7, 0], [8, -.05], [9, null]],
        flat: [[1, 0], [18, 0], [31, 0]],
        empty: []
      }[shape];
      const series = sample.map(([day, rate]) => ({ date: `2025-01-${String(day).padStart(2, '0')}`, rate }));
      data.drawdown = { maximum: -Math.min(0, ...series.map(point => point.rate ?? 0)), current: series.at(-1)?.rate ?? null,
        longestWithoutNewHigh: 17, peakDate: '2025-01-01', troughDate: '2025-01-18', series };
      await route.fulfill({ response, body: JSON.stringify(data) });
    });
    await page.goto(`${process.env.BASE_URL || 'http://127.0.0.1:8097'}/#/drawdown`, { waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-maximum').innerText(), /20%/);
    assert.match(await page.locator('#drawdown-current').innerText(), /10%/);
    assert.equal(await page.locator('#drawdown-duration').innerText(), '17 calendar days');
    assert.match(await page.locator('#drawdown-summary').innerText(), /peak.*trough/);
    const chart = page.locator('#drawdown-chart');
    const zero = await chart.locator('.zero-line').getAttribute('y1');
    assert((await chart.locator('.chart-area').getAttribute('d')).endsWith(`,${zero} Z`), 'drawdown area fills to the zero peak baseline');
    await chart.focus(); await chart.press('Home');
    const first = await page.locator('#drawdown-detail').innerText();
    await chart.press('End'); assert.notEqual(await page.locator('#drawdown-detail').innerText(), first);
    await chart.press('ArrowLeft'); const selected = await page.locator('#drawdown-detail').innerText();
    assert.match(selected, /20%/);
    const rows = page.locator('#drawdown-episodes-body tr');
    const highlight = rows.first().getByRole('button');
    await highlight.press('Enter');
    assert.equal(await highlight.getAttribute('aria-pressed'), 'true');
    assert.equal(await chart.locator('.drawdown-episode-band').count(), 1);
    assert.match(await rows.first().innerText(), /Still below peak.*13 days so far/s);
    await page.getByText('Largest decliner', { exact: true }).waitFor();
    assert.match(await page.locator('#drawdown-loss-contributions li').first().innerText(), /Largest decliner/);
    assert.match(await page.locator('#drawdown-gain-contributions').innerText(), /Largest offset/);
    assert.equal(contributionRequests[0].get('from'), '2025-01-01');
    assert.equal(contributionRequests[0].get('to'), '2025-01-18');
    const waterfall = await page.locator('#drawdown-waterfall-link').getAttribute('href');
    assert.match(waterfall, /#\/waterfall\?.*from=2025-01-01&to=2025-01-18&waterfall=contributions/);
    await page.locator('[data-route="overview"]').click();
    await page.locator('#total-value').waitFor({ state: 'visible' });
    await page.goBack(); await chart.locator('.drawdown-episode-band').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#drawdown-detail').innerText(), selected, 'Back restores inspected date');
    assert.equal(await chart.locator('.drawdown-episode-band').count(), 1, 'Back restores the highlighted episode');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    shape = 'episodes'; await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await rows.count(), 3);
    assert.match(await rows.nth(0).innerText(), /24%.*Jan 8.*Jan 9.*Jan 13.*4 days/s);
    assert.match(await rows.nth(1).innerText(), /15%.*Still below peak.*5 days so far/s);
    assert.match(await rows.nth(2).innerText(), /10%.*Jan 7.*5 days/s);
    await rows.nth(0).getByRole('button').click();
    assert.match(await page.locator('#drawdown-detail').innerText(), /Jan 9.*24%/);
    await page.waitForFunction(() => document.querySelector('#drawdown-waterfall-link').hash.includes('from=2025-01-08&to=2025-01-09'));
    shape = 'gaps'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await rows.nth(0).innerText(), /Jan 7.*Uncertain · data gap/s);
    assert.match(await rows.nth(1).innerText(), /Status unavailable.*Uncertain · data gap/s);
    shape = 'flat'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-summary').innerText(), /No decline/);
    assert.match(await rows.first().innerText(), /No drawdown episodes/);
    shape = 'empty'; await page.reload({ waitUntil: 'networkidle' });
    assert.match(await page.locator('#drawdown-detail').innerText(), /No drawdown data/);
    assert.match(await rows.first().innerText(), /No drawdown data/);
    assert.deepEqual(errors, []);
    console.log('Drawdown checks passed: ranked episodes, recovery times, gaps, chart highlighting, Back, flat and empty states, and mobile layout.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
