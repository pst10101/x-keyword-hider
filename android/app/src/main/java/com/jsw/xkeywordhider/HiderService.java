package com.jsw.xkeywordhider;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * X 앱·브라우저(x.com) 화면을 읽어서, 키워드가 걸린 게시물 위에 빨간 BLOCKED(Bebas Neue) 덮개를 띄웁니다.
 * 덮개는 TYPE_ACCESSIBILITY_OVERLAY라서 "다른 앱 위에 표시" 권한 없이 접근성 서비스만으로 뜹니다.
 */
public class HiderService extends AccessibilityService {
  static final String X_APP = "com.twitter.android";
  static final Set<String> PKGS = new HashSet<>(Arrays.asList(
      X_APP, "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser",
      "com.samsung.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser"));

  static final String LABEL = "BLOCKED";
  static final int RED = 0xFFF4212E;   // 크롬 확장과 같은 X 빨강
  static final int BG = 0xFF15181C;

  /** 덮개 하나 = 덮을 화면 영역 + 그 게시물이 들어 있는 스크롤 목록(없으면 null) */
  static final class Hit {
    final Rect r; final AccessibilityNodeInfo list;
    Hit(Rect r, AccessibilityNodeInfo list) { this.r = r; this.list = list; }
  }

  WindowManager wm;
  Typeface bebas;
  final Handler h = new Handler(Looper.getMainLooper());
  final List<TextView> views = new ArrayList<>();               // 띄워 둔 덮개 (재사용)
  final List<AccessibilityNodeInfo> lists = new ArrayList<>();  // views[i]가 덮은 게시물의 스크롤 목록
  List<Rect> shown = new ArrayList<>();
  Matcher matcher; String lastKw; boolean lastGap; boolean pending;

  @Override protected void onServiceConnected() {
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    try { bebas = Typeface.createFromAsset(getAssets(), "fonts/BebasNeue.ttf"); }
    catch (Exception e) { bebas = Typeface.DEFAULT_BOLD; }
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    CharSequence p = e.getPackageName();
    boolean ours = p != null && PKGS.contains(p.toString());
    if (!ours && views.isEmpty()) return;  // 상관없는 앱이고 띄운 덮개도 없으면 할 일 없음
    if (pending) return;
    pending = true;
    // 스크롤 중엔 덮개가 글을 빨리 따라가도록 짧게 기다림
    h.postDelayed(this::scan, e.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED ? 60 : 150);
  }

  @Override public void onInterrupt() {}

  @Override public void onDestroy() {
    h.removeCallbacksAndMessages(null);
    clear();
    super.onDestroy();
  }

  Matcher m() {
    SharedPreferences sp = getSharedPreferences("hider", MODE_PRIVATE);
    if (!sp.getBoolean("enabled", true)) return null;
    String k = sp.getString("keywords", "");
    boolean g = sp.getBoolean("ignoreGaps", true);
    if (!k.equals(lastKw) || g != lastGap) { matcher = new Matcher(k, g); lastKw = k; lastGap = g; }
    return matcher;
  }

  void scan() {
    pending = false;
    List<Hit> out = new ArrayList<>();
    Matcher mt = m();
    AccessibilityNodeInfo root = null;
    try { root = getRootInActiveWindow(); } catch (Exception ignored) {}
    if (mt != null && root != null && root.getPackageName() != null) {
      String pkg = root.getPackageName().toString();
      if (PKGS.contains(pkg) && (X_APP.equals(pkg) || onX(root, 0))) walk(root, mt, out, 0);
    }
    render(out);
  }

  /** 브라우저면 주소창에 x.com / twitter.com이 떠 있을 때만 검사 */
  boolean onX(AccessibilityNodeInfo n, int d) {
    if (n == null || d > 25) return false;
    CharSequence t = n.getText();
    if (t != null && t.toString().matches("(?i)^(https?://)?((www|mobile|pro)\\.)?(x|twitter)\\.com.*")) return true;
    for (int i = 0; i < n.getChildCount(); i++) if (onX(n.getChild(i), d + 1)) return true;
    return false;
  }

  void walk(AccessibilityNodeInfo n, Matcher mt, List<Hit> out, int d) {
    if (n == null || d > 60 || !n.isVisibleToUser()) return;
    if (mt.matches(n.getText()) || mt.matches(n.getContentDescription())) {
      Hit hit = cover(n);
      if (hit != null) {
        for (Hit o : out) if (o.r.equals(hit.r)) return;  // 한 게시물 안에서 두 번 걸려도 덮개는 하나
        out.add(hit);
        return;
      }
    }
    for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), mt, out, d + 1);
  }

  /** 스크롤 목록의 바로 아래 자식(=게시물 한 칸)을 찾아, 목록 안에 보이는 부분만 덮음 */
  Hit cover(AccessibilityNodeInfo n) {
    AccessibilityNodeInfo item = n, list = null;
    for (int i = 0; i < 12; i++) {
      AccessibilityNodeInfo p = item.getParent();
      if (p == null) break;
      if (p.isScrollable()) { list = p; break; }
      item = p;
    }
    if (list == null) item = n;
    Rect r = new Rect();
    item.getBoundsInScreen(r);
    if (list != null) {
      Rect lr = new Rect();
      list.getBoundsInScreen(lr);
      if (!r.intersect(lr)) return null;
    }
    return r.width() > 0 && r.height() > 0 ? new Hit(r, list) : null;
  }

  /** 덮개 창을 매번 새로 만들지 않고 위치·크기만 옮겨서 스크롤할 때 깜빡임을 줄임 */
  void render(List<Hit> hits) {
    if (wm == null) return;
    List<Rect> rs = new ArrayList<>();
    for (Hit x : hits) rs.add(x.r);
    lists.clear();
    for (Hit x : hits) lists.add(x.list);
    if (rs.equals(shown)) return;

    while (views.size() > rs.size()) {
      TextView v = views.remove(views.size() - 1);
      try { wm.removeView(v); } catch (Exception ignored) {}
    }
    for (int i = 0; i < rs.size(); i++) {
      WindowManager.LayoutParams lp = params(rs.get(i));
      if (i < views.size()) {
        try { wm.updateViewLayout(views.get(i), lp); } catch (Exception ignored) {}
      } else {
        TextView tv = makeCover(i);
        try { wm.addView(tv, lp); views.add(tv); } catch (Exception e) { break; }
      }
    }
    shown = new ArrayList<>(rs.subList(0, views.size()));
  }

  void clear() {
    if (wm != null) for (View v : views) try { wm.removeView(v); } catch (Exception ignored) {}
    views.clear();
    lists.clear();
    shown = new ArrayList<>();
  }

  WindowManager.LayoutParams params(Rect r) {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = r.left;
    lp.y = r.top;
    if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    return lp;
  }

  /** 빨간 BLOCKED(Bebas Neue). 글자 크기는 덮개 크기에 맞춰 자동으로 키우고 줄임 */
  TextView makeCover(final int idx) {
    TextView tv = new TextView(this);
    tv.setText(LABEL);
    tv.setTypeface(bebas);
    tv.setTextColor(RED);
    tv.setBackgroundColor(BG);
    tv.setGravity(Gravity.CENTER);
    tv.setMaxLines(1);
    tv.setIncludeFontPadding(false);
    tv.setLetterSpacing(0.04f);
    int pad = dp(6);
    tv.setPadding(pad, pad, pad, pad);
    tv.setAutoSizeTextTypeUniformWithConfiguration(14, 72, 1, TypedValue.COMPLEX_UNIT_DIP);
    // 누르기는 아래 게시물에 닿지 않게 막고, 위·아래로 쓸면 목록을 한 화면씩 넘김 (덮개 때문에 스크롤이 막히지 않게)
    tv.setOnTouchListener(new View.OnTouchListener() {
      float x0, y0;
      @Override public boolean onTouch(View v, MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { x0 = ev.getRawX(); y0 = ev.getRawY(); }
        else if (a == MotionEvent.ACTION_UP) {
          float dx = ev.getRawX() - x0, dy = ev.getRawY() - y0;
          if (Math.abs(dy) > dp(40) && Math.abs(dy) > Math.abs(dx)) page(idx, dy < 0);
        }
        return true;
      }
    });
    return tv;
  }

  void page(int idx, boolean forward) {
    if (idx >= lists.size()) return;
    AccessibilityNodeInfo list = lists.get(idx);
    if (list == null) return;
    try {
      list.refresh();
      list.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    } catch (Exception ignored) {}
  }

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
