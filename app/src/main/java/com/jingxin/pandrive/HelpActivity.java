package com.jingxin.pandrive;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;

/**
 * 使用帮助页面：展示软件功能、操作手势、设置说明等。
 * 内容全部写在 activity_help.xml 中，本类只负责绑定返回按钮。
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        Button btnBack = findViewById(R.id.btn_help_back);
        btnBack.setOnClickListener(v -> finish());

        // 保持屏幕常亮，与设置页一致
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
