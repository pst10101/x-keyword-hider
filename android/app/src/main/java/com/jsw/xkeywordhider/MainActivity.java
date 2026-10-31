package com.jsw.xkeywordhider;
import android.app.Activity; import android.content.*; import android.net.Uri; import android.os.Bundle; import android.provider.Settings; import android.view.*; import android.widget.*;
public class MainActivity extends Activity {
  @Override protected void onCreate(Bundle b){ super.onCreate(b);
    SharedPreferences sp=getSharedPreferences("hider",MODE_PRIVATE);
    LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40,60,40,40);
    TextView t=new TextView(this); t.setText("X 키워드 가리개"); t.setTextSize(22); l.addView(t);
    CheckBox en=new CheckBox(this); en.setText("켜기"); en.setChecked(sp.getBoolean("enabled",true)); l.addView(en);
    CheckBox gap=new CheckBox(this); gap.setText("\"분 탕\", \"분.탕\"처럼 끼워넣어도 잡기"); gap.setChecked(sp.getBoolean("ignoreGaps",true)); l.addView(gap);
    TextView h=new TextView(this); h.setText("키워드 (한 줄에 하나, /정규식/i 가능, // 는 주석)"); l.addView(h);
    EditText kw=new EditText(this); kw.setMinLines(8); kw.setGravity(Gravity.TOP); kw.setText(sp.getString("keywords","")); l.addView(kw,new LinearLayout.LayoutParams(-1,0,1));
    Button save=new Button(this); save.setText("저장"); l.addView(save);
    save.setOnClickListener(v->{ sp.edit().putString("keywords",kw.getText().toString()).putBoolean("enabled",en.isChecked()).putBoolean("ignoreGaps",gap.isChecked()).apply(); Toast.makeText(this,"저장됨",Toast.LENGTH_SHORT).show(); });
    Button ov=new Button(this); ov.setText("1. 다른 앱 위에 표시 권한"); l.addView(ov);
    ov.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
    Button ac=new Button(this); ac.setText("2. 접근성 서비스 켜기"); l.addView(ac);
    ac.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    setContentView(l);
  }
}
