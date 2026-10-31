// 우클릭 메뉴(선택한 글자 / 계정 추가)와 아이콘 배지 숫자
const PAGES = [
  "https://x.com/*",
  "https://twitter.com/*",
  "https://mobile.x.com/*",
  "https://mobile.twitter.com/*",
  "https://pro.x.com/*"
];

const RESERVED = new Set([
  "home", "explore", "notifications", "messages", "i", "search", "settings", "compose",
  "hashtag", "login", "logout", "signup", "tos", "privacy", "jobs", "premium", "grok",
  "communities", "lists", "bookmarks", "account", "intent", "share", "about", "download"
]);

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: "xkh-add-selection",
      title: "“%s” 가리기 키워드에 추가",
      contexts: ["selection"],
      documentUrlPatterns: PAGES
    });
    chrome.contextMenus.create({
      id: "xkh-add-account",
      title: "이 링크의 계정 가리기",
      contexts: ["link"],
      documentUrlPatterns: PAGES,
      targetUrlPatterns: PAGES
    });
  });
});

function handleFromUrl(url) {
  try {
    const u = new URL(url);
    const seg = u.pathname.split("/").filter(Boolean)[0] || "";
    if (/^[A-Za-z0-9_]{1,15}$/.test(seg) && !RESERVED.has(seg.toLowerCase())) return "@" + seg;
  } catch (e) {}
  return null;
}

async function addKeyword(word) {
  const { keywords } = await chrome.storage.local.get({ keywords: "" });
  const exists = keywords
    .split(/\r?\n/)
    .some((l) => l.trim().toLowerCase() === word.toLowerCase());
  if (exists) return;
  const base = keywords.replace(/\s+$/, "");
  await chrome.storage.local.set({ keywords: base ? base + "\n" + word : word });
}

chrome.contextMenus.onClicked.addListener((info) => {
  let word = null;
  if (info.menuItemId === "xkh-add-selection") {
    word = (info.selectionText || "").replace(/\s+/g, " ").trim().slice(0, 100);
  } else if (info.menuItemId === "xkh-add-account") {
    word = handleFromUrl(info.linkUrl);
  }
  if (word) addKeyword(word);
});

chrome.action.setBadgeBackgroundColor({ color: "#5b5bd6" });

chrome.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "xkh-count" && sender.tab && sender.tab.id != null) {
    const n = msg.count || 0;
    chrome.action.setBadgeText({ tabId: sender.tab.id, text: n ? (n > 999 ? "999+" : String(n)) : "" });
  }
});
