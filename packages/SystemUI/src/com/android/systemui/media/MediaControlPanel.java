/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.settingslib.Utils;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.util.animation.TransitionLayout;
import com.android.systemui.util.concurrency.DelayableExecutor;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    private static final String TAG = "MediaControlPanel";

    // Button IDs for QS controls
    static final int[] ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    private final SeekBarViewModel mSeekBarViewModel;
    private SeekBarObserver mSeekBarObserver;
    protected final Executor mBackgroundExecutor;
    private final ActivityStarter mActivityStarter;

    private Context mContext;
    private PlayerViewHolder mViewHolder;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mBackgroundColor;
    private int mAlbumArtSize;
    private int mAlbumArtRadius;

    /**
     * Initialize a new control panel
     * @param context
     * @param backgroundExecutor background executor, used for processing artwork
     * @param activityStarter activity starter
     */
    public MediaControlPanel(Context context, DelayableExecutor backgroundExecutor,
            ActivityStarter activityStarter, MediaHostStatesManager mediaHostStatesManager) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mActivityStarter = activityStarter;
        mSeekBarViewModel = new SeekBarViewModel(backgroundExecutor);
        mMediaViewController = new MediaViewController(context, mediaHostStatesManager);
        loadDimens();
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    private void loadDimens() {
        mAlbumArtRadius = mContext.getResources().getDimensionPixelSize(
                Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        mAlbumArtSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_media_album_size);
    }

    /**
     * Get the view holder used to display media controls
     * @return the view holder
     */
    @Nullable
    public PlayerViewHolder getView() {
        return mViewHolder;
    }

    /**
     * Get the view controller used to display media controls
     * @return the media view controller
     */
    @NonNull
    public MediaViewController getMediaViewController() {
        return mMediaViewController;
    }

    /**
     * Sets the listening state of the player.
     *
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    /**
     * Get the context
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /** Attaches the player to the view holder. */
    public void attach(PlayerViewHolder vh) {
        mViewHolder = vh;
        TransitionLayout player = vh.getPlayer();
        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        SeekBar bar = vh.getSeekBar();
        bar.setOnSeekBarChangeListener(mSeekBarViewModel.getSeekBarListener());
        bar.setOnTouchListener(mSeekBarViewModel.getSeekBarTouchListener());
        mMediaViewController.attach(player);
    }

    /**
     * Bind this view based on the data given
     */
    public void bind(@NotNull MediaData data) {
        if (mViewHolder == null) {
            return;
        }
        MediaSession.Token token = data.getToken();
        mBackgroundColor = data.getBackgroundColor();
        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();

        mViewHolder.getPlayer().setBackgroundTintList(
                ColorStateList.valueOf(mBackgroundColor));

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mViewHolder.getPlayer().setOnClickListener(v -> {
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent);
            });
        }

        ImageView albumView = mViewHolder.getAlbumView();
        // TODO: migrate this to a view with rounded corners instead of baking the rounding
        // into the bitmap
        boolean hasArtwork = data.getArtwork() != null;
        if (hasArtwork) {
            Drawable artwork = createRoundedBitmap(data.getArtwork());
            albumView.setImageDrawable(artwork);
        }
        setVisibleAndAlpha(collapsedSet, R.id.album_art, hasArtwork);
        setVisibleAndAlpha(expandedSet, R.id.album_art, hasArtwork);

        // App icon
        ImageView appIcon = mViewHolder.getAppIcon();
        if (data.getAppIcon() != null) {
            appIcon.setImageDrawable(data.getAppIcon());
        } else {
            Drawable iconDrawable = mContext.getDrawable(R.drawable.ic_music_note);
            appIcon.setImageDrawable(iconDrawable);
        }

        // Song name
        TextView titleText = mViewHolder.getTitleText();
        titleText.setText(data.getSong());

        // App title
        TextView appName = mViewHolder.getAppName();
        appName.setText(data.getApp());

        // Artist name
        TextView artistText = mViewHolder.getArtistText();
        artistText.setText(data.getArtist());

        // Transfer chip
        mViewHolder.getSeamless().setVisibility(View.VISIBLE);
        setVisibleAndAlpha(collapsedSet, R.id.media_seamless, true /*visible */);
        setVisibleAndAlpha(expandedSet, R.id.media_seamless, true /*visible */);
        mViewHolder.getSeamless().setOnClickListener(v -> {
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                    .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                            data.getPackageName())
                    .putExtra(MediaOutputSliceConstants.KEY_MEDIA_SESSION_TOKEN, mToken);
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        });

        ImageView iconView = mViewHolder.getSeamlessIcon();
        TextView deviceName = mViewHolder.getSeamlessText();

        // Update the outline color
        RippleDrawable bkgDrawable = (RippleDrawable) mViewHolder.getSeamless().getForeground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, deviceName.getCurrentTextColor());
        rect.setColor(Color.TRANSPARENT);

        final MediaDeviceData device = data.getDevice();
        if (device != null && !device.getEnabled()) {
            mViewHolder.getSeamless().setEnabled(false);
            // TODO(b/156875717): setEnabled should cause the alpha to change.
            mViewHolder.getSeamless().setAlpha(0.38f);
            iconView.setImageResource(R.drawable.ic_hardware_speaker);
            iconView.setVisibility(View.VISIBLE);
            deviceName.setText(R.string.media_seamless_remote_device);
        } else if (device != null) {
            mViewHolder.getSeamless().setEnabled(true);
            mViewHolder.getSeamless().setAlpha(1f);
            Drawable icon = device.getIcon();
            iconView.setVisibility(View.VISIBLE);

            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(device.getName());
        } else {
            // Reset to default
            Log.w(TAG, "device is null. Not binding output chip.");
            mViewHolder.getSeamless().setEnabled(true);
            mViewHolder.getSeamless().setAlpha(1f);
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }

        List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
        // Media controls
        int i = 0;
        List<MediaAction> actionIcons = data.getActions();
        for (; i < actionIcons.size() && i < ACTION_IDS.length; i++) {
            int actionId = ACTION_IDS[i];
            final ImageButton button = mViewHolder.getAction(actionId);
            MediaAction mediaAction = actionIcons.get(i);
            button.setImageDrawable(mediaAction.getDrawable());
            button.setContentDescription(mediaAction.getContentDescription());
            Runnable action = mediaAction.getAction();

            button.setOnClickListener(v -> {
                if (action != null) {
                    action.run();
                }
            });
            boolean visibleInCompat = actionsWhenCollapsed.contains(i);
            setVisibleAndAlpha(collapsedSet, actionId, visibleInCompat);
            setVisibleAndAlpha(expandedSet, actionId, true /*visible */);
        }

        // Hide any unused buttons
        for (; i < ACTION_IDS.length; i++) {
            setVisibleAndAlpha(expandedSet, ACTION_IDS[i], false /*visible */);
            setVisibleAndAlpha(collapsedSet, ACTION_IDS[i], false /*visible */);
        }

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        // Set up long press menu
        // TODO: b/156036025 bring back media guts

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        mMediaViewController.refreshState();
    }

    @UiThread
    private Drawable createRoundedBitmap(Icon icon) {
        if (icon == null) {
            return null;
        }
        // Let's scale down the View, such that the content always nicely fills the view.
        // ThumbnailUtils actually scales it down such that it may not be filled for odd aspect
        // ratios
        Drawable drawable = icon.loadDrawable(mContext);
        float aspectRatio = drawable.getIntrinsicHeight() / (float) drawable.getIntrinsicWidth();
        Rect bounds;
        if (aspectRatio > 1.0f) {
            bounds = new Rect(0, 0, mAlbumArtSize, (int) (mAlbumArtSize * aspectRatio));
        } else {
            bounds = new Rect(0, 0, (int) (mAlbumArtSize / aspectRatio), mAlbumArtSize);
        }
        if (bounds.width() > mAlbumArtSize || bounds.height() > mAlbumArtSize) {
            float offsetX = (bounds.width() - mAlbumArtSize) / 2.0f;
            float offsetY = (bounds.height() - mAlbumArtSize) / 2.0f;
            bounds.offset((int) -offsetX,(int) -offsetY);
        }
        drawable.setBounds(bounds);
        Bitmap scaled = Bitmap.createBitmap(mAlbumArtSize, mAlbumArtSize,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        drawable.draw(canvas);
        RoundedBitmapDrawable artwork = RoundedBitmapDrawableFactory.create(
                mContext.getResources(), scaled);
        artwork.setCornerRadius(mAlbumArtRadius);
        return artwork;
    }

    /**
     * Return the token for the current media session
     * @return the token
     */
    public MediaSession.Token getMediaSessionToken() {
        return mToken;
    }

    /**
     * Get the current media controller
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Get the name of the package associated with the current media controller
     * @return the package name, or null if no controller
     */
    public String getMediaPlayerPackage() {
        if (mController == null) {
            return null;
        }
        return mController.getPackageName();
    }

    /**
     * Check whether this player has an attached media session.
     * @return whether there is a controller with a current media session.
     */
    public boolean hasMediaSession() {
        return mController != null && mController.getPlaybackState() != null;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     * @param controller media controller to check
     * @return whether it is playing, or false if no controller information
     */
    protected boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        set.setVisibility(actionId, visible? ConstraintSet.VISIBLE : ConstraintSet.GONE);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
    }
}
