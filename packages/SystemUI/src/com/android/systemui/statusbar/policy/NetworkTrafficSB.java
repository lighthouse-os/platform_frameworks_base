package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;

public class NetworkTrafficSB extends NetworkTraffic implements DarkReceiver, StatusIconDisplayable {

    public static final String SLOT = "networktraffic";
    private int mVisibleState = -1;
    private boolean mSystemIconVisible = true;
    private boolean mStatusbarExpanded;
    private boolean mKeyguardShowing;

    /*
     *  @hide
     */
    public NetworkTrafficSB(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTrafficSB(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTrafficSB(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    @Override
    protected void setMode() {
        super.setMode();
        mIsEnabled = mIsEnabled && shouldShowOnSB(mContext);
    }

    @Override
    protected void setSpacingAndFonts() {
        super.setSpacingAndFonts();
        setTypeface(Typeface.create(txtFont, Typeface.BOLD));
        setLineSpacing(0.75f, 0.75f);
    }

    @Override
    protected RelativeSizeSpan getSpeedRelativeSizeSpan() {
        return new RelativeSizeSpan(0.75f);
    }

    @Override
    protected RelativeSizeSpan getUnitRelativeSizeSpan() {
        return new RelativeSizeSpan(0.7f);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        if (!mIsEnabled) return;
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        setTextColor(mTintColor);
        updateTrafficDrawable();
    }

    @Override
    public String getSlot() {
        return SLOT;
    }

    @Override
    public boolean isIconVisible() {
        return mIsEnabled;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean mIsEnabled) {
        if (state == mVisibleState || !mIsEnabled || !mAttached) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mSystemIconVisible = true;
                break;
            case STATE_DOT:
            case STATE_HIDDEN:
            default:
                mSystemIconVisible = false;
                break;
        }
    }

    @Override
    protected void updateVisibility() {
        boolean show = !mStatusbarExpanded && mSystemIconVisible && !mKeyguardShowing && mIsEnabled && !mTrafficInHeaderView;
        setVisibility(show ? View.VISIBLE
                : View.GONE);
        mVisible = show;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mTintColor = color;
        setTextColor(mTintColor);
        updateTrafficDrawable();
    }

    @Override
    public void setDecorColor(int color) {
    }

    public void onPanelExpanded(boolean isExpanded) {
        mStatusbarExpanded = isExpanded;
        if (isExpanded) {
          setVisibility(View.GONE);
          mVisible = false;
        } else {
            maybeRestoreVisibility();
        }
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        if (showing) {
          setVisibility(View.GONE);
          mVisible = false;
        } else {
            maybeRestoreVisibility();
        }
    }

    private void maybeRestoreVisibility() {
        if (!mVisible && mIsEnabled && !mStatusbarExpanded && !mKeyguardShowing && mSystemIconVisible
           && restoreViewQuickly()) {
          setVisibility(View.VISIBLE);
          mVisible = true;
          // then let the traffic handler do its checks
          update();
        }
    }

    private static boolean shouldShowOnSB(Context context) {
        if (context.getResources().getBoolean(R.bool.config_forceShowNetworkTrafficOnStatusBar))
            return true;

        int cutoutResId = context.getResources().getIdentifier("config_mainBuiltInDisplayCutout",
                "string", "android");
        if (cutoutResId > 0) {
            return context.getResources().getString(cutoutResId).equals("");
        }

        return true;
    }
}
