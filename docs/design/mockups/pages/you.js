// You / Settings.
const row = (icon, title, sub, right = ic('chevron-right', 'sm'), cls = '') =>
  `<div class="li ${cls}"><span class="nico" style="background:var(--surface-2)">${ic(icon)}</span><div class="grow"><b>${title}</b>${sub ? `<small>${sub}</small>` : ''}</div>${right}</div>`;
const html = `
  <div class="card" style="padding:18px;display:flex;align-items:center;gap:14px;margin-bottom:6px">
    <span class="avatar" style="width:64px;height:64px;font-size:24px;background:#0f9d76">S</span>
    <div class="grow"><h1 class="head" style="font-size:26px">Sam</h1><div class="muted" style="font-size:14px">@sam_builds · from Discord</div></div>
  </div>
  <div class="cols2">
    <div>
      <div class="group-title">Discovery</div>
      <div class="card list">
        ${row('globe', 'Servers', 'Discoverable in 2 of 5')}
        ${row('users', 'Blocked people', '1 person')}
      </div>
      <div class="group-title">Notifications</div>
      <div class="card list">
        ${row('bell', 'Notifications on this device', 'Offers, changes, reminders', toggle(true))}
        ${row('sparkles', 'Install Con-Fuzion', 'Add it to your home screen for push on iPhone')}
      </div>
      <div class="group-title">Time</div>
      <div class="card list">${row('globe', 'Your time zone', 'America/Los_Angeles · Pacific (PST)')}</div>
    </div>
    <div>
      <div class="group-title">Account</div>
      <div class="card list">
        ${row('log-out', 'Sign out', '', '')}
        ${row('log-out', 'Sign out everywhere', 'Ends your sessions on every device', '')}
        ${row('x', 'Delete my account', 'Removes your profile, plans and friends', '', 'danger')}
      </div>
      <div class="group-title">About</div>
      <div class="card list">
        ${row('info', 'About Con-Fuzion', 'Free meetups for convention-goers')}
        ${row('party-popper', 'Support Con-Fuzion', 'Help cover hosting costs')}
        ${row('lock', 'Privacy', '')}
        ${row('flag', 'Terms', '')}
      </div>
    </div>
  </div>`;
mount('you', html, { top: backTop('You', '') });
