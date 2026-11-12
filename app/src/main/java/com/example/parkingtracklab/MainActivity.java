package com.example.parkingtracklab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BLUE=0xff2768d8, DARK=0xff152c44, SUB=0xff6b7c90, WHITE=Color.WHITE;
    private LinearLayout root, container;
    private Button walk,drive,start,stop,mark,reset,export,stepMinus,stepPlus;
    private TextView status,coordinates,details,stats,modeLabel,strideText,driveMetrics;
    private TrajectoryView graph;
    private CheckBox gpsLayer,rawLayer,simulateBlackout;
    private boolean driving=false;
    private float stride=.7f;
    private String pendingMode="walk";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){public void run(){render();handler.postDelayed(this,300);}};
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xfff3f6fa);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildLayout();
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private android.graphics.drawable.GradientDrawable bg(int color,int r){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
        g.setColor(color);g.setCornerRadius(dp(r));return g;
    }
    private void padded(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void add(LinearLayout parent, View v,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(v,p);
    }
    private Button button(String title,int c,int foreground){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(foreground);b.setBackground(bg(c,13));b.setMinHeight(dp(46));
        return b;
    }
    private void inRow(LinearLayout r,View v){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.rightMargin=dp(7);r.addView(v,p);
    }
    private void buildLayout(){
        root=column();root.setBackgroundColor(0xfff3f6fa);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        container=column();container.setPadding(dp(18),dp(15),dp(18),dp(40));
        scroll.addView(container,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));setContentView(root);
        // Android 15+ enforces edge-to-edge for target 35: apply status/nav insets.
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int t=0,b=0;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets a=insets.getInsets(WindowInsets.Type.systemBars());t=a.top;b=a.bottom;}
            else {t=insets.getSystemWindowInsetTop();b=insets.getSystemWindowInsetBottom();}
            root.setPadding(0,t,0,b);return insets;
        });
        TextView title=text("轨迹实验室",26,DARK,true);add(container,title,0);
        TextView subtitle=text("在没有 GPS 的地方，测一测手机究竟能推算多远",13,SUB,false);add(container,subtitle,5);
        modeLabel=text("01 · 实验模式",14,DARK,true);add(container,modeLabel,23);
        LinearLayout modes=new LinearLayout(this);modes.setOrientation(LinearLayout.HORIZONTAL);add(container,modes,10);
        walk=button("步行实验",BLUE,WHITE);drive=button("驾车实验",0xffe1e8f1,DARK);
        inRow(modes,walk);inRow(modes,drive);
        walk.setOnClickListener(v->{driving=false;updateModes();});
        drive.setOnClickListener(v->{driving=true;updateModes();});
        TextView hint=text("步行：手机顶部朝向前进方向；驾车：手机固定在支架上。",12,SUB,false);add(container,hint,8);
        simulateBlackout=new CheckBox(this);
        simulateBlackout.setText("模拟 GPS 断联：连续获得 15 秒可靠行驶定位后，自动屏蔽 GPS 校正 120 秒");
        simulateBlackout.setTextSize(13);simulateBlackout.setTextColor(DARK);
        add(container,simulateBlackout,13);
        TextView section=text("02 · 实时水平轨迹",14,DARK,true);add(container,section,23);
        graph=new TrajectoryView(this);graph.setBackground(bg(WHITE,17));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(360));gp.topMargin=dp(10);
        container.addView(graph,gp);
        TextView warning=text("蓝线：步行模式只按有效步数移动；GPS 和惯性积分仅作对照，不能当作真实位置。",12,SUB,false);
        add(container,warning,8);
        gpsLayer=new CheckBox(this);gpsLayer.setText("显示 GPS 散点（定位噪声）");gpsLayer.setTextSize(12);gpsLayer.setTextColor(SUB);
        rawLayer=new CheckBox(this);rawLayer.setText("显示原始加速度积分（漂移实验）");rawLayer.setTextSize(12);rawLayer.setTextColor(SUB);
        add(container,gpsLayer,4);add(container,rawLayer,0);
        gpsLayer.setOnCheckedChangeListener((v,checked)->graph.setDiagnostics(gpsLayer.isChecked(),rawLayer.isChecked()));
        rawLayer.setOnCheckedChangeListener((v,checked)->graph.setDiagnostics(gpsLayer.isChecked(),rawLayer.isChecked()));
        stats=text("等待开始记录",20,DARK,true);add(container,stats,18);
        driveMetrics=text("驾车 GPS 断联对照：待开始",12,DARK,false);
        driveMetrics.setLineSpacing(dp(3),1);add(container,driveMetrics,9);
        status=text("尚未启动",14,BLUE,true);add(container,status,8);
        coordinates=text("东向 0.0 m  ·  北向 0.0 m",13,DARK,false);add(container,coordinates,7);
        details=text("GPS：等待中\n传感器：等待中",12,SUB,false);details.setLineSpacing(dp(4),1);add(container,details,8);
        TextView actionLabel=text("03 · 记录操作",14,DARK,true);add(container,actionLabel,24);
        LinearLayout mainBtns=new LinearLayout(this);mainBtns.setOrientation(LinearLayout.HORIZONTAL);add(container,mainBtns,10);
        start=button("开始记录",BLUE,WHITE);stop=button("结束记录",0xffe8edf4,DARK);
        inRow(mainBtns,start);inRow(mainBtns,stop);
        start.setOnClickListener(v->requestStart());
        stop.setOnClickListener(v->send(TrackingService.STOP,null));
        LinearLayout others=new LinearLayout(this);others.setOrientation(LinearLayout.HORIZONTAL);add(container,others,9);
        mark=button("标记当前位置",0xffe5edf9,BLUE);
        reset=button("重置相对原点",0xffe8edf4,DARK);
        inRow(others,mark);inRow(others,reset);
        mark.setOnClickListener(v->{Intent i=new Intent(this,TrackingService.class);i.setAction(TrackingService.MARK);i.putExtra("label","MANUAL_MARK");startService(i);Toast.makeText(this,"已标记当前估计位置",Toast.LENGTH_SHORT).show();});
        reset.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("重置原点？")
                .setMessage("会清空屏幕上的当前轨迹、重新从 0,0 计算；CSV 原始数据仍完整保留。")
                .setNegativeButton("取消",null).setPositiveButton("重置",(d,w)->send(TrackingService.RESET,null)).show());
        LinearLayout stepsRow=new LinearLayout(this);stepsRow.setOrientation(LinearLayout.HORIZONTAL);stepsRow.setGravity(Gravity.CENTER_VERTICAL);add(container,stepsRow,16);
        TextView stepLabel=text("步行假定步长",13,SUB,false);stepsRow.addView(stepLabel,new LinearLayout.LayoutParams(0,-2,1));
        stepMinus=button("−",0xffe8edf4,DARK);stepPlus=button("+",0xffe8edf4,DARK);
        strideText=text("0.70 m",15,DARK,true);strideText.setGravity(Gravity.CENTER);
        stepsRow.addView(stepMinus,new LinearLayout.LayoutParams(dp(43),dp(40)));
        stepsRow.addView(strideText,new LinearLayout.LayoutParams(dp(72),-2));
        stepsRow.addView(stepPlus,new LinearLayout.LayoutParams(dp(43),dp(40)));
        stepMinus.setOnClickListener(v->changeStride(-.05f));stepPlus.setOnClickListener(v->changeStride(.05f));
        export=button("导出最近一次 CSV 原始记录",0xffdce7f9,BLUE);add(container,export,14);
        export.setOnClickListener(v->shareLast());
        TextView footer=text("数据仅保存在手机应用目录中，未上传云端。建议户外先取得稳定 GPS 再进入车库；室内可直接从 (0,0) 开始测试。\n\n请不要在驾驶过程中操作手机。",12,SUB,false);
        footer.setLineSpacing(dp(3),1);add(container,footer,17);
        updateModes();
    }
    private void updateModes(){
        if(simulateBlackout!=null)simulateBlackout.setVisibility(driving?View.VISIBLE:View.GONE);
        if(gpsLayer!=null && !TrackingService.isRunning())gpsLayer.setChecked(driving);
        walk.setBackground(bg(driving?0xffe1e8f1:BLUE,13));walk.setTextColor(driving?DARK:WHITE);
        drive.setBackground(bg(driving?BLUE:0xffe1e8f1,13));drive.setTextColor(driving?WHITE:DARK);
    }
    private void changeStride(float change){
        stride=Math.max(.35f,Math.min(.95f,stride+change));
        strideText.setText(String.format(Locale.CHINA,"%.2f m",stride));
        Intent i=new Intent(this,TrackingService.class);i.setAction(TrackingService.STRIDE);i.putExtra("stride",stride);
        if(TrackingService.isRunning())startService(i);
    }
    private void requestStart(){
        if(TrackingService.isRunning())return;
        pendingMode=driving?"drive":"walk";
        ArrayList<String> missing=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if(Build.VERSION.SDK_INT>=29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),101);else doStart();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=101)return;
        boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        boolean coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(!fine && !coarse){
            Toast.makeText(this,"必须允许至少大致位置才能启动前台定位服务",Toast.LENGTH_LONG).show();return;
        }
        if(!fine)Toast.makeText(this,"未获得精确位置，GPS 锚点功能不可用",Toast.LENGTH_LONG).show();
        doStart();
    }
    private void doStart(){
        Intent i=new Intent(this,TrackingService.class);i.setAction(TrackingService.START);i.putExtra("mode",pendingMode);
        i.putExtra("simulateBlackout",driving && simulateBlackout.isChecked());
        try{startForegroundService(i);}catch(Exception ex){Toast.makeText(this,"无法启动："+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void send(String action,String label){Intent i=new Intent(this,TrackingService.class);i.setAction(action);if(label!=null)i.putExtra("label",label);startService(i);}
    private void shareLast(){
        if(TrackingService.isRunning()){
            Toast.makeText(this,"请先结束记录再导出，以保证 CSV 完整",Toast.LENGTH_SHORT).show();return;
        }
        File f=TrackingService.lastFile(this);
        if(f==null){Toast.makeText(this,"还没有可导出的记录",Toast.LENGTH_SHORT).show();return;}
        Uri uri=Uri.parse("content://com.example.parkingtracklab.files/"+f.getName());
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/csv");i.putExtra(Intent.EXTRA_STREAM,uri);
        i.setClipData(android.content.ClipData.newUri(getContentResolver(),f.getName(),uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"分享轨迹原始数据"));
    }
    private void render(){
        TrackStore.Snapshot s=TrackStore.INSTANCE.get();
        graph.setData(s);
        if(driveMetrics!=null){
            boolean isDrive="驾车".equals(s.mode);
            driveMetrics.setVisibility(isDrive?View.VISIBLE:View.GONE);
            if(isDrive){
                String phase=s.blackoutFinished?"120 秒对照已完成":
                        s.blackoutActive?"GPS 断联中 · "+s.blackoutSeconds+" 秒":
                        s.blackoutRequested?"等待连续 15 秒有效驾车 GPS":"正常 GPS / 惯导切换";
                driveMetrics.setText("测试状态："+phase+
                        String.format(Locale.CHINA,"\n当前误差：模型 %s / 恒速 %s",
                            fmtError(s.errorNow),fmtError(s.baseErrorNow))+
                        String.format(Locale.CHINA,"\n30秒：%s / %s   60秒：%s / %s   120秒：%s / %s",
                            fmtError(s.error30),fmtError(s.baseError30),
                            fmtError(s.error60),fmtError(s.baseError60),
                            fmtError(s.error120),fmtError(s.baseError120))+
                        "\n（左：加速度模型；右：恒速模型；对照 GPS 测量，非绝对真值）");
            }
        }
        start.setEnabled(!s.running);stop.setEnabled(s.running);
        walk.setEnabled(!s.running);drive.setEnabled(!s.running);
        mark.setEnabled(s.running);reset.setEnabled(s.running);export.setEnabled(!s.running);
        if(simulateBlackout!=null)simulateBlackout.setEnabled(!s.running);
        stepMinus.setEnabled(!driving);stepPlus.setEnabled(!driving);
        String minutes=String.format(Locale.US,"%02d:%02d",s.elapsedMs/60_000,(s.elapsedMs/1000)%60);
        stats.setText(s.running?"记录中 "+minutes+("驾车".equals(s.mode)?String.format(Locale.US," · 估计速度 %.1f km/h",s.speed*3.6f):" · "+s.steps+" 步"):s.file.isEmpty()?"等待开始记录":"上次记录已结束");
        status.setText(s.source);
        coordinates.setText(String.format(Locale.US,"东向 %+.1f m · 北向 %+.1f m · 距起点 %.1f m",
                s.east,s.north,Math.hypot(s.east,s.north)));
        String gpsOrigin=s.gpsAnchored?String.format(Locale.US,"锚点 %.6f, %.6f",s.lat,s.lon):"本地原点：0,0（无 GPS 锚点）";
        details.setText("GPS："+s.gpsText+"\n"+gpsOrigin+"\n设备："+s.sensorText+
                String.format(Locale.US,"\n手机顶部指向 %.0f°（磁北参考） · 原始积分：%+.1f / %+.1f m",s.heading,s.rawEast,s.rawNorth));
    }
    private String fmtError(float meters){
        return Float.isNaN(meters)?"—":String.format(Locale.US,"%.1f m",meters);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(ticker);handler.post(ticker);}
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}
}
