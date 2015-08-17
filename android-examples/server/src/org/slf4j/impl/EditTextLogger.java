package org.slf4j.impl;

import android.os.Handler;
import android.util.Log;
import android.widget.EditText;
import org.slf4j.Logger;
import org.slf4j.Marker;

import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * slf4j logger impl
 */
public class EditTextLogger implements Logger {

    private String sender;
    private String logCatSender;
    private boolean showSender;
    private boolean showTime;
    private EditText editText;
    private Handler handler;
    private DateFormat formatter = new SimpleDateFormat("HH:mm:ss.SSS");

    public EditTextLogger(String logCatSender, String sender, boolean showSender, boolean showTime, EditText editText, Handler handler) {
        this.logCatSender = logCatSender;
        this.sender = sender;
        this.showSender = showSender;
        this.showTime = showTime;
        this.editText = editText;
        this.handler = handler;
    }

    private void log(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                StringBuilder entry = new StringBuilder("\n");
                if (showTime) {
                    entry.append(formatter.format(new Date()));
                }

                if (showSender) {
                    if (showTime)
                        entry.append(" - ");

                    entry.append("[");
                    entry.append(sender);
                    entry.append("]");
                }

                if (showTime || showSender)
                    entry.append(" : ");

                entry.append(message);

                // logcat
                Log.d(logCatSender, "[" + sender + "] : " + message);

                editText.getText().append(entry.toString());
                editText.scrollTo(0, Integer.MAX_VALUE); // scroll to the end
            }
        });
    }

    @Override
    public void debug(String s) {
        log(s);
    }

    @Override
    public String getName() {
        return sender;
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public void trace(String s) {
        log(s);
    }

    @Override
    public void trace(String s, Object o) {
        log(s);
    }

    @Override
    public void trace(String s, Object o, Object o1) {
        log(s);
    }

    @Override
    public void trace(String s, Object... objects) {
        log(s);
    }

    @Override
    public void trace(String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return true;
    }

    @Override
    public void trace(Marker marker, String s) {
        log(s);
    }

    @Override
    public void trace(Marker marker, String s, Object o) {
        log(s);
    }

    @Override
    public void trace(Marker marker, String s, Object o, Object o1) {
        log(s);
    }

    @Override
    public void trace(Marker marker, String s, Object... objects) {
        log(s);
    }

    @Override
    public void trace(Marker marker, String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public void debug(String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void debug(String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void debug(String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void debug(String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return true;
    }

    @Override
    public void debug(Marker marker, String s) {
        log(s);
    }

    @Override
    public void debug(Marker marker, String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void debug(Marker marker, String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void debug(Marker marker, String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void debug(Marker marker, String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public void info(String s) {
        log(s);
    }

    @Override
    public void info(String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void info(String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void info(String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void info(String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return true;
    }

    @Override
    public void info(Marker marker, String s) {
        log(s);
    }

    @Override
    public void info(Marker marker, String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void info(Marker marker, String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void info(Marker marker, String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void info(Marker marker, String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(String s) {
        log(s);
    }

    @Override
    public void warn(String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void warn(String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void warn(String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void warn(String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    @Override
    public void warn(Marker marker, String s) {
        log(s);
    }

    @Override
    public void warn(Marker marker, String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void warn(Marker marker, String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void warn(Marker marker, String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void warn(Marker marker, String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void error(String s) {
        log(s);
    }

    @Override
    public void error(String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void error(String s, Object o, Object o1) {
        log(MessageFormat.format(s, o, o1));
    }

    @Override
    public void error(String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void error(String s, Throwable throwable) {
        log(s);
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return true;
    }

    @Override
    public void error(Marker marker, String s) {
        log(s);
    }

    @Override
    public void error(Marker marker, String s, Object o) {
        log(MessageFormat.format(s, o));
    }

    @Override
    public void error(Marker marker, String s, Object o, Object o1) {
        log(MessageFormat.format(s, o1));
    }

    @Override
    public void error(Marker marker, String s, Object... objects) {
        log(MessageFormat.format(s, objects));
    }

    @Override
    public void error(Marker marker, String s, Throwable throwable) {
        log(s);
    }
}
