#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:8097';
const chromiumPath = process.env.CHROMIUM_PATH || '/usr/bin/chromium';

async function installSyntheticFixture(page) {
  let quoteShape = 'normal';
  const responseNumbers = new WeakMap();
  let irregularLastDate = '';
  let priorTradingDate = '';
  let emptyRankings = false;
  await page.context().route('**/api/dashboard**', async route => {
    const owner = route.request().frame().page();
    const responseNumber = (responseNumbers.get(owner) || 0) + 1;
    responseNumbers.set(owner, responseNumber);
    const response = await route.fetch();
    // Slow responses exercise scroll restoration while the previous page is still being replaced.
    await new Promise(resolve => setTimeout(resolve, 350));
    const data = await response.json();
    const security = data.holdings.find(item => item.type === 'security' && item.uuid);
    if (security) {
      data.holdings = [...data.holdings, ...Array.from({ length: 120 }, (_, index) => ({
        ...security, name: `Synthetic long list holding ${String(index + 1).padStart(3, '0')}`
      }))];
    }
    if (data.instrument && quoteShape !== 'normal') {
      if (quoteShape === 'empty') data.instrument.prices = [];
      else {
        const all = data.instrument.prices;
        const date = all.at(-21)?.date || all[0]?.date || data.asOf;
        const plus = days => new Date(Date.parse(`${date}T12:00:00Z`) + days * 86400000).toISOString().slice(0, 10);
        irregularLastDate = plus(20);
        data.instrument.prices = quoteShape === 'single'
          ? [{ date, price: data.instrument.quoteScale * 125 }]
          : [
            { date, price: data.instrument.quoteScale * 100 },
            { date: plus(1), price: data.instrument.quoteScale * 110 },
            { date: plus(20), price: data.instrument.quoteScale * 150 }
          ];
      }
    }
    priorTradingDate = data.day.openingDate;
    if (new URL(route.request().url()).searchParams.get('rankings') === 'true') data.rankings = { unavailable: 1, neutral: 1, securities: emptyRankings ? [] : [
      { uuid: security?.uuid || '1', name: 'Rate leader', rate: 0.4, gain: 50 },
      { uuid: security?.uuid || '2', name: 'Money leader', rate: 0.1, gain: 500 },
      { uuid: security?.uuid || '3', name: 'Decliner', rate: -0.2, gain: -40 },
      { uuid: security?.uuid || '4', name: 'Unknown', rate: null, gain: null }
    ] };
    data.loadedAt = new Date(Date.now() + responseNumber * 1000).toISOString();
    data.revision = `browser-${responseNumber}-${data.revision}`;
    await route.fulfill({ response, body: JSON.stringify(data) });
  });
  return {
    setQuoteShape: value => { quoteShape = value; },
    responseNumber: owner => responseNumbers.get(owner) || 0,
    irregularLastDate: () => irregularLastDate, priorTradingDate: () => priorTradingDate, setEmptyRankings: value => { emptyRankings = value; }
  };
}

async function waitForRoute(page, route, fixture) {
  await page.waitForFunction(expected => location.hash.startsWith(`#/${expected}`), route);
  await page.locator('#page-title').waitFor();
  if (fixture) {
    const count = fixture.responseNumber(page);
    await page.waitForFunction(expected => document.querySelector('#revision').textContent.includes(`browser-${expected}`), count);
  }
}

async function assertScrollAt(page, expected, label) {
  const state = await page.evaluate(() => ({
    hash: location.hash,
    scrollY: window.scrollY,
    history: history.state
  }));
  assert(Math.abs(state.scrollY - expected) < 12,
    `${label}: expected scrollY=${expected}, actual=${state.scrollY}, entry=${JSON.stringify(state)}`);
}

async function main() {
  const browser = await chromium.launch({ executablePath: chromiumPath, headless: true, args: ['--no-sandbox'] });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const fixture = await installSyntheticFixture(page);
    await page.goto(baseUrl, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: 'Portfolio overview' }).waitFor();
    assert(await page.locator('[data-page]:visible').count() >= 2, 'overview shows its summary and market pages');

    await page.locator('[data-route="holdings"]').click();
    await waitForRoute(page, 'holdings', fixture);
    const securityLink = page.locator('.instrument-link').nth(80);
    await securityLink.scrollIntoViewIfNeeded();
    await page.evaluate(() => window.scrollBy(0, 120));
    await page.waitForTimeout(180);
    const holdingsScroll = await page.evaluate(() => window.scrollY);
    assert(holdingsScroll > 2000, `deep synthetic holding row is scrollable (actual=${holdingsScroll})`);
    const instrumentHref = await securityLink.getAttribute('href');
    const instrumentId = new URL(instrumentHref, baseUrl).hash.split('/')[2].split('?')[0];

    const popupReady = context.waitForEvent('page');
    await securityLink.click({ modifiers: ['Control'] });
    const popup = await popupReady;
    await waitForRoute(popup, 'instrument', fixture);
    assert(popup.url().includes(instrumentId), 'security anchor opens a detail page in a new tab');
    await popup.close();

    await securityLink.click();
    await waitForRoute(page, 'instrument', fixture);
    assert.equal(await page.locator('[data-page]:visible').count(), 1, 'only instrument detail content is visible');
    await page.goBack();
    await waitForRoute(page, 'holdings', fixture);
    await page.waitForTimeout(250);
    await assertScrollAt(page, holdingsScroll, 'browser Back restores the holding scroll position');
    await page.locator('.instrument-link').nth(80).click();
    await waitForRoute(page, 'instrument', fixture);
    await page.locator('#instrument-back').click();
    await waitForRoute(page, 'holdings', fixture);
    await page.waitForTimeout(250);
    await assertScrollAt(page, holdingsScroll, 'app Back restores the holding scroll position');
    await page.goForward();
    await waitForRoute(page, 'instrument', fixture);
    assert(page.url().includes(instrumentId), 'Forward reopens the same instrument detail');

    await page.locator('[data-route="performers"]').click();
    await waitForRoute(page, 'performers', fixture);
    assert.equal(await page.locator('#gainers-body tr:first-child a').innerText(), 'Rate leader', 'percentage basis sorts by return');
    await page.locator('[data-ranking-basis="gain"]').click();
    assert.equal(await page.locator('#gainers-body tr:first-child a').innerText(), 'Money leader', 'currency basis sorts by gain');
    assert(page.url().includes('basis=gain'), 'ranking basis is saved in the URL');
    await page.locator('#from').fill(await page.locator('#from').inputValue());
    const performerFrom = await page.locator('#from').inputValue();
    const performerTo = await page.locator('#to').inputValue();
    const performerLink = page.locator('#gainers-body a').first();
    await performerLink.click(); await waitForRoute(page, 'instrument', fixture);
    await page.goBack(); await waitForRoute(page, 'performers', fixture);
    assert.equal(await page.locator('[data-ranking-basis="gain"]').getAttribute('aria-pressed'), 'true', 'Back restores ranking basis');
    assert.equal(await page.locator('#from').inputValue(), performerFrom, 'Back restores ranking dates');
    assert.equal(await page.locator('#to').inputValue(), performerTo, 'Back restores closing date');
    await page.locator('[data-route="holdings"]').click(); await waitForRoute(page, 'holdings', fixture);
    await page.locator('[data-route="performers"]').click(); await waitForRoute(page, 'performers', fixture);
    assert.equal(await page.locator('[data-ranking-basis="gain"]').getAttribute('aria-pressed'), 'true', 'sidebar navigation preserves ranking basis');
    await page.reload({ waitUntil: 'networkidle' }); await waitForRoute(page, 'performers', fixture);
    assert.equal(await page.locator('[data-ranking-basis="gain"]').getAttribute('aria-pressed'), 'true', 'reload restores ranking basis');
    await page.locator('[data-period="1D"]').click(); await page.waitForTimeout(200);
    assert.equal(await page.locator('#from').inputValue(), fixture.priorTradingDate(), '1D preset uses the prior trading day');
    await page.locator('[data-period="MTD"]').click(); await page.waitForTimeout(200);
    const mtdFrom = await page.locator('#from').inputValue();
    const mtdTo = await page.locator('#to').inputValue();
    const expectedMtdOpen = new Date(`${mtdTo}T12:00:00Z`); expectedMtdOpen.setUTCDate(0);
    assert.equal(mtdFrom, expectedMtdOpen.toISOString().slice(0, 10), 'MTD opens on the final day of the previous month');
    await page.locator('[data-period="YTD"]').click(); await page.waitForTimeout(200);
    const ytdTo = new Date(`${await page.locator('#to').inputValue()}T12:00:00Z`);
    assert.equal(await page.locator('#from').inputValue(), `${ytdTo.getUTCFullYear() - 1}-12-31`, 'YTD uses the prior year end opening valuation');
    fixture.setEmptyRankings(true); await page.reload({ waitUntil: 'networkidle' }); await waitForRoute(page, 'performers', fixture);
    assert.match(await page.locator('#gainers-body').innerText(), /No positive/);
    assert.match(await page.locator('#decliners-body').innerText(), /No negative/);
    fixture.setEmptyRankings(false);

    await page.locator('[data-route="performance"]').click();
    await waitForRoute(page, 'performance', fixture);
    await page.locator('#mode-value').click();
    assert.equal(await page.locator('#chart .capital-line').count(), 1, 'value mode overlays native invested capital');
    assert(await page.locator('#value-legend').isVisible());
    assert.match(await page.locator('#chart-detail').innerText(), /Opening value \+ net contributions.*Investment gain \/ loss/);
    const from = await page.locator('#from').inputValue();
    const to = await page.locator('#to').inputValue();
    await page.locator('#from').fill(from);
    await page.locator('#to').fill(to);
    await page.locator('#dates button[type="submit"]').click();
    await page.waitForTimeout(250);
    assert(page.url().includes('mode=value') && page.url().includes(`from=${from}`), 'filter and chart mode are linkable URL state');
    await page.locator('[data-route="dividends"]').click();
    await waitForRoute(page, 'dividends', fixture);
    await page.goBack();
    await waitForRoute(page, 'performance', fixture);
    assert.equal(await page.locator('#mode-value').getAttribute('aria-pressed'), 'true', 'Back restores the selected chart mode');
    assert.equal(await page.locator('#from').inputValue(), from, 'Back restores the selected date range');
    await page.reload({ waitUntil: 'networkidle' });
    await waitForRoute(page, 'performance', fixture);
    assert.equal(await page.locator('#mode-value').getAttribute('aria-pressed'), 'true', 'reload restores chart mode');

    const portfolioSvg = page.locator('#chart');
    await portfolioSvg.scrollIntoViewIfNeeded();
    const portfolioBox = await portfolioSvg.boundingBox();
    await page.mouse.move(portfolioBox.x + portfolioBox.width * 0.15, portfolioBox.y + portfolioBox.height * 0.5);
    const firstPortfolioPoint = await page.locator('#chart-detail').innerText();
    await page.mouse.move(portfolioBox.x + portfolioBox.width * 0.83, portfolioBox.y + portfolioBox.height * 0.5);
    const lastPortfolioPoint = await page.locator('#chart-detail').innerText();
    assert.notEqual(firstPortfolioPoint, lastPortfolioPoint, 'portfolio chart hover selects distinct dates and values');
    await portfolioSvg.focus(); await page.keyboard.press('ArrowLeft');
    assert.notEqual(await page.locator('#chart-detail').innerText(), lastPortfolioPoint, 'portfolio chart supports keyboard date selection');

    fixture.setQuoteShape('irregular');
    await page.locator('[data-route="holdings"]').click(); await waitForRoute(page, 'holdings', fixture);
    await page.locator('.instrument-link').first().click(); await waitForRoute(page, 'instrument', fixture);
    const instrumentSvg = page.locator('#instrument-chart');
    const instrumentBox = await instrumentSvg.boundingBox();
    await page.mouse.move(instrumentBox.x + instrumentBox.width * 0.60, instrumentBox.y + instrumentBox.height * 0.5);
    const irregularHover = await page.locator('#instrument-chart-detail').innerText();
    const expectedIrregularDate = new Date(`${fixture.irregularLastDate()}T12:00:00Z`).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
    assert(irregularHover.includes(expectedIrregularDate), `instrument hover follows actual time spacing (${irregularHover} != ${expectedIrregularDate})`);
    fixture.setQuoteShape('single');
    await page.locator('[data-route="holdings"]').click(); await waitForRoute(page, 'holdings', fixture);
    await page.locator('.instrument-link').first().click(); await waitForRoute(page, 'instrument', fixture);
    assert.equal(await page.locator('#instrument-chart path.chart-line').count(), 1, 'single quote produces a visible series path');
    fixture.setQuoteShape('empty');
    await page.locator('[data-route="holdings"]').click(); await waitForRoute(page, 'holdings', fixture);
    await page.locator('.instrument-link').first().click(); await waitForRoute(page, 'instrument', fixture);
    assert.match(await page.locator('#instrument-chart').textContent(), /No stored quotes/);

    const direct = await context.newPage();
    await direct.goto(`${baseUrl}/#/instrument/${instrumentId}?from=${from}&to=${to}`, { waitUntil: 'networkidle' });
    await direct.locator('#instrument-name').waitFor();
    await direct.locator('#instrument-back').click();
    await waitForRoute(direct, 'holdings', fixture);
    assert(direct.url().startsWith(baseUrl), 'Back from a direct detail link stays inside the application');

    fixture.setQuoteShape('normal');
    await page.locator('[data-route="performance"]').click(); await waitForRoute(page, 'performance', fixture);
    assert.equal(await page.locator('input[type=range]:visible').count(), 0, 'graphs use direct inspection rather than sliders');
    const closingDate = await page.locator('#to').inputValue();
    for (const years of [2, 5, 10]) {
      await page.locator(`[data-period="${years}Y"]`).click();
      await page.waitForFunction(expected => !document.querySelector('#from').disabled && document.querySelector('#from').value.slice(0, 4) === String(Number(expected.date.slice(0, 4)) - expected.years), { date: closingDate, years });
      assert.equal(await page.locator(`[data-period="${years}Y"]`).getAttribute('aria-pressed'), 'true');
    }
    const historyResponse = await page.request.get(`${baseUrl}/api/dashboard`);
    const historyStart = (await historyResponse.json()).inceptionDate;
    await page.locator('[data-period="MAX"]').click();
    await page.waitForFunction(expected => !document.querySelector('#from').disabled && document.querySelector('#from').value === expected, historyStart);
    assert.equal(await page.locator('[data-period="MAX"]').getAttribute('aria-pressed'), 'true');
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await page.locator('[data-period="MAX"]').getAttribute('aria-pressed'), 'true', 'reload preserves MAX');
    await page.evaluate(() => window.scrollTo(0, 180));
    await page.waitForTimeout(180);
    const beforeUrl = page.url(), beforeY = await page.evaluate(() => window.scrollY);
    const refreshed = page.waitForResponse(response => response.url().includes('/api/dashboard'));
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await refreshed;
    await page.waitForTimeout(200);
    assert.equal(page.url(), beforeUrl, 'automatic refresh does not change route or filters');
    assert(Math.abs((await page.evaluate(() => window.scrollY)) - beforeY) < 4, 'automatic refresh does not jump scroll');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('[data-route="performers"]').click(); await waitForRoute(page, 'performers', fixture);
    const performersOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    assert(performersOverflow <= 1, `mobile performers layout should not overflow horizontally (overflow=${performersOverflow}px)`);
    await page.locator('[data-route="performance"]').click(); await waitForRoute(page, 'performance', fixture);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    assert(overflow <= 1, `mobile layout should not overflow horizontally (overflow=${overflow}px)`);

    assert.deepEqual(pageErrors, [], `browser should not report uncaught errors: ${pageErrors.join('; ')}`);

    await direct.close(); await context.close();
    console.log('Browser regression passed: routes/history/filters, direct detail Back, synthetic tall-list restoration, hover/keyboard, irregular/single/empty chart data, silent refresh, and mobile width.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exit(1); });
