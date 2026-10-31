// 키워드 → 정규식 변환과 매칭. content.js와 popup.js가 같이 씁니다.
(function (root) {
  const DEFAULTS = {
    enabled: true,
    keywords: "",
    mode: "collapse",        // "collapse" = 접어두기(눌러서 보기), "remove" = 완전히 숨기기
    ignoreGaps: true,        // "분 탕", "분.탕" 처럼 끼워넣어도 잡기
    checkAuthor: true,       // 작성자 이름·@아이디·재게시한 사람도 검사
    checkAlt: true,          // 이미지 설명(alt)도 검사
    checkOther: true,        // 알림·트렌드·추천 계정도 검사
    showKeyword: false       // BLOCKED 아래에 걸린 키워드도 작게 표시 (기본은 BLOCKED만)
  };

  // 띄어쓰기·자주 끼워넣는 기호
  const GAP = "[\\s.\\u00B7\\u30FB_\\-*~,]*";
  const ZERO_WIDTH = /[​-‍⁠﻿­]/g;
  const ASCII_WORD = /[A-Za-z0-9]/;

  function clean(s) {
    return String(s || "").normalize("NFKC").replace(ZERO_WIDTH, "");
  }

  function escapeChar(c) {
    return /[.*+?^${}()|[\]\\\/]/.test(c) ? "\\" + c : c;
  }

  const isAscii = (c) => /^[\x00-\x7F]$/.test(c);

  function buildPlain(word, ignoreGaps) {
    const chars = Array.from(word);
    let body = "";
    let prev = null;
    let spaceBefore = false;
    chars.forEach((c) => {
      if (/\s/.test(c)) {
        spaceBefore = true;
        return;
      }
      if (prev !== null) {
        // 끼워넣기 무시는 한글 등 비영문 글자 사이에만 적용.
        // (@아이디나 영어 단어에 적용하면 @ab_c 같은 다른 계정까지 걸림)
        const loose = ignoreGaps && (!isAscii(prev) || !isAscii(c));
        if (loose) body += GAP;
        else if (spaceBefore) body += "\\s+";
      }
      body += escapeChar(c);
      prev = c;
      spaceBefore = false;
    });
    const solid = chars.filter((c) => !/\s/.test(c));
    // 영어·숫자로 시작/끝나는 쪽은 단어 경계를 둬서 "ai"가 "said"에 걸리지 않게 함.
    // 한글 쪽은 조사가 붙으니(분탕이, 분탕을) 경계를 두지 않음.
    const before = ASCII_WORD.test(solid[0]) ? "(?<![A-Za-z0-9_])" : "";
    const after = ASCII_WORD.test(solid[solid.length - 1]) ? "(?![A-Za-z0-9_])" : "";
    return new RegExp(before + body + after, "iu");
  }

  // 반환: { rules: [{label, re}], errors: [{line, message}] }
  function compile(text, opts) {
    const o = Object.assign({}, DEFAULTS, opts || {});
    const rules = [];
    const errors = [];
    const seen = new Set();
    String(text || "")
      .split(/\r?\n/)
      .forEach((rawLine, idx) => {
        const line = clean(rawLine).trim();
        if (!line || line.startsWith("//")) return;
        if (seen.has(line.toLowerCase())) return;
        seen.add(line.toLowerCase());

        const rx = line.match(/^\/(.+)\/([a-z]*)$/);
        if (rx) {
          try {
            const flags = Array.from(new Set(rx[2].replace(/[gy]/g, "").split(""))).join("");
            rules.push({ label: line, re: new RegExp(rx[1], flags) });
          } catch (e) {
            errors.push({ line: idx + 1, message: `정규식 오류: ${line}` });
          }
          return;
        }
        try {
          const re = buildPlain(line, o.ignoreGaps);
          rules.push({ label: line, re });
        } catch (e) {
          errors.push({ line: idx + 1, message: `처리할 수 없는 키워드: ${line}` });
        }
      });
    return { rules, errors };
  }

  function findMatch(rules, text) {
    const t = clean(text);
    for (const r of rules) {
      if (r.re.test(t)) return r.label;
    }
    return null;
  }

  root.XKH = { DEFAULTS, compile, findMatch, clean };
})(typeof window !== "undefined" ? window : globalThis);
