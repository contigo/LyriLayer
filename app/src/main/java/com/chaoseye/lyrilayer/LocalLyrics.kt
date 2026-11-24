package com.chaoseye.lyrilayer

import android.content.Context
import android.net.Uri
import java.io.*
import java.nio.charset.Charset
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocalLyrics {
    private const val MAX=2*1024*1024
    fun readText(c:Context,uri:Uri):String =
        c.contentResolver.openInputStream(uri)?.use { decode(readBounded(it,MAX)) }
            ?:throw IOException("파일 열기 실패")
    private fun readBounded(input:InputStream,max:Int):ByteArray{
        val out=ByteArrayOutputStream();val b=ByteArray(8192)
        while(true){val n=input.read(b);if(n<0)break;if(out.size()+n>max)throw IOException("가사 파일 크기 제한");out.write(b,0,n)}
        return out.toByteArray()
    }
    private fun decode(bytes:ByteArray):String {
        if(bytes.size>=2 && ((bytes[0]==0xff.toByte()&&bytes[1]==0xfe.toByte())||(bytes[0]==0xfe.toByte()&&bytes[1]==0xff.toByte())))return bytes.toString(Charsets.UTF_16).trim()
        val utf=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        return if('\uFFFD' in utf)bytes.toString(Charset.forName("EUC-KR")).trim() else utf
    }
    private fun text(t:Track,raw:String,source:String):Lyric?{
        val clean=raw.trim('\u0000','\uFEFF',' ','\r','\n')
        if(clean.isBlank())return null
        val parsed=Lrc.parse(clean)
        return if(parsed.isEmpty())Lyric(t.key,clean,"",source,"FOUND")else Lyric(t.key,parsed.joinToString("\n"){it.text},clean,source,"FOUND")
    }
    fun find(c:Context,t:Track):Lyric?{
        if(t.excluded||t.uri.isBlank())return null
        val uri=Uri.parse(t.uri)
        // Scoped storage may block sibling documents; explicit SAF import remains available.
        val path=runCatching{c.contentResolver.query(uri,arrayOf("_data"),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()
        if(!path.isNullOrBlank()){
            val audio=File(path);val sibling=File(audio.parentFile,audio.nameWithoutExtension+".lrc")
            val local=runCatching{if(sibling.isFile&&sibling.length()<=MAX)text(t,decode(sibling.readBytes()),"LOCAL_LRC")else null}.getOrNull()
            if(local!=null)return local
        }
        return runCatching{
            c.contentResolver.openInputStream(uri)?.buffered()?.use{input->
                val magic=ByteArray(4);DataInputStream(input).readFully(magic)
                when {
                    magic.copyOfRange(0,3).contentEquals(byteArrayOf(73,68,51))->id3(input,magic[3].toInt() and 255)?.let{text(t,it,"EMBEDDED_ID3")}
                    magic.contentEquals("fLaC".toByteArray())->flac(input)?.let{text(t,it,"EMBEDDED_FLAC")}
                    else->null
                }
            }
        }.getOrNull()
    }
    private fun sync(b:ByteArray,p:Int,n:Int):Int{
        var v=0;for(i in p until p+n){val x=b[i].toInt() and 255;if(x>127)return -1;v=(v shl 7) or x};return v
    }
    private fun big(b:ByteArray,p:Int,n:Int):Int{
        var v=0;for(i in p until p+n)v=(v shl 8) or (b[i].toInt() and 255);return v
    }
    private fun id3(input:InputStream,version:Int):String?{
        if(version !in 2..4)return null
        val d=DataInputStream(input);val header=ByteArray(6);d.readFully(header)
        if(header[1].toInt() and 0xC0!=0)return null // unsupported unsynchronization / extended header
        val size=sync(header,2,4);if(size !in 1..MAX)return null
        val tag=ByteArray(size);d.readFully(tag);var pos=0
        val hs=if(version==2)6 else 10
        while(pos+hs<=tag.size){
            val id=String(tag,pos,if(version==2)3 else 4,Charsets.US_ASCII)
            val len=when(version){2->big(tag,pos+3,3);4->sync(tag,pos+4,4);else->big(tag,pos+4,4)}
            if(len<=0||len>tag.size-pos-hs)break
            if((id=="USLT"||id=="ULT")&&len>4&&(version==2||tag[pos+9].toInt()==0)){
                val from=pos+hs;val enc=tag[from].toInt() and 255
                val cs=when(enc){0->Charsets.ISO_8859_1;1->Charsets.UTF_16;2->Charsets.UTF_16BE;3->Charsets.UTF_8;else->return null}
                val width=if(enc==1||enc==2)2 else 1
                var k=from+4;val end=from+len
                while(k+width<=end){
                    if(tag[k].toInt()==0&&(width==1||tag[k+1].toInt()==0)){k+=width;return String(tag,k,end-k,cs)}
                    k+=width
                }
            }
            pos+=hs+len
        }
        return null
    }
    private fun flac(input:InputStream):String?{
        val d=DataInputStream(input);var last=false;var scanned=0
        while(!last&&scanned<MAX){
            val type=d.readUnsignedByte();last=type and 128!=0
            val size=(d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            if(size>MAX-scanned)return null
            scanned+=size+4
            val data=ByteArray(size);d.readFully(data)
            if(type and 127==4){
                val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                fun str():String{if(b.remaining()<4)throw IOException("FLAC tag");val n=b.int;if(n<0||n>b.remaining())throw IOException("FLAC tag");val v=ByteArray(n);b.get(v);return v.toString(Charsets.UTF_8)}
                str();if(b.remaining()<4)return null;val count=b.int
                if(count !in 0..10000)return null
                var plain:String?=null
                repeat(count){val entry=str();val key=entry.substringBefore('=').uppercase(java.util.Locale.ROOT);val v=entry.substringAfter('=',"");if(key=="SYNCEDLYRICS"&&Lrc.parse(v).isNotEmpty())return v;if(key in setOf("LYRICS","UNSYNCEDLYRICS"))plain=v}
                return plain
            }
        }
        return null
    }
}
