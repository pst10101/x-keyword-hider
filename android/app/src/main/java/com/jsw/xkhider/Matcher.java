package com.jsw.xkhider;
import java.text.Normalizer; import java.util.*; import java.util.regex.*;
/** matcher.js 포팅: 줄마다 키워드, /정규식/플래그 지원, // 주석, 한글 사이 끼워넣기 무시. */
public class Matcher {
  static final String GAP = "[\\s.\\u00B7\\u30FB_\\-*~,]*";
  final List<Pattern> rules = new ArrayList<>();
  static String clean(String s){ return s==null?"":Normalizer.normalize(s,Normalizer.Form.NFKC).replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD]",""); }
  static boolean ascii(int c){ return c<128; }
  static boolean word(int c){ return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9'); }
  public Matcher(String text, boolean ignoreGaps){
    Set<String> seen=new HashSet<>();
    for(String raw: clean(text).split("\\r?\\n")){
      String line=raw.trim(); if(line.isEmpty()||line.startsWith("//")||!seen.add(line.toLowerCase())) continue;
      java.util.regex.Matcher rx=Pattern.compile("^/(.+)/([a-z]*)$").matcher(line);
      try{
        if(rx.matches()){ int f=Pattern.UNICODE_CASE; if(rx.group(2).contains("i")) f|=Pattern.CASE_INSENSITIVE; if(rx.group(2).contains("s")) f|=Pattern.DOTALL; if(rx.group(2).contains("m")) f|=Pattern.MULTILINE; rules.add(Pattern.compile(rx.group(1),f)); continue; }
        StringBuilder b=new StringBuilder(); int prev=-1; boolean sp=false; int first=-1,last=-1;
        for(int cp: line.codePoints().toArray()){
          if(Character.isWhitespace(cp)){ sp=true; continue; }
          if(prev!=-1){ if(ignoreGaps&&(!ascii(prev)||!ascii(cp))) b.append(GAP); else if(sp) b.append("\\s+"); }
          b.append(Pattern.quote(new String(Character.toChars(cp)))); if(first==-1) first=cp; last=cp; prev=cp; sp=false;
        }
        String re=(word(first)?"(?<![A-Za-z0-9_])":"")+b+(word(last)?"(?![A-Za-z0-9_])":"");
        rules.add(Pattern.compile(re,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));
      }catch(Exception ignored){}
    }
  }
  public boolean matches(CharSequence s){ if(s==null||rules.isEmpty()) return false; String c=clean(s.toString()); for(Pattern p:rules) if(p.matcher(c).find()) return true; return false; }
}
