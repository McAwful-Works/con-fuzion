// Event page for a confirmed guest, with "Add to calendar" and its menu open.
document.head.insertAdjacentHTML('beforeend', '<style>@media(max-width:1023px){.main{padding-bottom:300px}.hero .back{display:none}}</style>');
const menu = (pos) => `
  <div class="card menu" style="${pos}">
    <div class="li">${ic('download', 'sm')}<div class="grow"><b style="font-size:14px">Download .ics</b><small>Apple Calendar, Outlook, most apps</small></div></div>
    <div class="li">${ic('calendar-plus', 'sm')}<div class="grow"><b style="font-size:14px">Open in Google Calendar</b><small>Adds it from a link</small></div></div>
    <div class="li" style="font-size:12.5px;color:var(--muted)">${ic('lock', 'sm')}<span>Includes the time and place you can see. Not kept in sync: if the host changes it, add it again.</span></div>
  </div>`;
const html = `
  <section class="hero withcover"><span class="covertag">Licensed photo · Tabletop</span>
    <a class="back">${ic('chevron-left', 'sm')}My plans</a>
    <div style="margin-top:14px"><span class="tagchip">Tabletop</span></div>
    <h1>Board game meetup</h1>
    <div class="hostline">${av('Alice')}<span>Hosted by <b>Alice</b> · for <b>Test Con</b></span></div>
  </section>
  <div class="card facts">
    <div class="fact"><span class="ico">${ic('clock')}</span><div><b>Saturday, Nov 28 · 6:00 – 9:00 PM EST</b><span>Your time: 3:00 – 6:00 PM PST</span></div></div>
    <div class="fact"><span class="ico">${ic('map-pin')}</span><div><b>Hall B, table 4</b><span>Shown because you’re going</span></div></div>
    <div class="fact"><span class="ico">${ic('users')}</span><div><b>8 of 8 spots taken · 3 waiting</b><span>First come, first served</span></div></div>
  </div>
  <h2 class="section-title head">About</h2>
  <p class="desc">Bring a game or play one of ours! Beginners welcome — we’ll teach.</p>`;
const rail = `
  <div class="sticky"><div class="card" style="padding:20px;position:relative">
    <div class="row" style="justify-content:space-between;margin-bottom:14px"><span class="pill going">${ic('check')}You’re going</span></div>
    <a class="btn primary block">${ic('calendar-plus', 'sm')}Add to calendar</a>
    <a class="btn ghost block" style="margin-top:6px">Leave</a>
    ${menu('top:118px;right:20px')}
  </div></div>`;
const after = `<div class="actionbar"><div style="position:relative">${menu('bottom:calc(100% + 18px);top:auto;right:0;width:100%')}</div><div class="row"><span class="pill going" style="height:34px">${ic('check')}Going</span><a class="btn primary grow">${ic('calendar-plus', 'sm')}Add to calendar</a><a class="btn ghost">Leave</a></div></div>`;
mount('plans', html, { rail, after, tabs: false, top: backTop('Meetup', '') });
