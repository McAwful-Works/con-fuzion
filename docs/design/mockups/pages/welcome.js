// Signed-out welcome page.
document.head.insertAdjacentHTML('beforeend', `<style>
.w-top{display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:2px solid #1d1630;background:#fff6ea}
.discord{background:#5865f2!important;color:#fff!important}
.w-hero{padding:34px 20px 30px}
.w-hero h1{font-size:44px;line-height:1.02;margin:14px 0 14px}
.w-hero h1 em{font-style:normal;background:#ffe08a;padding:0 6px;border:2px solid #1d1630;border-radius:10px;display:inline-block;transform:rotate(-1.5deg)}
.w-hero p{font-size:17px;color:var(--muted);margin:0 0 22px;max-width:520px}
.w-small{font-size:13px;color:var(--muted);margin-top:12px;display:flex;gap:6px;align-items:flex-start}
.w-stack{position:relative;height:400px;margin-top:30px}
.w-stack .card{position:absolute;width:300px}
.w-steps{padding:8px 20px 30px;display:grid;gap:14px}
.w-step{padding:18px}
.w-step .num{display:grid;place-items:center;width:38px;height:38px;border-radius:10px;background:#1d1630;color:#fff6ea;font-family:var(--font-head);font-weight:800;font-size:18px;margin-bottom:10px}
.w-step h3{font-size:19px;margin-bottom:4px}
.w-step p{margin:0;color:var(--muted);font-size:14.5px}
.w-privacy{margin:0 20px 30px;padding:18px;background:#1d1630;color:#fff6ea;border-radius:16px;display:grid;gap:10px}
.w-privacy div{display:flex;gap:10px;font-size:14.5px}
.w-foot{padding:18px 20px 30px;font-size:13.5px;color:var(--muted);display:flex;gap:18px;flex-wrap:wrap}
@media (min-width:1024px){
  .w-top{padding:18px 48px}
  .w-hero{display:grid;grid-template-columns:1.05fr .95fr;gap:40px;align-items:center;padding:56px 48px 40px;max-width:1280px;margin:0 auto}
  .w-hero h1{font-size:68px}
  .w-stack{height:430px;margin:0}
  .w-steps{grid-template-columns:repeat(3,1fr);padding:10px 48px 36px;max-width:1280px;margin:0 auto;gap:22px}
  .w-privacy{grid-template-columns:repeat(3,1fr);margin:0 auto 36px;max-width:1184px;padding:22px 26px}
  .w-foot{max-width:1184px;margin:0 auto;padding:0 0 36px}
}
</style>`);
const mini = (e, style) => `<div style="${style}">${card(e)}</div>`;
document.getElementById('app').className = '';
document.getElementById('app').innerHTML = `
  <header class="w-top"><div class="wordmark">Con<b>-Fuzion</b></div><a class="btn sm discord">Sign in with Discord</a></header>
  <section class="w-hero">
    <div>
      <span class="tagchip">Free · for convention-goers</span>
      <h1>Meetups for your <em>con crew</em>.</h1>
      <p>Host a meetup, choose exactly who can see it, and let spots fill first come, first served. When someone drops out, the waitlist moves up on its own.</p>
      <a class="btn primary discord" style="height:54px;padding:0 26px;font-size:16px">Sign in with Discord</a>
      <div class="w-small">${ic('info', 'sm')}<span>We only ask Discord for your name, avatar and the servers you’re in. Never your messages or friends list.</span></div>
    </div>
    <div class="w-stack">
      ${mini({ time: '6:00', ap: 'PM', title: 'Board game meetup', tag: 'Tabletop', loc: null, host: 'Alice', aud: 'Test Con', left: 0, cap: 8, status: 'offer' }, 'position:absolute;left:0;top:0;width:330px;transform:rotate(-3deg)')}
      ${mini({ time: '3:00', ap: 'PM', title: 'Cosplay photo walk', tag: 'Photoshoot', loc: 'Main lobby fountain', host: 'Kai', aud: 'Test Con', left: null, going: 14, status: 'going' }, 'position:absolute;right:0;top:200px;width:330px;transform:rotate(2.5deg)')}
    </div>
  </section>
  <section class="w-steps">
    <div class="card w-step"><div class="num">1</div><h3 class="head">Sign in, pick your servers</h3><p>Choose which of your Discord servers people can find you in. Everything starts switched off.</p></div>
    <div class="card w-step"><div class="num">2</div><h3 class="head">Host and choose who sees it</h3><p>Open a meetup to a server, your friends, a circle, or just a few people. Nobody else can see it.</p></div>
    <div class="card w-step"><div class="num">3</div><h3 class="head">Spots fill fairly</h3><p>First come, first served. If someone leaves, the next person in line gets an offer to accept.</p></div>
  </section>
  <section class="w-privacy">
    <div>${ic('lock')}<span>Hide the location until people are confirmed.</span></div>
    <div>${ic('users')}<span>Only the host and confirmed guests see who’s going.</span></div>
    <div>${ic('shield')}<span>Block or report anyone. Moderators review every report.</span></div>
  </section>
  <footer class="w-foot"><span>About</span><span>Support Con-Fuzion</span><span>Privacy</span><span>Terms</span></footer>`;
