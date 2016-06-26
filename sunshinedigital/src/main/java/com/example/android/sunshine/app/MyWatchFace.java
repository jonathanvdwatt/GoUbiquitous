/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final String TAG = MyWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        /* Member variables */
        private Time mTime;
        private Paint mBackgroundPaint;
        private Paint mTextPaint;
        private boolean mAmbient;

        private float mXOffset;
        private float mYOffset;
        private float mDateYOffset;
        private float mTimeYOffset;
        private float mTempsYOffset;
        private float mBitmapYOffset;
        private int mCenterX;
        private int mScreenHeight;
        private int mScreenWidth;
        private int mLeftBound;
        private int mRightBound;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mRegisteredTimeZoneReceiver = false;

        /* Sunshine's goodies */
        private long MSG_UPDATE_TIMEOUT_MS = 1000;
        private GoogleApiClient mGoogleApiClient;
        private Asset mWeatherAssetID;
        private Bitmap mWeatherBitmap;
        private Bitmap mWeatherBitmapScaled;
        private String mTempHigh;
        private String mTempLow;
        private String mPlaceholderText;
        private Paint mTempHighPaint;
        private Paint mTempLowPaint;
        private Paint mDatePaint;
        private Paint mPlaceholderTextPaint;

        private Calendar mCalendar;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            /*
             * Build the object that is to receive the data from the syncadapter
             */
            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();
            mCalendar = Calendar.getInstance();

            mDatePaint = createTextPaint(Color.WHITE);
            int highTempColor = resources.getColor(R.color.temp_high_text);
            int lowTempColor = resources.getColor(R.color.temp_low_text);
            int retrievingDataColor = resources.getColor(R.color.retrieving_data_text);
            mTempHighPaint = createTextPaint(highTempColor);
            mTempLowPaint = createTextPaint(lowTempColor);
            mPlaceholderTextPaint = createTextPaint(retrievingDataColor);


            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mTempsYOffset = resources.getDimension(R.dimen.digital_temperatures_y_offset);
            mBitmapYOffset = resources.getDimension(R.dimen.digital_icon_y_offset);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);

            mTempHighPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mTempLowPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCenterX = bounds.centerX();
            mScreenHeight = bounds.height();
            mScreenWidth = bounds.width();
            mLeftBound = bounds.left;
            mRightBound = bounds.right;

            // Draw the background.
            if(isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);

                if(mTempHigh != null && mTempLow != null && mWeatherBitmap != null) {
                    Paint ambientPaint = createTextPaint(Color.WHITE);

                    canvas.drawText(mTempHigh,
                            (mScreenWidth / 4) - (mTempHighPaint.measureText(mTempHigh) / 2),
                            (mScreenHeight / 2) + mTempsYOffset, ambientPaint);

                    canvas.drawText(mTempLow,
                            ((mScreenWidth / 4) * 3) - (mTempLowPaint.measureText(mTempLow) / 2),
                            (mScreenHeight / 2) + mTempsYOffset,
                            ambientPaint);
                }
            }
            else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

                if(mTempHigh != null && mTempLow != null && mWeatherBitmap != null) {
                    /*
                     * Draw the high temperature
                     */
                    mXOffset = (mScreenWidth/4) - (mTempHighPaint.measureText(mTempHigh)/2);
                    mYOffset = (mScreenHeight/2) + (mTempsYOffset-1);
                    canvas.drawText(mTempHigh, mXOffset, mYOffset, mTempHighPaint);

                    /*
                     * Draw the low temperature
                     */
                    mXOffset = ((mScreenWidth/4)*3) - (mTempLowPaint.measureText(mTempLow)/2);
                    mYOffset = (mScreenHeight/2) + (mTempsYOffset-1);
                    canvas.drawText(mTempLow, mXOffset, mYOffset, mTempLowPaint);

                    /*
                     * Draw the bitmap
                     */
                    mWeatherBitmapScaled = Bitmap.createScaledBitmap(mWeatherBitmap,
                            (mWeatherBitmap.getWidth()/2),
                            (mWeatherBitmap.getHeight()/2),
                            true
                    );

                    mXOffset = (mScreenWidth/2) - (mWeatherBitmapScaled.getWidth()/2);
                    mYOffset = (mScreenHeight/2) + (mBitmapYOffset - 1);
                    canvas.drawBitmap(mWeatherBitmapScaled, mXOffset, mYOffset, null);
                }
                else {
                    /*
                     * Draw placeholder text
                     */
                    Log.d(TAG, "########## No data yet, drawing placeholder text");

                    mPlaceholderText = getString(R.string.retrieving_weather_data_text);
                    mXOffset = (mScreenWidth/2) - (mPlaceholderTextPaint.measureText(mPlaceholderText)/2);
                    mYOffset = (mScreenHeight/2) + (mTempsYOffset-1);
                    canvas.drawText(mPlaceholderText, mXOffset, mYOffset, mPlaceholderTextPaint);
                }
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            /*
             * Draw the date
             */
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            String dateFormat = new SimpleDateFormat("E, dd MMM yyyy").format(mCalendar.getTime());
            float dOffset = mDatePaint.measureText(dateFormat)/2;
            mXOffset = (mScreenWidth/2) - dOffset;
            mYOffset = (mScreenHeight/2) - mDateYOffset;
            canvas.drawText(dateFormat, mXOffset, mYOffset, mDatePaint);

            /*
             * Draw the time
             */
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            float tOffset = mTextPaint.measureText(text)/2;
            mXOffset = mCenterX - tOffset;
            mYOffset = (mScreenHeight/2) - mTimeYOffset;
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            /*
             * http://developer.android.com/training/wearables/data-layer/data-items.html
             */
            for (DataEvent event:dataEventBuffer) {
                if(event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = event.getDataItem();

                    if (dataItem.getUri().getPath().compareTo("/weather-details") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                        mTempHigh = dataMap.getString("high_temp");
                        mTempLow = dataMap.getString("low_temp");
                        mWeatherAssetID = dataMap.getAsset("weather_id");
                        RetrieveAssetTask retrieveAssetTask = new RetrieveAssetTask();
                        retrieveAssetTask.execute(mWeatherAssetID);
                    }
                }
            }
        }

        private class RetrieveAssetTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {
                if(params != null) {
                    ConnectionResult connectionResult = mGoogleApiClient.blockingConnect(MSG_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    if(!connectionResult.isSuccess()) {
                        return null;
                    }

                    InputStream inputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, params[0]).await().getInputStream();

                    if(inputStream == null) {
                        return null;
                    }

                    return BitmapFactory.decodeStream(inputStream);
                }
                throw new IllegalArgumentException("Params cannot be null");
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                mWeatherBitmap = bitmap;
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
        }


    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
