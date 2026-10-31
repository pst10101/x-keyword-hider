package com.jsw.xkhider;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
  static final String ADB_ALLOW = "adb shell appops set com.jsw.xkhider ACCESS_RESTRICTED_SETTINGS allow";
  static final String ADB_INSTALL = "adb install -r XKeywordHider-debug.apk";
  static final int INK = 0xFF1F2328, MUTED = 0xFF5B6570, WARN = 0xFFB54708, OK = 0xFF00875A, BAD = 0xFFC4314B;

  TextView status, restricted;
  LinearLayout guide;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    SharedPreferences sp = getSharedPreferences("hider", MODE_PRIVATE);
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(16), dp(20), dp(16), dp(28));

    // 제목 + 버전 (업데이트가 제대로 깔렸는지 바로 확인)
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView t = text("X 키워드 가리개", 22, INK, true);
    head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(text("v" + versionName(), 14, MUTED, false));
    l.addView(head);

    // 가린 자리가 실제로 어떻게 보이는지 (Bebas Neue가 APK에 들어갔는지도 여기서 바로 확인됨)
    TextView preview = new TextView(this);
    preview.setText(HiderService.LABEL);
    try { preview.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/BebasNeue.ttf")); } catch (Exception ignored) {}
    preview.setTextColor(HiderService.RED);
    preview.setBackgroundColor(HiderService.BG);
    preview.setGravity(Gravity.CENTER);
    preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
    preview.setLetterSpacing(0.04f);
    preview.setIncludeFontPadding(false);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(64));
    plp.topMargin = dp(10);
    plp.bottomMargin = dp(6);
    l.addView(preview, plp);

    status = text("", 15, MUTED, true);
    status.setPadding(0, dp(4), 0, dp(4));
    l.addView(status);

    guide = buildGuide();
    l.addView(guide, margins(0, 8, 0, 12));

    // ---- 키워드 ----
    l.addView(sectionTitle("키워드"));
    CheckBox en = new CheckBox(this); en.setText("켜기"); en.setChecked(sp.getBoolean("enabled", true)); l.addView(en);
    CheckBox gap = new CheckBox(this); gap.setText("\"분 탕\", \"분.탕\"처럼 끼워넣어도 잡기"); gap.setChecked(sp.getBoolean("ignoreGaps", true)); l.addView(gap);
    l.addView(text("한 줄에 하나씩 · /정규식/i 가능 · // 로 시작하면 메모", 13, MUTED, false));
    l.addView(text("X 앱·브라우저(x.com)의 게시물과, 시계·알람 앱의 알람 이름에서 가려요.", 13, MUTED, false));
    EditText kw = new EditText(this);
    kw.setMinLines(6);
    kw.setGravity(Gravity.TOP);
    kw.setText(sp.getString("keywords", ""));
    l.addView(kw, new LinearLayout.LayoutParams(-1, -2));
    Button save = new Button(this); save.setText("저장"); l.addView(save);
    save.setOnClickListener(v -> {
      sp.edit().putString("keywords", kw.getText().toString())
          .putBoolean("enabled", en.isChecked())
          .putBoolean("ignoreGaps", gap.isChecked()).apply();
      Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show();
    });

    // ---- 언제든 쓸 수 있는 바로가기 ----
    l.addView(sectionTitle("설정 바로가기"));
    l.addView(button("접근성 설정 열기", v -> openA11y()));
    l.addView(button("앱 정보 열기 (⋮ → 제한된 설정 허용)", v -> openAppInfo()));

    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    sv.addView(l);
    setContentView(sv);
  }

  /** "앱의 액세스가 거부됨"(제한된 설정) + Play 프로텍트 안내. 접근성 서비스가 꺼져 있을 때만 보임 */
  LinearLayout buildGuide() {
    LinearLayout g = new LinearLayout(this);
    g.setOrientation(LinearLayout.VERTICAL);
    g.setPadding(dp(14), dp(14), dp(14), dp(14));
    g.setBackground(round(0xFFFFF4E5, 0xFFF5C27A));

    g.addView(text("처음 한 번만 하면 되는 설정", 18, WARN, true));
    g.addView(text("접근성에서 \"앱의 액세스가 거부됨\"이 뜨는 건 정상이에요. 안드로이드(One UI)는 폰에 직접 받은 APK의 "
        + "접근성 권한을 '제한된 설정'으로 막아 두고, 앱이 스스로 풀 수는 없어요. 아래 순서대로 한 번만 직접 허용해 주세요.",
        14, INK, false), margins(0, 6, 0, 0));
    restricted = text("", 14, INK, true);
    g.addView(restricted, margins(0, 8, 0, 0));

    step(g, "1단계 · 접근성에서 한 번 눌러 보기",
        "아래 버튼을 누른 뒤 (목록이 나오면 '설치된 앱' → X 키워드 가리개) '사용' 스위치를 눌러요. "
        + "\"앱의 액세스가 거부됨\" 창이 뜨면 '확인'으로 닫아요. 이걸 한 번 해야 2단계 메뉴가 생겨요.",
        "접근성 설정 열기", v -> openA11y());
    step(g, "2단계 · 제한된 설정 허용",
        "아래 버튼으로 앱 정보를 연 뒤 오른쪽 위 ⋮(점 세 개) → '제한된 설정 허용' → 지문·PIN으로 확인해요. "
        + "⋮나 그 메뉴가 안 보이면 1단계를 한 번 더 해 주세요.",
        "앱 정보 열기", v -> openAppInfo());
    step(g, "3단계 · 접근성 서비스 켜기",
        "다시 접근성 설정 → X 키워드 가리개 → '사용' 켜기 → '허용'. 이 화면으로 돌아오면 위 상태가 '켜짐'으로 바뀌어요.",
        "접근성 설정 열기", v -> openA11y());

    g.addView(subTitle("PC가 있다면 (USB 디버깅)"));
    g.addView(text("아래 명령을 한 번 실행하면 1·2단계 없이 바로 3단계로 가면 돼요.", 14, INK, false));
    g.addView(code(ADB_ALLOW), margins(0, 6, 0, 0));
    g.addView(button("명령 복사", v -> copy(ADB_ALLOW)));
    g.addView(text("PC에서 '" + ADB_INSTALL + "'로 설치하면 처음부터 제한이 안 걸리는 경우가 많아요.", 13, MUTED, false));

    g.addView(subTitle("Play 프로텍트가 설치를 막을 때 (업데이트 포함)"));
    g.addView(text("• \"앱이 차단됨\"·\"안전하지 않은 앱\" 창이 뜨면 '자세히' 또는 '세부정보' → '그래도 설치'를 눌러요.\n"
        + "• '그래도 설치'가 아예 없으면: Play 스토어 → 오른쪽 위 프로필 → Play 프로텍트 → 오른쪽 위 ⚙ → "
        + "'Play 프로텍트로 앱 검사'를 잠깐 끄고 설치한 뒤 바로 다시 켜요. PC에서 adb install로 설치해도 돼요.\n"
        + "• 업데이트는 지우지 말고 기존 앱 위에 덮어 설치하세요. 같은 키로 서명돼 있어서 키워드가 그대로 남아요.",
        14, INK, false));
    return g;
  }

  void step(LinearLayout g, String title, String body, String btn, View.OnClickListener click) {
    g.addView(text(title, 16, INK, true), margins(0, 14, 0, 0));
    g.addView(text(body, 14, INK, false), margins(0, 4, 0, 0));
    g.addView(button(btn, click));
  }

  @Override protected void onResume() {
    super.onResume();
    boolean on = serviceOn();
    status.setText(on ? "● 접근성 서비스 켜짐 — X에서 바로 가려집니다" : "○ 접근성 서비스 꺼짐 — 아래 순서대로 켜 주세요");
    status.setTextColor(on ? OK : MUTED);
    guide.setVisibility(on ? View.GONE : View.VISIBLE);

    int r = restrictedState();
    if (on || r < 0) {
      restricted.setVisibility(View.GONE);
    } else {
      restricted.setVisibility(View.VISIBLE);
      restricted.setText(r == 1 ? "✓ 제한된 설정은 이미 허용됐어요 → 3단계만 하면 돼요"
                                : "✗ 제한된 설정이 아직 막혀 있어요 → 1·2단계부터 해 주세요");
      restricted.setTextColor(r == 1 ? OK : BAD);
    }
  }

  boolean serviceOn() {
    String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (s == null) return false;
    ComponentName me = new ComponentName(this, HiderService.class);
    TextUtils.SimpleStringSplitter it = new TextUtils.SimpleStringSplitter(':');
    it.setString(s);
    for (String x : it) if (me.equals(ComponentName.unflattenFromString(x))) return true;
    return false;
  }

  /** 제한된 설정 상태: 1 = 허용됨, 0 = 막힘, -1 = 알 수 없음(이 경우엔 아무것도 표시하지 않음) */
  int restrictedState() {
    if (Build.VERSION.SDK_INT < 33) return 1;  // 안드로이드 12 이하엔 제한된 설정이 없음
    try {
      AppOpsManager ao = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
      int m = ao.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), getPackageName());
      if (m == AppOpsManager.MODE_ALLOWED) return 1;
      if (m == AppOpsManager.MODE_ERRORED || m == AppOpsManager.MODE_IGNORED) return 0;
    } catch (Exception ignored) {}
    return -1;
  }

  void openA11y() {
    String comp = new ComponentName(this, HiderService.class).flattenToString();
    if (Build.VERSION.SDK_INT >= 33) {
      // 이 앱의 접근성 서비스 화면으로 바로 이동 (안 되면 아래 목록 화면으로)
      Intent d = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
      d.putExtra(Intent.EXTRA_COMPONENT_NAME, comp);
      if (start(d)) return;
    }
    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    Bundle args = new Bundle();
    args.putString(":settings:fragment_args_key", comp);
    i.putExtra(":settings:fragment_args_key", comp);
    i.putExtra(":settings:show_fragment_args", args);
    if (!start(i)) start(new Intent(Settings.ACTION_SETTINGS));
  }

  void openAppInfo() {
    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
    if (!start(i)) start(new Intent(Settings.ACTION_SETTINGS));
  }

  boolean start(Intent i) {
    try { startActivity(i); return true; } catch (Exception e) { return false; }
  }

  void copy(String s) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("adb", s));
    Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show();
  }

  String versionName() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
  }

  // ---- 작은 UI 도우미 ----
  TextView text(String s, float sp, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setLineSpacing(0, 1.15f);
    if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
    return v;
  }

  TextView sectionTitle(String s) {
    TextView v = text(s, 17, INK, true);
    v.setPadding(0, dp(16), 0, dp(4));
    return v;
  }

  TextView subTitle(String s) {
    TextView v = text(s, 15, WARN, true);
    v.setPadding(0, dp(18), 0, dp(4));
    return v;
  }

  TextView code(String s) {
    TextView v = text(s, 13, INK, false);
    v.setTypeface(Typeface.MONOSPACE);
    v.setTextIsSelectable(true);
    v.setPadding(dp(10), dp(8), dp(10), dp(8));
    v.setBackground(round(0xFFFFFFFF, 0xFFE4D3B8));
    return v;
  }

  Button button(String s, View.OnClickListener click) {
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setOnClickListener(click);
    return b;
  }

  GradientDrawable round(int fill, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(12));
    d.setStroke(dp(1), stroke);
    return d;
  }

  LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(l), dp(t), dp(r), dp(b));
    return p;
  }

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
