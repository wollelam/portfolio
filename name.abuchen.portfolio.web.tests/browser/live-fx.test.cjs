#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const base = process.env.BASE_URL || 'http://127.0.0.1:8099';
async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.route('**/api/price-updates', route => route.fulfill({ json: {} }));
    await page.goto(`${base}/#/overview`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => report?.holdings.length);
    const check = async (mode, expected) => {
      const actual = await page.evaluate(mode => {
        report.currency = 'CHF'; report.asOf = mode === 'historical' ? '2000-01-01' : report.today;
        report.totalValue = 34000;
        report.holdings = [
          { type: 'security', uuid: 'stock', quoteCurrency: 'USD', shares: 1e8, value: 9000, valuationFxRate: .9 },
          { type: 'cash', quoteCurrency: 'EUR', value: 10000, valuationFxRate: 1 },
          { type: 'security', uuid: 'pence', quoteCurrency: 'GBX', value: 5000, valuationFxRate: .01 },
          { type: 'cash', quoteCurrency: 'CHF', value: 10000, valuationFxRate: 1 }
        ];
        const now = new Date().toISOString();
        const entry = (key, symbol, currency, bid, ask, type = 'CASH') => ({ stale: false, quote: {
          key, symbol, currency, bid, ask, secType: type, conId: 123, last: 999, marketDataType: 'real_time', status: 'active', receivedAt: now } });
        const usd = entry('fx:USD:CHF', 'USD', 'CHF', mode === 'tick' ? .99 : .89, mode === 'tick' ? 1.01 : .91);
        if (mode === 'stale') usd.stale = true;
        if (mode === 'old') usd.quote.receivedAt = new Date(Date.now() - 61000).toISOString();
        if (mode === 'crossed') usd.quote.bid = 2;
        if (mode === 'permission') usd.quote.status = 'not_subscribed';
        if (mode === 'missing-bid') usd.quote.bid = null;
        if (mode === 'identity') usd.quote.secType = 'STK';
        if (mode === 'forged') usd.quote.symbol = 'EUR';
        if (mode === 'frozen') usd.quote.marketDataType = 'frozen';
        const quotes = [usd, entry('fx:EUR:USD', 'EUR', 'USD', 1.09, 1.11), entry('fx:GBP:USD', 'GBP', 'USD', 1.29, 1.31)];
        const stock = entry('stock', 'TEST', 'USD', 119, 121, 'STK'); stock.quote.last = 120; quotes.push(stock);
        quotes.push(entry('fx:USD:DKK', 'USD', 'DKK', 6.9, 7.1));
        if (mode === 'duplicate') quotes.push(usd);
        if (mode === 'missing-gbp') quotes.splice(2, 1);
        if (mode === 'delayed') usd.quote.marketDataType = 'delayed';
        liveConnectionError = mode === 'failure';
        market = { liveQuotes: { available: true, connected: true, mode: mode === 'demo' ? 'demo' : 'ibkr', generatedAt: mode === 'heartbeat' ? new Date(Date.now() - 9000).toISOString() : now, quotes } };
        renderOverviewLive();
        return { value: document.querySelector('#total-value').textContent, note: document.querySelector('#total-date').textContent,
          saved: report.totalValue, gbp: liveFxRate('GBX')?.rate, dkk: liveFxRate('DKK')?.rate };
      }, mode);
      const formatted = await page.evaluate(value => money(value), expected);
      assert.equal(actual.value, formatted, mode);
      assert.equal(actual.saved, 34000);
      return actual;
    };
    const valid = await check('valid', 36550);
    assert(Math.abs(valid.gbp - .0117) < 1e-10);
    assert(Math.abs(valid.dkk - .9 / 7) < 1e-10);
    assert.match(valid.note, /3 positions with IBKR FX midpoint/);
    await check('tick', 39500);
    for (const mode of ['stale', 'old', 'crossed', 'permission', 'missing-bid', 'identity', 'forged', 'frozen', 'duplicate']) await check(mode, 35800);
    await check('missing-gbp', 35700);
    await check('historical', 34000); await check('failure', 34000); await check('heartbeat', 34000); await check('demo', 34000);
    assert.match((await check('delayed', 36550)).note, /includes delayed/);
    const penceTotal = await page.evaluate(() => {
      const now = new Date().toISOString();
      report.asOf = report.today; report.currency = 'CHF'; report.totalValue = 335584616;
      report.holdings = [{ type: 'security', uuid: 'psh', quoteCurrency: 'GBX', shares: 510e8,
        value: 2098030, valuationFxRate: .011023 }];
      const entry = quote => ({ stale: false, quote: { conId: 123, status: 'active', marketDataType: 'real_time', receivedAt: now, ...quote } });
      market = { liveQuotes: { available: true, connected: true, mode: 'ibkr', generatedAt: now, quotes: [
        entry({ key: 'psh', symbol: 'PSH', currency: 'GBX', secType: 'STK', last: 3778 }),
        entry({ key: 'fx:GBP:USD', symbol: 'GBP', currency: 'USD', secType: 'CASH', bid: 1.25, ask: 1.25 }),
        entry({ key: 'fx:USD:CHF', symbol: 'USD', currency: 'CHF', secType: 'CASH', bid: .88, ask: .88 })
      ] } };
      renderOverviewLive();
      return { actual: document.querySelector('#total-value').textContent, expected: money(335606044) };
    });
    assert.equal(penceTotal.actual, penceTotal.expected, 'PSH adds a CHF 21k position, not a CHF 2.1m position');
    assert.deepEqual(errors, []);
    console.log('Live FX: midpoint, inverses, USD cross, GBX, cash, FX-only moves, safe fallbacks and historical isolation passed.');
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
