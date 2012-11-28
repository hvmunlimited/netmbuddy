/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
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

import static free.yhc.netmbuddy.utils.Utils.eAssert;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.TextView;
import free.yhc.netmbuddy.db.DB;
import free.yhc.netmbuddy.db.DBHelper;
import free.yhc.netmbuddy.model.YTFeed;
import free.yhc.netmbuddy.model.YTPlayer;
import free.yhc.netmbuddy.model.YTSearchHelper;
import free.yhc.netmbuddy.model.YTVideoFeed;
import free.yhc.netmbuddy.utils.ImageUtils;
import free.yhc.netmbuddy.utils.UiUtils;
import free.yhc.netmbuddy.utils.Utils;

public abstract class YTVideoSearchActivity extends YTSearchActivity implements
YTSearchHelper.SearchDoneReceiver,
DBHelper.CheckDupDoneReceiver {
    private final DB        mDb = DB.get();
    private final YTPlayer  mMp = YTPlayer.get();

    private DBHelper        mDbHelper;
    private View.OnClickListener mToolBtnSearchAction;

    private YTVideoSearchAdapter.CheckStateListener mAdapterCheckListener
        = new YTVideoSearchAdapter.CheckStateListener() {
        @Override
        public void
        onStateChanged(int nrChecked, int pos, boolean checked) {
            if (0 == nrChecked) {
                setupToolBtn1(getToolButtonSearchIcon(), mToolBtnSearchAction);
            } else {
                setupToolBtn1(R.drawable.ic_add, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addCheckedMusicsTo();
                    }
                });
            }
        }
    };
    private final OnPlayerUpdateDBListener mOnPlayerUpdateDbListener
        = new OnPlayerUpdateDBListener();

    private class OnPlayerUpdateDBListener implements YTPlayer.OnDBUpdatedListener {
        @Override
        public void
        onDbUpdated(YTPlayer.DBUpdateType ty) {
            switch (ty) {
            case PLAYLIST:
                showLoadingLookAndFeel();
                checkDupAsync(null, (YTVideoFeed.Entry[])getAdapter().getEntries());
            }
            // others are ignored.
        }
    }

    private YTVideoSearchAdapter
    getAdapter() {
        return (YTVideoSearchAdapter)mListv.getAdapter();
    }

    private void
    showPlayer() {
        ViewGroup playerv = (ViewGroup)findViewById(R.id.player);
        playerv.setVisibility(View.VISIBLE);
    }

    /**
     * @return
     *   0 for success, otherwise error message id.
     */
    private int
    addToPlaylist(final long plid, final int pos, final int volume) {
        // NOTE
        // This function is designed to be able to run in background.
        // But, getting volume is related with YTPlayer instance.
        // And lots of functions of YTPlayer instance, requires running on UI Context
        //   to avoid synchronization issue.
        // So, volume should be gotten out of this function.
        eAssert(plid >= 0);

        Bitmap bm = getAdapter().getItemThumbnail(pos);
        if (null == bm) {
            return R.string.msg_no_thumbnail;
        }

        final YTVideoFeed.Entry entry = (YTVideoFeed.Entry)getAdapter().getItem(pos);
        int playtm = 0;
        try {
             playtm = Integer.parseInt(entry.media.playTime);
        } catch (NumberFormatException ex) {
            return R.string.msg_unknown_format;
        }

        DB.Err err = mDb.insertVideoToPlaylist(plid,
                                               entry.media.videoId,
                                               entry.media.title,
                                               entry.author.name,
                                               playtm,
                                               ImageUtils.compressBitmap(bm),
                                               volume);
        if (DB.Err.NO_ERR != err) {
            if (DB.Err.DUPLICATED == err)
                return R.string.msg_existing_muisc;
            else
                return Err.map(err).getMessage();
        }

        runOnUiThread(new Runnable() {
            @Override
            public void
            run() {
                getAdapter().setToDup(pos);
            }
        });

        return 0;
    }

    private void
    appendToPlayQ(YTPlayer.Video[] vids) {
        mMp.appendToPlayQ(vids);
        showPlayer();
    }

    private void
    appendCheckMusicsToPlayQ() {
        // # of adapter items are at most Policy.YTSEARCH_MAX_RESULTS
        // So, just do it at main UI thread!
        YTVideoSearchAdapter adpr = getAdapter();
        int[] checkedItems = adpr.getCheckItemSortedByTime();
        YTPlayer.Video[] vids = new YTPlayer.Video[checkedItems.length];
        int j = 0;
        for (int i : checkedItems) {
            vids[j++] = adpr.getYTPlayerVideo(i);
        }
        appendToPlayQ(vids);
        adpr.cleanChecked();
    }


    private void
    addCheckedMusicsToPlaylist(final long plid) {
        // Scan to check all thumbnails are loaded.
        // And prepare data for background execution.
        final YTVideoSearchAdapter adpr = getAdapter();
        final int[] checkedItems = adpr.getCheckedItem();
        final int[] itemVolumes = new int[checkedItems.length];
        for (int i = 0; i < checkedItems.length; i++) {
            int pos = checkedItems[i];
            if (null == adpr.getItemThumbnail(pos)) {
                UiUtils.showTextToast(this, R.string.msg_no_all_thumbnail);
                return;
            }
            itemVolumes[i] = adpr.getItemVolume(pos);
        }

        DiagAsyncTask.Worker worker = new DiagAsyncTask.Worker() {
            private int failedCnt = 0;

            @Override
            public void
            onPostExecute(DiagAsyncTask task, Err result) {
                adpr.cleanChecked();
                if (failedCnt > 0) {
                    CharSequence msg = getResources().getText(R.string.msg_fails_to_add);
                    UiUtils.showTextToast(YTVideoSearchActivity.this,
                                          msg + " : " + failedCnt);
                }
            }

            @Override
            public Err
            doBackgroundWork(DiagAsyncTask task) {
                mDb.beginTransaction();
                try {
                    for (int i = 0; i < checkedItems.length; i++) {
                        int pos = checkedItems[i];
                        int r = addToPlaylist(plid, pos, itemVolumes[i]);
                        if (0 != r && R.string.msg_existing_muisc != r)
                            failedCnt++;
                    }
                    mDb.setTransactionSuccessful();
                } finally {
                    mDb.endTransaction();
                }
                return Err.NO_ERR;
            }
        };

        new DiagAsyncTask(this,
                          worker,
                          DiagAsyncTask.Style.SPIN,
                          R.string.adding)
            .run();

    }

    private void
    addCheckedMusicsTo() {
        final int[] menuTextIds = new int[] { R.string.append_to_playq };

        final String[] userMenu = new String[menuTextIds.length];
        for (int i = 0; i < menuTextIds.length; i++)
            userMenu[i] = Utils.getResText(menuTextIds[i]);

        UiUtils.OnPlaylistSelectedListener action = new UiUtils.OnPlaylistSelectedListener() {
            @Override
            public void onPlaylist(final long plid, Object user) {
                addCheckedMusicsToPlaylist(plid);
            }

            @Override
            public void
            onUserMenu(int pos, Object user) {
                eAssert(0 <= pos && pos < menuTextIds.length);
                switch (menuTextIds[pos]) {
                case R.string.append_to_playq:
                    appendCheckMusicsToPlayQ();
                    break;

                default:
                    eAssert(false);
                }
            }
        };

        UiUtils.buildSelectPlaylistDialog(mDb,
                                          this,
                                          R.string.add_to,
                                          userMenu,
                                          action,
                                          DB.INVALID_PLAYLIST_ID,
                                          null)
               .show();
    }

    private void
    onContextMenuAddTo(final int position) {
        UiUtils.OnPlaylistSelectedListener action = new UiUtils.OnPlaylistSelectedListener() {
            @Override
            public void onPlaylist(long plid, Object user) {
                int pos = (Integer)user;
                int volume = getAdapter().getItemVolume(pos);
                int msg = addToPlaylist(plid, pos, volume);
                if (0 != msg)
                    UiUtils.showTextToast(YTVideoSearchActivity.this, msg);
            }

            @Override
            public void
            onUserMenu(int pos, Object user) {}

        };

        UiUtils.buildSelectPlaylistDialog(mDb,
                                          this,
                                          R.string.add_to,
                                          null,
                                          action,
                                          DB.INVALID_PLAYLIST_ID,
                                          position)
               .show();
    }

    private void
    onContextMenuAppendToPlayQ(final int position) {
        YTPlayer.Video vid = getAdapter().getYTPlayerVideo(position);
        appendToPlayQ(new YTPlayer.Video[] { vid });

    }

    private void
    onContextMenuPlayVideo(final int position) {
        UiUtils.playAsVideo(this, getAdapter().getItemVideoId(position));
    }

    private void
    onContextMenuVideosOfThisAuthor(final int position) {
        Intent i = new Intent(this, YTVideoSearchAuthorActivity.class);
        i.putExtra(MAP_KEY_SEARCH_TEXT, getAdapter().getItemAuthor(position));
        startActivity(i);
    }

    private void
    onContextMenuPlaylistsOfThisAuthor(final int position) {
        Intent i = new Intent(this, YTPlaylistSearchActivity.class);
        i.putExtra(MAP_KEY_SEARCH_TEXT, getAdapter().getItemAuthor(position));
        startActivity(i);
    }

    private void
    onListItemClick(View view, int position, long itemId) {
        YTPlayer.Video v = getAdapter().getYTPlayerVideo(position);
        showPlayer();
        mMp.startVideos(new YTPlayer.Video[] { v });
    }

    private void
    checkDupAsync(Object tag, YTVideoFeed.Entry[] entries) {
        mDbHelper.close();

        // Create new instance whenever it used to know owner of each callback.
        mDbHelper = new DBHelper();
        mDbHelper.setCheckDupDoneReceiver(this);
        mDbHelper.open();
        mDbHelper.checkDupAsync(new DBHelper.CheckDupArg(tag, entries));
    }

    private void
    checkDupDoneNewEntries(DBHelper.CheckDupArg arg, boolean[] results) {
        YTSearchHelper.SearchArg sarg = (YTSearchHelper.SearchArg)arg.tag;
        saveSearchArg(sarg.text, sarg.title);
        String titleText = getTitlePrefix() + " : " + sarg.title;
        ((TextView)findViewById(R.id.title)).setText(titleText);

        // helper's event receiver is changed to adapter in adapter's constructor.
        YTVideoSearchAdapter adapter = new YTVideoSearchAdapter(this,
                                                               mSearchHelper,
                                                               mAdapterCheckListener,
                                                               arg.ents);
        // First request is done!
        // Now we know total Results.
        // Let's build adapter and enable list.
        applyDupCheckResults(adapter, results);
        YTVideoSearchAdapter oldAdapter = getAdapter();
        mListv.setAdapter(adapter);
        // Cleanup before as soon as possible to secure memories.
        if (null != oldAdapter)
            oldAdapter.cleanup();
    }

    private void
    applyDupCheckResults(YTVideoSearchAdapter adapter, boolean[] results) {
        for (int i = 0; i < results.length; i++) {
            if (results[i])
                adapter.setToDup(i);
            else
                adapter.setToNew(i);
        }
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    @Override
    protected abstract YTSearchHelper.SearchType getSearchType();

    protected void
    onCreateInternal(String stext, String stitle) {
        mListv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void
            onItemClick(AdapterView<?> parent, View view, int position, long itemId) {
                onListItemClick(view, position, itemId);
            }
        });

        mDbHelper = new DBHelper();
        mToolBtnSearchAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doNewSearch();
            }
        };

        setupBottomBar(getToolButtonSearchIcon(), mToolBtnSearchAction,
                       0, null);

        if (null != stext)
            loadFirstPage(getSearchType(), stext, stitle);
        else
            doNewSearch();
    }

    /**
     * Override it to enabel tool button for search.
     * @return
     */
    protected int
    getToolButtonSearchIcon() {
        return 0;
    }

    /**
     * Override it.
     * @return
     */
    protected String
    getTitlePrefix() {
        return "";
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    @Override
    protected YTPlayer.OnDBUpdatedListener
    getOnPlayerUpdateDbListener() {
        return mOnPlayerUpdateDbListener;
    }

    @Override
    public void
    searchDone(YTSearchHelper helper, YTSearchHelper.SearchArg arg,
               YTFeed.Result result, YTSearchHelper.Err err) {
        if (!handleSearchResult(helper, arg, result, err))
            return; // There is an error in search
        checkDupAsync(arg, (YTVideoFeed.Entry[])result.entries);
    }

    @Override
    public void
    checkDupDone(DBHelper helper, DBHelper.CheckDupArg arg,
                 boolean[] results, DBHelper.Err err) {
        if (null == mDbHelper || helper != mDbHelper) {
            helper.close();
            return; // invalid callback.
        }

        stopLoadingLookAndFeel();

        if (DBHelper.Err.NO_ERR != err
            || results.length != arg.ents.length) {
            UiUtils.showTextToast(this, R.string.err_db_unknown);
            return;
        }

        if (null != getAdapter()
            && arg.ents == getAdapter().getEntries())
            // Entry is same with current adapter.
            // That means 'dup. checking is done for exsiting entries"
            applyDupCheckResults(getAdapter(), results);
        else
            checkDupDoneNewEntries(arg, results);

    }

    @Override
    public boolean
    onContextItemSelected(MenuItem mItem) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo)mItem.getMenuInfo();
        switch (mItem.getItemId()) {
        case R.id.add_to:
            onContextMenuAddTo(info.position);
            return true;

        case R.id.append_to_playq:
            onContextMenuAppendToPlayQ(info.position);
            return true;

        case R.id.play_video:
            onContextMenuPlayVideo(info.position);
            return true;

        case R.id.videos_of_this_author:
            onContextMenuVideosOfThisAuthor(info.position);
            return true;

        case R.id.playlists_of_this_author:
            onContextMenuPlaylistsOfThisAuthor(info.position);
            return true;
        }
        return false;
    }

    @Override
    public void
    onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.ytvideosearch_context, menu);
        // AdapterContextMenuInfo mInfo = (AdapterContextMenuInfo)menuInfo;
        boolean visible = (YTSearchHelper.SearchType.VID_AUTHOR == getSearchType())? false: true;
        menu.findItem(R.id.videos_of_this_author).setVisible(visible);
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void
    onResume() {
        super.onResume();

        if (mDb.isRegisteredToVideoTableWatcher(this)) {
            if (mDb.isVideoTableUpdated(this)
                && null != getAdapter()) {
                showLoadingLookAndFeel();
                checkDupAsync(null, (YTVideoFeed.Entry[])getAdapter().getEntries());
            }
            mDb.unregisterToVideoTableWatcher(this);
        }
    }

    @Override
    protected void
    onPause() {
        mDb.registerToVideoTableWatcher(this);
        super.onPause();
    }

    @Override
    protected void
    onDestroy() {
        mDbHelper.close();
        mDb.unregisterToVideoTableWatcher(this);
        super.onDestroy();
    }
}
