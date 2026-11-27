// Discover with every approved feature: search, filter chips, side cards, and cover photos per tag.
const COV = { Tabletop: ['#c8bbff', '#b9a8ff'], Music: ['#ffc9b8', '#ffb8a3'], Shopping: ['#bff0d9', '#a9e8cb'], Photoshoot: ['#ffe39a', '#ffd970'], Hobby: ['#bde0ff', '#a8d4ff'] };
const cc = (e) => card(e)
  .replace('<article class="card ev">', `<article class="card ev withcover"><div class="cover" style="--cv1:${COV[e.tag][0]};--cv2:${COV[e.tag][1]}"><span>Licensed photo · ${e.tag}</span></div><div class="evbody">`)
  .replace(/<\/article>\s*$/, '</div></article>');
const DAYS = [
  { day: 'Saturday', date: 'Nov 28', items: [
    { time: '6:00', ap: 'PM', title: 'Board game meetup', tag: 'Tabletop', loc: null, host: 'Alice', aud: 'Test Con', left: 0, cap: 8, status: 'offer' },
    { time: '11:00', ap: 'PM', title: 'Late-night karaoke', tag: 'Music', loc: 'Room 210', host: 'Jordan', aud: 'Friends', left: 5, cap: 12, status: null },
  ]},
  { day: 'Sunday', date: 'Nov 29', items: [
    { time: '11:00', ap: 'AM', title: 'Artist alley crawl', tag: 'Shopping', loc: 'Meet at Hall C entrance', host: 'Riley', aud: 'Anime Weekend 2027', left: 0, cap: 6, status: 'wait', pos: 2 },
    { time: '3:00', ap: 'PM', title: 'Cosplay photo walk', tag: 'Photoshoot', loc: 'Main lobby fountain', host: 'Kai', aud: 'Test Con', left: null, going: 14, status: 'going' },
  ]},
];
const html = `
  <div class="page-head"><div><div class="eyebrow">Test Con · Anime Weekend 2027</div><h1>Discover</h1></div></div>
  <div class="search">${ic('search')}<span>Search meetups</span></div>
  <div class="chips"><span class="chip on">All</span><span class="chip">${ic('globe', 'sm')}Test Con</span><span class="chip">Anime Weekend 2027</span><span class="chip">${ic('users', 'sm')}Friends</span><span class="chip">Has spots</span></div>
  ${DAYS.map((d) => `<div class="day"><h2>${d.day}</h2><span class="muted">${d.date}</span></div><div class="covergrid">${d.items.map(cc).join('')}</div>`).join('')}`;
const rail = `
  <div class="card"><h3 class="head">Needs your answer</h3>
    <div class="row" style="gap:12px">${ic('timer', 'lg')}<div class="grow"><b>Board game meetup</b><div class="muted" style="font-size:13px">A spot is held for you for 24 min</div></div></div>
    <a class="btn primary block sm" style="margin-top:14px">Review offer</a></div>
  <div class="card"><h3 class="head">Next up for you</h3>
    <div class="row" style="gap:12px;margin-bottom:12px">${ic('calendar-check')}<div class="grow"><b>Gunpla build session</b><div class="muted" style="font-size:13px">Sat 2:00 PM · you’re hosting</div></div></div>
    <div class="row" style="gap:12px">${ic('calendar-check')}<div class="grow"><b>Cosplay photo walk</b><div class="muted" style="font-size:13px">Sun 3:00 PM · going</div></div></div></div>
  <div class="card"><h3 class="head">Where you’re discoverable</h3>
    <div class="row" style="flex-wrap:wrap;gap:8px"><span class="chip">${ic('check', 'sm')}Test Con</span><span class="chip">${ic('check', 'sm')}Anime Weekend 2027</span></div>
    <a class="muted" style="display:block;margin-top:12px;font-size:13.5px;font-weight:600">Manage servers →</a></div>`;
mount('discover', html, { rail, top: `<div class="wordmark grow">Con<b>-Fuzion</b></div><a class="iconbtn">${av('Sam')}</a>` });
