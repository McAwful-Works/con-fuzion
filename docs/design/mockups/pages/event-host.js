// The host's own event page: the location shows, and the Manage section lists guests, offers and the waitlist.
document.head.insertAdjacentHTML('beforeend', '<style>@media(max-width:1023px){.main{padding-bottom:130px}.hero .back{display:none}}</style>');
const guest = (n, sub, action = `<a class="btn ghost sm">Remove</a>`) =>
  `<div class="li">${av(n)}<div class="grow"><b>${n}</b><small>${sub}</small></div>${action}</div>`;
const html = `
  <section class="hero">
    <a class="back">${ic('chevron-left', 'sm')}My plans</a>
    <div style="margin-top:14px;display:flex;gap:8px"><span class="tagchip">Tabletop</span><span class="pill host" style="height:24px">${ic('sparkles')}You’re hosting</span></div>
    <h1>Board game meetup</h1>
    <div class="hostline">${av('Sam')}<span>For <b>Test Con</b> and <b>Cosplay crew</b></span></div>
  </section>
  <div class="card facts">
    <div class="fact"><span class="ico">${ic('clock')}</span><div><b>Saturday, Nov 28 · 6:00 – 9:00 PM EST</b><span>Signup opened Thu 12:00 PM</span></div></div>
    <div class="fact"><span class="ico">${ic('map-pin')}</span><div><b>Hall B, table 4</b><span>Only you and confirmed guests see this</span></div></div>
    <div class="fact"><span class="ico">${ic('users')}</span><div><b>8 of 8 spots taken · 3 waiting</b><span>Offers last 30 minutes</span></div></div>
  </div>
  <h2 class="section-title head">Manage guests</h2>
  <div class="seg"><span class="on">Going <span class="n">8</span></span><span>Offered <span class="n">1</span></span><span>Waitlist <span class="n">3</span></span></div>
  <div class="card list">
    ${guest('Sam', 'Host · that’s you', '')}
    ${guest('Jordan', 'Joined Thu 12:00 PM')}
    ${guest('Kai', 'Joined Thu 12:01 PM')}
    ${guest('Riley', 'Joined Thu 12:03 PM')}
    ${guest('Mo', 'Joined Thu 1:15 PM')}
    <div class="li"><span class="muted" style="font-size:13.5px;font-weight:600">+ 3 more</span></div>
  </div>
  <h2 class="section-title head">About</h2>
  <p class="desc">Bring a game or play one of ours! We’ll have Wingspan, Cascadia and a couple of party games. Beginners welcome — we’ll teach.</p>`;
const rail = `
  <div class="sticky">
    <div class="card" style="padding:20px">
      <h3 class="head" style="margin-bottom:12px">Your meetup</h3>
      <div class="meter" style="margin-bottom:16px"><span class="bar full" style="width:120px"><i style="width:100%"></i></span>Full · 3 waiting</div>
      <a class="btn primary block">${ic('pencil', 'sm')}Edit details</a>
      <a class="btn block" style="margin-top:10px">${ic('users', 'sm')}Change who can see it</a>
      <a class="btn ghost block danger" style="margin-top:6px">${ic('x', 'sm')}Cancel meetup</a>
    </div>
    <div class="card" style="padding:18px"><h3 class="head">Waitlist</h3>
      ${['Lee', 'Noor', 'Ash'].map((n, i) => `<div class="row" style="margin-top:10px"><b style="width:22px">#${i + 1}</b>${av(n, 'xs')}<span>${n}</span></div>`).join('')}
    </div>
  </div>`;
const after = `<div class="actionbar"><div class="row"><a class="btn grow">${ic('pencil', 'sm')}Edit</a><a class="btn grow">${ic('users', 'sm')}Audience</a><a class="btn ghost danger">Cancel</a></div></div>`;
mount('plans', html, { rail, after, tabs: false, top: backTop('Your meetup', `<a class="iconbtn">${ic('ellipsis')}</a>`) });
