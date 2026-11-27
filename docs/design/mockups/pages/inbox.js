// Inbox: notifications, newest first, unread marked.
const N = (icon, bg, fg, title, body, when, unread, action = '') => `
  <div class="li" style="align-items:flex-start;padding:14px">
    <span class="nico" style="background:${bg};color:${fg}">${ic(icon)}</span>
    <div class="grow"><b>${title}</b><small style="margin-top:2px">${body}</small>${action}<small style="margin-top:6px;font-size:12px">${when}</small></div>
    ${unread ? '<span class="dot" style="margin-top:6px"></span>' : ''}
  </div>`;
const html = `
  <div class="page-head"><div><div class="eyebrow">3 unread</div><h1>Inbox</h1></div><a class="btn ghost sm">${ic('check', 'sm')}Mark all read</a></div>
  <div class="group-title">Today</div>
  <div class="card list">
    ${N('timer', '#ff5a36', '#fff', 'A spot opened up: Board game meetup', 'It’s held for you until 6:24 PM. Accept it or it goes to the next person.', '12 min ago', true, `<div style="margin-top:10px"><a class="btn primary sm">Review offer</a></div>`)}
    ${N('pencil', '#ffe08a', '#6b4a00', 'Ramen run moved to 12:30 PM', 'Mo changed the start time. Your spot is kept.', '1 hr ago', true)}
    ${N('users', '#e3dbff', '#3d22b8', 'Noor accepted your invite', 'You’re now friends.', '3 hr ago', true)}
  </div>
  <div class="group-title">Earlier</div>
  <div class="card list">
    ${N('circle-check', '#c9f2d6', '#0d5c2c', 'You’re in: Cosplay photo walk', 'See you Sunday at 3:00 PM.', 'Yesterday', false)}
    ${N('x', '#f6ead9', '#6b6079', 'Cancelled: Friday pub quiz', 'Jordan cancelled the meetup.', 'Yesterday', false)}
    ${N('shield', '#f6ead9', '#6b6079', 'We reviewed your report', 'Thanks for helping keep meetups safe.', 'Mon', false)}
  </div>`;
const rail = `
  <div class="card"><h3 class="head">Notifications on this device</h3>
    <div class="row" style="justify-content:space-between"><span class="muted" style="font-size:13.5px">Offers, changes and a reminder an hour before</span>${toggle(true)}</div>
  </div>`;
mount('inbox', html, { rail });
