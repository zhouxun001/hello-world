package com.example.parkingtracklab;

/** No Android runtime needed: javac/java smoke tests for DriveModel kinematics. */
public final class DriveModelSmokeTest {
    private static void near(float actual,float expected,float delta,String label){
        if(Float.isNaN(actual)||Math.abs(actual-expected)>delta)
            throw new AssertionError(label+": got "+actual+" expected "+expected+" +/-"+delta);
    }
    public static void main(String[] args){
        DriveModel m=new DriveModel();
        m.setGpsFix(0,0,0,0,false);
        if(m.initialized)throw new AssertionError("Stationary GPS bearing must not initialize course");
        m.setGpsFix(0,0,5,0,true);
        for(int i=0;i<=50;i++){
            long t=1_000_000_000L+i*20_000_000L;
            m.addWorldAcceleration(t,0,0);
        }
        near(m.y,5,.2f,"1s straight distance");
        near(m.baselineY,5,.2f,"baseline 1s");
        near(m.x,0,.1f,"straight lateral drift");
        long g0=2_000_000_000L;
        m.addYawRate(g0,0);
        for(int i=1;i<=50;i++){
            m.addYawRate(g0+i*20_000_000L,-.25f);
            m.addWorldAcceleration(g0+i*20_000_000L,0,0);
        }
        near((float)Math.toDegrees(m.headingRad),14.32f,1.0f,"gyro relative yaw");
        if(m.x<=0.2f)throw new AssertionError("Heading integration should turn east");
        m.setGpsFix(12,24,3,90,true);
        near(m.x,12,.001f,"GNSS correction x");
        near(m.y,24,.001f,"GNSS correction y");
        near(m.baselineSpeed,3,.001f,"CV speed");
        m.reset();
        if(m.initialized)throw new AssertionError("Reset must discard calibration");
        System.out.println("DriveModel smoke checks passed");
    }
}
