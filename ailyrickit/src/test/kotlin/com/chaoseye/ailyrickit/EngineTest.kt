package com.chaoseye.ailyrickit

import com.chaoseye.ailyrickit.engine.*
import com.chaoseye.ailyrickit.quality.BoundedLanguagePolicy as L
import com.chaoseye.ailyrickit.quality.LyricQualityPolicy as P
import com.chaoseye.ailyrickit.audio.Pcm16kConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private class Fake : InferenceBackend {
        var detections=0; var decodes=0; var closes=0
        override fun detect(pcm: FloatArray, threads: Int): L.Vote {
            detections++; return L.Vote("ko", .99)
        }
        override fun decode(pcm: FloatArray, language: String, threads: Int): List<P.Segment> {
            check(pcm.size <= 480000)
            decodes++
            return listOf(P.Segment("테스트 love",1500,3000,.8),P.Segment("구독과 좋아요",4000,5000,.9),P.Segment("됩니다",5000,5800,.9))
        }
        override fun close() { closes++ }
    }

    @Test fun engineUsesBoundedVotingAndQuarantinesRawContext() {
        val fake=Fake()
        val engine=LyricEngine(fake)
        val previews=mutableListOf<String>()
        val result=engine.extract(FloatArray(16000*160){.2f}, preview={previews.add(it)})
        assertEquals("ko",result.language)
        assertTrue(result.probeCount in 4..7)
        assertTrue(result.lines.isNotEmpty())
        assertTrue(result.lines.all{it.text=="테스트 love"})
        assertTrue(result.review.any{it.text=="됩니다"})
        assertTrue(result.lines.zipWithNext().all{(a,b)->a.endMs<=b.startMs})
        assertEquals(result.lrc,previews.last())
        assertEquals(result.decodedWindows,previews.size)
        val previous=fake.detections
        engine.extract(FloatArray(16000*9){.2f},"ja")
        assertEquals(previous,fake.detections)
        assertEquals(0,engine.extract(FloatArray(16000),"ko").decodedWindows)
        try {engine.extract(FloatArray(16000){.2f},cancelled={true});fail("not cancelled")} catch(_:CancellationException){}
        engine.close();engine.close()
        assertEquals(1,fake.closes)
    }

    @Test fun resamplingPreservesDuration() {
        for(rate in listOf(8000,16000,22050,44100,48000,96000)){
            val converter=Pcm16kConverter(rate,2,Pcm16kConverter.Format.S16)
            for(n in listOf(rate/3,rate-rate/3)){
                val b=ByteBuffer.allocate(n*4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(n){b.putShort(16384.toShort());b.putShort(8192.toShort())}
                b.flip();converter.append(b)
            }
            val samples=converter.finish()
            assertEquals(16000,samples.size)
            assertTrue(samples.all{kotlin.math.abs(it-.375f)<1e-6})
        }
    }
}
