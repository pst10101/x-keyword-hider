package com.jsw.xkhider;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * X 앱·브라우저(x.com)와 시계·알람 앱 화면을 읽어서, 키워드가 걸린 곳 위에 빨간 BLOCKED(Bebas Neue) 덮개를 띄웁니다.
 * 그 앱의 화면에 떠 있는 모든 창(본 화면, 알림 탭, 배너·팝업·대화상자)을 훑습니다.
 *
 * 속도 구조 (1.1.5):
 *  - xkh-scan 스레드: 화면 트리를 훑어 키워드가 걸린 노드를 찾음 (무거운 일, 스크롤 중 ~90ms 간격).
 *  - xkh-track 스레드: 이미 찾은 노드의 위치만 매 프레임(Choreographer) 다시 읽음 (노드당 IPC 1번).
 *    다음 프레임까지 움직일 만큼 살짝 앞서 그려서 지연을 줄임.
 *  - 메인 스레드: 바뀐 덮개만 updateViewLayout. 덮개 창은 재사용.
 */
public class HiderService extends AccessibilityService {
  static final String X_APP = "com.twitter.android";
  static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
      "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser",
      "com.samsung.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser"));
  /** 시계·알람 앱: 알람 이름(라벨)에 키워드가 있으면 그 글자 위를 덮음 */
  static final Set<String> CLOCKS = new HashSet<>(Arrays.asList(
      "com.sec.android.app.clockpackage",   // 삼성 시계
      "com.google.android.deskclock",       // 구글 시계
      "com.android.deskclock",              // AOSP·샤오미 등
      "com.oneplus.deskclock", "com.coloros.alarmclock"));

  static final String LABEL = "BLOCKED";
  static final int RED = 0xFFF4212E;   // 크롬 확장과 같은 X 빨강
  static final int BG = 0xFF15181C;

  static final long LINGER_MS = 350;        // 마지막 움직임·스크롤 뒤로 이만큼은 매 프레임 따라감
  static final long IDLE_POLL_FOR_MS = 3000; // 멈춘 뒤 이 시간 동안은 가끔 확인해서 다음 스크롤 시작을 빨리 알아챔
  static final long IDLE_POLL_MS = 50;
  static final long SCAN_GAP_ACTIVE = 90;   // 스크롤 중 새로 나타난 글 찾는 간격
  static final long SCAN_GAP_IDLE = 220;    // 내용만 바뀔 때(동영상 등) 다시 찾는 간격
  static final long VERIFY_MS = 150;        // 덮은 글이 그대로인지(목록 재활용) 다시 확인하는 간격
  static final float LEAD_MS = 14f;         // 다음 화면 갱신까지 미리 움직여 둘 시간

  /** 덮개 하나가 따라가는 대상 */
  static final class Track {
    final AccessibilityNodeInfo item;    // 덮을 노드 (X: 게시물 한 칸, 시계: 글자 노드)
    final AccessibilityNodeInfo anchor;  // 키워드가 실제로 들어 있는 노드 (재활용 확인용)
    final AccessibilityNodeInfo list;    // 스크롤 목록 (덮개 위에서 쓸어 넘길 때), 없으면 null
    final Rect clip;                     // 목록 영역 (미리 움직인 덮개가 목록 밖으로 안 나가게), 없으면 null
    final int pad;
    List<Rect> occ = Collections.emptyList(); // 이 창보다 위에 떠 있는 창들 (덮개가 그 위를 가리지 않게)
    final Rect r = new Rect();           // 마지막으로 읽은 위치
    long t, verifiedAt;
    float vTop, vBottom;                 // 위·아래 변의 속도 (px/ms)
    Track(AccessibilityNodeInfo item, AccessibilityNodeInfo anchor, AccessibilityNodeInfo list, Rect clip, int pad) {
      this.item = item; this.anchor = anchor; this.list = list; this.clip = clip; this.pad = pad;
    }
  }

  /** 메인 스레드에 넘기는 한 장면 */
  static final class Scene {
    final List<Rect> rs; final List<AccessibilityNodeInfo> ls;
    Scene(List<Rect> rs, List<AccessibilityNodeInfo> ls) { this.rs = rs; this.ls = ls; }
  }

  final Handler main = new Handler(Looper.getMainLooper());
  HandlerThread scanThread, trackThread;
  Handler scanH, trackH;
  WindowManager wm;
  Typeface bebas;

  // 여러 스레드가 보는 값
  volatile Matcher matcher;
  volatile long lastScanAt, lastScrollAt;
  volatile boolean targetOnScreen;   // 마지막 스캔 때 X·시계 창이 화면에 있었는지
  final AtomicBoolean scanQueued = new AtomicBoolean();
  final AtomicBoolean pokeQueued = new AtomicBoolean();
  final AtomicReference<Scene> pendingScene = new AtomicReference<>();

  // xkh-scan 스레드 전용
  String lastKw; boolean lastGap;

  // xkh-track 스레드 전용
  Choreographer choreo;
  List<Track> tracks = new ArrayList<>();
  List<Rect> published = new ArrayList<>();
  boolean frameOn;
  long lastMoveAt;

  // 메인 스레드 전용
  final List<TextView> views = new ArrayList<>();
  final List<Rect> viewRects = new ArrayList<>();
  List<AccessibilityNodeInfo> viewLists = new ArrayList<>();

  @Override protected void onServiceConnected() {
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    try { bebas = Typeface.createFromAsset(getAssets(), "fonts/BebasNeue.ttf"); }
    catch (Exception e) { bebas = Typeface.DEFAULT_BOLD; }
    if (scanThread == null) {
      scanThread = new HandlerThread("xkh-scan");
      scanThread.start();
      scanH = new Handler(scanThread.getLooper());
      trackThread = new HandlerThread("xkh-track");
      trackThread.start();
      trackH = new Handler(trackThread.getLooper());
      trackH.post(() -> {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}
        choreo = Choreographer.getInstance();
      });
    }
    requestScanNow();
  }

  // ===================== 이벤트 (메인 스레드) =====================

  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    CharSequence p = e.getPackageName();
    String pkg = p == null ? "" : p.toString();
    int type = e.getEventType();
    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      // 배너·팝업·키보드·알림창이 뜨고 질 때. 대상 앱이 화면에 있을 때만 다시 훑음
      if (targetOnScreen || !views.isEmpty()) requestScan(SCAN_GAP_ACTIVE);
      return;
    }
    if (!(X_APP.equals(pkg) || BROWSERS.contains(pkg) || CLOCKS.contains(pkg))) {
      // 다른 앱·홈·알림창으로 넘어가면 바로 다시 확인해서 덮개를 치움
      if (!views.isEmpty() && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) requestScanNow();
      return;
    }
    long now = SystemClock.uptimeMillis();
    if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
      lastScrollAt = now;
      poke();
      requestScan(SCAN_GAP_ACTIVE);
    } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      requestScanNow();
    } else {
      poke();
      requestScan(now - lastScrollAt < LINGER_MS ? SCAN_GAP_ACTIVE : SCAN_GAP_IDLE);
    }
  }

  @Override public void onInterrupt() {}

  @Override public void onDestroy() {
    main.removeCallbacksAndMessages(null);
    if (scanThread != null) scanThread.quitSafely();
    if (trackThread != null) trackThread.quitSafely();
    clearViews();
    super.onDestroy();
  }

  /** 트래킹 스레드를 깨움 (이미 깨우는 중이면 무시) */
  void poke() {
    if (trackH != null && pokeQueued.compareAndSet(false, true)) {
      trackH.postAtFrontOfQueue(() -> { pokeQueued.set(false); wake(); });
    }
  }

  void requestScan(long gap) {
    if (scanH == null || !scanQueued.compareAndSet(false, true)) return;
    long wait = Math.max(0, lastScanAt + gap - SystemClock.uptimeMillis());
    scanH.postDelayed(scanRunnable, wait);
  }

  void requestScanNow() {
    if (scanH == null) return;
    scanH.removeCallbacks(scanRunnable);
    scanQueued.set(true);
    scanH.post(scanRunnable);
  }

  final Runnable scanRunnable = this::scan;

  // ===================== 찾기 (xkh-scan 스레드) =====================

  Matcher m() {
    SharedPreferences sp = getSharedPreferences("hider", MODE_PRIVATE);
    if (!sp.getBoolean("enabled", true)) { matcher = null; return null; }
    String k = sp.getString("keywords", "");
    boolean g = sp.getBoolean("ignoreGaps", true);
    Matcher cur = matcher;
    if (cur == null || !k.equals(lastKw) || g != lastGap) { cur = new Matcher(k, g); matcher = cur; lastKw = k; lastGap = g; }
    return cur;
  }

  void scan() {
    scanQueued.set(false);
    lastScanAt = SystemClock.uptimeMillis();
    List<Track> found = new ArrayList<>();
    boolean target = false;
    try {
      Matcher mt = m();
      List<AccessibilityWindowInfo> ws = null;
      try { ws = getWindows(); } catch (Exception ignored) {}
      if (ws != null && !ws.isEmpty()) {
        // 화면에 떠 있는 창마다: X·시계 앱의 창이면 (본 화면이든 배너·팝업이든) 훑음
        for (AccessibilityWindowInfo w : ws) {
          if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
          AccessibilityNodeInfo root = w.getRoot();
          int mode = modeOf(root);
          if (mode == MODE_NONE) continue;
          target = true;
          if (mt == null) continue;
          int before = found.size();
          walk(mode, root, mt, found);
          if (found.size() > before) {
            List<Rect> occ = occludersAbove(ws, w);
            for (int i = before; i < found.size(); i++) found.get(i).occ = occ;
          }
        }
      } else {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        int mode = modeOf(root);
        if (mode != MODE_NONE) { target = true; if (mt != null) walk(mode, root, mt, found); }
      }
    } catch (Exception ignored) {}
    targetOnScreen = target;
    final List<Track> next = found;
    if (trackH != null) trackH.post(() -> adopt(next));
  }

  static final int MODE_NONE = 0, MODE_X = 1, MODE_CLOCK = 2;

  int modeOf(AccessibilityNodeInfo root) {
    if (root == null || root.getPackageName() == null) return MODE_NONE;
    String pkg = root.getPackageName().toString();
    if (X_APP.equals(pkg) || (BROWSERS.contains(pkg) && onX(root, 0))) return MODE_X;
    if (CLOCKS.contains(pkg)) return MODE_CLOCK;
    return MODE_NONE;
  }

  void walk(int mode, AccessibilityNodeInfo root, Matcher mt, List<Track> out) {
    if (mode == MODE_X) walkX(root, mt, out, 0); else walkClock(root, mt, out, 0);
  }

  /**
   * 이 창보다 위에 떠 있는 창의 영역: 대화상자·팝업·배너, 키보드, 펼친 알림창 등.
   * 덮개가 그 위를 가리지 않게 이 영역은 잘라 냄. 얇은 상태 표시줄·내비게이션 바는 빼서 그 아래로 지나가는 글도 덮음.
   */
  List<Rect> occludersAbove(List<AccessibilityWindowInfo> ws, AccessibilityWindowInfo me) {
    List<Rect> out = new ArrayList<>();
    int screenH = getResources().getDisplayMetrics().heightPixels;
    for (AccessibilityWindowInfo o : ws) {
      if (o == me || o.getLayer() <= me.getLayer()) continue;
      int ty = o.getType();
      Rect b = new Rect();
      o.getBoundsInScreen(b);
      if (b.isEmpty()) continue;
      boolean take = ty == AccessibilityWindowInfo.TYPE_APPLICATION
          || ty == AccessibilityWindowInfo.TYPE_INPUT_METHOD
          || (ty == AccessibilityWindowInfo.TYPE_SYSTEM && b.height() > screenH / 4);
      if (take) out.add(b);
    }
    return out;
  }

  /** 브라우저면 주소창에 x.com / twitter.com이 떠 있을 때만 검사 */
  boolean onX(AccessibilityNodeInfo n, int d) {
    if (n == null || d > 25) return false;
    CharSequence t = n.getText();
    if (t != null && t.toString().matches("(?i)^(https?://)?((www|mobile|pro)\\.)?(x|twitter)\\.com.*")) return true;
    for (int i = 0; i < n.getChildCount(); i++) if (onX(n.getChild(i), d + 1)) return true;
    return false;
  }

  /** X: 키워드가 걸린 글이 들어 있는 게시물 한 칸 전체를 덮음 */
  void walkX(AccessibilityNodeInfo n, Matcher mt, List<Track> out, int d) {
    if (n == null || d > 60 || !n.isVisibleToUser()) return;
    if (mt.matches(n.getText()) || mt.matches(n.getContentDescription())) {
      Track t = trackFor(n, true);
      if (t != null) { add(out, t); return; }
    }
    for (int i = 0; i < n.getChildCount(); i++) walkX(n.getChild(i), mt, out, d + 1);
  }

  /** 시계·알람: 키워드가 든 글자 노드만 덮음. 글자가 없고 설명(contentDescription)에만 있으면 그 칸을 덮음 */
  void walkClock(AccessibilityNodeInfo n, Matcher mt, List<Track> out, int d) {
    if (n == null || d > 60 || !n.isVisibleToUser()) return;
    if (mt.matches(n.getText())) {
      Track t = trackFor(n, false);
      if (t != null) add(out, t);
      return;
    }
    int before = out.size();
    for (int i = 0; i < n.getChildCount(); i++) walkClock(n.getChild(i), mt, out, d + 1);
    if (out.size() == before && mt.matches(n.getContentDescription())) {
      Track t = trackFor(n, false);
      if (t != null) add(out, t);
    }
  }

  /** wholeItem=true면 스크롤 목록의 바로 아래 자식(=게시물 한 칸)을 덮고, false면 그 노드만 덮음 */
  Track trackFor(AccessibilityNodeInfo n, boolean wholeItem) {
    AccessibilityNodeInfo c = n, list = null;
    for (int i = 0; i < 12; i++) {
      AccessibilityNodeInfo p = c.getParent();
      if (p == null) break;
      if (p.isScrollable()) { list = p; break; }
      c = p;
    }
    AccessibilityNodeInfo item = (wholeItem && list != null) ? c : n;
    Rect r = new Rect();
    item.getBoundsInScreen(r);   // 화면에 보이는 부분만 (위쪽 부모들에 잘린 크기)
    if (r.isEmpty()) return null;
    Rect clip = null;
    if (list != null) { clip = new Rect(); list.getBoundsInScreen(clip); if (clip.isEmpty()) clip = null; }
    Track t = new Track(item, n, list, clip, wholeItem ? 0 : dp(2));
    t.r.set(r);
    t.t = t.verifiedAt = SystemClock.uptimeMillis();
    return t;
  }

  static void add(List<Track> out, Track t) {
    for (Track o : out) if (o.item.equals(t.item)) return;  // 한 칸 안에서 두 번 걸려도 덮개는 하나
    out.add(t);
  }

  // ===================== 따라가기 (xkh-track 스레드) =====================

  final Choreographer.FrameCallback frameCb = this::onFrame;
  final Runnable idlePoll = this::wake;

  void adopt(List<Track> next) {
    // 같은 노드를 계속 덮고 있으면 속도를 이어받아 부드럽게
    for (Track n : next) {
      for (Track o : tracks) {
        if (o.item.equals(n.item)) { n.vTop = o.vTop; n.vBottom = o.vBottom; break; }
      }
    }
    tracks = next;
    publish();
    wake();
  }

  void wake() {
    if (trackH != null) trackH.removeCallbacks(idlePoll);
    if (frameOn || choreo == null) return;
    if (tracks.isEmpty()) { publish(); return; }
    frameOn = true;
    choreo.postFrameCallback(frameCb);
  }

  void onFrame(long frameTimeNanos) {
    frameOn = false;
    if (tracks.isEmpty()) { publish(); return; }
    long now = SystemClock.uptimeMillis();
    if (refreshAll(now)) lastMoveAt = now;
    publish();
    long active = Math.max(lastMoveAt, lastScrollAt);
    if (now - active < LINGER_MS) {
      wake();                                    // 움직이는 중: 매 프레임
    } else if (now - active < IDLE_POLL_FOR_MS && !tracks.isEmpty()) {
      trackH.postDelayed(idlePoll, IDLE_POLL_MS); // 막 멈춘 뒤: 다음 스크롤 시작을 빨리 알아채려고 가끔 확인
    }
  }

  /** 덮고 있는 노드들의 현재 위치만 다시 읽음. 하나라도 움직였으면 true */
  boolean refreshAll(long now) {
    boolean moved = false, lost = false;
    Rect nr = new Rect();
    for (int i = tracks.size() - 1; i >= 0; i--) {
      Track t = tracks.get(i);
      boolean ok;
      try { ok = t.item.refresh(); } catch (Exception e) { ok = false; }
      if (ok) {
        t.item.getBoundsInScreen(nr);
        ok = !nr.isEmpty() && t.item.isVisibleToUser();
      }
      if (ok) {
        // 한 번에 너무 멀리 뛰었으면 목록이 이 칸을 다른 글로 재활용했을 수 있으니 바로 확인
        boolean jumped = Math.abs(nr.top - t.r.top) > Math.max(dp(160), t.r.height());
        if (jumped || now - t.verifiedAt >= VERIFY_MS) { ok = verify(t); t.verifiedAt = now; }
      }
      if (!ok) { tracks.remove(i); lost = true; continue; }
      float dt = Math.max(1, now - t.t);
      if (!nr.equals(t.r)) {
        moved = true;
        t.vTop = 0.5f * t.vTop + 0.5f * ((nr.top - t.r.top) / dt);
        t.vBottom = 0.5f * t.vBottom + 0.5f * ((nr.bottom - t.r.bottom) / dt);
      } else {
        t.vTop *= 0.5f;
        t.vBottom *= 0.5f;
      }
      t.r.set(nr);
      t.t = now;
    }
    if (lost) requestScan(0);
    return moved;
  }

  /** 덮은 자리에 아직 키워드가 있는지 (목록이 칸을 재활용하면 내용이 바뀜) */
  boolean verify(Track t) {
    Matcher mt = matcher;
    if (mt == null) return false;
    try {
      if (t.anchor != t.item && !t.anchor.refresh()) return false;
      return mt.matches(t.anchor.getText()) || mt.matches(t.anchor.getContentDescription());
    } catch (Exception e) { return false; }
  }

  /** 현재 위치(+다음 프레임까지 예상 이동)를 덮개 사각형으로 만들어 메인 스레드로 보냄 */
  void publish() {
    List<Rect> raw = new ArrayList<>();
    List<AccessibilityNodeInfo> rawLists = new ArrayList<>();
    List<List<Rect>> rawOcc = new ArrayList<>();
    int cap = dp(32);
    for (Track t : tracks) {
      Rect r = new Rect(t.r);
      r.top += clamp(Math.round(t.vTop * LEAD_MS), cap);       // 잘려서 멈춰 있는 변은 속도 0이라 그대로
      r.bottom += clamp(Math.round(t.vBottom * LEAD_MS), cap);
      if (t.pad > 0) r.inset(-t.pad, -t.pad);
      if (t.clip != null && !r.intersect(t.clip)) continue;
      if (r.isEmpty()) continue;
      merge(raw, rawLists, rawOcc, r, t.list, t.occ);
    }
    List<Rect> rs = new ArrayList<>();
    List<AccessibilityNodeInfo> ls = new ArrayList<>();
    int min = dp(10);
    for (int i = 0; i < raw.size(); i++) {
      for (Rect piece : cut(raw.get(i), rawOcc.get(i), min)) { rs.add(piece); ls.add(rawLists.get(i)); }
    }
    if (rs.equals(published)) return;
    published = rs;
    if (pendingScene.getAndSet(new Scene(rs, ls)) == null) main.postAtFrontOfQueue(renderRunnable);
  }

  /** 겹치는 덮개는 하나로 합침 (시계 앱에서 글자끼리 겹칠 때, X에서 배너와 글이 겹칠 때 등) */
  static void merge(List<Rect> rs, List<AccessibilityNodeInfo> ls, List<List<Rect>> occs,
                    Rect r, AccessibilityNodeInfo list, List<Rect> occ) {
    for (int i = 0; i < rs.size(); i++) {
      if (Rect.intersects(rs.get(i), r)) {
        Rect u = rs.remove(i);
        AccessibilityNodeInfo l = ls.remove(i);
        List<Rect> o = occs.remove(i);
        u.union(r);
        List<Rect> both = o;
        if (!occ.isEmpty()) { both = new ArrayList<>(o); both.addAll(occ); }
        merge(rs, ls, occs, u, l != null ? l : list, both);
        return;
      }
    }
    rs.add(r);
    ls.add(list);
    occs.add(occ);
  }

  /** 덮개에서 위에 떠 있는 창 영역을 잘라 냄 (최대 4조각). 너무 작은 조각은 버림 */
  static List<Rect> cut(Rect c, List<Rect> occ, int min) {
    List<Rect> cur = new ArrayList<>();
    cur.add(c);
    for (Rect h : occ) {
      List<Rect> nx = new ArrayList<>();
      for (Rect a : cur) {
        if (!Rect.intersects(a, h)) { nx.add(a); continue; }
        int top = Math.max(a.top, h.top), bot = Math.min(a.bottom, h.bottom);
        if (h.top > a.top) keep(nx, new Rect(a.left, a.top, a.right, h.top), min);
        if (h.bottom < a.bottom) keep(nx, new Rect(a.left, h.bottom, a.right, a.bottom), min);
        if (h.left > a.left) keep(nx, new Rect(a.left, top, h.left, bot), min);
        if (h.right < a.right) keep(nx, new Rect(h.right, top, a.right, bot), min);
      }
      cur = nx;
    }
    return cur;
  }

  static void keep(List<Rect> out, Rect r, int min) {
    if (r.width() >= min && r.height() >= min) out.add(r);
  }

  static int clamp(int v, int cap) { return Math.max(-cap, Math.min(cap, v)); }

  // ===================== 그리기 (메인 스레드) =====================

  final Runnable renderRunnable = () -> {
    Scene s = pendingScene.getAndSet(null);
    if (s != null) render(s.rs, s.ls);
  };

  /** 덮개 창은 재사용하고, 위치·크기가 바뀐 것만 옮김 */
  void render(List<Rect> rs, List<AccessibilityNodeInfo> ls) {
    if (wm == null) return;
    viewLists = ls;
    while (views.size() > rs.size()) {
      TextView v = views.remove(views.size() - 1);
      viewRects.remove(viewRects.size() - 1);
      try { wm.removeView(v); } catch (Exception ignored) {}
    }
    for (int i = 0; i < rs.size(); i++) {
      Rect r = rs.get(i);
      if (i < views.size()) {
        if (r.equals(viewRects.get(i))) continue;
        TextView v = views.get(i);
        try {
          WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
          place(lp, r);
          fitPadding(v, r);
          wm.updateViewLayout(v, lp);
          viewRects.get(i).set(r);
        } catch (Exception ignored) {}
      } else {
        TextView tv = makeCover(i);
        fitPadding(tv, r);
        try { wm.addView(tv, params(r)); views.add(tv); viewRects.add(new Rect(r)); } catch (Exception e) { break; }
      }
    }
  }

  void clearViews() {
    if (wm != null) for (View v : views) try { wm.removeView(v); } catch (Exception ignored) {}
    views.clear();
    viewRects.clear();
    viewLists = new ArrayList<>();
  }

  WindowManager.LayoutParams params(Rect r) {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.windowAnimations = 0;
    if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    place(lp, r);
    return lp;
  }

  static void place(WindowManager.LayoutParams lp, Rect r) {
    lp.x = r.left;
    lp.y = r.top;
    lp.width = r.width();
    lp.height = r.height();
  }

  /** 작은 덮개(알람 이름 한 줄 등)에서도 BLOCKED가 보이게 여백을 크기에 맞춤 */
  void fitPadding(TextView v, Rect r) {
    int p = Math.max(dp(1), Math.min(dp(6), Math.min(r.width(), r.height()) / 10));
    if (v.getPaddingTop() != p) v.setPadding(p, p, p, p);
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
    tv.setAutoSizeTextTypeUniformWithConfiguration(8, 72, 1, TypedValue.COMPLEX_UNIT_DIP);
    // 누르기는 아래 글에 닿지 않게 막고, 위·아래로 쓸면 목록을 한 화면씩 넘김 (덮개 때문에 스크롤이 막히지 않게)
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
    List<AccessibilityNodeInfo> ls = viewLists;
    if (idx >= ls.size()) return;
    AccessibilityNodeInfo list = ls.get(idx);
    if (list == null) return;
    try {
      list.refresh();
      list.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    } catch (Exception ignored) {}
    lastScrollAt = SystemClock.uptimeMillis();
    poke();
  }

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
