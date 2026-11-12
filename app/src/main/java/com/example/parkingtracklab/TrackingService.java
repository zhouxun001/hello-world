package com.example.parkingtracklab;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.hardware.GeomagneticField;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class TrackingService extends Service implements SensorEventListener, LocationListener {
    public static final String START="com.example.parkingtracklab.START";
    public static final String STOP="com.example.parkingtracklab.STOP";
    public static final String MARK="com.example.parkingtracklab.MARK";
    public static final String STRIDE="com.example.parkingtracklab.STRIDE";
    public static final String RESET="com.example.parkingtracklab.RESET";
    private final Object lock=new Object();
    private SensorManager sensors;
    private LocationManager locations;
    private HandlerThread thread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private BufferedWriter writer;
    private long lastFlush=0, tStartNs, tStartWallMs, prevAccelNs=0, lastPointNs=0, lastStepNs=0, stationarySinceNs=0, lastGoodGpsNs=0;
    private long wrote=0;
    private long lastFixNs=0;
    private float lastFixE=0,lastFixN=0,declination=0;
    private boolean running=false, hasRotation=false, anchored=false, stepSensor=false, hasStepPermission=false;
    private String mode="walk", source="未开始", gpsText="无可用定位", sensorText="检测中", fileName="";
    private float stride=.7f, velE=0, velN=0, rawVE=0, rawVN=0, x=0,y=0, rawX=0,rawY=0, heading=0, speed=0;
    private float accelMagnitude=0;
    private int steps=0;
    private double anchorLat, anchorLon;
    private final float[] matrix=new float[9];
    private final ArrayList<TrackStore.Pt> track=new ArrayList<>(), rawTrack=new ArrayList<>(), gpsTrack=new ArrayList<>(), markers=new ArrayList<>();
    private static final float MAX_CAR_SPEED=22f;
    private final DriveModel driveModel=new DriveModel();
    private final ArrayList<TrackStore.Pt> baselineTrack=new ArrayList<>();
    private boolean simulateBlackout=false,blackoutActive=false,blackoutFinished=false;
    private long sustainedDrivingNs=0,blackoutStartNs=0;
    private float errorNow=Float.NaN,baseErrorNow=Float.NaN;
    private float err30=Float.NaN,err60=Float.NaN,err120=Float.NaN;
    private float baseErr30=Float.NaN,baseErr60=Float.NaN,baseErr120=Float.NaN;

    @Override public void onCreate() {
        super.onCreate();
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        thread=new HandlerThread("track-sensors");thread.start();worker=new Handler(thread.getLooper());
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("tracking","轨迹记录",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(START.equals(action)) startSession("drive".equals(intent.getStringExtra("mode"))?"drive":"walk",intent.getBooleanExtra("simulateBlackout",false));
        else if(STOP.equals(action)) stopSession();
        else if(MARK.equals(action)) mark(intent.getStringExtra("label"));
        else if(STRIDE.equals(action)) { synchronized(lock) { stride=Math.min(.95f,Math.max(.35f,intent.getFloatExtra("stride",.7f))); log("SETTING","stride="+stride,0,0,0,0); publish(); } }
        else if(RESET.equals(action)) resetOrigin();
        return START_NOT_STICKY;
    }
    private Notification notification() {
        return new Notification.Builder(this,"tracking")
                .setContentTitle("停车轨迹实验室正在记录")
                .setContentText("正在记录传感器数据 · 返回应用可停止")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE))
                .build();
    }
    private void startSession(String chosen, boolean simulate) {
        synchronized(lock) {
            if(running) return;
            try {
                int type=Build.VERSION.SDK_INT>=29?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0;
                if(Build.VERSION.SDK_INT>=29)startForeground(101,notification(),type);
                else startForeground(101,notification());
            } catch(Exception ex) {
                gpsText="无法启动前台定位："+ex.getClass().getSimpleName();publish();stopSelf();return;
            }
            mode=chosen;
            simulateBlackout=simulate && "drive".equals(chosen);
            blackoutActive=blackoutFinished=false;
            sustainedDrivingNs=blackoutStartNs=0;
            errorNow=baseErrorNow=err30=err60=err120=baseErr30=baseErr60=baseErr120=Float.NaN;
            driveModel.reset(); baselineTrack.clear();
            tStartNs=SystemClock.elapsedRealtimeNanos(); tStartWallMs=System.currentTimeMillis();
            prevAccelNs=lastPointNs=lastStepNs=stationarySinceNs=lastGoodGpsNs=0;
            steps=0; anchored=false; hasRotation=false;lastFixNs=0;declination=0;
            velE=velN=rawVE=rawVN=x=y=rawX=rawY=heading=speed=accelMagnitude=0;
            source="等待传感器";gpsText="等待 GPS（室内可从本地原点开始）";sensorText="等待设备数据";
            track.clear();rawTrack.clear();gpsTrack.clear();markers.clear();
            track.add(new TrackStore.Pt(0,0));rawTrack.add(new TrackStore.Pt(0,0));baselineTrack.add(new TrackStore.Pt(0,0));
            String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            fileName="track_"+stamp+"_"+mode+".csv";
            try {
                File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"sessions");
                if(!dir.exists() && !dir.mkdirs())throw new IOException("无法创建目录");
                writer=new BufferedWriter(new FileWriter(new File(dir,fileName)),65536);
                writer.write("elapsed_s,elapsed_realtime_ns,wall_time_ms,event,x,y,z,w,latitude,longitude,gps_accuracy_m,east_m,north_m,raw_east_m,raw_north_m,speed_mps,heading_deg,note\n");
                getSharedPreferences("track",MODE_PRIVATE).edit().putString("lastFile",fileName).apply();
            } catch(IOException e){
                writer=null;gpsText="写入失败："+e.getMessage();
                stopForeground(STOP_FOREGROUND_REMOVE);publish();stopSelf();return;
            }
            running=true;
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ParkingTrackLab:recording");
            wakeLock.acquire(2*60*60*1000L); // Safety time limit: at most two hours.
            log("START",mode+(simulateBlackout?"_auto_gps_blackout_after_15s_moving":""),0,0,0,0);
            setupSensors();
            setupLocation();
            publish();
        }
    }
    private void setupSensors() {
        register(Sensor.TYPE_ROTATION_VECTOR,20_000);
        if(sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)==null) register(Sensor.TYPE_GAME_ROTATION_VECTOR,20_000);
        register(Sensor.TYPE_LINEAR_ACCELERATION,20_000);
        register(Sensor.TYPE_GYROSCOPE,20_000);
        register(Sensor.TYPE_ACCELEROMETER,40_000);
        register(Sensor.TYPE_PRESSURE,200_000);
        stepSensor=sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)!=null;
        hasStepPermission=Build.VERSION.SDK_INT<29||checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED;
        if(stepSensor && hasStepPermission) register(Sensor.TYPE_STEP_DETECTOR,0);
        else stepSensor=false;
        sensorText="姿态"+(sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)!=null?"√":"×")+
                " / 线性加速"+(sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)!=null?"√":"×")+
                " / 陀螺仪"+(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null?"√":"×")+
                (stepSensor?" / 硬件步数":" / 加速度步态兜底");
        log("SENSOR_SUPPORT",sensorText,0,0,0,0);
    }
    private void register(int sensorType,int us) {
        Sensor s=sensors.getDefaultSensor(sensorType);
        if(s!=null) sensors.registerListener(this,s,us,worker);
    }
    private void setupLocation() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            gpsText="缺少精确位置授权，仅记录相对轨迹";return;
        }
        try {
            if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,thread.getLooper());
                Location last=locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if(last!=null && SystemClock.elapsedRealtimeNanos()-last.getElapsedRealtimeNanos()<8_000_000_000L) onLocationChanged(last);
            } else gpsText="GPS 未开启，使用本地原点";
            if(locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000,0,this,thread.getLooper());
        } catch(SecurityException ex) { gpsText="GPS 权限不可用"; }
        catch(IllegalArgumentException ex){gpsText="位置服务不可用";}
    }
    private void stopSession() {
        synchronized(lock) {
            if(!running) {stopSelf();return;}
            log("STOP","stop",0,0,0,0);running=false;
            sensors.unregisterListener(this);
            try {locations.removeUpdates(this);}catch(SecurityException ignored) {}
            try{ if(writer!=null){writer.flush();writer.close();} }catch(IOException ignored){}writer=null;
            if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();
            source="已停止 · CSV 可导出";publish();
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    @Override public void onDestroy() {
        synchronized(lock){if(running)stopSession();}
        thread.quitSafely();super.onDestroy();
    }
    private void mark(String label) {
        synchronized(lock) {
            if(!running)return;
            if(label==null || label.isEmpty())label="手动标记";
            markers.add(new TrackStore.Pt(x,y));
            log("MARK",label,0,0,0,0);publish();
        }
    }
    private void resetOrigin() {
        synchronized(lock){
            if(!running)return;
            x=y=rawX=rawY=velE=velN=rawVE=rawVN=0;
            track.clear();rawTrack.clear();gpsTrack.clear();markers.clear();
            track.add(new TrackStore.Pt(0,0));rawTrack.add(new TrackStore.Pt(0,0));
            driveModel.reset();baselineTrack.clear();baselineTrack.add(new TrackStore.Pt(0,0));
            blackoutActive=blackoutFinished=false;sustainedDrivingNs=blackoutStartNs=0;
            err30=err60=err120=baseErr30=baseErr60=baseErr120=errorNow=baseErrorNow=Float.NaN;
            // Relocating origin also clears GPS anchor to avoid a discontinuous geographic overlay.
            anchored=false;lastGoodGpsNs=0;lastFixNs=0;declination=0;source="已手动重置本地原点";
            log("RESET_ORIGIN","manual",0,0,0,0);publish();
        }
    }
    @Override public void onSensorChanged(SensorEvent ev) {
        synchronized(lock) {
            if(!running)return;
            int type=ev.sensor.getType();
            String tag;
            switch(type){
                case Sensor.TYPE_ROTATION_VECTOR: tag="ROTATION_VECTOR";break;
                case Sensor.TYPE_GAME_ROTATION_VECTOR: tag="GAME_ROTATION_VECTOR";break;
                case Sensor.TYPE_LINEAR_ACCELERATION: tag="LINEAR_ACCEL";break;
                case Sensor.TYPE_GYROSCOPE: tag="GYRO";break;
                case Sensor.TYPE_ACCELEROMETER: tag="ACCEL";break;
                case Sensor.TYPE_PRESSURE: tag="PRESSURE";break;
                case Sensor.TYPE_STEP_DETECTOR: tag="STEP_SENSOR";break;
                default:tag="OTHER";
            }
            logAt(ev.timestamp,tag,"",value(ev,0),value(ev,1),value(ev,2),value(ev,3));
            if(type==Sensor.TYPE_ROTATION_VECTOR||type==Sensor.TYPE_GAME_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(matrix,ev.values);
                hasRotation=true;
                // Forward assumed to coincide with phone's +Y axis; only defensible when it is held pointing forward.
                float[] orient=new float[3];SensorManager.getOrientation(matrix,orient);
                if("walk".equals(mode))heading=(float)Math.toDegrees(orient[0]);
            } else if(type==Sensor.TYPE_GYROSCOPE && "drive".equals(mode) && hasRotation) {
                // Rotate device gyroscope into the earth frame. World +Z is up;
                // vehicle bearing (clockwise from north) is minus yaw about +Z.
                float yawUp=matrix[6]*value(ev,0)+matrix[7]*value(ev,1)+matrix[8]*value(ev,2);
                driveModel.addYawRate(ev.timestamp,yawUp);
                if(driveModel.initialized)heading=(float)Math.toDegrees(driveModel.headingRad);
            } else if(type==Sensor.TYPE_STEP_DETECTOR && "walk".equals(mode)) {
                doStep(ev.timestamp);
            } else if(type==Sensor.TYPE_LINEAR_ACCELERATION){
                processAcceleration(ev);
            }
        }
    }
    private float value(SensorEvent ev,int i) {return ev.values.length>i?ev.values[i]:0;}
    private void processAcceleration(SensorEvent ev){
        if(!hasRotation)return;
        long time=ev.timestamp;
        float ax=ev.values[0], ay=ev.values[1], az=ev.values[2];
        // Android rotation matrix maps device XYZ to world east/north/up (magnetic north).
        float eastMag=matrix[0]*ax+matrix[1]*ay+matrix[2]*az;
        float northMag=matrix[3]*ax+matrix[4]*ay+matrix[5]*az;
        // Magnetic ENU to geographic ENU when a reliable geographic anchor exists.
        float d=(float)Math.toRadians(declination);
        float east=(float)(eastMag*Math.cos(d)+northMag*Math.sin(d));
        float north=(float)(northMag*Math.cos(d)-eastMag*Math.sin(d));
        accelMagnitude=(float)Math.hypot(ax,ay);
        if(prevAccelNs==0){prevAccelNs=time;return;}
        float dt=(time-prevAccelNs)*1e-9f;prevAccelNs=time;
        if(dt<=0 || dt>.25f)return;
        // Raw double integral: intentionally uncorrected; expect fast drift.
        rawVE+=east*dt;rawVN+=north*dt;
        rawX+=rawVE*dt;rawY+=rawVN*dt;
        if("drive".equals(mode)){
            driveModel.addWorldAcceleration(time,east,north);
            if(blackoutActive && time-blackoutStartNs>=124_000_000_000L){
                blackoutActive=false;blackoutFinished=true;
                logAt(time,"BLACKOUT_END","completed 120s comparison window",0,0,0,0);
            }
            if(driveModel.initialized){
                x=driveModel.x;y=driveModel.y;speed=driveModel.speed;
                heading=(float)Math.toDegrees(driveModel.headingRad);
                source=blackoutActive?"GPS 已屏蔽 · 车辆模型推算":
                        "车辆模型（GPS 可用时校正，失联后推算）";
            } else {
                // Zero coordinates before the first trustworthy moving GNSS fix.
                source="等待室外 GPS 提供可靠速度与航向";
                speed=0;
            }
        }else{
            if(!stepSensor){
                // Basic step fallback: peak over threshold with refractory interval; not calibrated.
                float total=(float)Math.sqrt(ax*ax+ay*ay+az*az);
                if(total>1.35f && time-lastStepNs>370_000_000L) doStep(time);
            }
            speed=0;
            source="步行：仅按有效步数移动";
        }
        if(time-lastPointNs>200_000_000L){
            lastPointNs=time;
            track.add(new TrackStore.Pt(x,y));rawTrack.add(new TrackStore.Pt(rawX,rawY));
            if("drive".equals(mode) && driveModel.initialized){
                baselineTrack.add(new TrackStore.Pt(driveModel.baselineX,driveModel.baselineY));
                logAt(time,"DR_MODEL","baselineE="+driveModel.baselineX+";baselineN="+driveModel.baselineY+
                        ";baseSpeed="+driveModel.baselineSpeed+";yawRadPerSec="+driveModel.lastYawRate+
                        ";forwardAccel="+driveModel.lastForwardAccel,0,0,0,0);
            }
            logAt(time,"ESTIMATE",source,0,0,0,0);
            publish();
        }
    }
    private void doStep(long time){
        if(time-lastStepNs<260_000_000L)return;
        lastStepNs=time;steps++;
        // heading is phone +Y direction; avoid calling this a true walking heading.
        float r=(float)Math.toRadians(heading+declination);
        x+=stride*Math.sin(r);y+=stride*Math.cos(r);
        track.add(new TrackStore.Pt(x,y));
        logAt(time,"STEP_ESTIMATE","stride="+stride,0,0,0,0);
        publish();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    @Override public void onLocationChanged(Location loc){
        synchronized(lock){
            if(!running)return;
            String provider=loc.getProvider();
            float acc=loc.hasAccuracy()?loc.getAccuracy():999;
            long age=SystemClock.elapsedRealtimeNanos()-loc.getElapsedRealtimeNanos();
            boolean trustworthy=LocationManager.GPS_PROVIDER.equals(provider)&&acc<=12 && age>=-500_000_000L && age<3_000_000_000L;
            // Reject implausible GPS jumps, even when the reported precision appears good.
            if(trustworthy && anchored && lastFixNs>0){
                double lat0=Math.toRadians(anchorLat);
                float ne=(float)((loc.getLongitude()-anchorLon)*111320.0*Math.cos(lat0));
                float nn=(float)((loc.getLatitude()-anchorLat)*111132.0);
                float dt=Math.max(0,(loc.getElapsedRealtimeNanos()-lastFixNs)*1e-9f);
                float maxDistance=12f+acc*2f+("drive".equals(mode)?25f:4.5f)*dt;
                if(dt>0 && dt<10 && Math.hypot(ne-lastFixE,nn-lastFixN)>maxDistance)trustworthy=false;
            }
            gpsText=String.format(Locale.CHINA,"%s ±%.1f m · %s",provider,acc,
                    blackoutActive?"对照真值，不参与计算":
                    trustworthy?"可观测，符合要求时校正":"只记录不校正");
            logAt(loc.getElapsedRealtimeNanos(),"GPS",provider+
                    ";speedAccuracy="+(loc.hasSpeedAccuracy()?loc.getSpeedAccuracyMetersPerSecond():-1)+
                    ";bearingAccuracy="+(loc.hasBearingAccuracy()?loc.getBearingAccuracyDegrees():-1),
                    (float)loc.getLatitude(),(float)loc.getLongitude(),acc,loc.hasSpeed()?loc.getSpeed():-1);
            if(trustworthy){
                if(!anchored){
                    anchored=true;anchorLat=loc.getLatitude();anchorLon=loc.getLongitude();
                    GeomagneticField mag=new GeomagneticField((float)anchorLat,(float)anchorLon,
                            loc.hasAltitude()?(float)loc.getAltitude():0,System.currentTimeMillis());
                    declination=mag.getDeclination();
                    if("drive".equals(mode)) {
                        x=y=rawX=rawY=0;velE=velN=rawVE=rawVN=0;
                        track.clear();rawTrack.clear();markers.clear();baselineTrack.clear();
                        track.add(new TrackStore.Pt(0,0));rawTrack.add(new TrackStore.Pt(0,0));
                        baselineTrack.add(new TrackStore.Pt(0,0));
                        driveModel.reset();
                    }
                    gpsTrack.clear();
                    logAt(loc.getElapsedRealtimeNanos(),"ANCHOR_SET","origin",0,0,0,0);
                }
                double lat0=Math.toRadians(anchorLat);
                float e=(float)((loc.getLongitude()-anchorLon)*111320.0*Math.cos(lat0));
                float n=(float)((loc.getLatitude()-anchorLat)*111132.0);
                gpsTrack.add(new TrackStore.Pt(e,n));
                lastFixE=e;lastFixN=n;lastFixNs=loc.getElapsedRealtimeNanos();
                lastGoodGpsNs=loc.getElapsedRealtimeNanos();
                if("drive".equals(mode)){
                    float gpsSpeed=loc.hasSpeed()?loc.getSpeed():-1f;
                    float speedAcc=loc.hasSpeedAccuracy()?loc.getSpeedAccuracyMetersPerSecond():2f;
                    float bearingAcc=loc.hasBearingAccuracy()?loc.getBearingAccuracyDegrees():35f;
                    boolean goodSpeed=gpsSpeed>=0 && speedAcc<=1.0f;
                    boolean goodCourse=loc.hasBearing() && gpsSpeed>=1.8f && bearingAcc<=30f;
                    long stamp=loc.getElapsedRealtimeNanos();
                    // The automatic outage simulation starts only after a full
                    // 15 seconds of continuously usable moving GNSS measurements.
                    if(simulateBlackout && !blackoutActive && !blackoutFinished){
                        if(goodSpeed && goodCourse){
                            if(sustainedDrivingNs==0)sustainedDrivingNs=stamp;
                            if(stamp-sustainedDrivingNs>=15_000_000_000L && driveModel.initialized){
                                blackoutActive=true;blackoutStartNs=stamp;
                                logAt(stamp,"BLACKOUT_START","GPS observations ignored for 120s",e,n,driveModel.speed,0);
                            }
                        } else sustainedDrivingNs=0;
                    }
                    // Freeze GNSS corrections during the simulated outage.
                    if(!blackoutActive && goodSpeed){
                        if(goodCourse){
                            driveModel.setGpsFix(e,n,gpsSpeed,loc.getBearing(),true);
                        } else if(gpsSpeed<.30f && speedAcc<.35f && driveModel.initialized){
                            // Genuine zero speed must be independently confirmed
                            // by GNSS Doppler, NOT only by a quiet accelerometer.
                            // Once stopped, repeated GPS coordinate jitter should not move the blue dot.
                            driveModel.setKnownStop(driveModel.speed<.30f?driveModel.x:e,
                                    driveModel.speed<.30f?driveModel.y:n);
                            logAt(stamp,"GPS_ZERO_SPEED","trusted velocity",0,0,0,0);
                        }
                        if(driveModel.initialized){
                            x=driveModel.x;y=driveModel.y;speed=driveModel.speed;
                            heading=(float)Math.toDegrees(driveModel.headingRad);
                            source=blackoutFinished?"测试结束 · GPS 恢复校正":"GPS 校正 · 车辆模型";
                            track.add(new TrackStore.Pt(x,y));
                        }
                    }
                    if(blackoutActive && driveModel.initialized){
                        float seconds=(stamp-blackoutStartNs)*1e-9f;
                        errorNow=(float)Math.hypot(driveModel.x-e,driveModel.y-n);
                        baseErrorNow=(float)Math.hypot(driveModel.baselineX-e,driveModel.baselineY-n);
                        logAt(stamp,"GPS_REFERENCE",
                                "elapsed="+seconds+";gpsAcc="+acc+";speedAcc="+speedAcc,
                                e,n,errorNow,baseErrorNow);
                        if(seconds>=30f && seconds<=33f && Float.isNaN(err30)){err30=errorNow;baseErr30=baseErrorNow;}
                        if(seconds>=60f && seconds<=63f && Float.isNaN(err60)){err60=errorNow;baseErr60=baseErrorNow;}
                        if(seconds>=120f && seconds<=123f && Float.isNaN(err120)){
                            err120=errorNow;baseErr120=baseErrorNow;
                        }
                    }
                }else{
                    source="步行：GPS 仅观测，不改写步行轨迹";
                }
            }
            publish();
        }
    }
    private void log(String event,String note,float a,float b,float c,float d){logAt(SystemClock.elapsedRealtimeNanos(),event,note,a,b,c,d);}
    private void logAt(long time,String event,String note,float a,float b,float c,float d){
        if(writer==null)return;
        double secs=(time-tStartNs)*1e-9;
        long wall=tStartWallMs+(long)((time-tStartNs)/1_000_000L);
        try {
            writer.write(String.format(Locale.US,"%.3f,%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%.3f,%.3f,%s\n",
                    secs,time,wall,event,a,b,c,d,
                    "GPS".equals(event)?String.format(Locale.US,"%.8f",a):("ANCHOR_SET".equals(event)?String.format(Locale.US,"%.8f",anchorLat):""),
                    "GPS".equals(event)?String.format(Locale.US,"%.8f",b):("ANCHOR_SET".equals(event)?String.format(Locale.US,"%.8f",anchorLon):""),
                    "GPS".equals(event)?String.format(Locale.US,"%.2f",c):"",
                    x,y,rawX,rawY,speed,heading,
                    note==null?"":note.replace(',',';').replace('\n',' ')));
            wrote++;
            if(time-lastFlush>5_000_000_000L){writer.flush();lastFlush=time;}
        }catch(IOException ex){gpsText="CSV 写入错误："+ex.getMessage();}
    }
    private void publish(){
        TrackStore.Snapshot s=new TrackStore.Snapshot();
        s.running=running;s.mode="drive".equals(mode)?"驾车":"步行";
        s.source=source;s.gpsText=gpsText;s.sensorText=sensorText;s.file=fileName;
        s.elapsedMs=running?(SystemClock.elapsedRealtimeNanos()-tStartNs)/1_000_000L:0;
        s.east=x;s.north=y;s.rawEast=rawX;s.rawNorth=rawY;
        s.speed=speed;s.heading=heading;s.stride=stride;s.steps=steps;
        s.gpsAnchored=anchored;s.sensorHeading=hasRotation;s.lat=anchorLat;s.lon=anchorLon;
        s.blackoutRequested=simulateBlackout;s.blackoutActive=blackoutActive;s.blackoutFinished=blackoutFinished;
        s.blackoutSeconds=blackoutStartNs==0?0:
                (SystemClock.elapsedRealtimeNanos()-blackoutStartNs)/1_000_000_000L;
        s.errorNow=errorNow;s.baseErrorNow=baseErrorNow;
        s.error30=err30;s.error60=err60;s.error120=err120;
        s.baseError30=baseErr30;s.baseError60=baseErr60;s.baseError120=baseErr120;
        s.baselineSpeed=driveModel.baselineSpeed;
        s.fused.addAll(track);s.inertial.addAll(rawTrack);s.gps.addAll(gpsTrack);s.marks.addAll(markers);s.baseline.addAll(baselineTrack);
        TrackStore.INSTANCE.set(s);
    }
    public static File lastFile(Context context){
        String name=context.getSharedPreferences("track",MODE_PRIVATE).getString("lastFile","");
        if(!name.matches("track_[0-9]{8}_[0-9]{6}_(walk|drive)\\.csv"))return null;
        File dir=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"sessions");
        File f=new File(dir,name);return f.isFile()?f:null;
    }
    public static boolean isRunning(){return TrackStore.INSTANCE.get().running;}
    @Override public IBinder onBind(Intent intent){return null;}
}
