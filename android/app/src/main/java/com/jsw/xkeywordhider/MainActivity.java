package com.jsw.xkeywordhider;

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

public class MainActivity extends Activity {
  TextView status;

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

    Button ac = new Button(this); ac.setText("접근성 서비스 켜기"); l.addView(ac);
    ac.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

    setContentView(l);
  }

  @Override protected void onResume() {
    super.onResume();
    boolean on = serviceOn();
    status.setText(on ? "● 접근성 서비스 켜짐 — X에서 바로 가려집니다" : "○ 접근성 서비스 꺼짐 — 아래 버튼으로 켜 주세요");
    status.setTextColor(on ? 0xFF00BA7C : 0xFF8B98A5);
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

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
