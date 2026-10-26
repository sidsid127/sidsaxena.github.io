package com.sidsaxena.daybook;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left to right, wrapping onto new lines (used for "Buy again" chips). */
class FlowLayout extends ViewGroup {

    FlowLayout(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxWidth) {
                x = 0;
                y += lineHeight;
                lineHeight = 0;
            }
            x += w;
            lineHeight = Math.max(lineHeight, h);
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + lineHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxWidth) {
                x = 0;
                y += lineHeight;
                lineHeight = 0;
            }
            int left = getPaddingLeft() + x + lp.leftMargin;
            int top = getPaddingTop() + y + lp.topMargin;
            child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
            x += w;
            lineHeight = Math.max(lineHeight, h);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
