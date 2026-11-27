// My plans: everything you're going to, waiting for, or hosting.
const DAYS = [
  { day: 'Saturday', date: 'Nov 28', items: [
    { time: '2:00', ap: 'PM', title: 'Gunpla build session', tag: 'Hobby', loc: 'Room 114', host: 'Sam', aud: 'Test Con', left: 3, cap: 8, status: 'host' },
    { time: '6:00', ap: 'PM', title: 'Board game meetup', tag: 'Tabletop', loc: null, host: 'Alice', aud: 'Test Con', left: 0, cap: 8, status: 'offer' },
  ]},
  { day: 'Sunday', date: 'Nov 29', items: [
    { time: '11:00', ap: 'AM', title: 'Artist alley crawl', tag: 'Shopping', loc: 'Meet at Hall C entrance', host: 'Riley', aud: 'Anime Weekend 2027', left: 0, cap: 6, status: 'wait', pos: 2 },
    { time: '12:30', ap: 'PM', title: 'Ramen run', tag: 'Food', loc: 'Lobby, by the escalators', host: 'Mo', aud: 'Friends', left: 2, cap: 6, status: 'going', warn: 'Overlaps with Artist alley crawl' },
    { time: '3:00', ap: 'PM', title: 'Cosplay photo walk', tag: 'Photoshoot', loc: 'Main lobby fountain', host: 'Kai', aud: 'Test Con', left: null, going: 14, status: 'going' },
  ]},
];
const html = `
  <div class="page-head"><div><div class="eyebrow">5 upcoming</div><h1>My plans</h1></div></div>
  <div class="seg"><span class="on">All <span class="n">5</span></span><span>Going <span class="n">2</span></span><span>Waiting <span class="n">2</span></span><span>Hosting <span class="n">1</span></span></div>
  ${DAYS.map((d) => `<div class="day"><h2>${d.day}</h2><span class="muted">${d.date}</span></div>${d.items.map(card).join('')}`).join('')}`;
const rail = `
  <div class="card"><h3 class="head">Needs your answer</h3>
    <div class="row" style="gap:12px">${ic('timer', 'lg')}<div class="grow"><b>Board game meetup</b><div class="muted" style="font-size:13px">A spot is held for you for 24 min</div></div></div>
    <a class="btn primary block sm" style="margin-top:14px">Review offer</a>
  </div>
  <div class="card"><h3 class="head">Saved for offline</h3>
    <p class="muted" style="margin:0;font-size:13.5px">My plans works without a connection at the con. Joining and leaving need one.</p>
  </div>
  <div class="card"><h3 class="head">Get a nudge</h3>
    <p class="muted" style="margin:0 0 12px;font-size:13.5px">Turn on notifications for offers, changes and a reminder an hour before.</p>
    <a class="btn sm block">${ic('bell', 'sm')}Turn on notifications</a>
  </div>`;
mount('plans', html, { rail });
