document.head.insertAdjacentHTML('beforeend','<style>@media(max-width:1023px){.main{padding-bottom:220px}}</style>');
const offer = `
  <div class="offer-box">
    <div class="grow"><b class="head" style="font-size:17px">A spot opened up for you</b><div class="muted" style="font-size:13.5px">Accept before Sat 6:24 PM or it goes to the next person</div></div>
    <div class="countdown">24:12</div>
  </div>`;
const actions = `<div class="row"><a class="btn ghost">Decline</a><a class="btn primary grow">${ic('check')}Accept spot</a></div>`;
const html = `
  <section class="hero withcover"><span class="covertag">Licensed photo · Tabletop</span>
    <a class="back">${ic('chevron-left', 'sm')}Discover</a>
    <div style="margin-top:14px"><span class="tagchip">Tabletop</span></div>
    <h1>Board game meetup</h1>
    <div class="hostline">${av('Alice')}<span>Hosted by <b>Alice</b> · for <b>Test Con</b></span></div>
  </section>
  <div class="card facts">
    <div class="fact"><span class="ico">${ic('clock')}</span><div><b>Saturday, Nov 28 · 6:00 – 9:00 PM EST</b><span>Your time: 3:00 – 6:00 PM PST</span></div></div>
    <div class="fact"><span class="ico">${ic('lock')}</span><div><b>Location shared when you’re confirmed</b><span>Accept your spot to see where to meet</span></div></div>
    <div class="fact"><span class="ico">${ic('users')}</span><div><b>8 of 8 spots taken · 3 waiting</b><span>First come, first served. Spots free up as people leave.</span></div></div>
  </div>
  <h2 class="section-title head">About</h2>
  <p class="desc">Bring a game or play one of ours! We’ll have Wingspan, Cascadia and a couple of party games. Beginners welcome — we’ll teach.</p>
  <h2 class="section-title head">Who’s going</h2>
  <div class="card locked"><span class="ico">${ic('lock')}</span>
    <div><b style="font-size:14.5px">8 going</b><div class="muted" style="font-size:13.5px">Only the host and confirmed guests see who’s going</div></div>
  </div>
  <div class="row" style="margin-top:22px"><a class="btn ghost sm">${ic('flag', 'sm')}Report event</a></div>`;
const rail = `
  <div class="sticky">
    <div class="card" style="padding:20px">
      <div class="row" style="justify-content:space-between;margin-bottom:14px"><span class="pill offer">${ic('timer')}Spot offered</span><span class="meter"><span class="bar full"><i style="width:100%"></i></span>Full</span></div>
      ${offer.replace('class="offer-box"', 'class="offer-box stacked"').replace('<div class="countdown">24:12</div>', '').replace('<div class="grow">', '<div class="countdown">24:12</div><div class="grow">')}${actions}
    </div>
  </div>`;
const after = `<div class="actionbar">${offer}${actions}</div>`;
mount('discover', html, { rail, after, tabs: false });
