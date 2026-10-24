package xyz.a150811.hermes;

import org.json.JSONObject;

/**
 * Restyles the official Hermes web UI (assistant-ui + shadcn sidebar) into a
 * WeChat-like conversation and offers a left/top switch for the icon rail.
 * All changes are DOM-level and reversible; the React app keeps running.
 */
final class ChatSkin {
    private static final String CSS = """
            html.hermes-wx{
              --ui-chat-surface-background:#ededed!important;
              --ui-chat-bubble-background:#95ec69!important;
              --dt-user-bubble:#95ec69!important;
              --conversation-turn-gap:14px!important;
            }
            html.hermes-wx body{background:#ededed!important}
            html.hermes-wx [data-slot=aui_thread-viewport],
            html.hermes-wx [data-slot=aui_thread-content]{background:#ededed!important}
            html.hermes-wx [data-slot=aui_thread-content]{max-width:100%!important;padding-left:10px!important;padding-right:10px!important}
            html.hermes-wx [data-slot=aui_turn-pair]{gap:10px!important}

            html.hermes-wx .composer-human-message{
              display:flex!important;
              width:fit-content!important;max-width:84%!important;min-width:0!important;
              margin-left:auto!important;margin-right:0!important;
              background:#95ec69!important;color:#111!important;
              border:0!important;border-radius:8px!important;
              padding:8px 12px!important;
              box-shadow:0 1px 2px rgba(0,0,0,.06)!important;
              backdrop-filter:none!important;-webkit-backdrop-filter:none!important;
              overflow:visible!important;position:relative!important;
              text-align:left!important;
            }
            html.hermes-wx .composer-human-message,
            html.hermes-wx .composer-human-message *{color:#111!important}
            html.hermes-wx .composer-human-message::after{
              content:"";position:absolute;top:13px;right:-6px;width:0;height:0;
              border-top:6px solid transparent;border-bottom:6px solid transparent;
              border-left:7px solid #95ec69;
            }
            html.hermes-wx [data-slot=aui_user-inline-code]{background:rgba(255,255,255,.6)!important;border-radius:4px!important}
            html.hermes-wx [data-slot=aui_user-fence]{background:rgba(255,255,255,.55)!important;border-radius:6px!important}
            html.hermes-wx [data-slot=aui_user-bubble-actions]{align-self:flex-end!important}

            html.hermes-wx [data-slot=aui_assistant-message-content]{
              background:#ffffff!important;color:#111!important;
              border:0!important;border-radius:8px!important;
              width:fit-content!important;max-width:94%!important;
              padding:9px 12px!important;padding-left:12px!important;
              box-shadow:0 1px 2px rgba(0,0,0,.05)!important;
              overflow-x:auto!important;overflow-y:hidden!important;
            }
            html.hermes-wx [data-slot=aui_assistant-message-content] p,
            html.hermes-wx [data-slot=aui_assistant-message-content] li,
            html.hermes-wx [data-slot=aui_assistant-message-content] h1,
            html.hermes-wx [data-slot=aui_assistant-message-content] h2,
            html.hermes-wx [data-slot=aui_assistant-message-content] h3,
            html.hermes-wx [data-slot=aui_assistant-message-content] h4,
            html.hermes-wx [data-slot=aui_assistant-message-content] h5,
            html.hermes-wx [data-slot=aui_assistant-message-content] h6,
            html.hermes-wx [data-slot=aui_assistant-message-content] td,
            html.hermes-wx [data-slot=aui_assistant-message-content] th{color:#111!important}
            html.hermes-wx [data-slot=aui_assistant-message-content] a{color:#576b95!important}
            html.hermes-wx [data-slot=aui_system-message-root]{color:#8a8a8a!important}
            html.hermes-wx [data-slot=aui_thinking-disclosure]{color:#8a8a8a!important}
            html.hermes-wx [data-slot=aui_msg-actions]{opacity:.75!important}

            html.hermes-wx [data-slot=composer-root]{background:#f7f7f7!important}
            html.hermes-wx [data-slot=composer-surface]{
              background:#ffffff!important;border:1px solid #e3e3e3!important;
              border-radius:10px!important;box-shadow:none!important;
            }
            html.hermes-wx [data-slot=composer-bounds]{background:transparent!important}
            html.hermes-wx [data-slot=composer-rich-input]{color:#111!important}

            #hermes-nav-tab{
              position:fixed!important;right:0!important;top:38%!important;
              width:26px!important;height:42px!important;
              display:none;align-items:center;justify-content:center;
              padding:0!important;margin:0!important;
              border:1px solid #d0d0d0!important;border-right:0!important;
              border-radius:8px 0 0 8px!important;
              background:rgba(255,255,255,.86)!important;color:#444!important;
              font-size:12px!important;font-weight:600!important;line-height:1!important;
              box-shadow:0 1px 4px rgba(0,0,0,.15)!important;
              z-index:2147483000!important;cursor:pointer!important;
              font-family:inherit!important;
            }

            html.hermes-nav-top [data-hermes-rail-wrap="1"]{
              width:0!important;min-width:0!important;max-width:0!important;flex:0 0 0!important;
              padding:0!important;margin:0!important;border:0!important;
              overflow:visible!important;background:transparent!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"]{
              position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:auto!important;
              width:auto!important;height:46px!important;min-height:46px!important;max-height:46px!important;
              box-sizing:border-box!important;
              display:flex!important;flex-direction:row!important;align-items:center!important;justify-content:flex-start!important;
              gap:4px!important;padding:0 8px!important;margin:0!important;
              background:#ffffff!important;border:0!important;border-bottom:1px solid #dcdcdc!important;
              border-radius:0!important;overflow-x:auto!important;overflow-y:hidden!important;
              box-shadow:0 1px 4px rgba(0,0,0,.06)!important;
              z-index:2147483000!important;
              scrollbar-width:none!important;-ms-overflow-style:none!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"]::-webkit-scrollbar,
            html.hermes-nav-top [data-hermes-rail="1"] *::-webkit-scrollbar{
              width:0!important;height:0!important;display:none!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"] *{scrollbar-width:none!important}
            html.hermes-nav-top [data-hermes-rail="1"] :is(div,ul,menu,nav,section){
              display:flex!important;flex-direction:row!important;align-items:center!important;
              width:auto!important;min-width:0!important;height:auto!important;max-height:46px!important;
              margin:0!important;padding:0!important;overflow:visible!important;
              border:0!important;gap:4px!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"] *{
              flex-shrink:0!important;transform:none!important;writing-mode:horizontal-tb!important;
              max-height:46px!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"] button,
            html.hermes-nav-top [data-hermes-rail="1"] [role=button],
            html.hermes-nav-top [data-hermes-rail="1"] a,
            html.hermes-nav-top [data-hermes-rail="1"] [data-slot=pane-tab]{
              width:38px!important;min-width:38px!important;height:38px!important;min-height:38px!important;
              display:flex!important;align-items:center!important;justify-content:center!important;
              padding:0!important;margin:0!important;border-radius:8px!important;
            }
            html.hermes-nav-top [data-hermes-rail="1"] :is(button,[role=button],a,[data-slot=pane-tab]):is([aria-selected="true"],[data-active="true"],[data-state="on"],[aria-current="page"],[data-selected="true"]){
              background:#e3f0ff!important;color:#1a73e8!important;
            }
            """;

    private static final String JS = """
            (function(){
              var d=document;
              if(!d.documentElement)return;
              var css=__CSS__;
              var skin=__SKIN__;
              d.documentElement.classList.toggle('hermes-wx',!!skin);
              var st=d.getElementById('hermes-wx-style');
              if(!st){
                st=d.createElement('style');st.id='hermes-wx-style';
                (d.head||d.documentElement).appendChild(st);
              }
              st.textContent=css;

              setupVoice();

              if(window.__hermesNav){
                window.__hermesNav.refresh();
                return;
              }

              var ATTR='data-hermes-rail';
              var WRAP='data-hermes-rail-wrap';
              var TAB='hermes-nav-tab';
              var KEY='hermes_nav_top';
              var rail=null;
              var wraps=[];
              var timer=0;
              var topMode=true;
              try{topMode=localStorage.getItem(KEY)!=='left';}catch(e){}

              function navEls(el){
                return el.querySelectorAll('button,[role="button"],a[href],[data-slot="pane-tab"],[tabindex]');
              }
              function findRail(){
                var all=d.body.querySelectorAll('div,aside,nav,section,ul,menu');
                var best=null,bestScore=-1;
                for(var i=0;i<all.length;i++){
                  var el=all[i];
                  if(el.id===TAB||el.hasAttribute(ATTR))continue;
                  var r=el.getBoundingClientRect();
                  if(!r||r.width<24||r.width>88)continue;
                  if(r.left>10||r.right>100)continue;
                  if(r.height<Math.min(260,(window.innerHeight||600)*0.4))continue;
                  var n=navEls(el).length;
                  if(n<5){
                    var svgs=el.querySelectorAll('svg').length;
                    if(svgs>=5&&r.width<=56)n=svgs;else continue;
                  }
                  var score=n*100-r.width;
                  if(score>bestScore){bestScore=score;best=el;}
                }
                return best;
              }
              function pickOuter(el){
                var n=navEls(el).length;
                var p=el.parentElement;
                while(p&&p!==d.body&&p!==d.documentElement){
                  var r=p.getBoundingClientRect();
                  if(r.width>0&&r.width<=100&&r.left<=10&&navEls(p).length===n){
                    el=p;p=p.parentElement;
                  }else break;
                }
                return el;
              }
              function markWraps(el){
                clearWraps();
                var n=navEls(el).length;
                var p=el.parentElement;
                while(p&&p!==d.body&&p!==d.documentElement){
                  var r=p.getBoundingClientRect();
                  if(r.width>0&&r.width<=110&&r.left<=10&&navEls(p).length===n){
                    p.setAttribute(WRAP,'1');wraps.push(p);p=p.parentElement;
                  }else break;
                }
              }
              function clearWraps(){
                for(var i=0;i<wraps.length;i++){
                  try{wraps[i].removeAttribute(WRAP);}catch(e){}
                }
                wraps=[];
              }
              function dropRail(){
                if(rail){try{rail.removeAttribute(ATTR);}catch(e){}}
                rail=null;clearWraps();
              }
              function apply(){
                var de=d.documentElement;
                var ok=!!(rail&&rail.isConnected);
                de.classList.toggle('hermes-nav-top',ok&&topMode);
                var tab=d.getElementById(TAB);
                if(!ok){
                  if(tab)tab.style.display='none';
                  return;
                }
                if(!tab){
                  tab=d.createElement('button');
                  tab.id=TAB;tab.type='button';
                  tab.addEventListener('click',function(){
                    if(window.__hermesNav)window.__hermesNav.toggle();
                  });
                  (d.body||de).appendChild(tab);
                }
                tab.style.display='flex';
                tab.textContent=topMode?'左':'顶';
                tab.title=topMode?'导航栏移回左侧':'导航栏移到顶部';
                tab.setAttribute('aria-label',tab.title);
              }
              function scan(){
                if(rail&&rail.isConnected){apply();return;}
                var f=findRail();
                if(!f){dropRail();apply();return;}
                dropRail();
                rail=pickOuter(f);
                try{rail.setAttribute(ATTR,'1');}catch(e){}
                markWraps(rail);
                apply();
              }
              window.__hermesNav={refresh:scan,toggle:function(){
                topMode=!topMode;
                try{localStorage.setItem(KEY,topMode?'top':'left');}catch(e){}
                apply();
              }};

              function setupVoice(){
                if(window.__hermesVoice){
                  try{window.__hermesVoice.refresh();}catch(e){}
                  return;
                }
                var insecure=!window.isSecureContext
                  ||!navigator.mediaDevices
                  ||typeof navigator.mediaDevices.getUserMedia!=='function';
                if(!insecure){
                  window.__hermesVoice={refresh:function(){},isMic:function(){return false;}};
                  return;
                }
                var RE=/voice|dictat|speech|microphone|mic|record|listen|语音|听写|转写|话筒|麦克风|录音/i;
                var SKIP=/engine|引擎|播放|speak|read|朗读|对话|conversation/i;
                function composer(){
                  return d.querySelector('[data-slot="composer-root"]')
                    ||d.querySelector('[data-slot="composer-dock"]')
                    ||d.querySelector('[data-slot="composer-surface"]')
                    ||d.querySelector('[data-slot="composer-bounds"]');
                }
                function label(b){
                  return ((b.getAttribute('aria-label')||'')+' '
                    +(b.getAttribute('title')||'')+' '
                    +(b.getAttribute('data-tooltip')||'')).trim();
                }
                function isMic(b){
                  if(!b||b.tagName!=='BUTTON')return false;
                  var anchor=b.closest?b.closest('[data-slot="fan-menu-anchor"]'):null;
                  if(anchor&&b.getAttribute('aria-haspopup')==='true')return true;
                  var root=composer();
                  if(root&&root.contains(b)){
                    var l=label(b);
                    if(l&&RE.test(l)&&!SKIP.test(l))return true;
                    if(b.hasAttribute('aria-pressed')&&b.hasAttribute('data-active')
                      &&!(b.textContent||'').trim())return true;
                  }
                  if(!root){
                    var l2=label(b);
                    if(l2&&RE.test(l2)&&!SKIP.test(l2)&&!(b.textContent||'').trim())return true;
                  }
                  return false;
                }
                function findMic(){
                  var root=composer();
                  if(root){
                    var hub=root.querySelector('[data-slot="fan-menu-anchor"] button[aria-haspopup="true"]');
                    if(hub)return hub;
                    var bs=root.querySelectorAll('button');
                    for(var i=0;i<bs.length;i++){
                      var l=label(bs[i]);
                      if(l&&RE.test(l)&&!SKIP.test(l))return bs[i];
                    }
                    var pa=root.querySelectorAll('button[aria-pressed][data-active]');
                    for(var j=0;j<pa.length;j++){
                      if(!(pa[j].textContent||'').trim())return pa[j];
                    }
                  }
                  var all=d.querySelectorAll('button[aria-label]');
                  for(var k=0;k<all.length;k++){
                    var l2=label(all[k]);
                    if(l2&&RE.test(l2)&&!SKIP.test(l2)&&!(all[k].textContent||'').trim())return all[k];
                  }
                  return null;
                }
                function unlock(){
                  var m=findMic();
                  if(!m)return;
                  m.setAttribute('data-hermes-voice','1');
                  if(m.disabled||m.getAttribute('aria-disabled')==='true'){
                    try{m.disabled=false;}catch(e){}
                    m.removeAttribute('disabled');
                    m.setAttribute('aria-disabled','false');
                  }
                }
                function click(e){
                  var t=e.target;
                  var b=t&&t.closest?t.closest('button'):null;
                  if(!isMic(b))return;
                  e.preventDefault();
                  e.stopPropagation();
                  try{e.stopImmediatePropagation();}catch(err){}
                  try{
                    if(window.HermesVoice&&window.HermesVoice.start)window.HermesVoice.start();
                  }catch(err2){}
                }
                d.addEventListener('click',click,true);
                window.__hermesVoice={refresh:unlock,isMic:isMic};
                unlock();
                setInterval(unlock,800);
              }

              scan();
              setInterval(scan,1500);
              try{
                var mo=new MutationObserver(function(){
                  if(timer)return;
                  timer=setTimeout(function(){timer=0;scan();},400);
                });
                mo.observe(d.body,{childList:true,subtree:true});
              }catch(e){}
            })();
            """;

    private ChatSkin() {
    }

    static String applyScript(boolean skin) {
        return JS.replace("__CSS__", JSONObject.quote(CSS))
                .replace("__SKIN__", skin ? "true" : "false");
    }

    /** Types recognized speech into the live composer, then lets React flush it. */
    static String insertTextScript(String text) {
        return """
                (function(t){
                  try{
                    var d=document;
                    var el=d.querySelector('[data-slot="composer-rich-input"]');
                    if(!el)el=d.querySelector('[data-slot="composer-surface"] [contenteditable="true"]');
                    if(!el)el=d.querySelector('[data-slot="composer-surface"] textarea');
                    if(!el)el=d.querySelector('textarea,[contenteditable="true"]');
                    if(!el)return '0';
                    el.focus();
                    if(el.isContentEditable){
                      var s=window.getSelection();
                      if(!s.rangeCount||!el.contains(s.anchorNode)){
                        var r=d.createRange();
                        r.selectNodeContents(el);r.collapse(false);
                        s.removeAllRanges();s.addRange(r);
                      }
                      var ok=false;
                      try{ok=d.execCommand('insertText',false,t);}catch(e){}
                      if(!ok){
                        var r2=s.getRangeAt(0);
                        r2.deleteContents();
                        var n=d.createTextNode(t);
                        r2.insertNode(n);
                        r2.setStartAfter(n);r2.collapse(true);
                        s.removeAllRanges();s.addRange(r2);
                        try{el.dispatchEvent(new InputEvent('input',
                          {bubbles:true,inputType:'insertText',data:t}));}
                        catch(e2){el.dispatchEvent(new Event('input',{bubbles:true}));}
                      }
                    }else{
                      var p=el.tagName==='TEXTAREA'
                        ?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                      var set=Object.getOwnPropertyDescriptor(p,'value').set;
                      set.call(el,(el.value||'')+t);
                      el.dispatchEvent(new Event('input',{bubbles:true}));
                    }
                    return '1';
                  }catch(e3){return '0';}
                })(__TEXT__)
                """.replace("__TEXT__", JSONObject.quote(text));
    }

    static String removeScript() {
        return "(function(){var d=document;"
                + "d.documentElement.classList.remove('hermes-wx');"
                + "d.documentElement.classList.remove('hermes-nav-top');"
                + "var s=d.getElementById('hermes-wx-style');if(s)s.remove();"
                + "var t=d.getElementById('hermes-nav-tab');if(t)t.remove();"
                + "var r=d.querySelectorAll('[data-hermes-rail],[data-hermes-rail-wrap]');"
                + "for(var i=0;i<r.length;i++){"
                + "r[i].removeAttribute('data-hermes-rail');"
                + "r[i].removeAttribute('data-hermes-rail-wrap');}"
                + "window.__hermesNav=null;})();";
    }
}
