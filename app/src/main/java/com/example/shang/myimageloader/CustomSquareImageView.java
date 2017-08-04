package com.example.shang.myimageloader;

import android.content.Context;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Created by shang on 2017/8/2.
 */

public class CustomSquareImageView extends android.support.v7.widget.AppCompatImageView {


    public CustomSquareImageView(Context context) {
        super(context);
    }

    public CustomSquareImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomSquareImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
