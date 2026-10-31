package com.jsw.xkhider;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;

public class MainActivity extends Activity {
  TextView status; LinearLayout help;
  static final String ADB1 = "adb shell appops set com.jsw.xkhider ACCESS_RESTRICTED_SETTINGS allow";
  static final String ADB2 = "adb install -r XKeywordHider-debug.apk";

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    SharedPreferences sp = getSharedPreferences("hider", MODE_PRIVATE);
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(40, 60, 40, 40);

    TextView t = new TextView(this); t.setText("X 키워드 가리개"); t.setTextSize(22); l.addView(t);

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

    status = new TextView(this); status.setPadding(0, dp(4), 0, dp(4)); l.addView(status);

    CheckBox en = new CheckBox(this); en.setText("켜기"); en.setChecked(sp.getBoolean("enabled", true)); l.addView(en);
    CheckBox gap = new CheckBox(this); gap.setText("\"분 탕\", \"분.탕\"처럼 끼워넣어도 잡기"); gap.setChecked(sp.getBoolean("ignoreGaps", true)); l.addView(gap);
    TextView h = new TextView(this); h.setText("키워드 (한 줄에 하나, /정규식/i 가능, // 는 주석)"); l.addView(h);
    EditText kw = new EditText(this); kw.setMinLines(8); kw.setGravity(Gravity.TOP); kw.setText(sp.getString("keywords", ""));
    l.addView(kw, new LinearLayout.LayoutParams(-1, 0, 1));

    Button save = new Button(this); save.setText("저장"); l.addView(save);
    save.setOnClickListener(v -> {
      sp.edit().putString("keywords", kw.getText().toString())
          .putBoolean("enabled", en.isChecked())
          .putBoolean("ignoreGaps", gap.isChecked()).apply();
      Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show();
    });

    // ---- 접근성 "앱의 액세스가 거부됨"(제한된 설정) 안내 ----
    help = new LinearLayout(this); help.setOrientation(LinearLayout.VERTICAL);
    help.setBackgroundColor(0xFFFFF4E5); help.setPadding(dp(12), dp(12), dp(12), dp(12));
    TextView ht = new TextView(this); ht.setTextSize(16); ht.setTextColor(0xFFB54708);
    ht.setText("⚠ \"앱의 액세스가 거부됨\"이 뜨면 (제한된 설정)"); help.addView(ht);
    TextView hb = new TextView(this); hb.setTextColor(0xFF333333);
    hb.setText("안드로이드/One UI는 직접 받은 APK의 접근성 권한을 기본으로 막아요. 앱이 스스로 풀 수는 없고, 아래처럼 직접 허용해야 해요.\n\n"
      + "[폰에서]\n1. 아래 '접근성 설정 열기' → X 키워드 가리개 눌러서 거부 창을 한 번 띄우고 닫기\n"
      + "2. '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용' (지문/PIN 확인)\n"
      + "3. 다시 '접근성 설정 열기' → X 키워드 가리개 → 켜기\n"
      + "※ ⋮ 메뉴가 안 보이면 1번을 먼저 해야 나타나요.\n\n"
      + "[PC에서 (USB/무선 디버깅)]\n" + ADB1 + "\n그 다음 접근성 설정에서 서비스 켜기.\n"
      + "또는 PC에서 설치하면 제한이 아예 안 걸리는 경우가 많아요:\n" + ADB2);
    hb.setTextIsSelectable(true); help.addView(hb);
    Button info = new Button(this); info.setText("① 앱 정보 열기 (⋮ → 제한된 설정 허용)"); help.addView(info);
    info.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    Button cp = new Button(this); cp.setText("ADB 명령 복사"); help.addView(cp);
    cp.setOnClickListener(v -> {
      ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("adb", ADB1));
      Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show();
    });
    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2); hlp.topMargin = dp(8); hlp.bottomMargin = dp(8);
    l.addView(help, 3, hlp);

    Button ac = new Button(this); ac.setText("② 접근성 설정 열기 / 서비스 켜기"); l.addView(ac);
    ac.setOnClickListener(v -> openA11y());

    kw.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    ScrollView sv = new ScrollView(this); sv.setFillViewport(true); sv.addView(l);
    setContentView(sv);
  }

  @Override protected void onResume() {
    super.onResume();
    boolean on = serviceOn();
    status.setText(on ? "● 접근성 서비스 켜짐 — X에서 바로 가려집니다" : "○ 접근성 서비스 꺼짐 — 아래 버튼으로 켜 주세요");
    status.setTextColor(on ? 0xFF00BA7C : 0xFF8B98A5);
    help.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
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

  void openA11y() {
    String comp = new ComponentName(this, HiderService.class).flattenToString();
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      try {
        Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        i.putExtra(Intent.EXTRA_COMPONENT_NAME, comp);
        startActivity(i); return;
      } catch (Exception ignored) {}
    }
    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    Bundle bb = new Bundle(); bb.putString(":settings:fragment_args_key", comp);
    i.putExtra(":settings:fragment_args_key", comp); i.putExtra(":settings:show_fragment_args", bb);
    startActivity(i);
  }

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
