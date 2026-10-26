package com.simplehometv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.widget.FrameLayout;

/** An input full screen, for TVs where no app takes the passthrough link (Xiaomi's China firmware). */
public class InputActivity extends Activity {

    private static final String EXTRA_INPUT = "input";

    private TvView tv;
    private String inputId;

    static Intent intent(Context c, String inputId) {
        return new Intent(c, InputActivity.class).putExtra(EXTRA_INPUT, inputId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inputId = getIntent().getStringExtra(EXTRA_INPUT);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        tv = new TvView(this);
        tv.setZOrderOnTop(true);        // under the window, these TVs never show the picture
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (inputId == null) {
            finish();
            return;
        }
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    @Override protected void onStop() {
        super.onStop();
        tv.reset();
        finish();       // the home opens the input again from its tile
    }
}
