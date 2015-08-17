package org.slf4j.impl;

import android.os.Handler;
import android.widget.EditText;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * slf4j logger factory impl
 */
public class EditTextLoggerFactory implements ILoggerFactory {

    private static EditTextLoggerFactory instance;

    public static EditTextLoggerFactory get() {
        if (instance == null)
            instance = new EditTextLoggerFactory();
        return instance;
    }

    private boolean showSender = true;

    public boolean isShowSender() {
        return showSender;
    }

    public void setShowSender(boolean showSender) {
        this.showSender = showSender;
    }

    private boolean showTime = true;

    public boolean isShowTime() {
        return showTime;
    }

    public void setShowTime(boolean showTime) {
        this.showTime = showTime;
    }

    public void setEditText(EditText editText) {
        this.editText = editText;
    }

    private EditText editText;
    private Handler handler = new Handler();


    @Override
    public Logger getLogger(String s) {
        return new EditTextLogger("AllieBleServer", s, showSender, showTime, editText, handler);
    }
}
