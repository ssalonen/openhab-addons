import test from 'node:test';
import assert from 'node:assert/strict';
import * as helpers from '../../main/resources/migration/controller.mjs';
import { readFile } from 'node:fs/promises';

const base = new URL('../../main/resources/migration/', import.meta.url);

test('review layout puts a labelled focusable semantic table before collapsed technical YAML', async () => {
  const html = await readFile(new URL('index.html',base),'utf8');
  assert.match(html, /<h3[^>]*>Manual review entries<\/h3>/);
  assert.match(html, /role="region" aria-label="Manual review entries" tabindex="0"/);
  assert.match(html, /<th scope="col">Classification<\/th>/);
  assert.match(html, /<th scope="col">Next action<\/th>/);
  assert.match(html, /<tbody id="manual">/);
  assert.match(html, /<details><summary>Generated YAML/);
  assert.match(html, /read-only Number/);
  assert.match(html, /default link profile/);
  assert.match(html, /poller2 capabilities.*migrator coverage/i);
  assert.match(html, /not.*configuration.*invalid/i);
});

test('actual rendering separates candidate channels from zero planned Item links and safely details reasons', async () => {
  class Element {
    constructor(tag = '') { this.tagName = tag; this.value = ''; this.handlers = {}; this.children = []; }
    addEventListener(name, fn) { this.handlers[name] = fn; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
  }
  const ids = new Map();
  globalThis.document = {cookie:'',getElementById:id => { if (!ids.has(id)) ids.set(id,new Element()); return ids.get(id); }, createElement:tag => new Element(tag)};
  globalThis.window = {addEventListener() {}};
  globalThis.location = {origin:'https://openhab.test'};
  globalThis.localStorage = {getItem:() => 'refresh'};
  const unknown = '<img src=x onerror=alert(1)>';
  const work = [{sourceUid:'modbus:data:demo:meter:power:number',reason:profileReason},
    {sourceUid:'modbus:data:demo:meter:other',reason:unknown}];
  const plan = {identity:'review',groups:[{sourcePollerUid:'source',targetPollerUid:'candidate',targetBridgeUid:'bridge',channels:[{},{},{}],links:[]}],
    impacts:[1,2,3].map(i => ({itemName:`Item${i}`,sourceChannelUid:`source:${i}`,targetChannelUid:`candidate:${i}`,targetLinkPlanned:false})),manualWork:work,yaml:'things:'};
  globalThis.fetch = async url => new Response(JSON.stringify(url === '/rest/auth/token'
    ? {access_token:'access',expires_in:60} : url.endsWith('/previews')
      ? {plan,manualWork:[work[0]]} : {preview:plan,status:'PREVIEW'}));
  await import(new URL('app.mjs?readability',base));
  await ids.get('preview').handlers.click();
  const text = element => [element.textContent || '', ...element.children.map(text)].join(' ');
  const all = element => [element,...element.children.flatMap(all)];
  assert.ok(ids.has('overview'), 'renderer must populate an overview');
  assert.match(text(ids.get('overview')), /Candidate groups 1.*Candidate channels 3.*Planned Item links 0.*Manual review entries 2/);
  assert.match(text(ids.get('link-coverage')), /No fully automatic Item migrations.*0 planned Item links/);
  const rows = ids.get('manual').children;
  assert.equal(rows.length,2);
  for (const row of rows) assert.equal(row.children.length,4);
  assert.ok(all(ids.get('manual')).some(el => el.tagName === 'span' && el.className.includes('badge-profile') && el.textContent === 'Link profile review'));
  assert.ok(all(ids.get('manual')).some(el => el.tagName === 'p' && el.textContent === unknown), 'unknown reason stays literal text');
  const sourceDetails = all(ids.get('manual')).find(el => el.tagName === 'details' && text(el).includes('modbus:data:demo:meter:power:number'));
  assert.ok(sourceDetails);
  assert.ok(!sourceDetails.open);
  sourceDetails.open = true;
  await ids.get('refresh').handlers.click();
  assert.equal(all(ids.get('manual')).find(el => el.tagName === 'details' && text(el).includes('modbus:data:demo:meter:power:number')).open,true,'retain user-expanded details across renders');
  assert.equal(ids.get('error').hidden,true);
});

const writeReason = 'writes require manual migration; configuration omitted';
const profileReason = 'Item link profile configuration is not proven compatible; target link was not planned';

test('case styles provide contrasting text badges and keyboard-scrollable mobile tables', async () => {
  const css = await readFile(new URL('style.css',base),'utf8');
  const luminance = hex => {
    const rgb = hex.match(/[0-9a-f]{2}/gi).map(value => parseInt(value,16) / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
  };
  for (const category of ['writes','channels','conversion','profile','parent','target','transform','unknown']) {
    const style = css.match(new RegExp(`\\.badge-${category} \\{([^}]+)\\}`))?.[1];
    assert.ok(style, category);
    const fg = style.match(/color: (#[0-9a-f]{6});/)?.[1];
    const bg = style.match(/background: (#[0-9a-f]{6});/)?.[1];
    assert.ok(fg && bg);
    const values = [luminance(fg),luminance(bg)].sort((a,b) => a-b);
    assert.ok((values[1] + 0.05) / (values[0] + 0.05) >= 4.5, `${category} text contrast`);
  }
  assert.match(css, /\.review-table \{[^}]*min-width:/);
  assert.match(css, /:focus-visible/);
  const html = await readFile(new URL('index.html',base),'utf8');
  assert.match(html, /role="region" aria-label="Item link impact" tabindex="0"/);
});

test('review rows deduplicate exact source plus reason deterministically, not Things or categories', () => {
  assert.equal(typeof helpers.reviewEntries, 'function');
  const work = [
    {sourceUid:'modbus:data:b:p:z',reason:writeReason,configurationIdentity:'revision-2'},
    {sourceUid:'modbus:data:b:p:a:number',reason:profileReason},
    {sourceUid:'modbus:data:b:p:z',reason:writeReason,configurationIdentity:'revision-1'},
    {sourceUid:'modbus:data:b:p:z',reason:profileReason}
  ];
  const rows = helpers.reviewEntries(work, [work[0]]);
  assert.equal(rows.length, 3);
  assert.deepEqual(rows, helpers.reviewEntries([...work].reverse()));
  assert.equal(rows[0].sourceUid, 'modbus:data:b:p:a:number', 'channel ID must remain intact');
  assert.equal(rows[0].sourceLabel, 'number', 'short label is not claimed to be a Thing ID');
  const writes = rows.find(row => row.category === 'writes');
  assert.deepEqual(writes.configurationIdentities, ['revision-1','revision-2']);
  assert.equal(rows.filter(row => row.sourceUid === 'modbus:data:b:p:z').length, 2);
});

test('review rows never display an opaque malformed optional configuration identity', () => {
  const rows = helpers.reviewEntries([{sourceUid:'source',reason:writeReason,configurationIdentity:{private:'not a digest'}}]);
  assert.deepEqual(rows[0].configurationIdentities, []);
});

test('overview counts only DTO candidate groups, planned impacts and deduplicated review entries', () => {
  assert.equal(typeof helpers.reviewOverview, 'function');
  const external = [{sourceUid:'channel:number',reason:profileReason}];
  const plan = {groups:[{channels:[{},{}]},{channels:[{}]}], impacts:[{targetLinkPlanned:true},{targetLinkPlanned:false},{targetLinkPlanned:true}],
    manualWork:[...external,{sourceUid:'data',reason:writeReason}]};
  assert.deepEqual(helpers.reviewOverview(plan, external), {candidateGroups:2,candidateChannels:3,plannedLinks:2,manualReviewEntries:2});
  assert.deepEqual(helpers.reviewOverview(null, external), {candidateGroups:null,candidateChannels:null,plannedLinks:null,manualReviewEntries:1});
  assert.deepEqual(helpers.reviewOverview({groups:[{}],impacts:[],manualWork:[]}),
    {candidateGroups:1,candidateChannels:null,plannedLinks:0,manualReviewEntries:0});
});

test('sanitized server reasons classify conservatively without inferring conversion safety', () => {
  const cases = [
    ['writes require manual migration; configuration omitted', 'writes'],
    ['JSON write transform requires manual migration; configuration omitted', 'writes'],
    ['distinct source channels require manual migration; channel contracts cannot be collapsed', 'channels'],
    ['source channel type is unknown or its conversion is not proven compatible; requires manual migration', 'conversion'],
    ['Item link profile configuration is not proven compatible; target link was not planned', 'profile'],
    ['legacy data parent is not a poller; writes require manual migration', 'parent'],
    ['target poller2 Thing already exists', 'target'],
    ['custom readTransform requires manual migration; configuration omitted', 'transform'],
    ['new writes reason not in this client', 'unknown'],
    ['<img src=x onerror=alert(1)>', 'unknown']
  ];
  assert.equal(typeof helpers.classifyReviewReason, 'function');
  for (const [reason, category] of cases) {
    const result = helpers.classifyReviewReason(reason);
    assert.equal(result.category, category, reason);
    for (const field of ['label','explanation','nextAction']) assert.ok(result[field]?.length, field);
    assert.doesNotMatch(result.label, /ready|safe|authorized/i);
  }
});
