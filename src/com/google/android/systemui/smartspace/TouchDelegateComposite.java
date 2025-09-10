package com.google.android.systemui.smartspace;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;

public final class TouchDelegateComposite extends TouchDelegate {
    public ArrayList mDelegates;

    public TouchDelegateComposite(Rect rect, View delegateView) {
        super(rect, delegateView);
        mDelegates = new ArrayList();
    }

    @Override
    public final boolean onTouchEvent(MotionEvent motionEvent) {
        float x = motionEvent.getX();
        float y = motionEvent.getY();
        Iterator it = mDelegates.iterator();
        while (it.hasNext()) {
            TouchDelegate touchDelegate = (TouchDelegate) it.next();
            motionEvent.setLocation(x, y);
            if (touchDelegate.onTouchEvent(motionEvent)) {
                return true;
            }
        }
        return false;
    }
}
