package com.example.parkingtracklab;

/**
 * Experimental planar vehicle model, NOT certified navigation.
 * Horizontal frame: x=east, y=north (meters); heading clockwise from true north.
 * GPS supplies position/velocity/course while available. Gyro supplies yaw changes;
 * forward acceleration changes speed. No wheel odometer is available.
 *
 * During a simulated blackout, GPS is still logged as ground truth but MUST NOT
 * be passed into setGpsFix() until the blackout ends.
 */
public final class DriveModel {
    public float x, y, baselineX, baselineY, speed, baselineSpeed, headingRad;
    public boolean initialized;
    public float lastForwardAccel, lastYawRate;
    private long lastAccelNs, lastGyroNs;

    private static final float MAX_SPEED_MPS = 22f;
    private static final float ACCEL_DEADBAND = .10f;
    private static final float MAX_ACCEL_MPS2 = 3.0f;
    private static final float MIN_TURN_RADIUS_M = 2.8f;

    public void reset() {
        x=y=baselineX=baselineY=speed=baselineSpeed=headingRad=0f;
        initialized=false;
        lastAccelNs=lastGyroNs=0L;
        lastForwardAccel=lastYawRate=0;
    }

    /** A trusted GNSS fix. Course is meaningful only when the vehicle moves. */
    public void setGpsFix(float east, float north, float speedMps, float bearingDeg, boolean courseUsable) {
        x=baselineX=east; y=baselineY=north;
        speed=baselineSpeed=clamp(speedMps,0,MAX_SPEED_MPS);
        if(courseUsable) {
            headingRad=(float)Math.toRadians(bearingDeg);
            initialized=true;
        }
        // Never infer course from noisy GNSS bearings while the car is stationary.
    }

    /**
     * Convert measured yaw around world UP to clockwise heading change.
     * Reject a sudden phone rotation that exceeds plausible vehicle curvature.
     * The speed/radius bound is intentionally loose at parking speeds.
     */
    public void addYawRate(long timestampNs, float worldUpRadPerSec) {
        if(!initialized){lastGyroNs=timestampNs;return;}
        if(lastGyroNs==0){lastGyroNs=timestampNs;return;}
        float dt=(timestampNs-lastGyroNs)*1e-9f;
        lastGyroNs=timestampNs;
        if(dt<=0 || dt>.25f)return;
        float maxYaw=clamp(speed/MIN_TURN_RADIUS_M+.08f,.08f,.85f);
        lastYawRate=clamp(-worldUpRadPerSec,-maxYaw,maxYaw);
        headingRad=normalize(headingRad+lastYawRate*dt);
    }

    /**
     * World-horizontal acceleration (m/s2) projected onto current forward heading.
     * A small deadband rejects some sensor bias; GPS-free speed will still drift.
     * Baseline keeps last trustworthy speed unchanged for fair comparison.
     */
    public void addWorldAcceleration(long timestampNs, float eastAccel, float northAccel) {
        if(!initialized){lastAccelNs=timestampNs;return;}
        if(lastAccelNs==0){lastAccelNs=timestampNs;return;}
        float dt=(timestampNs-lastAccelNs)*1e-9f;
        lastAccelNs=timestampNs;
        if(dt<=0 || dt>.25f)return;
        float a=(float)(eastAccel*Math.sin(headingRad)+northAccel*Math.cos(headingRad));
        lastForwardAccel=clamp(a,-MAX_ACCEL_MPS2,MAX_ACCEL_MPS2);
        a=Math.abs(lastForwardAccel)<=ACCEL_DEADBAND?0f:
                lastForwardAccel-Math.signum(lastForwardAccel)*ACCEL_DEADBAND;
        speed=clamp(speed+a*dt,0f,MAX_SPEED_MPS);
        float sin=(float)Math.sin(headingRad),cos=(float)Math.cos(headingRad);
        x+=speed*sin*dt; y+=speed*cos*dt;
        baselineX+=baselineSpeed*sin*dt; baselineY+=baselineSpeed*cos*dt;
    }

    public void setKnownStop(float east, float north) {
        // Only call when a credible GNSS fix confirms zero speed.
        // Cannot infer stationary merely from low accelerometer readings.
        x=baselineX=east; y=baselineY=north;
        speed=baselineSpeed=0f;
    }

    private static float clamp(float v,float low,float high){
        return Math.max(low,Math.min(high,v));
    }
    private static float normalize(float rad) {
        float pi2=(float)(Math.PI*2);
        rad%=pi2;
        if(rad<0)rad+=pi2;
        return rad;
    }
}
