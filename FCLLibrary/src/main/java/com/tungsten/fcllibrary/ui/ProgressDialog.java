package com.tungsten.fcllibrary.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.tungsten.fcllibrary.component.dialog.FCLDialog;
import com.tungsten.fcllibrary.component.view.FCLProgressBar;
import com.tungsten.fcllibrary.component.view.FCLTextView;
import com.tungsten.fcllibrary.util.ConvertUtils;

public class ProgressDialog extends FCLDialog {
    public ProgressDialog(@NonNull Context context) {
        this(context, null);
    }

    public ProgressDialog(@NonNull Context context, String message) {
        super(context);
        setCancelable(false);
        setCanceledOnTouchOutside(false);
        if (message == null) {
            FCLProgressBar progressBar = new FCLProgressBar(context);
            setContentView(progressBar);
        } else {
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            int padding = ConvertUtils.dip2px(context, 20);
            layout.setPadding(padding, padding, padding, padding);
            FCLProgressBar progressBar = new FCLProgressBar(context);
            layout.addView(progressBar);
            FCLTextView textView = new FCLTextView(context);
            textView.setText(message);
            textView.setTextSize(16);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            layoutParams.setMarginStart(ConvertUtils.dip2px(context, 15));
            textView.setLayoutParams(layoutParams);
            layout.addView(textView);
            setContentView(layout);
        }
        show();
    }
}
