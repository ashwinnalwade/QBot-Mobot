package edhyah.com.qbot;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;

import java.util.List;

import hanqis.com.qbot.Sample_algorithm;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOActivity;

public class MobotActivity extends IOIOActivity implements CameraBridgeViewBase.CvCameraViewListener2,
        MobotLooper.MobotDriver {

    private static final String TAG = "MobotActivity";
    private static final int LINE_THICKNESS = 5;
    private static final int POINT_THICKNESS = 2;
    private static final int LEFT = 1;
    private static final int RIGHT = 0;
    private static final int DEFUALT_ANGLE_PICK = 0;
    private static final int ANGLE_BISECTION = 12;
    private SharedPreferences mSharedPref;
    private PortraitCameraView mOpenCvCameraView;
    private boolean mStatusConnected;

    private MobotLooper mobotLooper;
    private Sample_algorithm mAlgorithm = new Sample_algorithm();
    private ParameterFiltering mFilt;
    private TurnDetector mTurnDetector = new TurnDetector();
    private HillDetection mHillDetect;

    private double[] mAngles = new double[2];
    private double mAngleFinal = 0.0;
    private int mTurnRight = 0;
    private boolean mMadeDecision = false;
    private boolean mLineIgnore = false;
    private int mHill2Decision = RIGHT;

    private double mTunning = 0;
    private double mSpeed = 0;
    private double mMaxSpeed = MobotLooper.MAX_SPEED;
    private ParameterBar mTestBar;

    /* need to be in [0,3) */
    private double mThreshold = 0.5;
    private int mSamplingPoints = 2000;
    private int mStdThreshold = 25;
    private long mDelayHill2Long = 5000;
    private long mDelayHill2Short = 2000;
    private long mSplitTimeHill2 = 0;
    private double mStdFirstHill2 = 35;
    private double mStdSecondHill2 = 25;
    private double mStdLaterHill2 = 35;
    private int mDimension = 2;
    private int mCounter2 = 1;
    private int mSplitTimeCounter = 0;
    private int mSplitThreshold = 2;
    private boolean mHill2AdjustSpeed = false;
    private double mHill2SpeedNormal = 0.35;
    private double mHill2SpeedSlow = 0.30;
    private double mHill2SpeedFast = 0.45;
    //private int mPrevChoice = LEFT;

    private int[] TURNS_2_LEFT = new int[]{LEFT,RIGHT,LEFT,LEFT,RIGHT,RIGHT,LEFT,LEFT,RIGHT,RIGHT,LEFT,LEFT};
    private int[] TURNS_2_RIGHT = new int[]{RIGHT,LEFT,RIGHT,RIGHT,LEFT,LEFT,RIGHT,RIGHT,LEFT,LEFT,RIGHT,RIGHT};
    private int[] TURNS_2015_COURSE = new int[]{RIGHT, LEFT, RIGHT, RIGHT, LEFT, LEFT, RIGHT, RIGHT, LEFT, RIGHT};
    private int[] mFinalTurns;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    @Override
    public void onResume()
    {
        super.onResume();
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_6, this, mLoaderCallback);
    }

    //------------------------------------------------------------

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_mobot);

        // OpenCV
        mOpenCvCameraView = (PortraitCameraView) findViewById(R.id.video_surface);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        addSpeedBar();
        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        mThreshold = mSharedPref.getFloat(MainActivity.PREF_THRESHOLD, -1);
        Log.i(TAG, "Thresh " + mThreshold);

        mFilt = new ParameterFiltering(getP(), getI(), getD());
        mStdThreshold = (int) mSharedPref.getFloat(MainActivity.PREF_STD_THRESHOLD,50);
        mDimension = (int) mSharedPref.getFloat(MainActivity.PREF_DIMENSION, 2);
        int turningPatternChoice = (int) mSharedPref.getFloat(MainActivity.PREF_TUNNING,0);
        mFinalTurns = TURNS_2015_COURSE;//(turningPatternChoice == 1) ? TURNS_2_RIGHT : TURNS_2_LEFT;
        mTurnRight = (int) mSharedPref.getFloat(MainActivity.PREF_TURN_DIRECTION,0);
        mSplitThreshold = (int) mSharedPref.getFloat(MainActivity.PREF_STD_SPLITTH,2);

        // Hill Detection
        float hillThresh = mSharedPref.getFloat(MainActivity.PREF_HILL_THRESH,0);
        mHillDetect = new HillDetection(this, hillThresh);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    protected IOIOLooper createIOIOLooper() {
        Log.i(TAG, "Created Looper");
        mobotLooper = new MobotLooper(this,this);
        return mobotLooper;
    }

    public void onCameraViewStarted(int width, int height) {
    }

    public void onCameraViewStopped() {
    }

    //------------ Img Processing -------------------------------------------

    @Override public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat img = inputFrame.rgba();
        double[] angles = mAlgorithm.Sampling(img,mDimension,mThreshold,mSamplingPoints,mStdThreshold);
        boolean split = mAlgorithm.getSplit();
        int numHills = mHillDetect.getNumHillsPassed();
        boolean onHill = mHillDetect.isOnHill();

        // Update turn with prev angle
        TurnDetector.Turn turn = mTurnDetector.updateTurn(mAngleFinal);

        int lineChoice = pickAngle(split, numHills, turn, angles,mAlgorithm.getResStd());
        double finalAngle = angles[lineChoice];

        // On hill decision
        if (onHill) {
            finalAngle = mFilt.onHillFilter(finalAngle);
        } else if (numHills == 0) {
            // Slow drive angle in first part too
            finalAngle = mFilt.beginningFilter(finalAngle);
        }
        if (numHills == 2 && !mHill2AdjustSpeed){
            mSpeed = mHill2SpeedNormal;
            updateSpeed((int)mSpeed * 100,100);
            mHill2AdjustSpeed = true;
        }

        // Filtering
        finalAngle = mFilt.filter(finalAngle);

        // Update UI
        updateAngle(finalAngle, angles[1-lineChoice]);
        updateAlgParams(mAlgorithm.getResStd(), numHills, split, turn, onHill, mCounter2);

        addSelectedPoints(img, split, lineChoice);
        addFoundLines(img, finalAngle, angles, split, lineChoice);

        // Save
        mAngleFinal = finalAngle;
        mAngles = angles;

        return img;
    }

    private int pickAngle(boolean split, int numHills, TurnDetector.Turn turn,
                          double[] angles, double std) {

        //mSplitTimeCounter needs to be at least 2 for the two lines to become available
        if (split) {
            mSplitTimeCounter++;
        } else {
            //Reset mSplitTimeCounter when there is no two lines appear.
            mSplitTimeCounter = 0;
            //And be ready for the next turn.
        }
        boolean splitAtHill1 = split && numHills == 1 && mSplitTimeCounter >= mSplitThreshold;
        boolean splitAtHill2 = split && numHills >= 2 && mSplitTimeCounter >= mSplitThreshold;

        int lineChoice = DEFUALT_ANGLE_PICK;
        if (splitAtHill2){
            if (mCounter2 == 1){
                if (std > mStdFirstHill2){
                    mHill2Decision = RIGHT; mSplitTimeHill2 = System.currentTimeMillis();
                    mCounter2++;
                    mSpeed = mHill2SpeedSlow;
                    updateSpeed((int)(mSpeed*100),100);
                }
            } else if (mCounter2 == 2) {
                if (System.currentTimeMillis() - mSplitTimeHill2 < mDelayHill2Long) {
                   return mHill2Decision;
                } if ( std > mStdSecondHill2){
                    mHill2Decision = LEFT;
                    mSplitTimeHill2 = System.currentTimeMillis();
                    mCounter2++;
                    mSpeed = mHill2SpeedNormal;
                    updateSpeed((int)(mSpeed*100),100);
                }
            } else if (3 == mCounter2 && mCounter2 <= 6){
                if (mCounter2 == 3 &&
                        (System.currentTimeMillis() - mSplitTimeHill2) < mDelayHill2Short) {
                    return mHill2Decision;
                } else if (System.currentTimeMillis() - mSplitTimeHill2 < mDelayHill2Long) {
                    return mHill2Decision;
                }  if (angles[RIGHT] > 2 * TurnDetector.ANGLE_EPSILON &&
                       angles[LEFT] < -2 * TurnDetector.ANGLE_EPSILON &&
                       std > mStdLaterHill2){
                           mHill2Decision = 1 - mHill2Decision;
                           mSplitTimeHill2 = System.currentTimeMillis();
                           mCounter2++;
                    }
                if (mCounter2 == 6) {
                    mSpeed = mHill2SpeedSlow;
                    updateSpeed((int)mSpeed * 100,100);
                }
            } else if (mCounter2 == 7){
                if (System.currentTimeMillis() - mSplitTimeHill2 < mDelayHill2Short) {
                    return mHill2Decision;
                }
                mSpeed = mHill2SpeedFast;
                updateSpeed((int) mSpeed * 100,100);
                mHill2Decision = RIGHT;
            }
            lineChoice = mHill2Decision;
        } else if (splitAtHill1) {
            // Splits after hill 1 for error correction
            if (turn == TurnDetector.Turn.LEFT)
                lineChoice = LEFT;
            else if (turn == TurnDetector.Turn.RIGHT) {
                lineChoice = RIGHT;
            }
        }

        return lineChoice;
    }

    private void addFoundLines(Mat img, double angleFinal, double[] anglesFound, boolean split,
                               int lineChoice) {
        int height = img.height();
        int width = img.width();
        Point centerPt = new Point(width / 2, height);
        Point p0 = getEndPoint(angleFinal,width, height, height);
        Point p1 = getEndPoint(anglesFound[0],width, height, height/3);
        Point p2 = getEndPoint(anglesFound[1],width, height, height/3);
        int red = Color.RED;
        int blue = Color.CYAN;
        int green = Color.GREEN;

        // Display green is choice line
        if (lineChoice == RIGHT) {
            Core.line(img, centerPt, p1, new Scalar(Color.red(green), Color.blue(green), Color.green(green)), LINE_THICKNESS);
            Core.line(img, centerPt, p2, new Scalar(Color.red(blue), Color.blue(blue), Color.green(blue)), LINE_THICKNESS);
        } else {
            Core.line(img, centerPt, p2, new Scalar(Color.red(green), Color.blue(green), Color.green(green)), LINE_THICKNESS);
            Core.line(img, centerPt, p1, new Scalar(Color.red(blue), Color.blue(blue), Color.green(blue)), LINE_THICKNESS);
        }

        // Display filtered angle line
        Core.line(img, centerPt, p0, new Scalar(Color.red(red), Color.blue(red), Color.green(red)), LINE_THICKNESS);
    }

    private Point getEndPoint(double angle, int w, int h, int len) {
        return new Point(w / 2 + len * Math.sin(Math.toRadians(angle)),
                h - len*Math.cos(Math.toRadians(angle)));
    }

    private void addSelectedPoints(Mat img, boolean split, int lineChoice){
        int green = Color.GREEN;
        int yellow = Color.YELLOW;
        Scalar sGreen = new Scalar(Color.red(green),Color.green(green),Color.blue(green));
        Scalar sYellow = new Scalar(Color.red(yellow),Color.green(yellow),Color.blue(yellow));

        if (!split) {
          List<Point> pts = mAlgorithm.getSelectedPoints().get(0);
          for (int i = 0;i < pts.size();i++){
              Core.circle(img,pts.get(i),2,sYellow,POINT_THICKNESS);
          }
        } else {
          List<Point> ptsA = mAlgorithm.getSelectedPoints().get(0);
          List<Point> ptsB = mAlgorithm.getSelectedPoints().get(1);

          if (lineChoice == RIGHT) {
              for (int i = 0; i < ptsA.size(); i++) {
                  Core.circle(img, ptsA.get(i), 2, sYellow, POINT_THICKNESS);
              }
              for (int i = 0; i < ptsB.size(); i++) {
                  Core.circle(img, ptsB.get(i), 2, sGreen, POINT_THICKNESS);
              }
          } else {
              for (int i = 0; i < ptsA.size(); i++) {
                  Core.circle(img, ptsA.get(i), 2, sGreen, POINT_THICKNESS);
              }
              for (int i = 0; i < ptsB.size(); i++) {
                  Core.circle(img, ptsB.get(i), 2, sYellow, POINT_THICKNESS);
              }
          }

        }
    }

    //------------ Speed Bar -----------------------------------------------

    private void addSpeedBar() {
        SeekBar speedBar = (SeekBar) findViewById(R.id.speed_bar);
        mSpeed = updateSpeed(speedBar.getProgress(), speedBar.getMax());
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mSpeed = updateSpeed(seekBar.getProgress(), seekBar.getMax());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSpeed = updateSpeed(seekBar.getProgress(), seekBar.getMax());
            }
        });
    }

    private double updateSpeed(int val, int maxVal) {
        final double speed = 1.0 * val / maxVal;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView msg = (TextView) findViewById(R.id.speed_val);
                msg.setText(String.format("%.2f", speed));
            }
        });
        return speed;
    }

    //------------ Driving Mobot -------------------------------------------

    @Override
    public double getDriveAngle() {
        return mAngleFinal;
    }

    @Override
    public double getDriveSpeed() {
        return mSpeed;
    }

    @Override
    public double getTunning() { return mSharedPref.getFloat(MainActivity.PREF_TUNNING, 0); }

    @Override
    public double getMaxSpeed() { return mSharedPref.getFloat(MainActivity.PREF_MAX_SPEED, 0); }

    public double getP() { return mSharedPref.getFloat(MainActivity.PREF_PID_P, 0); }
    public double getI() { return mSharedPref.getFloat(MainActivity.PREF_PID_I, 0); }
    public double getD() { return mSharedPref.getFloat(MainActivity.PREF_PID_D, 0); }

    @Override
    public void setStatusOnline(boolean status) {
        mStatusConnected = status;
        if (status) {
            updateMsg(getString(R.string.connected_msg));
        } else {
            updateMsg(getString(R.string.not_connected_msg));
        }
    }

    //------------ Updating View -------------------------------------------

    private void updateAngle(final Double a,final Double a2){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView angle = (TextView) findViewById(R.id.angle_test);
                angle.setText(String.format("Angle: %.2f,Igo :%.2f, Cn:%d",a,a2,mCounter2));
            }
        });
    }

    private void updateAlgParams(final double st, final int hills, final boolean split,
                                 final TurnDetector.Turn turn, final boolean onHill, final int splitCnt2){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView angle = (TextView) findViewById(R.id.std_test);
                angle.setText(String.format("Std:%.0f Hills:%d(%s) Split:%d(%s) Turn:%s",st,hills,
                        bool2Str(onHill),splitCnt2,bool2Str(split),turn));
            }
        });
    }

    private String bool2Str(boolean b) {
        return b ? "T" : "F";
    }

    private void updateMsg(final String m) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView msg = (TextView) findViewById(R.id.mobot_messages);
                msg.setText(m);
            }
        });
    }

}