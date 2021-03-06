/*****************************************************************************
 *    Copyright (C) 2012, 2013 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of YTMPlayer.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.netmbuddy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import free.yhc.netmbuddy.model.NotiManager;
import free.yhc.netmbuddy.model.UnexpectedExceptionHandler;
import free.yhc.netmbuddy.model.YTPlayer;
import free.yhc.netmbuddy.model.YTPlayer.StopState;
import free.yhc.netmbuddy.utils.Utils;

public class LockScreenActivity extends Activity implements
YTPlayer.VideosStateListener,
UnexpectedExceptionHandler.Evidence {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(LockScreenActivity.class);

    static final String INTENT_KEY_APP_FOREGROUND   = "app_foreground";

    private final YTPlayer  mMp = YTPlayer.get();

    private boolean         mForeground = false;

    public static class ScreenMonitor extends BroadcastReceiver {
        public static void
        init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            ScreenMonitor rcvr = new ScreenMonitor();
            Utils.getAppContext().registerReceiver(rcvr, filter);
        }

        @Override
        public void
        onReceive(Context context, Intent intent) {
            Intent i = new Intent(Utils.getAppContext(), LockScreenActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                       | Intent.FLAG_ACTIVITY_SINGLE_TOP
                       | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                i.putExtra(INTENT_KEY_APP_FOREGROUND, Utils.isAppForeground());
                if (YTPlayer.get().hasActiveVideo()) {
                    if (Utils.isPrefLockScreen())
                        context.startActivity(i);
                } else
                    NotiManager.get().removePlayerNotification();
            }
        }
    }


    private YTPlayer.ToolButton
    getToolButton() {
        View.OnClickListener onClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mMp.stopVideos();
            }
        };
        // Stop may need in lockscreen because sometimes user don't want to see this control-lockscreen again
        //   when screen turns on at next time.
        return new YTPlayer.ToolButton(R.drawable.ic_media_stop, onClick);
    }

    private void
    close() {
        if (!mForeground)
            moveTaskToBack(true);
        finish();
    }

    // ========================================================================
    //
    // Overriding 'YTPlayer.VideosStateListener'
    //
    // ========================================================================
    @Override
    public void
    onStarted() {
    }

    @Override
    public void
    onStopped(StopState state) {
        close();
    }

    @Override
    public void
    onChanged() {

    }

    // ========================================================================
    //
    // Overriding
    //
    // ========================================================================
    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lvl) {
        return this.getClass().getName();
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UnexpectedExceptionHandler.get().registerModule(this);
        mForeground = getIntent().getBooleanExtra(INTENT_KEY_APP_FOREGROUND, false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(R.layout.lockscreen);
        findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void
            onClick(View v) {
                close();
            }
        });

        mMp.addVideosStateListener(this, this);
    }

    @Override
    public void
    onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    protected void
    onResume() {
        super.onResume();
        ViewGroup playerv = (ViewGroup)findViewById(R.id.player);
        mMp.setController(this,
                          playerv,
                          (ViewGroup)findViewById(R.id.list_drawer),
                          null,
                          getToolButton());

    }

    @Override
    protected void
    onPause() {
        mMp.unsetController(this);
        super.onPause();
    }

    @Override
    protected void
    onStop() {
        super.onStop();
    }

    @Override
    protected void
    onDestroy() {
        mMp.removeVideosStateListener(this);
        UnexpectedExceptionHandler.get().unregisterModule(this);
        super.onDestroy();
    }

    @Override
    public void
    onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Do nothing!
    }

    @Override
    public void
    onBackPressed() {
        close();
        super.onBackPressed();
    }
}
