package uk.kmay.media;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared TV design primitives. Icons use one stroke weight and stay sharp at every density. */
final class TvStyle {
    static final int BACKGROUND=0xff101012, SURFACE=0xff1c1c21, TEXT=0xfff3f1f5,
        SECONDARY=0xffaaa6b1, ACCENT=0xffc9b5ed, BORDER=0xff343239;
    final MainActivity a;
    TvStyle(MainActivity a){this.a=a;}
    GradientDrawable surface(int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(a.dp(radius));
        if(stroke!=0)d.setStroke(a.dp(1),stroke);return d;
    }
    StateListDrawable background(int resting,boolean primary){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{-android.R.attr.state_enabled},surface(0xff19191d,0,5));
        d.addState(new int[]{android.R.attr.state_focused},surface(primary?0xfff7f2ff:0xff39313f,primary?0xffffffff:ACCENT,5));
        d.addState(new int[]{android.R.attr.state_pressed},surface(primary?0xffe3d7f7:0xff39313f,ACCENT,5));
        d.addState(new int[]{},surface(resting,primary?0:resting==0?0:BORDER,5));return d;
    }
    void button(Button b,boolean primary){
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextSize(13);b.setLetterSpacing(.015f);b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{SECONDARY,primary?BACKGROUND:TEXT}));
        b.setBackground(background(primary?ACCENT:SURFACE,primary));b.setPadding(a.dp(18),0,a.dp(18),0);b.setMinHeight(a.dp(42));b.setMinimumHeight(a.dp(42));
    }
    void secondary(Button b){b.setBackground(background(0,false));}
    void label(TextView label){label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));label.setLetterSpacing(.16f);label.setTextSize(10);label.setTextColor(SECONDARY);}
    Drawable icon(String name,int color){Glyph icon=new Glyph(name,color);icon.setBounds(0,0,a.dp(20),a.dp(20));return icon;}
    static final class Glyph extends Drawable {
        final String name;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(String name,int color){this.name=name;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        public void draw(Canvas c){int save=c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
            switch(name){
                case "Home":path(c,3,11,12,3,21,11);path(c,5,10,5,21,10,21,10,15,14,15,14,21,19,21,19,10);break;
                case "Films":c.drawRoundRect(3,5,21,20,2,2,p);c.drawLine(3,10,21,10,p);c.drawLine(8,5,6,10,p);c.drawLine(14,5,12,10,p);c.drawLine(20,5,18,10,p);break;
                case "Series":c.drawRoundRect(3,5,21,18,2,2,p);c.drawLine(8,22,16,22,p);c.drawLine(12,18,12,22,p);break;
                case "My list":path(c,6,3,18,3,18,22,12,18,6,22,6,3);break;
                case "Search":c.drawCircle(10,10,6.5f,p);c.drawLine(15,15,21,21,p);break;
                case "Settings":c.drawLine(3,6,21,6,p);c.drawLine(3,12,21,12,p);c.drawLine(3,18,21,18,p);c.drawCircle(8,6,2,p);c.drawCircle(16,12,2,p);c.drawCircle(10,18,2,p);break;
                case "Collapse":path(c,14,5,7,12,14,19);break;
                case "Expand":path(c,9,5,16,12,9,19);break;
                case "Play":p.setStyle(Paint.Style.FILL);path(c,7,4,20,12,7,20,7,4);p.setStyle(Paint.Style.STROKE);break;
                default:c.drawRoundRect(4,4,20,20,2,2,p);c.drawLine(4,12,20,12,p);c.drawLine(12,4,12,20,p);
            }c.restoreToCount(save);
        }
        void path(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}
        public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
