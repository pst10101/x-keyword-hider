package com.jsw.xkeywordhider;
import android.accessibilityservice.AccessibilityService; import android.content.SharedPreferences; import android.graphics.*; import android.os.*; import android.view.*; import android.view.accessibility.*; import android.widget.TextView; import java.util.*;
public class HiderService extends AccessibilityService {
  static final Set<String> PKGS=new HashSet<>(Arrays.asList("com.twitter.android","com.android.chrome","com.chrome.beta","com.sec.android.app.sbrowser","org.mozilla.firefox","com.microsoft.emmx","com.brave.browser","com.samsung.android.app.sbrowser"));
  WindowManager wm; final Handler h=new Handler(Looper.getMainLooper()); final List<View> views=new ArrayList<>(); final List<Rect> shown=new ArrayList<>();
  Typeface bebas; Matcher matcher; String lastKw=null; boolean lastGap; boolean pending;
  @Override protected void onServiceConnected(){ wm=(WindowManager)getSystemService(WINDOW_SERVICE); bebas=Typeface.createFromAsset(getAssets(),"fonts/BebasNeue.ttf"); }
  @Override public void onAccessibilityEvent(AccessibilityEvent e){ if(!pending){ pending=true; h.postDelayed(this::scan,200);} }
  @Override public void onInterrupt(){}
  Matcher m(){ SharedPreferences sp=getSharedPreferences("hider",MODE_PRIVATE); String k=sp.getString("keywords",""); boolean g=sp.getBoolean("ignoreGaps",true);
    if(!sp.getBoolean("enabled",true)) return null; if(!k.equals(lastKw)||g!=lastGap){ matcher=new Matcher(k,g); lastKw=k; lastGap=g; } return matcher; }
  void scan(){ pending=false; List<Rect> out=new ArrayList<>(); Matcher mt=m();
    AccessibilityNodeInfo root=getRootInActiveWindow();
    if(mt!=null&&root!=null&&root.getPackageName()!=null&&PKGS.contains(root.getPackageName().toString())){
      boolean browser=!"com.twitter.android".equals(root.getPackageName().toString());
      if(!browser||onX(root,0)) walk(root,mt,out,0);
    }
    render(out);
  }
  boolean onX(AccessibilityNodeInfo n,int d){ if(n==null||d>25) return false; CharSequence t=n.getText();
    if(t!=null){ String s=t.toString(); if(s.matches("(?i)^(https?://)?((www|mobile|pro)\\.)?(x|twitter)\\.com.*")) return true; }
    for(int i=0;i<n.getChildCount();i++) if(onX(n.getChild(i),d+1)) return true; return false; }
  void walk(AccessibilityNodeInfo n,Matcher mt,List<Rect> out,int d){ if(n==null||d>60) return;
    if(mt.matches(n.getText())||mt.matches(n.getContentDescription())){ Rect r=new Rect(); container(n).getBoundsInScreen(r); if(r.width()>0&&r.height()>0){ out.add(r); return; } }
    for(int i=0;i<n.getChildCount();i++) walk(n.getChild(i),mt,out,d+1); }
  /** 스크롤 목록의 바로 아래 자식(=게시물 한 칸)을 찾아 그 영역을 덮음 */
  AccessibilityNodeInfo container(AccessibilityNodeInfo n){ AccessibilityNodeInfo c=n; for(int i=0;i<12;i++){ AccessibilityNodeInfo p=c.getParent(); if(p==null) break; if(p.isScrollable()) return c; c=p; } return n; }
  void render(List<Rect> rs){ if(rs.equals(shown)) return; for(View v:views) try{wm.removeView(v);}catch(Exception ignored){} views.clear(); shown.clear(); shown.addAll(rs);
    for(Rect r:rs){ TextView tv=new TextView(this); tv.setText("BLOCKED"); tv.setTypeface(bebas); tv.setTextColor(0xFFE0245E); tv.setGravity(Gravity.CENTER);
      tv.setTextSize(TypedValueSp(Math.min(64,Math.max(18,r.height()/getResources().getDisplayMetrics().density*0.6f)))); tv.setBackgroundColor(0xFF15181C); tv.setClickable(true); tv.setOnTouchListener((v,ev)->true);
      WindowManager.LayoutParams lp=new WindowManager.LayoutParams(r.width(),r.height(),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
      lp.gravity=Gravity.TOP|Gravity.START; lp.x=r.left; lp.y=r.top;
      if(Build.VERSION.SDK_INT>=28) lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      try{ wm.addView(tv,lp); views.add(tv);}catch(Exception ignored){} } }
  static float TypedValueSp(float v){ return v; }
}
