package com.jingxin.pandrive.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.ScrollView;

import com.jingxin.pandrive.R;

/**
 * 帮助页 View（悬浮态下叠加在 GridBackgroundView 上）
 *
 * 逻辑与 HelpActivity 完全一致，仅将 Activity 依赖改为 Context。
 * 非悬浮态仍使用 HelpActivity。
 */
public class HelpView extends ScrollView {

    /** 关闭回调（移除自身 View） */
    public Runnable onClose;

    public HelpView(Context context) {
        super(context);
        init();
    }

    public HelpView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.activity_help, this, true);

        Button btnBack = findViewById(R.id.btn_help_back);
        btnBack.setOnClickListener(v -> close());
    }

    private void close() {
        if (onClose != null) {
            onClose.run();
        }
    }
}
