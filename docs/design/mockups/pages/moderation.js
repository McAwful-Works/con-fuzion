// Moderation: the report queue with the selected report's detail.
document.head.insertAdjacentHTML('beforeend', `<style>
.modgrid{display:block}.mcards{display:block}.mtable{display:none}
@media (min-width:1024px){.content.single{grid-template-columns:minmax(0,1160px)}.modgrid{display:grid;grid-template-columns:1fr 380px;gap:24px;align-items:start}.mcards{display:none}.mtable{display:block}}
</style>`);
const reason = (r, c) => `<span class="pill" style="background:${c[0]};color:${c[1]}">${r}</span>`;
const R = [
  ['Board game meetup', 'Event', 'Unsafe', ['#ffd8cc', '#9c2a0b'], 'Lee', '14 min', true],
  ['Jordan', 'User', 'Harassment', ['#ffe08a', '#6b4a00'], 'Noor', '2 hr', false],
  ['FREE NITRO giveaway', 'Event', 'Spam', ['#e3dbff', '#3d22b8'], 'Kai', '5 hr', false],
  ['Late-night karaoke', 'Event', 'Other', ['#f6ead9', '#6b6079'], 'Ash', '1 day', false],
];
const detail = `
  <div class="card" style="padding:20px">
    <div class="row" style="justify-content:space-between;margin-bottom:10px">${reason('Unsafe', R[0][3])}<span class="muted" style="font-size:13px">Open · 14 min ago</span></div>
    <h3 class="head" style="font-size:20px">Board game meetup</h3>
    <div class="muted" style="font-size:13.5px;margin:2px 0 14px">Event by Alice · Test Con · Sat 6:00 PM · 8 going</div>
    <div class="card" style="padding:12px 14px;box-shadow:none;margin-bottom:14px;background:var(--surface-2)"><b style="font-size:13px">Reporter’s note</b><p style="margin:4px 0 0;font-size:14px">The location is a hotel room, not a public space. Doesn’t feel safe for a public meetup.</p></div>
    <div class="row" style="justify-content:space-between;font-size:13.5px;margin-bottom:14px"><span class="muted">Reporter · hidden from the host</span><a style="font-weight:700">Look up</a></div>
    <div style="display:grid;gap:10px">
      <a class="btn primary">${ic('lock', 'sm')}Hide event</a>
      <div class="row"><a class="btn sm grow">Cancel event</a><a class="btn sm grow">Suspend host</a></div>
      <a class="btn sm">${ic("check","sm")}Resolve with a note</a><span class="hint" style="margin:0">Act first, then resolve. The reporter only hears that it was reviewed.</span>
    </div>
  </div>`;
const html = `
  <div class="page-head"><div><div class="eyebrow">Moderators and admins</div><h1>Moderation</h1></div></div>
  <div class="seg"><span class="on">Reports <span class="n">4</span></span><span>Users</span><span>Audit log</span></div>
  <div class="modgrid">
    <div class="card mtable" style="padding:0;overflow:hidden">
      <table class="table"><thead><tr><th>Reported</th><th>Reason</th><th>Waiting</th></tr></thead><tbody>
      ${R.map(([t, k, r, c, by, age, sel]) => `<tr class="${sel ? 'sel' : ''}"><td><b>${t}</b><div class="muted" style="font-size:12.5px">${k}</div></td><td>${reason(r, c)}</td><td>${age}</td></tr>`).join('')}
      </tbody></table>
    </div>
    <div class="mcards">${R.map(([t, k, r, c, by, age]) => `<div class="card" style="padding:14px;margin-bottom:10px"><div class="row" style="justify-content:space-between">${reason(r, c)}<span class="muted" style="font-size:12.5px">${age}</span></div><b style="display:block;margin-top:8px;font-size:16px">${t}</b><span class="muted" style="font-size:13px">${k}</span></div>`).join('')}</div>
    <div class="only-d-detail">${detail}</div>
  </div>`;
document.head.insertAdjacentHTML('beforeend', '<style>.only-d-detail{display:none}@media(min-width:1024px){.only-d-detail{display:block}}</style>');
mount('mod', html, { top: backTop('Moderation', '') });
