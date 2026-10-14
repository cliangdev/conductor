/** Slide stylesheet. `assetBase` is the URL (ending in "/") of the folder holding the bundled TikTok Sans TTFs. */
export function slideCss(assetBase: string): string {
  return String.raw`
@font-face{font-family:"TT";font-weight:600;src:url(${assetBase}tiktok-sans-600.ttf)}
@font-face{font-family:"TT";font-weight:700;src:url(${assetBase}tiktok-sans-700.ttf)}
@font-face{font-family:"TT";font-weight:800;src:url(${assetBase}tiktok-sans-800.ttf)}
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1080px;height:var(--H);overflow:hidden;background:#111}
#s{position:relative;width:1080px;height:var(--H);overflow:hidden;font-family:"TT",-apple-system,"Helvetica Neue",sans-serif}
.bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.dim{position:absolute;inset:0;background:rgba(0,0,0,.28)}
.blur{filter:blur(28px) brightness(.55) saturate(1.1);transform:scale(1.15)}
/* native TikTok text */
.txt{position:absolute;left:70px;right:150px;display:flex;flex-direction:column;align-items:center;gap:18px;text-align:center;z-index:5}
.txt.full{right:70px}
.box{display:inline;font-weight:700;font-size:var(--fs,60px);line-height:1.34;color:#111;background:#fff;padding:4px 18px;border-radius:14px;-webkit-box-decoration-break:clone;box-decoration-break:clone}
.box.dark{background:#111;color:#fff}
.stroke{font-weight:800;font-size:var(--fs,60px);line-height:1.22;color:#fff;-webkit-text-stroke:0;text-shadow:0 0 3px #000,0 0 3px #000,0 0 4px #000,0 2px 6px rgba(0,0,0,.8)}
.sub{font-weight:700;font-size:var(--ss,44px);line-height:1.25;color:#fff;text-shadow:0 0 3px #000,0 0 3px #000,0 2px 6px rgba(0,0,0,.8)}
/* phone with a real screenshot */
.phone{position:absolute;left:50%;width:var(--pw,640px);transform:translateX(-50%) rotate(var(--rot,0deg));border-radius:72px;background:#0b0b0c;padding:16px;box-shadow:0 40px 90px rgba(0,0,0,.55),0 0 0 3px #2a2a2e;z-index:3}
.phone .scr{border-radius:58px;overflow:hidden;position:relative;background:#fff}
.phone img{display:block;width:100%}
.phone .crop{overflow:hidden}
/* screenshot callouts: x/y are % of the screenshot box */
.callouts{position:absolute;left:16px;right:16px;top:16px;bottom:16px;z-index:6;pointer-events:none}
.callouts .dot{position:absolute;width:30px;height:30px;margin:-15px 0 0 -15px;border-radius:50%;background:#ff2d55;border:5px solid #fff;box-shadow:0 2px 10px rgba(0,0,0,.5)}
.callouts .ln{position:absolute;width:6px;margin-left:-3px;background:#ff2d55;box-shadow:0 0 0 2px #fff,0 2px 8px rgba(0,0,0,.4)}
.callouts .pill{position:absolute;width:max-content;max-width:560px;text-align:center;font-weight:700;font-size:42px;line-height:1.25;color:#111;background:#fff;padding:6px 20px;border-radius:14px;box-shadow:0 6px 22px rgba(0,0,0,.45)}
/* iOS Notes */
.notes{position:absolute;inset:0;background:#fffefa;color:#1c1c1e;font-family:-apple-system,"SF Pro Text","Helvetica Neue",sans-serif;padding:0 70px}
.notes .bar{display:flex;align-items:center;justify-content:space-between;color:#d9a400;font-size:52px;height:120px;margin-top:var(--top,120px)}
.notes .bar b{font-weight:400}
.notes .date{text-align:center;color:#8e8e93;font-size:36px;margin:6px 0 26px}
.notes h1{font-size:104px;font-weight:700;line-height:1.1;margin-bottom:26px;letter-spacing:-.5px}
.notes ul{list-style:none;display:grid;gap:var(--gap,20px)}
.notes li{font-size:var(--ls,64px);line-height:1.25;display:flex;gap:24px;align-items:flex-start}
.notes li i{flex:none;width:58px;height:58px;border-radius:50%;border:3px solid #c7c7cc;margin-top:6px}
.notes li.done i{background:#d9a400;border-color:#d9a400;position:relative}
.notes li.done i::after{content:"";position:absolute;left:13px;top:6px;width:11px;height:22px;border:solid #fff;border-width:0 5px 5px 0;transform:rotate(45deg)}
.notes li.done span{color:#8e8e93}
.notes li.strike span{text-decoration:line-through;color:#8e8e93}
.notes p{font-size:var(--ls,48px);line-height:1.3;margin-bottom:16px}
/* camera roll grid */
.grid{position:absolute;inset:0;background:#000;display:grid;grid-template-columns:repeat(var(--cols,3),1fr);gap:3px;align-content:start;padding-top:var(--top,0px)}
.grid div{position:relative;aspect-ratio:1/1;overflow:hidden}
.grid img{width:100%;height:100%;object-fit:cover}
.grid div.x img{filter:grayscale(1) brightness(.45)}
.grid div.ok::after{content:"";position:absolute;inset:0;box-shadow:inset 0 0 0 8px #34c759}
.badge{position:absolute;right:10px;bottom:8px;color:#fff;font:600 24px -apple-system,"Helvetica Neue",sans-serif;text-shadow:0 0 4px rgba(0,0,0,.8)}
.grid div.restore img{filter:saturate(.95) contrast(.96) sepia(.05)}
.grid .cap{position:absolute;left:0;right:0;bottom:0;height:30%;background:linear-gradient(transparent,rgba(0,0,0,.75));display:flex;flex-direction:column;justify-content:flex-end;gap:6px;padding:0 14px 14px}
.grid .cap b{display:block;height:9px;border-radius:5px;background:rgba(255,255,255,.75);width:80%}
.grid .cap b+b{width:55%}
.grid div.restore{outline:8px solid #fff;outline-offset:-4px;z-index:2;transform:scale(1.1);box-shadow:0 18px 50px rgba(0,0,0,.7)}
.grade{filter:saturate(.88) contrast(.94) sepia(.07) brightness(1.03)}
.grain{position:absolute;inset:0;z-index:4;pointer-events:none;opacity:.10;background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='240'><filter id='n'><feTurbulence baseFrequency='.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>")}
.stack{position:absolute;inset:0;display:grid;grid-template-rows:repeat(var(--n,3),1fr);gap:6px;background:#000}
.stack div{overflow:hidden;position:relative}
.stack img{width:100%;height:100%;object-fit:cover}
.stack .lbl{position:absolute;left:40px;top:var(--lt,30px);z-index:5;max-width:760px}
.stack .lbl .box{font-size:44px}
/* tier list */
.tier{position:absolute;left:0;right:0;top:var(--top,250px);font-family:"TT",sans-serif}
.tier h2{color:#fff;font-weight:800;font-size:46px;line-height:1.2;text-align:center;padding:0 80px 26px}
.tier .row{display:flex;min-height:150px;border-top:4px solid #000}
.tier .row:last-child{border-bottom:4px solid #000}
.tier .k{flex:none;width:170px;display:grid;place-items:center;font-weight:800;font-size:76px;color:#111}
.tier .v{flex:1;background:#1f1f22;color:#fff;display:flex;flex-wrap:wrap;align-items:center;gap:14px;padding:16px 22px;font-weight:700;font-size:var(--ts,40px);line-height:1.2}
.tier .v span{background:#33333a;border-radius:12px;padding:10px 16px}
/* week grid */
.week{position:absolute;left:60px;right:60px;top:var(--top,260px);font-family:-apple-system,"Helvetica Neue",sans-serif;background:#fff;border-radius:36px;padding:34px 36px;box-shadow:0 30px 80px rgba(0,0,0,.35)}
.week h3{font-size:56px;font-weight:700;color:#1c1c1e;margin-bottom:22px}
.week .d{display:flex;align-items:center;gap:26px;padding:20px 0;border-top:2px solid #ececf0}
.week .d b{flex:none;width:120px;font-size:40px;color:#8e8e93;font-weight:600;text-transform:uppercase}
.week .d .im{flex:none;width:110px;height:110px;border-radius:20px;overflow:hidden;background:#e9e9ee;display:grid;place-items:center;font-size:56px;color:#b0b0b8}
.week .d .im img{width:100%;height:100%;object-fit:cover}
.week .d span{font-size:52px;line-height:1.2;color:#1c1c1e}
.week .d.fog span{color:#8e8e93}
/* receipt */
.receipt{position:absolute;left:150px;right:150px;top:var(--top,250px);background:#fbfaf6;color:#222;font-family:"Courier New",Courier,monospace;font-size:36px;line-height:1.35;padding:46px 46px 70px;box-shadow:0 30px 80px rgba(0,0,0,.4);transform:rotate(var(--rot,-1.5deg))}
.receipt::after{content:"";position:absolute;left:0;right:0;bottom:-18px;height:20px;background:linear-gradient(-45deg,transparent 12px,#fbfaf6 0) 0 0/24px 20px repeat-x,linear-gradient(45deg,transparent 12px,#fbfaf6 0) 0 0/24px 20px repeat-x}
.receipt .c{text-align:center}
.receipt hr{border:0;border-top:3px dashed #999;margin:18px 0}
.receipt .a{font-weight:700;margin-top:12px}
.receipt .r{display:flex;justify-content:space-between}
/* museum placard */
.placard{position:absolute;left:90px;right:170px;top:var(--y);background:#f7f5ef;color:#1b1b1b;font-family:Georgia,"Times New Roman",serif;padding:34px 40px;box-shadow:0 18px 50px rgba(0,0,0,.45);border-radius:4px}
.placard .ex{font:600 30px "TT";letter-spacing:.14em;text-transform:uppercase;color:#6b6457;margin-bottom:10px}
.placard h4{font-size:60px;line-height:1.1;font-weight:700;margin-bottom:16px}
.placard p{font-size:40px;line-height:1.38}
.placard p i{font-style:normal;color:#6b6457}
/* iMessage thread */
.msgs{position:absolute;inset:0;background:#fff;font-family:-apple-system,"SF Pro Text","Helvetica Neue",sans-serif}
.msgs.clear{background:transparent}
.msgs .in{position:absolute;left:60px;right:150px;top:var(--top,300px);display:flex;flex-direction:column;gap:14px}
.msgs .ttl{text-align:center;font-size:48px;font-weight:600;color:#1c1c1e;margin-bottom:26px}
.msgs .b{max-width:80%;font-size:52px;line-height:1.25;padding:22px 36px;border-radius:50px;overflow-wrap:anywhere}
.msgs .me{align-self:flex-end;background:#0a84ff;color:#fff}
.msgs .them{align-self:flex-start;background:#e9e9eb;color:#000}
`
}
