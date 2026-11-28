// Host a meetup, When: the start date picked on a month grid that shows your own plans, then the time.
document.head.insertAdjacentHTML('beforeend', `<style>
.only-m{display:block}.only-d{display:none}
.sec{padding:20px;margin-bottom:18px}.sec h2{font-size:20px;margin-bottom:4px}.sec .sub{color:var(--muted);font-size:13.5px;margin:0 0 16px}
.mstep{padding-bottom:150px}.mstep h1{font-size:28px;margin:4px 0 6px}
.mbar{position:fixed;left:0;right:0;bottom:0;padding:14px 16px 26px;background:var(--bar-bg);border-top:var(--bar-border);display:flex;gap:10px}
.calwrap{display:grid;gap:16px}
@media (min-width:1024px){.only-m{display:none}.only-d{display:block}.calwrap{grid-template-columns:340px 1fr;align-items:start}}
</style>`);
const PLANS = { 28: ['ho', 'of'], 29: ['wa', 'go', 'go'], 21: ['go'] };
const cells = [];
for (let d = 1; d <= 30; d++) cells.push({ d, out: false });
for (let d = 1; d <= 5; d++) cells.push({ d, out: true });
const SEL = 29, TODAY = 26;
const grid = `
  <div class="card cal" style="box-shadow:none">
    <div class="cal-head"><h3 class="head" style="font-size:16px">November 2026</h3><div class="cal-nav"><a>${ic('chevron-left', 'sm')}</a><a>${ic('chevron-right', 'sm')}</a></div></div>
    <div class="dow">${['S', 'M', 'T', 'W', 'T', 'F', 'S'].map((d) => `<span>${d}</span>`).join('')}</div>
    <div class="grid">${cells.map((c) => {
      const p = c.out ? [] : PLANS[c.d] || [];
      const past = !c.out && c.d < TODAY;
      const cls = [c.out ? 'out' : '', c.d === TODAY && !c.out ? 'today' : '', c.d === SEL && !c.out ? 'sel' : '', past ? 'out' : ''].join(' ');
      return `<div class="d ${cls}">${c.d}<span class="dots">${p.map((k) => `<i class="${k}"></i>`).join('')}</span></div>`;
    }).join('')}</div>
    <div class="hint" style="margin-top:10px">Dots are your own plans. Past days can’t be picked.</div>
  </div>`;
const side = `
  <div>
    <span class="flabel">Sunday, Nov 29 · start time</span>
    <div class="timepick" style="margin-bottom:12px">${['10:00 AM', '12:00 PM', '2:00 PM', '4:00 PM', '6:00 PM', '8:00 PM'].map((t) => `<span class="${t === '6:00 PM' ? 'on' : ''}">${t}</span>`).join('')}<span>${ic('clock', 'sm')}Other…</span></div>
    <div class="card" style="padding:12px 14px;box-shadow:none;background:var(--surface-2);margin-bottom:14px">
      <b style="font-size:13.5px;display:block;margin-bottom:6px">Already that day</b>
      <div class="row" style="font-size:13.5px;margin-bottom:4px"><span class="dots"><i class="wa"></i></span>11:00 AM · Artist alley crawl <span class="muted">(waitlisted)</span></div>
      <div class="row" style="font-size:13.5px;margin-bottom:4px"><span class="dots"><i class="go"></i></span>12:30 PM · Ramen run</div>
      <div class="row" style="font-size:13.5px"><span class="dots"><i class="go"></i></span>3:00 PM · Cosplay photo walk</div>
    </div>
    ${field('Ends <span class="muted">(optional)</span>', 'Sun, Nov 29 · 9:00 PM', { icon: 'clock' })}
    <div class="warnline">${ic('info', 'sm')}Your time: 3:00 – 6:00 PM PST</div>
  </div>`;
const tz = field('Time zone', 'America/New_York <span class="muted">· Eastern (EST)</span>', { icon: 'globe', trailing: ic('chevron-right', 'sm') });
const desktop = `
  <div class="page-head"><div><div class="eyebrow">Host a meetup</div><h1>New meetup</h1></div></div>
  <section class="card sec"><h2 class="head">When</h2><p class="sub">Times are in the event’s own time zone, where the con is.</p>${tz}
    <span class="flabel">Starts</span><div class="calwrap">${grid}${side}</div></section>`;
const mobile = `
  <div class="mstep">
    <div class="steps"><i class="on"></i><i class="on"></i><i></i><i></i><i></i></div>
    <div class="eyebrow">Step 2 of 5 · When</div><h1 class="head">When is it?</h1>
    <div style="margin-top:14px">${tz}<span class="flabel">Starts</span>${grid}<div style="height:14px"></div>${side}</div>
  </div>
  <div class="mbar"><a class="btn">Back</a><a class="btn primary grow">Next: Spots ${ic('chevron-right', 'sm')}</a></div>`;
const rail = `<div class="sticky"><div class="eyebrow" style="margin-bottom:10px">Preview in Discover</div>${card({ time: '6:00', ap: 'PM', title: 'Board game meetup', tag: 'Tabletop', loc: null, host: 'Sam', aud: 'Test Con', left: 7, cap: 8, status: 'host' })}<a class="btn primary block" style="margin-top:14px">${ic('party-popper', 'sm')}Create meetup</a></div>`;
mount('host', `<div class="only-d">${desktop}</div><div class="only-m">${mobile}</div>`, { rail, tabs: false, top: backTop('Host a meetup', `<span class="muted" style="font-weight:600;font-size:14px;padding-right:8px">Cancel</span>`) });
