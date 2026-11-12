package com.example.parkingtracklab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.List;
import java.util.Locale;

/** Meter-coordinate trajectory viewer. No map imagery, no claim of map-matched accuracy. */
public class TrajectoryView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private TrackStore.Snapshot data=new TrackStore.Snapshot();
    private final float density;
    private boolean showGps=false, showRaw=false;
    public void setDiagnostics(boolean gps,boolean raw){showGps=gps;showRaw=raw;invalidate();}
    public TrajectoryView(Context context) {super(context);density=getResources().getDisplayMetrics().density;}
    public void setData(TrackStore.Snapshot next){data=next;invalidate();}
    private float dp(float v){return v*density;}
    private void color(int c,float w){p.reset();p.setAntiAlias(true);p.setColor(c);p.setStrokeWidth(dp(w));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),h=getHeight();
        c.drawColor(Color.rgb(249,251,254));
        float left=dp(20),right=w-dp(18),top=dp(23),bottom=h-dp(24);
        float minE=0,maxE=0,minN=0,maxN=0;
        for(TrackStore.Pt pt:data.fused){minE=Math.min(minE,pt.e);maxE=Math.max(maxE,pt.e);minN=Math.min(minN,pt.n);maxN=Math.max(maxN,pt.n);}
        if(showGps)for(TrackStore.Pt pt:data.gps){minE=Math.min(minE,pt.e);maxE=Math.max(maxE,pt.e);minN=Math.min(minN,pt.n);maxN=Math.max(maxN,pt.n);}
        minE=Math.min(minE,data.east);maxE=Math.max(maxE,data.east);
        minN=Math.min(minN,data.north);maxN=Math.max(maxN,data.north);
        float midE=(minE+maxE)/2,midN=(minN+maxN)/2;
        float spanE=Math.max(35,(maxE-minE)*1.3f),spanN=Math.max(35,(maxN-minN)*1.3f);
        float scale=Math.min((right-left)/spanE,(bottom-top)/spanN);
        float cx=(left+right)*.5f,cy=(top+bottom)*.5f;
        // View world transform: east points right, north points up.
        c.save();c.clipRect(left,top,right,bottom);
        float gridStep=10;
        while(gridStep*scale<dp(37))gridStep*=2;
        while(gridStep*scale>dp(110))gridStep/=2;
        color(0xffe6ebf1,.7f);
        int e0=(int)Math.floor((midE-(cx-left)/scale)/gridStep)-1;
        int e1=(int)Math.ceil((midE+(right-cx)/scale)/gridStep)+1;
        int n0=(int)Math.floor((midN-(bottom-cy)/scale)/gridStep)-1;
        int n1=(int)Math.ceil((midN+(cy-top)/scale)/gridStep)+1;
        for(int k=Math.max(-1000,e0);k<=Math.min(1000,e1);k++){
            float gx=cx+(k*gridStep-midE)*scale;c.drawLine(gx,top,gx,bottom,p);
        }
        for(int k=Math.max(-1000,n0);k<=Math.min(1000,n1);k++){
            float gy=cy-(k*gridStep-midN)*scale;c.drawLine(left,gy,right,gy,p);
        }
        float originX=cx-midE*scale,originY=cy+midN*scale;
        color(0xffbccad6,1.2f);c.drawLine(originX,top,originX,bottom,p);c.drawLine(left,originY,right,originY,p);
        if(showRaw)drawPath(c,data.inertial,0xffe8a34e,1.5f,true,cx,cy,midE,midN,scale);
        // GPS is displayed as isolated observations: connecting fixes implies motion that
        // may not have occurred. In particular, static phones still have GPS jitter.
        if(showGps) {
            color(0xff2bae91,1);
            for(TrackStore.Pt pt:data.gps) {
                float gx=cx+(pt.e-midE)*scale,gy=cy-(pt.n-midN)*scale;
                c.drawCircle(gx,gy,dp(2),p);
            }
        }
        drawPath(c,data.fused,0xff3976e7,3.2f,false,cx,cy,midE,midN,scale);
        color(0xff2bae91,1);
        c.drawCircle(originX,originY,dp(5),p);
        color(Color.WHITE,1);c.drawCircle(originX,originY,dp(2),p);
        for(TrackStore.Pt marker:data.marks){
            float px=cx+(marker.e-midE)*scale,py=cy-(marker.n-midN)*scale;
            color(0xffe07d4e,1);c.drawCircle(px,py,dp(7),p);
            color(Color.WHITE,1);c.drawCircle(px,py,dp(2.6f),p);
        }
        float currentX=cx+(data.east-midE)*scale,currentY=cy-(data.north-midN)*scale;
        color(0x293976e7,1);c.drawCircle(currentX,currentY,dp(12),p);
        color(Color.WHITE,1);c.drawCircle(currentX,currentY,dp(6.2f),p);
        color(0xff3976e7,1);c.drawCircle(currentX,currentY,dp(4.2f),p);
        c.restore();
        color(0xff62748d,1);p.setTextSize(dp(11));
        c.drawText("N ↑",left,dp(14),p);
        c.drawText(String.format(Locale.US,"网格 %.0f m",gridStep),left,h-dp(8),p);
        String scaleText=String.format(Locale.US,"相对位移 %.1f m",Math.hypot(data.east,data.north));
        c.drawText(scaleText,Math.max(left,w-dp(150)),dp(14),p);
        drawLegend(c,w,h);
    }
    private void drawLegend(Canvas c,float w,float h){
        float px=dp(17),py=h-dp(43);
        String[] names={"估计轨迹","原始积分漂移","GPS散点"};
        int[] cols={0xff3976e7,0xffe8a34e,0xff2bae91};
        for(int i=0;i<3;i++){
            if(i==1 && !showRaw)continue;
            if(i==2 && !showGps)continue;
            color(cols[i],3);c.drawLine(px,py,px+dp(13),py,p);
            color(0xff697b91,1);p.setTextSize(dp(10));c.drawText(names[i],px+dp(16),py+dp(4),p);
            px+=dp(103);
        }
    }
    private void drawPath(Canvas c,List<TrackStore.Pt> points,int color,float width,boolean dash,
                          float cx,float cy,float midE,float midN,float scale){
        if(points.size()<2)return;
        Path path=new Path();
        for(int i=0;i<points.size();i++){
            TrackStore.Pt pt=points.get(i);
            float x=cx+(pt.e-midE)*scale,y=cy-(pt.n-midN)*scale;
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        color(color,width);p.setStyle(Paint.Style.STROKE);
        if(dash)p.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(5)},0));
        c.drawPath(path,p);
        p.setPathEffect(null);p.setStyle(Paint.Style.FILL);
    }
}
