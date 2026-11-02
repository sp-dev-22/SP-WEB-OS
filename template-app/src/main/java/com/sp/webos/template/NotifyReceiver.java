package com.sp.webos.template;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a scheduled notification (set up by NotifyHelper.schedule). */
public class NotifyReceiver extends BroadcastReceiver {

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_BODY = "body";
    static final String EXTRA_ID = "id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        String body = intent.getStringExtra(EXTRA_BODY);
        int id = intent.getIntExtra(EXTRA_ID,
                (int) (System.currentTimeMillis() % Integer.MAX_VALUE));
        NotifyHelper.post(context, id, title, body);
    }
}
