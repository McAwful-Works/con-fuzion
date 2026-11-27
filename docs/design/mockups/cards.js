// Shared mockup pieces: event cards, pills, meters, toggles and form fields.
window.pill = (e) => ({
  host: `<span class="pill host">${ic('sparkles')}Hosting</span>`,
  offer: `<span class="pill offer">${ic('timer')}Spot offered · 24:12</span>`,
  wait: `<span class="pill wait">${ic('hourglass')}Waitlisted #${e.pos}</span>`,
  going: `<span class="pill going">${ic('check')}Going</span>`,
}[e.status] || (e.left === 0 ? `<span class="pill full">Full · join waitlist</span>` : ''));
window.meter = (e) => e.cap
  ? `<div class="meter"><div class="bar ${e.left === 0 ? 'full' : ''}"><i style="width:${((e.cap - e.left) / e.cap) * 100}%"></i></div>${e.left === 0 ? 'Full' : `${e.left} of ${e.cap} left`}</div>`
  : `<div class="meter">${ic('users', 'sm')}${e.going} going · no limit</div>`;
window.card = (e) => `
  <article class="card ev">
    <div class="when"><div class="t">${e.time}</div><div class="ap">${e.ap}</div></div>
    <div class="grow">
      <span class="tagchip">${e.tag}</span>
      <h3>${e.title}</h3>
      <div class="meta">${e.loc ? ic('map-pin') + e.loc : ic('lock') + 'Location shared with confirmed attendees'}</div>
      <div class="meta">${av(e.host, 'xs')}<span>${e.host === 'Sam' ? 'You’re hosting' : 'Hosted by ' + e.host} · ${e.aud}</span></div>
      ${e.warn ? `<div class="warnline">${ic('info', 'sm')}${e.warn}</div>` : ''}
      <div class="foot">${meter(e)}${pill(e)}</div>
    </div>
  </article>`;
window.toggle = (on) => `<span class="switch ${on ? 'on' : ''}"><i></i></span>`;
window.field = (label, value, { hint = '', icon = '', placeholder = false, trailing = '' } = {}) => `
  <label class="field"><span class="flabel">${label}</span>
    <span class="input ${placeholder ? 'ph' : ''}">${icon ? ic(icon) : ''}<span class="grow">${value}</span>${trailing}</span>
    ${hint ? `<span class="hint">${hint}</span>` : ''}
  </label>`;
window.serverIcon = (name, color) =>
  `<span class="sicon" style="background:${color}">${name.split(' ').map((w) => w[0]).join('').slice(0, 2)}</span>`;
