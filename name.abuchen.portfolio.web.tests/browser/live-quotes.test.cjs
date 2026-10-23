'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const base = process.env.BASE_URL || 'http://127.0.0.1:8097';
    await page.goto(`${base}/#/live-quotes`);
    await page.waitForFunction(() => document.querySelector('#live-status').textContent === 'Simulation running');
    assert.match(await page.locator('#live-source').innerText(), /SIMULATED.*NO IBKR CONNECTION/);
    assert.equal(await page.locator('#live-quotes-body tr').count(), 5);
    assert(await page.locator('#date-controls').isHidden());
    const before = await page.locator('#live-quotes-body tr').first().locator('td').nth(2).getAttribute('data-value');
    await page.waitForFunction(value => document.querySelector('#live-quotes-body tr').cells[2].dataset.value !== value, before);

    await page.waitForFunction(() => document.querySelector('.live-price[data-direction="up"]')
      && document.querySelector('.live-price[data-direction="down"]'));
    const colors = await page.evaluate(() => [getComputedStyle(document.querySelector('.quote-up')).color,
      getComputedStyle(document.querySelector('.quote-down')).color]);
    assert.notEqual(colors[0], colors[1], 'up/down ticks use distinct colors');
    assert.match(await page.locator('.quote-up').first().getAttribute('aria-label'), /up since previous update/);

    const chart = page.locator('#live-chart');
    await chart.scrollIntoViewIfNeeded();
    const box = await chart.boundingBox();
    await page.mouse.move(box.x + box.width * .25, box.y + box.height * .5);
    const frozen = await page.locator('#live-chart-detail').innerText();
    await page.waitForTimeout(1400);
    assert.equal(await page.locator('#live-chart-detail').innerText(), frozen, 'live polling preserves a hovered sample');
    assert.match(await page.locator('#live-chart-mode').innerText(), /paused/);
    await page.locator('#live-follow').click();
    assert(await page.locator('#live-follow').isDisabled());
    assert.notEqual(await page.locator('#live-chart-detail').innerText(), frozen);

    await page.locator('tr[data-quote="demo-eurusd"] button').click();
    assert(page.url().includes('quote=demo-eurusd'));
    assert.match(await page.locator('tr[data-quote="demo-eurusd"]').innerText(), /Simulated Delayed/);
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#live-selected-name').textContent.includes('EUR / USD'));
    await page.locator('tr[data-quote="demo-unavailable"] button').click();
    assert.match(await page.locator('#live-chart-detail').innerText(), /No last-trade/);

    await page.locator('tr[data-quote="demo-aapl"] button').click();
    await chart.focus();
    await page.keyboard.press('Home');
    const first = await page.locator('#live-chart-detail').innerText();
    await page.keyboard.press('End');
    assert.notEqual(await page.locator('#live-chart-detail').innerText(), first, 'intraday keyboard selection uses distinct received times');

    await page.route('**/api/price-updates', async route => {
      const response = await page.request.get(`${base}/api/price-updates`);
      const data = await response.json();
      data.liveQuotes.available = false;
      data.liveQuotes.state = 'stale';
      data.liveQuotes.error = 'The collector has stopped updating. Last received quotes are shown.';
      for (const entry of data.liveQuotes.quotes) entry.stale = true;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.waitForFunction(() => document.querySelector('#live-status').textContent === 'Simulation paused');
    assert.match(await page.locator('tr[data-quote="demo-aapl"]').innerText(), /stale/);
    assert.match(await page.locator('#live-error').innerText(), /stopped updating/);
    await page.unroute('**/api/price-updates');
    await page.waitForFunction(() => document.querySelector('#live-status').textContent === 'Simulation running');

    const flat = await (await page.request.get(`${base}/api/price-updates`)).json();
    await page.route('**/api/price-updates', async route => {
      const current = await (await page.request.get(`${base}/api/price-updates`)).json();
      current.liveQuotes.quotes = flat.liveQuotes.quotes;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(current) });
    });
    await page.waitForTimeout(3500);
    assert.equal(await page.locator('.quote-up,.quote-down').count(), 0, 'flat prices return to neutral');
    assert(await page.locator('.quote-direction').evaluateAll(items => items.every(item => item.textContent.trim() === '')), 'flat tick markers are blank');
    await page.unroute('**/api/price-updates');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForFunction(() => document.querySelector('.quote-up,.quote-down'));
    assert.equal(await page.locator('.quote-up,.quote-down').first().evaluate(element => getComputedStyle(element).animationName), 'none', 'reduced motion disables pulses');

    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'watchlist scrolls internally on mobile');
    const stream = await (await page.request.get(`${base}/api/price-updates`)).json();
    assert.equal(stream.liveQuotes.mode, 'demo');
    assert.equal(stream.liveQuotes.connected, false);
    assert.deepEqual(errors, []);
    console.log('Live quote browser checks passed: explicit simulation, changing prices, intraday hover/keyboard, follow/pause, deep link, delayed/unavailable/stale/recovery and mobile.');
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
