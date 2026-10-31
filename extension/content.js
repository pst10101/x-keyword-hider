(function injectBlockedFont() {
  if (document.getElementById("xkh-blocked-font")) return;
  const s = document.createElement("style");
  s.id = "xkh-blocked-font";
  s.textContent = `@font-face{font-family:"XKHBlocked";src:url("${chrome.runtime.getURL("fonts/BebasNeue.ttf")}") format("truetype");font-weight:900;font-style:normal;font-display:swap;}`;
  (document.head || document.documentElement).appendChild(s);
})();

// X 페이지에서 게시물·답글·알림·트렌드를 검사해 키워드가 있으면 가립니다.
(() => {
  if (window.__xkhLoaded) return;
  window.__xkhLoaded = true;

  const { DEFAULTS, compile, findMatch } = window.XKH;

  const SEL_TWEET = 'article[data-testid="tweet"]';
  const SEL_OTHER = 'article[data-testid="notification"], [data-testid="UserCell"], [data-testid="trend"]';
  const SEL_CELL = '[data-testid="cellInnerDiv"]';

  let settings = Object.assign({}, DEFAULTS);
  let rules = [];
  let ready = false;
  let lastText = new WeakMap();   // 요소별로 마지막으로 검사한 글 (안 바뀌었으면 건너뜀)
  const revealed = new WeakMap(); // 사용자가 눌러서 펼친 요소 → 그때의 글
  const hiddenKeys = new Set();   // 이 화면에서 가린 게시물 (개수 표시용)
  let lastPath = location.pathname;
  let alive = true;

  // ---------- 글 모으기 ----------
  // innerText는 가려진(display:none) 부분을 못 읽으므로 DOM에서 직접 모음. 이모지는 <img alt>.
  function textOf(el, sep) {
    if (!el) return "";
    const out = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let n = walker.currentNode;
    while (n) {
      if (n.nodeType === 3) out.push(n.data);
      else if (n.tagName === "IMG" && n.alt) out.push(n.alt);
      n = walker.nextNode();
    }
    return out.join(sep);
  }

  function collectTweet(article) {
    const parts = [];
    article.querySelectorAll('[data-testid="tweetText"]').forEach((el) => parts.push(textOf(el, "")));
    if (settings.checkAuthor) {
      article
        .querySelectorAll('[data-testid="User-Name"], [data-testid="socialContext"]')
        .forEach((el) => parts.push(textOf(el, " ")));
    }
    article.querySelectorAll('[data-testid="card.wrapper"]').forEach((el) => parts.push(textOf(el, " ")));
    if (settings.checkAlt) {
      article.querySelectorAll('[data-testid="tweetPhoto"]').forEach((el) => parts.push(textOf(el, " ")));
    }
    return parts.join("\n");
  }

  // ---------- 가리기 / 펼치기 ----------
  function shorten(s) {
    return s.length > 40 ? s.slice(0, 39) + "…" : s;
  }

  function hide(el, hit, text, isTweet) {
    hiddenKeys.add(text.slice(0, 300));
    const cell = el.closest(SEL_CELL);
    if (settings.mode === "remove" && cell) {
      el.removeAttribute("data-xkh");
      el.removeAttribute("data-xkh-label");
      cell.setAttribute("data-xkh-gone", "");
    } else {
      if (cell) cell.removeAttribute("data-xkh-gone");
      const label = "Blocked";
      el.setAttribute("data-xkh-label", label);
      el.setAttribute("data-xkh", "");
    }
    scheduleCount();
  }

  function unhide(el) {
    el.removeAttribute("data-xkh");
    el.removeAttribute("data-xkh-label");
    const cell = el.closest(SEL_CELL);
    if (cell) cell.removeAttribute("data-xkh-gone");
  }

  function reveal(el) {
    revealed.set(el, lastText.get(el));
    el.removeAttribute("data-xkh");
    el.removeAttribute("data-xkh-label");
  }

  function check(el) {
    const isTweet = el.matches(SEL_TWEET);
    const text = isTweet ? collectTweet(el) : textOf(el, " ");
    if (lastText.get(el) === text) return;
    lastText.set(el, text);

    const hit = settings.enabled && rules.length ? findMatch(rules, text) : null;
    if (hit && revealed.get(el) !== text) hide(el, hit, text, isTweet);
    else unhide(el);
  }

  function scan() {
    if (!ready || !alive) return;
    if (location.pathname !== lastPath) {
      lastPath = location.pathname;
      hiddenKeys.clear();
      scheduleCount();
    }
    document.querySelectorAll(SEL_TWEET).forEach(check);
    if (settings.checkOther) document.querySelectorAll(SEL_OTHER).forEach(check);
  }

  function resetAll() {
    document.querySelectorAll("[data-xkh]").forEach((el) => {
      el.removeAttribute("data-xkh");
      el.removeAttribute("data-xkh-label");
    });
    document.querySelectorAll("[data-xkh-gone]").forEach((el) => el.removeAttribute("data-xkh-gone"));
    lastText = new WeakMap();
    hiddenKeys.clear();
  }

  // ---------- 화면 변화 감시 ----------
  let pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    const run = () => {
      pending = false;
      scan();
    };
    // 백그라운드 탭에선 rAF가 멈추니 타이머로도 한 번 보장
    const t = setTimeout(run, 150);
    requestAnimationFrame(() => {
      clearTimeout(t);
      if (pending) run();
    });
  }

  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });

  // 가린 자리를 누르면 그 게시물로 이동하지 않고 펼치기만
  function onPointer(e) {
    const el = e.target instanceof Element ? e.target.closest("[data-xkh]") : null;
    if (!el) return;
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    if (e.type === "click") reveal(el);
  }
  window.addEventListener("click", onPointer, true);
  window.addEventListener("auxclick", onPointer, true);

  // ---------- 개수 배지 ----------
  let countTimer = null;
  function scheduleCount() {
    if (countTimer) return;
    countTimer = setTimeout(() => {
      countTimer = null;
      sendCount();
    }, 300);
  }

  function sendCount() {
    try {
      const p = chrome.runtime.sendMessage({ type: "xkh-count", count: hiddenKeys.size });
      if (p && p.catch) p.catch(() => {});
    } catch (e) {
      shutdown(); // 확장을 새로고침/삭제하면 이 탭의 옛 스크립트는 정리
    }
  }

  function shutdown() {
    alive = false;
    observer.disconnect();
    window.removeEventListener("click", onPointer, true);
    window.removeEventListener("auxclick", onPointer, true);
    resetAll();
  }

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg && msg.type === "xkh-get-count") {
      sendResponse({ count: hiddenKeys.size, enabled: settings.enabled });
    }
  });

  // ---------- 설정 ----------
  function applySettings(s) {
    settings = Object.assign({}, DEFAULTS, s);
    rules = compile(settings.keywords, settings).rules;
    ready = true;
    resetAll();
    scan();
    scheduleCount();
  }

  chrome.storage.local.get(DEFAULTS, applySettings);
  chrome.storage.onChanged.addListener((_changes, area) => {
    if (area === "local" && alive) chrome.storage.local.get(DEFAULTS, applySettings);
  });
})();
