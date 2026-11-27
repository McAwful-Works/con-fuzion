// People: friends, circles, invites and blocks.
const friends = [['Alice', 'Friends since Nov 12'], ['Jordan', 'Friends since Nov 20'], ['Kai', 'Friends since Nov 20'], ['Mo', 'Friends since Nov 21'], ['Noor', 'Friends since Nov 22'], ['Riley', 'Friends since Nov 24']];
const invite = `
  <div class="card" style="padding:18px">
    <h3 class="head" style="font-size:18px;margin-bottom:6px">Add a friend</h3>
    <p class="muted" style="margin:0 0 14px;font-size:13.5px">Show your code at the con, or send the link. It works once and expires in 24 hours.</p>
    <div class="row" style="gap:16px;align-items:flex-start"><div class="qr">Your QR code<br>(generated in the app)</div>
      <div class="grow" style="display:grid;gap:10px"><a class="btn sm">${ic('share-2', 'sm')}Copy link</a><a class="btn sm">${ic('qr-code', 'sm')}Scan a code</a></div></div>
  </div>`;
const circles = `
  <div class="card list">
    <div class="li"><span class="nico" style="background:#c9f2d6;color:#0d5c2c">${ic('users-round')}</span><div class="grow"><b>Cosplay crew</b><small>5 people</small></div>${ic('chevron-right', 'sm')}</div>
    <div class="li"><span class="nico" style="background:#ffe08a;color:#6b4a00">${ic('users-round')}</span><div class="grow"><b>Tabletop regulars</b><small>9 people</small></div>${ic('chevron-right', 'sm')}</div>
    <div class="li"><span class="nico" style="background:#e3dbff;color:#3d22b8">${ic('users-round')}</span><div class="grow"><b>Room 1412</b><small>4 people</small></div>${ic('chevron-right', 'sm')}</div>
  </div>
  <a class="btn block sm" style="margin-top:10px">${ic('plus', 'sm')}New circle</a>`;
const html = `
  <div class="page-head"><div><div class="eyebrow">Your people</div><h1>People</h1></div><a class="btn primary sm only-m-btn">${ic('plus', 'sm')}Add</a></div>
  <div class="seg"><span class="on">Friends <span class="n">12</span></span><span>Circles <span class="n">3</span></span><span>Blocked <span class="n">1</span></span></div>
  <div class="card list">
    ${friends.map(([n, s]) => `<div class="li">${av(n, 'lg')}<div class="grow"><b>${n}</b><small>${s}</small></div><a class="iconbtn">${ic('ellipsis')}</a></div>`).join('')}
  </div>
  <p class="hint" style="margin-top:12px">Circles are private lists only you see. Use them to open a meetup to a few people at once.</p>`;
const rail = `${invite}<div style="height:16px"></div><h3 class="head" style="font-size:15px;margin:0 0 10px">Circles</h3>${circles}`;
mount('people', html, { rail });
