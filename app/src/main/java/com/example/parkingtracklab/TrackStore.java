package com.example.parkingtracklab;

import java.util.ArrayList;
import java.util.List;

/** Shared in-process preview only. Persistent data is the CSV file written by TrackingService. */
public final class TrackStore {
    public static final TrackStore INSTANCE = new TrackStore();
    public static final class Pt {
        public final float e, n;
        public Pt(float e, float n) { this.e=e; this.n=n; }
    }
    public static final class Snapshot {
        public boolean running, gpsAnchored, sensorHeading;
        public boolean blackoutRequested,blackoutActive,blackoutFinished;
        public long blackoutSeconds;
        public float errorNow=Float.NaN,baseErrorNow=Float.NaN;
        public float error30=Float.NaN,error60=Float.NaN,error120=Float.NaN;
        public float baseError30=Float.NaN,baseError60=Float.NaN,baseError120=Float.NaN;
        public float baselineSpeed;
        public String mode="步行", source="未开始", gpsText="无可用定位", sensorText="待检测", file="";
        public long elapsedMs; public float east, north, rawEast, rawNorth, speed, heading, stride=.7f;
        public int steps; public double lat, lon;
        public final List<Pt> fused=new ArrayList<>(), inertial=new ArrayList<>(), gps=new ArrayList<>();
        public final List<Pt> marks=new ArrayList<>(), baseline=new ArrayList<>();
    }
    private Snapshot snapshot=new Snapshot();
    public synchronized void set(Snapshot next) { snapshot=next; }
    public synchronized Snapshot get() { return snapshot; }
    private TrackStore() {}
}
