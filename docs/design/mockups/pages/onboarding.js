// First sign-in: step 1 of 3, where you're discoverable.
document.head.insertAdjacentHTML('beforeend', `<style>
.ob{max-width:640px;margin:0 auto;padding:18px 16px 140px}
.ob h1{font-size:32px;line-height:1.08;margin:8px 0 8px}
.ob .lead{color:var(--muted);font-size:15.5px;margin:0 0 18px}
.ob-top{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:2px solid #1d1630}
.ob-bar{position:fixed;left:0;right:0;bottom:0;padding:14px 16px 26px;background:var(--bar-bg);border-top:var(--bar-border);display:flex;gap:10px}
.ob-labels{display:flex;justify-content:space-between;font-size:12.5px;font-weight:700;color:var(--muted);margin:-10px 0 18px}
.ob-labels b{color:var(--ink)}
@media (min-width:1024px){
  .ob{padding:40px 0 140px}
  .ob h1{font-size:42px}
  .ob-top{padding:18px 48px}
  .ob-bar{position:static;background:none;border:0;padding:8px 0 0;justify-content:flex-end}
}
</style>`);
const servers = [
  ['Anime Weekend 2027', '#7c5cff', true, '2,340 members'],
  ['Test Con', '#ff5a36', true, '812 members'],
  ['Cosplay Corner', '#0f9d76', false, '5,120 members'],
  ['🎮 Game Night', '#1c7ed6', false, '64 members'],
  ['Work stuff', '#495057', false, '38 members'],
];
document.getElementById('app').className = '';
document.getElementById('app').innerHTML = `
  <header class="ob-top"><div class="wordmark">Con<b>-Fuzion</b></div><span class="muted" style="font-weight:600;font-size:14px">Skip for now</span></header>
  <div class="ob">
    <div class="steps"><i class="on"></i><i></i><i></i></div>
    <div class="ob-labels"><b>1 · Servers</b><span>2 · Notifications</span><span>3 · Install</span></div>
    <h1 class="head">Where can people find you?</h1>
    <p class="lead">Turn on the servers where you’re happy for other members to see you and invite you. Hosts in those servers can open meetups to you. Everything starts off.</p>
    <div class="sheet-head"><span class="eyebrow">Your servers · 5</span><span style="font-weight:700;font-size:14px;display:flex;gap:10px;align-items:center">Turn on all ${toggle(false)}</span></div>
    <div class="card list">
      ${servers.map(([n, c, on, m]) => `<div class="li">${serverIcon(n.replace(/^\W+/, ''), c)}<div class="grow"><b>${n}</b><small>${on ? 'Discoverable' : 'Hidden'}</small></div>${toggle(on)}</div>`).join('')}
    </div>
    <p class="hint" style="margin-top:12px">${'You can change this any time in Settings → Servers. We never see a server’s member list.'}</p>
    <div class="ob-bar"><a class="btn primary grow" style="max-width:320px;margin-left:auto">Continue ${ic('chevron-right', 'sm')}</a></div>
  </div>`;
