const { DEFAULTS, compile } = XKH;
const $ = (id) => document.getElementById(id);

const CHECKS = ["ignoreGaps", "checkAuthor", "checkAlt", "checkOther", "showKeyword"];
let current = Object.assign({}, DEFAULTS);
let typingTimer = null;
let savedTimer = null;

function flashSaved() {
  const el = $("saved");
  el.classList.add("on");
  clearTimeout(savedTimer);
  savedTimer = setTimeout(() => el.classList.remove("on"), 1200);
}

function save(patch) {
  Object.assign(current, patch);
  chrome.storage.local.set(patch).then(flashSaved);
}

function validate() {
  const { rules, errors } = compile($("keywords").value, current);
  $("ruleCount").textContent = rules.length ? `키워드 ${rules.length}개` : "";
  const ul = $("errors");
  ul.textContent = "";
  errors.forEach((e) => {
    const li = document.createElement("li");
    li.textContent = `${e.line}번째 줄 · ${e.message}`;
    ul.appendChild(li);
  });
}

function reflect() {
  document.body.classList.toggle("disabled", !current.enabled);
  $("showKeywordRow").classList.toggle("off", current.mode !== "collapse");
}

function fill(s) {
  current = Object.assign({}, DEFAULTS, s);
  $("enabled").checked = current.enabled;
  if (document.activeElement !== $("keywords")) $("keywords").value = current.keywords;
  CHECKS.forEach((k) => ($(k).checked = !!current[k]));
  document.querySelectorAll('input[name="mode"]').forEach((r) => (r.checked = r.value === current.mode));
  reflect();
  validate();
}

async function showCount() {
  const stat = $("stat");
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const res = tab && tab.id != null ? await chrome.tabs.sendMessage(tab.id, { type: "xkh-get-count" }) : null;
    if (!res) throw new Error("no tab");
    if (!current.enabled) stat.textContent = "꺼져 있어요";
    else stat.textContent = res.count ? `이 화면에서 ${res.count}개 가렸어요` : "이 화면에선 아직 가린 게 없어요";
  } catch (e) {
    stat.textContent = current.enabled ? "X 탭에서 열면 가린 개수가 보여요" : "꺼져 있어요";
  }
}

// ---------- 이벤트 ----------
$("enabled").addEventListener("change", (e) => {
  save({ enabled: e.target.checked });
  reflect();
  showCount();
});

$("keywords").addEventListener("input", () => {
  validate();
  clearTimeout(typingTimer);
  typingTimer = setTimeout(() => save({ keywords: $("keywords").value }), 250);
});

function flushKeywords() {
  clearTimeout(typingTimer);
  if ($("keywords").value !== current.keywords) save({ keywords: $("keywords").value });
}
$("keywords").addEventListener("blur", flushKeywords);
// 입력 직후 팝업을 닫아도 저장되도록
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") flushKeywords();
});
window.addEventListener("pagehide", flushKeywords);

CHECKS.forEach((k) =>
  $(k).addEventListener("change", (e) => {
    save({ [k]: e.target.checked });
    validate();
  })
);

document.querySelectorAll('input[name="mode"]').forEach((r) =>
  r.addEventListener("change", (e) => {
    if (e.target.checked) {
      save({ mode: e.target.value });
      reflect();
    }
  })
);

// 우클릭 메뉴로 추가된 경우 등 바깥 변경 반영
chrome.storage.onChanged.addListener((_c, area) => {
  if (area === "local") chrome.storage.local.get(DEFAULTS).then(fill);
});

// 옵션 페이지(탭)로 열렸는지
chrome.tabs.getCurrent().then((t) => {
  if (t) document.body.classList.add("page");
});

chrome.storage.local.get(DEFAULTS).then((s) => {
  fill(s);
  showCount();
});
