// My plans, calendar view: a month grid with dots on busy days; tapping a day lists its meetups.
const PLANS = {
  28: [['2:00 PM', 'Gunpla build session', 'ho', 'Hosting'], ['6:00 PM', 'Board game meetup', 'of', 'Spot offered · 24:12']],
  29: [['11:00 AM', 'Artist alley crawl', 'wa', 'Waitlisted #2'], ['12:30 PM', 'Ramen run', 'go', 'Going'], ['3:00 PM', 'Cosplay photo walk', 'go', 'Going']],
  21: [['7:00 PM', 'Friday pub quiz', 'go', 'Going']],
  4: [['1:00 PM', 'Figure swap meet', 'go', 'Going']],
};
const SEL = 29, TODAY = 26;
const PILL = { go: 'pill going', wa: 'pill wait', of: 'pill offer', ho: 'pill host' };
// November 2026 starts on a Sunday; Dec 1–5 fill the last row.
const cells = [];
for (let d = 1; d <= 30; d++) cells.push({ d, out: false });
for (let d = 1; d <= 5; d++) cells.push({ d, out: true });
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const small = `
  <div class="card cal">
    <div class="cal-head"><h3 class="head">November 2026</h3><div class="cal-nav"><a>${ic('chevron-left', 'sm')}</a><a>${ic('chevron-right', 'sm')}</a></div></div>
    <div class="dow">${DOW.map((d) => `<span>${d[0]}</span>`).join('')}</div>
    <div class="grid">${cells.map((c) => {
      const p = c.out ? [] : PLANS[c.d] || [];
      const cls = [c.out ? 'out' : '', c.d === TODAY && !c.out ? 'today' : '', c.d === SEL && !c.out ? 'sel' : '', !c.out && c.d < TODAY ? 'past' : ''].join(' ');
      return `<div class="d ${cls}">${c.d}<span class="dots">${p.slice(0, 3).map((e) => `<i class="${e[2]}"></i>`).join('')}</span></div>`;
    }).join('')}</div>
    <div class="legend"><span><i style="background:#1aa36a"></i>Going</span><span><i style="background:#e0a400"></i>Waiting</span><span><i style="background:#ff5a36"></i>Offer</span><span><i style="background:#7450f5"></i>Hosting</span></div>
  </div>`;

const big = `
  <div class="card cal bigcal">
    <div class="cal-head"><h3 class="head">November 2026</h3><div class="cal-nav"><a class="btn sm" style="width:auto;padding:0 12px">Today</a><a>${ic('chevron-left', 'sm')}</a><a>${ic('chevron-right', 'sm')}</a></div></div>
    <div class="dow">${DOW.map((d) => `<span>${d}</span>`).join('')}</div>
    <div class="grid">${cells.map((c) => {
      const p = c.out ? [] : PLANS[c.d] || [];
      const cls = [c.out ? 'out' : '', c.d === TODAY && !c.out ? 'today' : '', c.d === SEL && !c.out ? 'sel' : ''].join(' ');
      return `<div class="d ${cls}"><span class="n">${c.d}</span>${p.slice(0, 2).map((e) => `<span class="ev-chip ${e[2]}">${e[0].replace(':00', '')} ${e[1]}</span>`).join('')}${p.length > 2 ? `<span class="ev-chip" style="color:var(--muted)">+${p.length - 2} more</span>` : ''}</div>`;
    }).join('')}</div>
  </div>`;

const day = `
  <div class="daylist">
    <h2 class="head">Sunday, Nov 29 <span class="muted" style="font-size:14px;font-family:var(--font-body);font-weight:600">· 3 meetups</span></h2>
    ${PLANS[29].map(([t, title, k, label]) => `<div class="card mini"><span class="t">${t}</span><div class="grow"><b>${title}</b><small>${k === 'wa' ? 'Location shared when confirmed' : 'Main lobby fountain'}</small></div><span class="${PILL[k]}">${label}</span></div>`).join('')}
    <div class="warnline">${ic('info', 'sm')}Artist alley crawl and Ramen run overlap</div>
  </div>`;

const head = `
  <div class="page-head"><div><div class="eyebrow">5 upcoming</div><h1>My plans</h1></div>
    <div class="seg viewtoggle" style="margin:0"><span>${ic('list', 'sm')}List</span><span class="on">${ic('calendar-days', 'sm')}Calendar</span></div></div>
  <div class="seg"><span class="on">All <span class="n">6</span></span><span>Going <span class="n">4</span></span><span>Waiting <span class="n">2</span></span><span>Hosting <span class="n">1</span></span></div>`;

document.head.insertAdjacentHTML('beforeend', '<style>.only-m{display:block}.only-d{display:none}@media(min-width:1024px){.only-m{display:none}.only-d{display:block}}@media(max-width:1023px){.page-head{flex-wrap:wrap}.viewtoggle{width:auto!important}}</style>');
const rail = `<div class="card" style="padding:16px">${day.replace('<div class="daylist">', '<div class="daylist" style="margin-top:-18px">')}</div>`;
mount('plans', `${head}<div class="only-m">${small}${day}</div><div class="only-d">${big}</div>`, { rail });
