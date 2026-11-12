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
        if(START.equals(action)) startSession("drive".equals(intent.getStringExtra("mode"))?"drive":"walk");
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
    private void startSession(String chosen) {
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
            tStartNs=SystemClock.elapsedRealtimeNanos(); tStartWallMs=System.currentTimeMillis();
            prevAccelNs=lastPointNs=lastStepNs=stationarySinceNs=lastGoodGpsNs=0;
            steps=0; anchored=false; hasRotation=false;lastFixNs=0;declination=0;
            velE=velN=rawVE=rawVN=x=y=rawX=rawY=heading=speed=accelMagnitude=0;
            source="等待传感器";gpsText="等待 GPS（室内可从本地原点开始）";sensorText="等待设备数据";
            track.clear();rawTrack.clear();gpsTrack.clear();markers.clear();
            track.add(new TrackStore.Pt(0,0));rawTrack.add(new TrackStore.Pt(0,0));
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
            log("START",mode,0,0,0,0);
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
                heading=(float)Math.toDegrees(orient[0]);
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
        // Secondary experimental leaky integrator, not a validated inertial navigation solution.
        float deadband=.09f;
        float ae=Math.abs(east)<deadband?0:east-Math.signum(east)*deadband;
        float an=Math.abs(north)<deadband?0:north-Math.signum(north)*deadband;
        velE+=ae*dt;velN+=an*dt;
        float damp=(float)Math.exp(-dt/("drive".equals(mode)?14f:3f));
        velE*=damp;velN*=damp;
        float v=(float)Math.hypot(velE,velN);
        if(v>MAX_CAR_SPEED){velE*=MAX_CAR_SPEED/v;velN*=MAX_CAR_SPEED/v;}
        boolean quiet=accelMagnitude<.13;
        if(quiet){if(stationarySinceNs==0)stationarySinceNs=time;}
        else stationarySinceNs=0;
        // Do not claim stationary from low acceleration alone: constant-speed driving is also quiet.
        // Damped preview intentionally does not implement ZUPT without an external stop detector.
        if("drive".equals(mode)){
            x+=velE*dt;y+=velN*dt;speed=(float)Math.hypot(velE,velN);
            if(time-lastGoodGpsNs>3_000_000_000L)source="惯性估计（高漂移风险）";
        }else{
            if(!stepSensor){
                // Basic step fallback: peak over threshold with refractory interval; not calibrated.
                float total=(float)Math.sqrt(ax*ax+ay*ay+az*az);
                if(total>1.35f && time-lastStepNs>370_000_000L) doStep(time);
            }
            speed=0;
            if(time-lastGoodGpsNs>3_000_000_000L)source="步数×步长（方向依赖手机朝向）";
        }
        if(time-lastPointNs>200_000_000L){
            lastPointNs=time;
            track.add(new TrackStore.Pt(x,y));rawTrack.add(new TrackStore.Pt(rawX,rawY));
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
            gpsText=String.format(Locale.CHINA,"%s · ±%.1f m%s",provider,acc,trustworthy?" · 用于校正":" · 仅记录不校正");
            logAt(loc.getElapsedRealtimeNanos(),"GPS",provider,loc.getLatitude()==0?0:(float)loc.getLatitude(),(float)loc.getLongitude(),acc,loc.hasSpeed()?loc.getSpeed():-1);
            if(trustworthy){
                if(!anchored){
                    anchored=true;anchorLat=loc.getLatitude();anchorLon=loc.getLongitude();
                    GeomagneticField mag=new GeomagneticField((float)anchorLat,(float)anchorLon,
                            loc.hasAltitude()?(float)loc.getAltitude():0,System.currentTimeMillis());
                    declination=mag.getDeclination();
                    x=y=rawX=rawY=0;velE=velN=rawVE=rawVN=0;
                    track.clear();rawTrack.clear();gpsTrack.clear();markers.clear();
                    track.add(new TrackStore.Pt(0,0));rawTrack.add(new TrackStore.Pt(0,0));
                    logAt(loc.getElapsedRealtimeNanos(),"ANCHOR_SET","true_north_gps_vs_magnetic_heading",0,0,0,0);
                }
                // Equirectangular short-distance projection, meters. Only meaningful near anchor.
                double lat0=Math.toRadians(anchorLat);
                float e=(float)((loc.getLongitude()-anchorLon)*111320.0*Math.cos(lat0));
                float n=(float)((loc.getLatitude()-anchorLat)*111132.0);
                gpsTrack.add(new TrackStore.Pt(e,n));
                lastFixE=e;lastFixN=n;lastFixNs=loc.getElapsedRealtimeNanos();
                x=e;y=n;
                if("drive".equals(mode) && loc.hasSpeed() && loc.hasBearing()){
                    float bearing=(float)Math.toRadians(loc.getBearing());
                    velE=loc.getSpeed()*(float)Math.sin(bearing);
                    velN=loc.getSpeed()*(float)Math.cos(bearing);
                }
                speed=loc.hasSpeed()?loc.getSpeed():speed;
                lastGoodGpsNs=loc.getElapsedRealtimeNanos();
                source="高精度 GPS";
                track.add(new TrackStore.Pt(x,y));
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
        s.fused.addAll(track);s.inertial.addAll(rawTrack);s.gps.addAll(gpsTrack);s.marks.addAll(markers);
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
