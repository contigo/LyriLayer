package com.chaoseye.lyrilayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Composable internal fun RecognitionGlyph(name:String,modifier:Modifier=Modifier.size(20.dp),color:Color=LocalContentColor.current){
    Canvas(modifier){
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
            val width=1.75f
            val stroke=Stroke(width,cap=StrokeCap.Round)
            fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(x1,y1),Offset(x2,y2),width,StrokeCap.Round)
            fun path(vararg points:Float,closed:Boolean=false,filled:Boolean=false){
                if(points.size<4)return
                val p=Path().apply{moveTo(points[0],points[1]);for(i in 2 until points.size step 2)lineTo(points[i],points[i+1]);if(closed)close()}
                if(filled)drawPath(p,color)else drawPath(p,color,style=stroke)
            }
            when(name){
                "back"->{path(12f,5f,5f,12f,12f,19f);line(5f,12f,20f,12f)}
                "scan"->{path(8f,3f,3f,3f,3f,8f);path(16f,3f,21f,3f,21f,8f);path(3f,16f,3f,21f,8f,21f);path(16f,21f,21f,21f,21f,16f);listOf(4f,8f,13f,7f,4f).forEachIndexed{i,h->line(6f+3f*i,12f-h/2,6f+3f*i,12f+h/2)}}
                "lyrics"->{drawRoundRect(color,Offset(5f,3f),Size(14f,18f),CornerRadius(2f),style=stroke);line(8f,8f,16f,8f);line(8f,12f,16f,12f);line(8f,16f,13f,16f)}
                "ai"->{path(12f,2f,14.5f,9.5f,22f,12f,14.5f,14.5f,12f,22f,9.5f,14.5f,2f,12f,9.5f,9.5f,closed=true);line(19f,3f,19f,7f);line(17f,5f,21f,5f)}
                "play"->path(8f,5f,19f,12f,8f,19f,closed=true,filled=true)
                "stop"->drawRoundRect(color,Offset(6f,6f),Size(12f,12f),CornerRadius(2f))
                "check"->path(5f,12f,10f,17f,19f,7f)
                "clock"->{drawCircle(color,8.5f,Offset(12f,12f),style=stroke);path(12f,7f,12f,12f,15f,14f)}
                "alert"->{drawCircle(color,8.5f,Offset(12f,12f),style=stroke);line(12f,7f,12f,13f);drawCircle(color,1f,Offset(12f,16.5f))}
                "info"->{drawCircle(color,8.5f,Offset(12f,12f),style=stroke);drawCircle(color,1f,Offset(12f,7.5f));line(12f,11f,12f,17f)}
                "tune"->{listOf(6f,12f,18f).forEachIndexed{i,y->line(4f,y,20f,y);drawCircle(color,2.6f,Offset(listOf(9f,15f,8f)[i],y))}}
                "close"->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                "chevron"->path(9f,5f,16f,12f,9f,19f)
                "music"->{path(10f,17f,10f,5f,20f,3f,20f,15f);drawOval(color,Offset(4f,15f),Size(6f,5f));drawOval(color,Offset(14f,13f),Size(6f,5f))}
                "select"->{drawRoundRect(color,Offset(4f,4f),Size(16f,16f),CornerRadius(2f),style=stroke);path(7f,12f,10.5f,15.5f,17f,8.5f)}
                else->drawCircle(color,3f,Offset(12f,12f))
            }
        }
    }
}
