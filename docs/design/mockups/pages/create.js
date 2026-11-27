// Host a meetup. Desktop: one page in sections with a live preview. Phone: five short steps (STEP picks which one).
document.head.insertAdjacentHTML('beforeend', `<style>
.only-m{display:block}.only-d{display:none}
.sec{padding:20px;margin-bottom:18px}
.sec h2{font-size:20px;margin-bottom:4px}
.sec .sub{color:var(--muted);font-size:13.5px;margin:0 0 16px}
.tagpick{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:16px}
.stepper{display:flex;align-items:center;gap:0;border:var(--chip-border);border-radius:12px;overflow:hidden;width:fit-content;background:var(--surface)}
.stepper span{display:grid;place-items:center;height:46px;min-width:46px;font-weight:800;font-size:18px}
.stepper span.v{min-width:70px;border-left:2px solid #1d1630;border-right:2px solid #1d1630;font-family:var(--font-head)}
.mstep{padding-bottom:150px}
.mstep h1{font-size:28px;margin:4px 0 6px}
.mbar{position:fixed;left:0;right:0;bottom:0;padding:14px 16px 26px;background:var(--bar-bg);border-top:var(--bar-border);display:flex;gap:10px}
@media (min-width:1024px){.only-m{display:none}.only-d{display:block}}
</style>`);
const STEP = window.STEP || 4;
const aud = (label, sub, on, icon) => `<div class="card optcard ${on ? 'sel' : ''}">${icon}<div class="grow"><b>${label}</b><div class="muted" style="font-size:13px">${sub}</div></div><span class="check ${on ? 'on' : ''}">${on ? ic('check') : ''}</span></div>`;
const audiences = `
  ${aud('All my friends', '12 friends', false, `<span class="nico" style="background:#e3dbff;color:#3d22b8">${ic('users')}</span>`)}
  <div class="group-title">Servers where you’re discoverable</div>
  ${aud('Test Con', 'Members who are discoverable there', true, serverIcon('Test Con', '#ff5a36'))}
  ${aud('Anime Weekend 2027', 'Members who are discoverable there', false, serverIcon('Anime Weekend', '#7c5cff'))}
  <div class="group-title">Your circles</div>
  ${aud('Cosplay crew', '5 people', true, `<span class="nico" style="background:#c9f2d6;color:#0d5c2c">${ic('users-round')}</span>`)}
  <div class="group-title">Specific people</div>
  ${field('', 'Search friends and people you share a server with', { icon: 'search', placeholder: true })}
  <div class="row" style="flex-wrap:wrap;margin-top:-6px"><span class="addedchip">${av('Noor', 'xs')}Noor ${ic('x', 'sm')}</span><span class="addedchip">${av('Pat', 'xs')}Pat ${ic('x', 'sm')}</span></div>`;
const tags = ['Tabletop', 'Photoshoot', 'Food', 'Music', 'Panel', 'Shopping', 'Hobby'].map((t, i) => `<span class="chip ${i === 0 ? 'on' : ''}">${t}</span>`).join('') + `<span class="chip">${ic('plus', 'sm')}Other</span>`;
const when = `
  ${field('Time zone', 'America/New_York <span class="muted">· Eastern (EST)</span>', { icon: 'globe', trailing: ic('chevron-right', 'sm'), hint: 'Search by city or zone. Defaults to the zone you used last.' })}
  <div class="formgrid two">
    ${field('Starts', 'Sat, Nov 28 · 6:00 PM', { icon: 'calendar' })}
    ${field('Ends <span class="muted">(optional)</span>', 'Sat, Nov 28 · 9:00 PM', { icon: 'clock' })}
  </div>
  <div class="warnline" style="margin-top:-4px">${ic('info', 'sm')}Your time: 3:00 – 6:00 PM PST</div>`;
const spots = `
  <div class="row" style="justify-content:space-between;margin-bottom:16px"><div><b>Limit the spots</b><div class="muted" style="font-size:13px">Includes you. Off means anyone can join.</div></div>${toggle(true)}</div>
  <div class="row" style="gap:14px;margin-bottom:18px"><div class="stepper"><span>−</span><span class="v">8</span><span>+</span></div><span class="muted" style="font-size:13.5px">When it’s full, people join a waitlist and move up as others leave.</span></div>
  <div class="formgrid two">
    ${field('Signup opens <span class="muted">(optional)</span>', 'Thu, Nov 26 · 12:00 PM', { icon: 'timer', hint: 'Lock Join until a set time so everyone gets a fair shot.' })}
    ${field('Time to accept an offer', '30 minutes', { icon: 'hourglass', trailing: ic('chevron-right', 'sm'), hint: 'How long the next person in line has to say yes.' })}
  </div>`;
const details = `
  ${field('Title', 'Board game meetup')}
  <span class="flabel">Tag</span><div class="tagpick">${tags}</div>
  ${field('Description <span class="muted">(optional)</span>', 'Bring a game or play one of ours! Beginners welcome — we’ll teach.', {})}
  ${field('Location', 'Hall B, table 4', { icon: 'map-pin' })}
  <div class="row" style="justify-content:space-between"><div><b>Hide the location until people are confirmed</b><div class="muted" style="font-size:13px">Viewers see “Location shared with confirmed attendees”.</div></div>${toggle(true)}</div>`;
const desktop = `
  <div class="page-head"><div><div class="eyebrow">Host a meetup</div><h1>New meetup</h1></div></div>
  <section class="card sec"><h2 class="head">Details</h2><p class="sub">What it is and where to meet.</p>${details}</section>
  <section class="card sec"><h2 class="head">When</h2><p class="sub">Times are in the event’s own time zone, where the con is.</p>${when}</section>
  <section class="card sec"><h2 class="head">Spots</h2><p class="sub">First come, first served.</p>${spots}</section>
  <section class="card sec"><h2 class="head">Who can see this</h2><p class="sub">Only the people you pick can find it. Pick at least one.</p>${audiences}</section>`;
const STEPS = [
  ['Details', 'What’s the plan?', details],
  ['When', 'When is it?', when],
  ['Spots', 'How many spots?', spots],
  ['Who can see this', 'Who can see it?', audiences],
  ['Review', 'Look good?', ''],
];
const [label, title, body] = STEPS[STEP - 1];
const mobile = `
  <div class="mstep">
    <div class="steps">${STEPS.map((_, i) => `<i class="${i < STEP ? 'on' : ''}"></i>`).join('')}</div>
    <div class="eyebrow">Step ${STEP} of 5 · ${label}</div>
    <h1 class="head">${title}</h1>
    <div style="margin-top:14px">${body}</div>
  </div>
  <div class="mbar"><a class="btn">Back</a><a class="btn primary grow">Next: ${STEPS[STEP][0]} ${ic('chevron-right', 'sm')}</a></div>`;
const rail = `
  <div class="sticky">
    <div class="eyebrow" style="margin-bottom:10px">Preview in Discover</div>
    <div style="margin-bottom:16px">${card({ time: '6:00', ap: 'PM', title: 'Board game meetup', tag: 'Tabletop', loc: null, host: 'Sam', aud: 'Test Con +1', left: 7, cap: 8, status: 'host' })}</div>
    <div class="card" style="padding:16px;margin-bottom:14px"><b style="display:block;margin-bottom:6px">Visible to</b>
      <div class="muted" style="font-size:13.5px">Test Con (discoverable members) · Cosplay crew · Noor · Pat</div></div>
    <a class="btn primary block">${ic('party-popper', 'sm')}Create meetup</a>
  </div>`;
mount('host', `<div class="only-d">${desktop}</div><div class="only-m">${mobile}</div>`, { rail, tabs: false, top: backTop('Host a meetup', `<span class="muted" style="font-weight:600;font-size:14px;padding-right:8px">Cancel</span>`) });
