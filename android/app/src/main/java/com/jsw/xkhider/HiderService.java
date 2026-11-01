package com.jsw.xkhider;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * X 앱·브라우저(x.com)와 시계·알람 앱 화면을 읽어서, 키워드가 걸린 곳 위에 빨간 BLOCKED(Bebas Neue) 덮개를 띄웁니다.
 * 그 앱의 화면에 떠 있는 모든 창(본 화면, 알림 탭, 배너·팝업·대화상자)을 훑습니다.
 *
 * 구조 (1.1.6):
 *  - xkh-scan 스레드: 화면 트리를 훑어 키워드가 걸린 노드를 찾음 (무거운 일, 스크롤 중 ~90ms 간격).
 *  - xkh-track 스레드: 이미 찾은 노드의 실제 위치를 매 프레임(Choreographer) 다시 읽음 (노드당 IPC 1번). 예측 없음.
 *  - 메인 스레드: 노드 하나에 덮개 창 하나. 그 노드를 덮는 동안 같은 창이 따라가고, 덮을 곳이 없어지면 바로 없앰.
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
  static final int JUMP_DP = 120;           // 한 프레임에 이보다 멀리 뛰면 목록이 칸을 재활용했을 수 있어 바로 확인
  static final int MAX_MISSES = 2;          // 스캔이 이만큼 연달아 못 찾아도 실제로 그 자리에 있으면 유지 (스캔 중 트리가 잠깐 비어도 안 깜빡이게)
  static final int MAX_UP = 12, MAX_UP_X = 40; // 목록을 찾아 부모로 올라가는 한도 (브라우저는 문서 전체가 한 칸으로 잡히지 않게 낮게 둠)

  /** 덮개 하나가 따라가는 대상 */
  static final class Track {
    final AccessibilityNodeInfo item;    // 덮을 노드 (X: 게시물 한 칸, 시계: 글자 노드)
    final boolean cell;                  // item이 스크롤 목록의 한 칸 전체인지
    final int pad;
    AccessibilityNodeInfo anchor;        // 키워드가 실제로 들어 있는 노드 (재활용 확인용)
    AccessibilityNodeInfo list;          // 스크롤 목록 (덮개 위에서 쓸어 넘길 때), 없으면 null
    List<Rect> occ = Collections.emptyList(); // 이 창보다 위에 떠 있는 창들 (덮개가 그 위를 가리지 않게)
    final Rect r = new Rect();           // 마지막으로 읽은 실제 위치
    long id, verifiedAt;                 // id: 덮개 창을 이어 가는 열쇠
    int misses;                          // 스캔이 연달아 못 찾은 횟수
    Track(AccessibilityNodeInfo item, AccessibilityNodeInfo anchor, AccessibilityNodeInfo list, boolean cell, int pad) {
      this.item = item; this.anchor = anchor; this.list = list; this.cell = cell; this.pad = pad;
    }
  }

  /** 합치고 자르기 전의 덮개 자리 */
  static final class Box {
    final long id; final int win; final boolean cell; final Rect r;
    final AccessibilityNodeInfo list; final List<Rect> occ;
    Box(long id, int win, boolean cell, Rect r, AccessibilityNodeInfo list, List<Rect> occ) {
      this.id = id; this.win = win; this.cell = cell; this.r = r; this.list = list; this.occ = occ;
    }
  }

  /** 메인 스레드에 넘기는 덮개 한 장. key가 같으면 같은 창이 따라감 */
  static final class Cover {
    final long key; final Rect r; final AccessibilityNodeInfo list;
    Cover(long key, Rect r, AccessibilityNodeInfo list) { this.key = key; this.r = r; this.list = list; }
    @Override public boolean equals(Object o) {
      return o instanceof Cover && key == ((Cover) o).key && r.equals(((Cover) o).r);
    }
    @Override public int hashCode() { return Long.hashCode(key); }
  }

  /** 화면에 떠 있는 덮개 창 */
  static final class Shown {
    TextView v;
    final Rect r = new Rect();
    AccessibilityNodeInfo list;
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
  final AtomicReference<List<Cover>> pendingScene = new AtomicReference<>();

  // xkh-scan 스레드 전용
  String lastKw; boolean lastGap;

  // xkh-track 스레드 전용
  Choreographer choreo;
  List<Track> tracks = new ArrayList<>();
  List<Cover> published = new ArrayList<>();
  boolean frameOn;
  long lastMoveAt, nextId;

  // 메인 스레드 전용
  final Map<Long, Shown> shown = new HashMap<>();
  boolean dead;

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
      if (targetOnScreen || !shown.isEmpty()) { poke(); requestScan(SCAN_GAP_ACTIVE); }
      return;
    }
    if (!(X_APP.equals(pkg) || BROWSERS.contains(pkg) || CLOCKS.contains(pkg))) {
      // 다른 앱·홈·알림창으로 넘어가면 바로 다시 확인해서 덮개를 치움
      if (!shown.isEmpty() && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) requestScanNow();
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
    dead = true;   // 트래킹 스레드가 뒤늦게 보낸 장면이 창을 다시 만들지 않게
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
    } catch (Exception e) { target = targetOnScreen; }
    targetOnScreen = target;
    final List<Track> next = found;
    final boolean seen = target;
    if (trackH != null) trackH.post(() -> adopt(next, seen));
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
    if (mode == MODE_X) walkX(root, mt, out, 0, false, false); else walkClock(root, mt, out, 0);
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

  /**
   * X: 키워드가 걸린 글이 들어 있는 게시물 한 칸 전체를 덮음.
   * X 앱에서는 칸이 조금이라도 보이면 그 안을 전부 검사함. 글은 화면 밖으로 나가고 이미지만 남은 게시물도 덮어야 하니까.
   * inList: 부모가 세로 목록 (이 노드가 한 칸), inCell: 보이는 칸의 안쪽.
   */
  void walkX(AccessibilityNodeInfo n, Matcher mt, List<Track> out, int d, boolean inList, boolean inCell) {
    if (n == null || d > 60 || (!inCell && !n.isVisibleToUser())) return;
    if (mt.matches(n.getText()) || mt.matches(n.getContentDescription())) {
      Track t = trackFor(n, true);
      if (t != null) { add(out, t); return; }
    }
    boolean cells = inCell || inList;
    boolean isList = isXApp(n) && n.isScrollable() && n.getCollectionInfo() != null && !sideways(n);
    for (int i = 0; i < n.getChildCount(); i++) walkX(n.getChild(i), mt, out, d + 1, isList, cells);
  }

  static boolean isXApp(AccessibilityNodeInfo n) { return X_APP.equals(String.valueOf(n.getPackageName())); }

  /** 가로로 넘기는 목록인지 (탭 ViewPager, 게시물 안의 이미지·카드 캐러셀). 게시물 칸을 찾을 때는 건너뜀 */
  static boolean sideways(AccessibilityNodeInfo p) {
    CharSequence cn = p.getClassName();
    if (cn != null && (cn.toString().contains("HorizontalScrollView") || cn.toString().contains("ViewPager"))) return true;
    AccessibilityNodeInfo.CollectionInfo ci = p.getCollectionInfo();
    return ci != null && ci.getRowCount() == 1 && ci.getColumnCount() != 1;
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

  /** wholeItem=true면 세로 스크롤 목록의 바로 아래 자식(=게시물 한 칸)을 덮고, false면 그 노드만 덮음 */
  Track trackFor(AccessibilityNodeInfo n, boolean wholeItem) {
    int up = isXApp(n) ? MAX_UP_X : MAX_UP;
    AccessibilityNodeInfo c = n, list = null;
    for (int i = 0; i < up; i++) {
      AccessibilityNodeInfo p = c.getParent();
      if (p == null) break;
      if (p.isScrollable() && !sideways(p)) { list = p; break; }
      c = p;
    }
    boolean cell = wholeItem && list != null;
    AccessibilityNodeInfo item = cell ? c : n;
    Rect r = new Rect();
    item.getBoundsInScreen(r);   // 화면에 보이는 부분만 (위쪽 부모들에 잘린 크기)
    if (r.isEmpty()) return null;
    return new Track(item, n, list, cell, wholeItem ? 0 : dp(2));
  }

  static void add(List<Track> out, Track t) {
    for (Track o : out) if (o.item.equals(t.item)) return;  // 한 칸 안에서 두 번 걸려도 덮개는 하나
    out.add(t);
  }

  // ===================== 따라가기 (xkh-track 스레드) =====================

  final Choreographer.FrameCallback frameCb = this::onFrame;
  final Runnable idlePoll = this::wake;

  /**
   * 스캔 결과 반영. 이미 덮던 노드는 같은 덮개로 이어 가고, 새 노드는 지금 실제 위치를 읽어 확인한 뒤에만 덮음.
   * (스캔 결과의 위치·글자는 스캔하는 사이에 낡았을 수 있어서 그대로 쓰면 덮개가 뒤로 튐)
   */
  void adopt(List<Track> found, boolean target) {
    long now = SystemClock.uptimeMillis();
    Rect nr = new Rect();
    List<Track> next = new ArrayList<>();
    for (Track o : tracks) {
      Track n = take(found, o.item);
      if (n != null) {
        o.anchor = n.anchor; o.list = n.list; o.occ = n.occ; o.misses = 0;
        next.add(o);
      } else if (target && ++o.misses <= MAX_MISSES && read(o, nr) && verify(o)) {
        o.r.set(nr);
        o.verifiedAt = now;
        next.add(o);
      }
    }
    for (Track n : found) {
      if (!read(n, nr) || !verify(n)) continue;
      n.r.set(nr);
      n.verifiedAt = now;
      n.id = ++nextId;
      addUnlessInside(next, n);
    }
    tracks = next;
    publish();
    wake();
  }

  static Track take(List<Track> found, AccessibilityNodeInfo item) {
    for (int i = 0; i < found.size(); i++) if (found.get(i).item.equals(item)) return found.remove(i);
    return null;
  }

  /** 같은 창에서 이미 덮은 자리의 안쪽이면 덮개를 또 만들지 않음. 새 자리가 더 크면 안쪽 것을 빼고 하나만 */
  static void addUnlessInside(List<Track> ts, Track n) {
    for (int i = ts.size() - 1; i >= 0; i--) {
      Track k = ts.get(i);
      if (k.item.getWindowId() != n.item.getWindowId()) continue;
      if (k.r.contains(n.r)) return;
      if (n.r.contains(k.r)) ts.remove(i);
    }
    ts.add(n);
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

  /** 노드의 지금 실제 위치를 읽음. 사라졌거나 안 보이면 false */
  boolean read(Track t, Rect out) {
    try {
      if (!t.item.refresh()) return false;
      t.item.getBoundsInScreen(out);
      return !out.isEmpty() && t.item.isVisibleToUser();
    } catch (Exception e) { return false; }
  }

  /** 덮고 있는 노드들의 현재 위치만 다시 읽음. 하나라도 움직였으면 true */
  boolean refreshAll(long now) {
    boolean moved = false, lost = false;
    Rect nr = new Rect();
    int jump = dp(JUMP_DP);
    for (int i = tracks.size() - 1; i >= 0; i--) {
      Track t = tracks.get(i);
      boolean ok = read(t, nr);
      if (ok) {
        // 한 번에 멀리 뛰었으면 목록이 이 칸을 다른 글로 재활용했을 수 있으니 옮기기 전에 바로 확인
        boolean jumped = Math.abs(nr.top - t.r.top) > jump || Math.abs(nr.bottom - t.r.bottom) > jump;
        if (jumped || now - t.verifiedAt >= VERIFY_MS) { ok = verify(t); t.verifiedAt = now; }
      }
      if (!ok) { tracks.remove(i); lost = true; continue; }
      if (!nr.equals(t.r)) { moved = true; t.r.set(nr); }
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

  /** 지금 읽은 실제 위치를 덮개로 만들어 메인 스레드로 보냄 */
  void publish() {
    Point size = new Point();
    wm.getDefaultDisplay().getRealSize(size);
    Rect screen = new Rect(0, 0, size.x, size.y);
    List<Box> boxes = new ArrayList<>();
    for (Track t : tracks) {
      Rect r = new Rect(t.r);
      if (t.pad > 0) r.inset(-t.pad, -t.pad);
      if (!r.intersect(screen)) continue;    // 화면 밖에는 덮개를 띄우지 않음
      Box b = new Box(t.id, t.item.getWindowId(), t.cell, r, t.list, t.occ);
      if (t.cell) boxes.add(b); else merge(boxes, b);
    }
    List<Cover> covers = new ArrayList<>();
    int min = dp(10);
    for (Box b : boxes) {
      long piece = 0;
      for (Rect r : cut(b.r, b.occ, min)) covers.add(new Cover(b.id << 16 | piece++, r, b.list));
    }
    if (covers.equals(published)) return;
    published = covers;
    if (pendingScene.getAndSet(covers) == null) main.postAtFrontOfQueue(renderRunnable);
  }

  /**
   * 같은 창에서 겹치는 글자 덮개는 하나로 합침 (시계 앱에서 글자끼리 겹칠 때 등).
   * 게시물 칸은 합치지 않음: 칸끼리는 원래 안 겹치고, 위치를 읽는 시점 차이로 잠깐 겹쳐 보일 때 합치면 덮개 창이 늘었다 줄었다 함.
   */
  static void merge(List<Box> bs, Box b) {
    for (int i = 0; i < bs.size(); i++) {
      Box o = bs.get(i);
      if (o.cell || o.win != b.win || !Rect.intersects(o.r, b.r)) continue;
      bs.remove(i);
      Rect u = new Rect(o.r);
      u.union(b.r);
      List<Rect> both = o.occ;
      if (!b.occ.isEmpty()) { both = new ArrayList<>(o.occ); both.addAll(b.occ); }
      merge(bs, new Box(Math.min(o.id, b.id), o.win, false, u, o.list != null ? o.list : b.list, both));
      return;
    }
    bs.add(b);
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

  // ===================== 그리기 (메인 스레드) =====================

  final Runnable renderRunnable = () -> {
    List<Cover> cs = pendingScene.getAndSet(null);
    if (cs != null) render(cs);
  };

  /** 장면에 없는 덮개 창은 먼저 바로 없애고, 남은 창은 자기 노드를 따라 옮기고, 새 노드에만 새 창을 만듦 */
  void render(List<Cover> covers) {
    if (wm == null || dead) return;
    Set<Long> keep = new HashSet<>();
    for (Cover c : covers) keep.add(c.key);
    for (Iterator<Map.Entry<Long, Shown>> it = shown.entrySet().iterator(); it.hasNext();) {
      Map.Entry<Long, Shown> e = it.next();
      if (!keep.contains(e.getKey())) { destroy(e.getValue()); it.remove(); }
    }
    for (Cover c : covers) {
      Shown s = shown.get(c.key);
      if (s != null) {
        s.list = c.list;
        if (c.r.equals(s.r)) continue;
        try {
          WindowManager.LayoutParams lp = (WindowManager.LayoutParams) s.v.getLayoutParams();
          place(lp, c.r);
          fitPadding(s.v, c.r);
          wm.updateViewLayout(s.v, lp);
          s.r.set(c.r);
          continue;
        } catch (Exception e) {
          destroy(s);   // 옮길 수 없는 창은 남겨 두지 않고 새로 만듦
          shown.remove(c.key);
        }
      }
      s = new Shown();
      s.list = c.list;
      s.r.set(c.r);
      s.v = makeCover(s);
      fitPadding(s.v, c.r);
      try { wm.addView(s.v, params(c.r)); shown.put(c.key, s); } catch (Exception e) { break; }
    }
  }

  /** 다음 화면 갱신을 기다리지 않고 바로 없앰 (창 닫힘 애니메이션도 없음) */
  void destroy(Shown s) {
    try { wm.removeViewImmediate(s.v); } catch (Exception ignored) {}
  }

  void clearViews() {
    if (wm != null) for (Shown s : shown.values()) destroy(s);
    shown.clear();
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
  TextView makeCover(final Shown s) {
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
          if (Math.abs(dy) > dp(40) && Math.abs(dy) > Math.abs(dx)) page(s.list, dy < 0);
        }
        return true;
      }
    });
    return tv;
  }

  void page(AccessibilityNodeInfo list, boolean forward) {
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
