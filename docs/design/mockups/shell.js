// Mockup shell: theme from ?t=, the navigation (sidebar on desktop, top bar + tab bar on phones), and icons.
const params = new URLSearchParams(location.search);
const T = params.get('t') || 'a';
document.body.classList.add('t-' + T[0]);
if (T.endsWith('d')) document.body.classList.add('dark');

const ic = (n, cls = '') =>
  `<svg class="i ${cls}" viewBox="0 0 24 24" aria-hidden="true">${window.ICONS[n] || ''}</svg>`;
const PEOPLE = {
  Alice: '#7c5cff', Jordan: '#e8590c', Sam: '#0f9d76', Riley: '#d6336c', Kai: '#1c7ed6', Mo: '#ae3ec9', Dev: '#2b8a3e',
  Lee: '#c2255c', Noor: '#5f3dc4', Ash: '#e67700', Pat: '#1098ad', Quinn: '#495057',
};
const av = (name, cls = '') =>
  `<span class="avatar ${cls}" style="background:${PEOPLE[name] || '#868e96'}">${name[0]}</span>`;
window.ic = ic;
window.av = av;

const NAV = [
  { id: 'discover', label: 'Discover', icon: 'compass' },
  { id: 'plans', label: 'My plans', icon: 'calendar-check' },
  { id: 'host', label: 'Host', icon: 'plus' },
  { id: 'people', label: 'People', icon: 'users' },
  { id: 'inbox', label: 'Inbox', icon: 'bell', count: 3 },
];

function shell(active, top) {
  const side = `
  <aside class="sidebar">
    <div class="wordmark">Con<b>-Fuzion</b></div>
    ${NAV.filter((n) => n.id !== 'host')
      .map((n) => `<a class="nav ${n.id === active ? 'on' : ''}">${ic(n.icon)}${n.label}${n.count ? `<span class="count">${n.count}</span>` : ''}</a>`)
      .join('')}
    <a class="btn primary block" ${active === 'host' ? 'style="outline:3px solid #fff6ea;outline-offset:2px"' : ''}>${ic('plus')}Host a meetup</a>
    <div class="sep"></div>
    <a class="nav ${active === 'mod' ? 'on' : ''}">${ic('shield')}Moderation<span class="count">4</span></a>
    <a class="nav ${active === 'you' ? 'on' : ''}">${ic('settings')}Settings</a>
    <div class="me">${av('Sam')}<div class="grow"><b>Sam</b><span>Discoverable in 2 servers</span></div>${ic('chevron-right', 'sm')}</div>
  </aside>`;
  const topbar = `
  <header class="topbar">
    ${top || `<div class="wordmark grow">Con<b>-Fuzion</b></div><a class="iconbtn">${ic('search')}</a><a class="iconbtn">${av('Sam')}</a>`}
  </header>`;
  const tabs = `
  <nav class="tabbar">
    ${NAV.map((n) =>
      n.id === 'host'
        ? `<a class="tab host"><span class="plus">${ic('plus')}</span>Host</a>`
        : `<a class="tab ${n.id === active ? 'on' : ''}">${ic(n.icon)}${n.label}${n.count ? `<span class="badge">${n.count}</span>` : ''}</a>`,
    ).join('')}
  </nav>`;
  return { side, topbar, tabs };
}

/** A phone top bar with a back arrow and a title, for pages below the five places. */
window.backTop = (title, right = '') =>
  `<a class="iconbtn">${ic('chevron-left')}</a><div class="grow head" style="font-size:17px">${title}</div>${right}`;

window.mount = (active, html, { rail = '', after = '', tabs = true, top = '' } = {}) => {
  const s = shell(active, top);
  document.getElementById('app').innerHTML = `
    ${s.side}
    <div class="col">
      ${s.topbar}
      <div class="content ${rail ? '' : 'single'}">
        <main class="main">${html}</main>
        ${rail ? `<aside class="rail">${rail}</aside>` : ''}
      </div>
    </div>
    ${after}
    ${tabs ? s.tabs : ''}`;
};
