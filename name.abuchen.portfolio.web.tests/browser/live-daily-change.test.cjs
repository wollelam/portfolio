#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let last = 110;
    await page.route('**/api/price-updates', async route => {
      const now = new Date().toISOString();
      const quotes = [
        ['up', last, 100], ['down', 95, 100], ['flat', 100, 100],
        ['missing-close', 110, null], ['zero-close', 110, 0], ['missing-last', null, 100]
      ].map(([key, price, close]) => ({ stale: false, quote: { key, name: key, symbol: key, currency: 'USD', conId: 1,
        marketDataType: 'delayed', status: 'active', receivedAt: now, last: price, previousClose: close, bid: 99, ask: 101, dayHigh: key === 'missing-last' ? null : 125, dayLow: key === 'missing-last' ? null : 90, history: [] } }));
      await route.fulfill({ json: { observedAt: now, liveQuotes: { configured: true, available: true, connected: true,
        mode: 'ibkr', state: 'connected', generatedAt: now, quotes } } });
    });
    await page.goto(`${base}/#/live-quotes`, { waitUntil: 'networkidle' });
    const change = key => page.locator(`tr[data-quote="${key}"] .live-daily-change`);
    await change('up').getByText('+10.00%', { exact: true }).waitFor();
    assert.equal(await change('down').innerText(), '-5.00%');
    assert.equal(await change('flat').innerText(), '0.00%');
    for (const key of ['missing-close', 'zero-close', 'missing-last']) assert.equal(await change(key).innerText(), '—');
    assert.match(await change('up').getAttribute('class'), /positive/);
    assert.match(await change('down').getAttribute('class'), /negative/);
    assert.match(await change('up').getAttribute('title'), /IBKR previous close: 100 USD/);
    const row = page.locator('tr[data-quote="up"]');
    assert.match(await row.locator('td').nth(4).innerText(), /99/);
    assert.match(await row.locator('td').nth(5).innerText(), /101/);
    assert.match(await row.locator('td').nth(9).innerText(), /Delayed/);
    assert.match(await row.locator('td').nth(6).innerText(), /125/);
    assert.match(await row.locator('td').nth(7).innerText(), /90/);
    assert.equal(await page.locator('tr[data-quote="missing-last"] td').nth(6).getAttribute('data-value'), '');
    last = 120;
    await change('up').getByText('+20.00%', { exact: true }).waitFor();
    assert.match(await change('up').getAttribute('class'), /quote-up/);
    last = 115;
    await change('up').getByText('+15.00%', { exact: true }).waitFor();
    assert.match(await change('up').getAttribute('class'), /quote-down/, 'a falling daily gain flashes red');
    last = 100;
    await change('up').getByText('0.00%', { exact: true }).waitFor();
    assert.match(await change('up').getAttribute('class'), /quote-down/, 'zero is a valid percentage');
    await page.waitForFunction(() => !document.querySelector('tr[data-quote="up"] .live-daily-change').classList.contains('quote-down'));
    last = 120;
    await change('up').getByText('+20.00%', { exact: true }).waitFor();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    assert.equal(await change('up').evaluate(el => getComputedStyle(el).animationName), 'none');
    const heading = page.locator('th').filter({ hasText: 'Day %' });
    await heading.locator('button').click();
    assert.equal(await page.locator('#live-quotes-body tr').first().getAttribute('data-quote'), 'down');
    await heading.locator('button').click();
    assert.equal(await page.locator('#live-quotes-body tr').first().getAttribute('data-quote'), 'up');
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    last = 3793.9999;
    await page.waitForFunction(() => document.querySelector('tr[data-quote="up"]').cells[2].dataset.value === '3794');
    assert.equal(await row.locator('td').nth(2).locator('span').last().innerText(), '3,794');
    await page.waitForFunction(() => document.querySelector('tr[data-quote="up"]').cells[2].dataset.direction === '');
    last = 3794.0001;
    await page.waitForFunction(() => market.liveQuotes.quotes[0].quote.last === 3794.0001);
    assert.equal(await row.locator('td').nth(2).getAttribute('data-direction'), '', 'invisible precision changes do not flash');
    last = 3794.56789;
    await page.waitForFunction(() => document.querySelector('tr[data-quote="up"]').cells[2].dataset.value === '3794.57');
    assert.equal(await row.locator('td').nth(2).locator('span').last().innerText(), '3,794.57');
    assert.equal(await page.evaluate(() => market.liveQuotes.quotes[0].quote.last), 3794.56789, 'calculation input retains precision');
    assert.deepEqual(errors, []);
    console.log('Daily quote change: up/down/flat, missing baseline, updates and table columns passed.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
